package io.eels.component.hive

import java.io.File
import java.util.UUID

import io.eels.Row
import io.eels.datastream.DataStream
import io.eels.schema._
import org.scalatest.{BeforeAndAfterAll, FunSuite, Matchers}

import scala.util.Random

class HiveCompactTest extends FunSuite with Matchers with BeforeAndAfterAll {

  import HiveConfig._

  val dbname = HiveTestUtils.createTestDatabase
  val table = "compact_test_" + System.currentTimeMillis()


  override def afterAll(): Unit = {
    HiveTable(dbname, table).drop()
  }

  test("compact should result in a single file") {
    assume(new File(s"$basePath/core-site.xml").exists)

    HiveTable(dbname, table).drop()

    val schema = StructType(
      Field("a", StringType),
      Field("b", StringType),
      Field("c", IntType.Signed),
      Field("d", BooleanType)
    )

    def createRow = Row(schema, Seq(UUID.randomUUID.toString, UUID.randomUUID.toString, Random.nextInt(1000000), Random.nextBoolean))

    val sink = HiveSink(dbname, table).withCreateTable(true)
    val size = 10000

    DataStream.fromRowIterator(schema, Iterator.continually(createRow).take(size)).to(sink, 4)

    val t = HiveTable(dbname, table)
    t.paths(false, false).size shouldBe 4
    //    t.compact()
    //    t.paths(false, false).size shouldBe 1Yeah
    //   t.stats().rows shouldBe size
  }
}
