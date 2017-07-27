/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.arrow

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.ArrayData
import org.apache.spark.sql.execution.vectorized.ArrowColumnVector
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

class ArrowWriterSuite extends SparkFunSuite {

  test("simple") {
    def check(dt: DataType, data: Seq[Any]): Unit = {
      val schema = new StructType().add("value", dt, nullable = true)
      val writer = ArrowWriter.create(schema)
      assert(writer.schema === schema)

      data.foreach { datum =>
        writer.write(InternalRow(datum))
      }
      writer.finish()

      val reader = new ArrowColumnVector(writer.root.getFieldVectors().get(0))
      data.zipWithIndex.foreach {
        case (null, rowId) => assert(reader.isNullAt(rowId))
        case (datum, rowId) =>
          val value = dt match {
            case BooleanType => reader.getBoolean(rowId)
            case ByteType => reader.getByte(rowId)
            case ShortType => reader.getShort(rowId)
            case IntegerType => reader.getInt(rowId)
            case LongType => reader.getLong(rowId)
            case FloatType => reader.getFloat(rowId)
            case DoubleType => reader.getDouble(rowId)
            case StringType => reader.getUTF8String(rowId)
            case BinaryType => reader.getBinary(rowId)
          }
          assert(value === datum)
      }

      writer.root.close()
    }
    check(BooleanType, Seq(true, null, false))
    check(ByteType, Seq(1.toByte, 2.toByte, null, 4.toByte))
    check(ShortType, Seq(1.toShort, 2.toShort, null, 4.toShort))
    check(IntegerType, Seq(1, 2, null, 4))
    check(LongType, Seq(1L, 2L, null, 4L))
    check(FloatType, Seq(1.0f, 2.0f, null, 4.0f))
    check(DoubleType, Seq(1.0d, 2.0d, null, 4.0d))
    check(StringType, Seq("a", "b", null, "d").map(UTF8String.fromString))
    check(BinaryType, Seq("a".getBytes(), "b".getBytes(), null, "d".getBytes()))
  }

  test("get multiple") {
    def check(dt: DataType, data: Seq[Any]): Unit = {
      val schema = new StructType().add("value", dt, nullable = false)
      val writer = ArrowWriter.create(schema)
      assert(writer.schema === schema)

      data.foreach { datum =>
        writer.write(InternalRow(datum))
      }
      writer.finish()

      val reader = new ArrowColumnVector(writer.root.getFieldVectors().get(0))
      val values = dt match {
        case BooleanType => reader.getBooleans(0, data.size)
        case ByteType => reader.getBytes(0, data.size)
        case ShortType => reader.getShorts(0, data.size)
        case IntegerType => reader.getInts(0, data.size)
        case LongType => reader.getLongs(0, data.size)
        case FloatType => reader.getFloats(0, data.size)
        case DoubleType => reader.getDoubles(0, data.size)
      }
      assert(values === data)

      writer.root.close()
    }
    check(BooleanType, Seq(true, false))
    check(ByteType, (0 until 10).map(_.toByte))
    check(ShortType, (0 until 10).map(_.toShort))
    check(IntegerType, (0 until 10))
    check(LongType, (0 until 10).map(_.toLong))
    check(FloatType, (0 until 10).map(_.toFloat))
    check(DoubleType, (0 until 10).map(_.toDouble))
  }

  test("array") {
    val schema = new StructType()
      .add("arr", ArrayType(IntegerType, containsNull = true), nullable = true)
    val writer = ArrowWriter.create(schema)
    assert(writer.schema === schema)

    writer.write(InternalRow(ArrayData.toArrayData(Array(1, 2, 3))))
    writer.write(InternalRow(ArrayData.toArrayData(Array(4, 5))))
    writer.write(InternalRow(null))
    writer.write(InternalRow(ArrayData.toArrayData(Array.empty[Int])))
    writer.write(InternalRow(ArrayData.toArrayData(Array(6, null, 8))))
    writer.finish()

    val reader = new ArrowColumnVector(writer.root.getFieldVectors().get(0))

    val array0 = reader.getArray(0)
    assert(array0.numElements() === 3)
    assert(array0.getInt(0) === 1)
    assert(array0.getInt(1) === 2)
    assert(array0.getInt(2) === 3)

    val array1 = reader.getArray(1)
    assert(array1.numElements() === 2)
    assert(array1.getInt(0) === 4)
    assert(array1.getInt(1) === 5)

    assert(reader.isNullAt(2))

    val array3 = reader.getArray(3)
    assert(array3.numElements() === 0)

    val array4 = reader.getArray(4)
    assert(array4.numElements() === 3)
    assert(array4.getInt(0) === 6)
    assert(array4.isNullAt(1))
    assert(array4.getInt(2) === 8)

    writer.root.close()
  }

