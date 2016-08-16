package io.eels.component.parquet

import java.util.UUID

import io.eels.schema.Field
import io.eels.schema.FieldType
import io.eels.schema.Schema
import org.apache.avro.SchemaBuilder
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

class ParquetReaderFnsTest extends WordSpec with Matchers with BeforeAndAfterAll {

  val path = new Path(UUID.randomUUID().toString())

  override def afterAll(): Unit = {
    val fs = FileSystem.get(new Configuration())
    fs.delete(path, false)
  }

  val avroSchema = SchemaBuilder.record("com.chuckle").fields()
    .requiredString("str").requiredLong("looong").requiredDouble("dooble").endRecord()

  val writer = AvroParquetWriter.builder[GenericRecord](path)
    .withSchema(avroSchema)
    .build()

  val record = new GenericData.Record(avroSchema)
  record.put("str", "wibble")
  record.put("looong", 999L)
  record.put("dooble", 12.34)
  writer.write(record)
  writer.close()

  val schema = Schema(Field("str"), Field("looong", FieldType.Long, true), Field("dooble", FieldType.Double, true))

  "ParquetReaderSupport" should {
    "support projections on doubles" in {

      val reader = ParquetReaderFns.createReader(path, None, Option(schema.removeField("looong")))
      val record = reader.read()
      reader.close()

      record.get("str").toString() shouldBe "wibble"
      record.get("dooble") shouldBe 12.34
    }
    "support projections on longs" in {

      val reader = ParquetReaderFns.createReader(path, None, Option(schema.removeField("str")))
      val record = reader.read()
      reader.close()

      record.get("looong") shouldBe 999L
    }
    "support full projections" in {

      val reader = ParquetReaderFns.createReader(path, None, Option(schema))
      val record = reader.read()
      reader.close()

      record.get("str").toString() shouldBe "wibble"
      record.get("looong") shouldBe 999L
      record.get("dooble") shouldBe 12.34

    }
    "support non projections" in {

      val reader = ParquetReaderFns.createReader(path, None, None)
      val record = reader.read()
      reader.close()

      record.get("str").toString() shouldBe "wibble"
      record.get("looong") shouldBe 999L
      record.get("dooble") shouldBe 12.34

    }
  }
}