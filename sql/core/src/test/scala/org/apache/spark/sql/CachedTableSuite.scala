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

package org.apache.spark.sql

import org.apache.spark.sql.TestData._
import org.apache.spark.sql.columnar.{InMemoryColumnarTableScan, InMemoryRelation}
import org.apache.spark.sql.test.TestSQLContext._
import org.apache.spark.storage.RDDBlockId

case class BigData(s: String)

class CachedTableSuite extends QueryTest {
  TestData // Load test tables.

  def assertCached(query: SchemaRDD, numCachedTables: Int = 1): Unit = {
    val planWithCaching = query.queryExecution.withCachedData
    val cachedData = planWithCaching collect {
      case cached: InMemoryRelation => cached
    }

    assert(
      cachedData.size == numCachedTables,
      s"Expected query to contain $numCachedTables, but it actually had ${cachedData.size}\n" +
        planWithCaching)
  }

  def rddIdOf(tableName: String): Int = {
    val executedPlan = table(tableName).queryExecution.executedPlan
    executedPlan.collect {
      case InMemoryColumnarTableScan(_, _, relation) =>
        relation.cachedColumnBuffers.id
      case _ =>
        fail(s"Table $tableName is not cached\n" + executedPlan)
    }.head
  }

  def isMaterialized(rddId: Int): Boolean = {
    sparkContext.env.blockManager.get(RDDBlockId(rddId, 0)).nonEmpty
  }

  test("too big for memory") {
    val data = "*" * 10000
    sparkContext.parallelize(1 to 1000000, 1).map(_ => BigData(data)).registerTempTable("bigData")
    cacheTable("bigData")
    assert(table("bigData").count() === 1000000L)
    uncacheTable("bigData")
  }

  test("calling .cache() should use in-memory columnar caching") {
    table("testData").cache()
    assertCached(table("testData"))
  }

  test("calling .unpersist() should drop in-memory columnar cache") {
    table("testData").cache()
    table("testData").count()
    table("testData").unpersist(blocking = true)
    assertCached(table("testData"), 0)
  }

  test("isCached") {
    cacheTable("testData")

    assertCached(table("testData"))
    assert(table("testData").queryExecution.withCachedData match {
      case _: InMemoryRelation => true
      case _ => false
    })

    uncacheTable("testData")
    assert(!isCached("testData"))
    assert(table("testData").queryExecution.withCachedData match {
      case _: InMemoryRelation => false
      case _ => true
    })
  }

  test("SPARK-1669: cacheTable should be idempotent") {
    assume(!table("testData").logicalPlan.isInstanceOf[InMemoryRelation])

    cacheTable("testData")
    assertCached(table("testData"))

    assertResult(1, "InMemoryRelation not found, testData should have been cached") {
      table("testData").queryExecution.withCachedData.collect {
        case r: InMemoryRelation => r
      }.size
    }

    cacheTable("testData")
    assertResult(0, "Double InMemoryRelations found, cacheTable() is not idempotent") {
      table("testData").queryExecution.withCachedData.collect {
        case r @ InMemoryRelation(_, _, _, _, _: InMemoryColumnarTableScan) => r
      }.size
    }
  }

  test("read from cached table and uncache") {
    cacheTable("testData")
    checkAnswer(table("testData"), testData.collect().toSeq)
    assertCached(table("testData"))

    uncacheTable("testData")
    checkAnswer(table("testData"), testData.collect().toSeq)
    assertCached(table("testData"), 0)
  }

  test("correct error on uncache of non-cached table") {
    intercept[IllegalArgumentException] {
      uncacheTable("testData")
    }
  }

  test("SELECT star from cached table") {
    sql("SELECT * FROM testData").registerTempTable("selectStar")
    cacheTable("selectStar")
    checkAnswer(
      sql("SELECT * FROM selectStar WHERE key = 1"),
      Seq(Row(1, "1")))
    uncacheTable("selectStar")
  }

  test("Self-join cached") {
    val unCachedAnswer =
      sql("SELECT * FROM testData a JOIN testData b ON a.key = b.key").collect()
    cacheTable("testData")
    checkAnswer(
      sql("SELECT * FROM testData a JOIN testData b ON a.key = b.key"),
      unCachedAnswer.toSeq)
    uncacheTable("testData")
  }

  test("'CACHE TABLE' and 'UNCACHE TABLE' SQL statement") {
    sql("CACHE TABLE testData")
    assertCached(table("testData"))

    val rddId = rddIdOf("testData")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    sql("UNCACHE TABLE testData")
    assert(!isCached("testData"), "Table 'testData' should not be cached")
    assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
  }

  test("CACHE TABLE tableName AS SELECT * FROM anotherTable") {
    sql("CACHE TABLE testCacheTable AS SELECT * FROM testData")
    assertCached(table("testCacheTable"))

    val rddId = rddIdOf("testCacheTable")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    uncacheTable("testCacheTable")
    assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
  }

  test("CACHE TABLE tableName AS SELECT ...") {
    sql("CACHE TABLE testCacheTable AS SELECT key FROM testData LIMIT 10")
    assertCached(table("testCacheTable"))

    val rddId = rddIdOf("testCacheTable")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    uncacheTable("testCacheTable")
    assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
  }

  test("CACHE LAZY TABLE tableName") {
    sql("CACHE LAZY TABLE testData")
    assertCached(table("testData"))

    val rddId = rddIdOf("testData")
    assert(
      !isMaterialized(rddId),
      "Lazily cached in-memory table shouldn't be materialized eagerly")

    sql("SELECT COUNT(*) FROM testData").collect()
    assert(
      isMaterialized(rddId),
      "Lazily cached in-memory table should have been materialized")

    uncacheTable("testData")
    assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
  }
}