  test("nested array") {
    val schema = new StructType().add("nested", ArrayType(ArrayType(IntegerType)))
    val writer = ArrowWriter.create(schema)
    assert(writer.schema === schema)

    writer.write(InternalRow(ArrayData.toArrayData(Array(
      ArrayData.toArrayData(Array(1, 2, 3)),
      ArrayData.toArrayData(Array(4, 5)),
      null,
      ArrayData.toArrayData(Array.empty[Int]),
      ArrayData.toArrayData(Array(6, null, 8))))))
    writer.write(InternalRow(null))
    writer.write(InternalRow(ArrayData.toArrayData(Array.empty)))
    writer.finish()

    val reader = new ArrowColumnVector(writer.root.getFieldVectors().get(0))

    val array0 = reader.getArray(0)
    assert(array0.numElements() === 5)

    val array00 = array0.getArray(0)
    assert(array00.numElements() === 3)
    assert(array00.getInt(0) === 1)
    assert(array00.getInt(1) === 2)
    assert(array00.getInt(2) === 3)

    val array01 = array0.getArray(1)
    assert(array01.numElements() === 2)
    assert(array01.getInt(0) === 4)
    assert(array01.getInt(1) === 5)

    assert(array0.isNullAt(2))

    val array03 = array0.getArray(3)
    assert(array03.numElements() === 0)

    val array04 = array0.getArray(4)
    assert(array04.numElements() === 3)
    assert(array04.getInt(0) === 6)
    assert(array04.isNullAt(1))
    assert(array04.getInt(2) === 8)

    assert(reader.isNullAt(1))

    val array2 = reader.getArray(2)
    assert(array2.numElements() === 0)

    writer.root.close()
  }

  test("struct") {
    val schema = new StructType()
      .add("struct", new StructType().add("i", IntegerType).add("str", StringType))
    val writer = ArrowWriter.create(schema)
    assert(writer.schema === schema)

    writer.write(InternalRow(InternalRow(1, UTF8String.fromString("str1"))))
    writer.write(InternalRow(InternalRow(null, null)))
    writer.write(InternalRow(null))
    writer.write(InternalRow(InternalRow(4, null)))
    writer.write(InternalRow(InternalRow(null, UTF8String.fromString("str5"))))
    writer.finish()

    val reader = new ArrowColumnVector(writer.root.getFieldVectors().get(0))

    val struct0 = reader.getStruct(0, 2)
    assert(struct0.getInt(0) === 1)
    assert(struct0.getUTF8String(1) === UTF8String.fromString("str1"))

    val struct1 = reader.getStruct(1, 2)
    assert(struct1.isNullAt(0))
    assert(struct1.isNullAt(1))

    assert(reader.isNullAt(2))

    val struct3 = reader.getStruct(3, 2)
    assert(struct3.getInt(0) === 4)
    assert(struct3.isNullAt(1))

    val struct4 = reader.getStruct(4, 2)
    assert(struct4.isNullAt(0))
    assert(struct4.getUTF8String(1) === UTF8String.fromString("str5"))

    writer.root.close()
  }

  test("nested struct") {
    val schema = new StructType().add("struct",
      new StructType().add("nested", new StructType().add("i", IntegerType).add("str", StringType)))
    val writer = ArrowWriter.create(schema)
    assert(writer.schema === schema)

    writer.write(InternalRow(InternalRow(InternalRow(1, UTF8String.fromString("str1")))))
    writer.write(InternalRow(InternalRow(InternalRow(null, null))))
    writer.write(InternalRow(InternalRow(null)))
    writer.write(InternalRow(null))
    writer.finish()

    val reader = new ArrowColumnVector(writer.root.getFieldVectors().get(0))

    val struct00 = reader.getStruct(0, 1).getStruct(0, 2)
    assert(struct00.getInt(0) === 1)
    assert(struct00.getUTF8String(1) === UTF8String.fromString("str1"))

    val struct10 = reader.getStruct(1, 1).getStruct(0, 2)
    assert(struct10.isNullAt(0))
    assert(struct10.isNullAt(1))

    val struct2 = reader.getStruct(2, 1)
    assert(struct2.isNullAt(0))

    assert(reader.isNullAt(3))

    writer.root.close()
  }
}
