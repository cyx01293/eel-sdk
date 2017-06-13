package io.eels.dataframe

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.LongAdder
import java.util.function

import com.sksamuel.exts.Logging
import io.eels.component.parquet.ParquetSink
import io.eels.schema.StructType
import io.eels.{Row, Sink}
import org.reactivestreams.{Subscriber, Subscription}
import reactor.core.publisher.Flux

import scala.language.implicitConversions

/**
  * A DataStream is kind of like a table of data. It has fields (like columns) and rows of data. Each row
  * has an entry for each field (this may be null depending on the field definition).
  *
  * It is a lazily evaluated data structure. Each operation on a stream will create a new derived stream,
  * but those operations will only occur when a final action is performed.
  *
  * You can create a DataStream from an IO source, such as a Parquet file or a Hive table, or you may
  * create a fully evaluated one from an in memory structure. In the case of the former, the data
  * will only be loaded on demand as an action is performed.
  *
  * A DataStream is split into one or more partitions. Each partition can operate independantly
  * of the others. For example, if you filter a stream, each partition will be filtered seperately,
  * which allows it to be parallelized. If you write out a stream, each partition can be written out
  * to individual files, again allowing parallelization.
  *
  */
trait DataStream extends Logging {
  outer =>

  import io.eels.util.FluxImplicits._

  /**
    * Returns the Schema for this stream. This call will not cause a full evaluation, but only
    * the operations required to retrieve a schema will occur. For example, on a stream backed
    * by a JDBC source, an empty resultset will be obtained in order to query the metadata for
    * the database columns.
    */
  def schema: StructType

  private[dataframe] def partitions: Seq[Flux[Row]]
  private[dataframe] def coalesce: Flux[Row] = partitions.reduceLeft((a, b) => a.mergeWith(b))

  def map(f: Row => Row): DataStream = new DataStream {
    override def schema: StructType = outer.schema
    override private[eels] def partitions = outer.partitions.map(_.asScala.map(f))
  }

  /**
    * For each row in the stream, filter drops any rows which do not match the predicate.
    */
  def filter(p: Row => Boolean): DataStream = new DataStream {
    override def schema: StructType = outer.schema
    // we can keep each partition as is, and just filter individually
    override def partitions: Seq[Flux[Row]] = {
      import io.eels.util.FluxImplicits._
      outer.partitions.map(_.asScala.filter(p))
    }
  }

  def renameField(nameFrom: String, nameTo: String): DataStream = new DataStream {
    override def schema: StructType = outer.schema.renameField(nameFrom, nameTo)
    override private[eels] def partitions = {
      val updatedSchema = schema
      outer.partitions.map { flux =>
        flux.map(new function.Function[Row, Row] {
          override def apply(row: Row): Row = Row(updatedSchema, row.values)
        }): Flux[Row]
      }
    }
  }


  /**
    * Combines two frames together such that the fields from this frame are joined with the fields
    * of the given frame. Eg, if this frame has A,B and the given frame has C,D then the result will
    * be A,B,C,D
    *
    * Each stream has different partitions so we'll need to re-partition it to ensure we have an even
    * distribution.
    */
  def join(other: DataStream): DataStream = new DataStream {
    override def schema: StructType = outer.schema.join(other.schema)
    override def partitions: Seq[Flux[Row]] = {
      // we must collapse each stream into a single partition, because otherwise each partition
      // may have differing numbers of rows and then they wouldn't match up properly
      val a = outer.coalesce
      val b = other.coalesce
      // with two partitions we can now join together
      Nil
    }
  }

  def takeWhile(fieldName: String, pred: Any => Boolean): DataStream = takeWhile(row => pred(row.get(fieldName)))
  def takeWhile(pred: Row => Boolean): DataStream = new DataStream {
    override def schema: StructType = outer.schema
    override def partitions: Seq[Flux[Row]] = Seq(outer.coalesce.asScala.takeWhile(pred))
  }

  def takeUntil(fieldName: String, pred: Any => Boolean): DataStream = takeUntil(row => pred(row.get(fieldName)))
  def takeUntil(pred: Row => Boolean): DataStream = new DataStream {
    override def schema: StructType = outer.schema
    override def partitions: Seq[Flux[Row]] = Seq(outer.coalesce.asScala.takeUntil(pred))
  }

