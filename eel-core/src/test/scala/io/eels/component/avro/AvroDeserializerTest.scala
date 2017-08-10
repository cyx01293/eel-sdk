package io.eels.component.avro

import com.typesafe.config.ConfigFactory
import io.eels.Row
import io.eels.schema._
import org.apache.avro.generic.GenericData
import org.scalatest.{Matchers, WordSpec}

class AvroDeserializerTest extends WordSpec with Matchers {

  private val config = ConfigFactory.parseString(""" eel.avro.fillMissingValues = true """)

  "toRow" should {
    "create eel row from supplied avro record" in {
      val schema = StructType(Field("a", nullable = false), Field("b", nullable = false), Field("c", nullable = false))
      val record = new GenericData.Record(AvroSchemaFns.toAvroSchema(schema))
      record.put("a", "aaaa")
      record.put("b", "bbbb")
      record.put("c", "cccc")
      val row = new AvroDeserializer(true).toValues(record)
      row shouldBe Vector("aaaa", "bbbb", "cccc")
    }
    "support arrays" in {
      val schema = StructType(Field("a"), Field("b", ArrayType(BooleanType)))
      val record = new GenericData.Record(AvroSchemaFns.toAvroSchema(schema))
      record.put("a", "aaaa")
      record.put("b", Array(true, false))
      new AvroDeserializer().toValues(record).head shouldBe "aaaa"
      new AvroDeserializer().toValues(record).last.asInstanceOf[Array[Boolean]].toList shouldBe List(true, false)
    }
  }
}
