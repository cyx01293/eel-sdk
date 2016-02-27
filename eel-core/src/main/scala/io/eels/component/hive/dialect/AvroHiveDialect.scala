package io.eels.component.hive.dialect

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.slf4j.StrictLogging
import io.eels.{Schema, InternalRow}
import io.eels.component.avro.{AvroRecordFn, AvroSchemaFn}
import io.eels.component.hive.{HiveDialect, HiveWriter}
import org.apache.avro.file.{DataFileReader, DataFileWriter}
import org.apache.avro.generic.{GenericDatumWriter, GenericRecord}
import org.apache.avro.{file, generic}
import org.apache.commons.io.IOUtils
import org.apache.hadoop.fs.{FileSystem, Path}

object AvroHiveDialect extends HiveDialect with StrictLogging {

  val config = ConfigFactory.load()

  override def iterator(path: Path, schema: Schema, ignored: Seq[String])
                       (implicit fs: FileSystem): Iterator[InternalRow] = {

    logger.debug(s"Creating avro iterator for $path")

    val in = fs.open(path)
    val bytes = IOUtils.toByteArray(in)
    in.close()

    val datumReader = new generic.GenericDatumReader[GenericRecord]()
    val reader = new DataFileReader[GenericRecord](new file.SeekableByteArrayInput(bytes), datumReader)

    new Iterator[InternalRow] {
      override def hasNext: Boolean = reader.hasNext
      override def next(): InternalRow = AvroRecordFn.fromRecord(reader.next)
    }
  }

  override def writer(sourceSchema: Schema, targetSchema: Schema, path: Path)
                     (implicit fs: FileSystem): HiveWriter = {
    logger.debug(s"Creating avro writer for $path")

    val avroSchema = AvroSchemaFn.toAvro(targetSchema)
    val datumWriter = new GenericDatumWriter[GenericRecord](avroSchema)
    val dataFileWriter = new DataFileWriter[GenericRecord](datumWriter)
    val out = fs.create(path, false)
    val writer = dataFileWriter.create(avroSchema, out)

    new HiveWriter {
      override def close(): Unit = writer.close()
      override def write(row: InternalRow): Unit = {
        val record = AvroRecordFn.toRecord(row, avroSchema, sourceSchema, config)
        writer.append(record)
      }
    }
  }
}