  def take(k: Int): DataStream = new DataStream {
    override def schema: StructType = outer.schema
    override def partitions: Seq[Flux[Row]] = Seq(outer.coalesce.take(k))
  }

  /**
    * Returns a new DataStream where k number of rows has been dropped.
    * This operation requires a reshuffle.
    */
  def drop(k: Int): DataStream = new DataStream {
    override def schema: StructType = outer.schema
    override def partitions: Seq[Flux[Row]] = {
      Seq(outer.coalesce.skip(k))
    }
  }

  def withLowerCaseSchema(): DataStream = new DataStream {
    private lazy val lowerSchema = outer.schema.toLowerCase()
    override def schema: StructType = lowerSchema
    override def partitions: Seq[Flux[Row]] = outer.partitions
  }

  /**
    * Action which results in all the rows being returned in memory as a Vector.
    * Alias for 'collect()'
    */
  def toVector: Vector[Row] = collect

  /**
    * Action which returns a scala.collection.Iterator, which will result in the
    * lazy evaluation of the stream, element by element.
    */
  def iterator(): Iterator[Row] = IteratorAction(this).execute()

  /**
    * Action which results in all the rows being returned in memory as a Vector.
    */
  def collect(): Vector[Row] = VectorAction(this).execute()

  def to(sink: ParquetSink): Long = SinkAction(this, sink).execute()
}

object DataStream {

  import scala.reflect.runtime.universe._

  /**
    * Create an in memory DataStream from the given Seq of Products.
    * The schema will be derived from the fields of the products using scala reflection.
    * This will result in a single partitioned DataStream.
    */
  def apply[T <: Product : TypeTag](ts: Seq[T]): DataStream = {
    val schema = StructType.from[T]
    val values = ts.map(_.productIterator.toVector)
    fromValues(schema, values)
  }

  def fromRows(_schema: StructType, first: Row, rest: Row*): DataStream = fromRows(_schema, first +: rest)
  def fromRows(_schema: StructType, rows: Seq[Row]): DataStream = new DataStream {

    import scala.collection.JavaConverters._

    override def schema: StructType = _schema
    override private[dataframe] def partitions = Seq(Flux.fromIterable(rows.asJava))
  }

  /**
    * Create an in memory DataStream from the given Seq of values, and schema.
    * This will result in a single partitioned DataStream.
    */
  def fromValues(schema: StructType, values: Seq[Seq[Any]]): DataStream = fromRows(schema, values.map(Row(schema, _)))
}

trait Action[T] {
  def execute(): T
}

case class VectorAction(ds: DataStream) extends Action[Vector[Row]] {

  import scala.collection.JavaConverters._

  def execute(): Vector[Row] = ds.coalesce.toIterable.asScala.toVector
}

case class SinkAction(ds: DataStream, sink: Sink) extends Action[Long] with Logging {

  def execute(): Long = {

    val schema = ds.schema
    val count = new LongAdder
    val latch = new CountDownLatch(ds.partitions.size)

    // for each flux we create a separate writer
    ds.partitions.zipWithIndex.foreach { case (flux, k) =>
      logger.info(s"Processing partition ${k + 1}")

      flux.subscribe(new Subscriber[Row] {

        val localCount = new LongAdder
        val writer = sink.writer(schema)

        override def onError(t: Throwable): Unit = {
          logger.info(s"Partition ${k + 1} has errored; wrote ${localCount.sum} records; closing writer", t)
          writer.close()
          latch.countDown()
        }

        override def onComplete(): Unit = {
          logger.info(s"Partition ${k + 1} has completed; wrote ${localCount.sum} records; closing writer")
          writer.close()
          latch.countDown()
        }

        override def onNext(row: Row): Unit = {
          writer.write(row)
          count.increment()
          localCount.increment()
        }

        override def onSubscribe(s: Subscription): Unit = {
          s.request(Long.MaxValue)
        }
      })
    }

    latch.await(21, TimeUnit.DAYS)
    count.sum()
  }
}