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

package org.apache.spark.rdd

import scala.reflect.ClassTag

import org.apache.spark.{ Partition, TaskContext }

import java.io.Serializable
import org.apache.spark.sgx.SgxIteratorServer
import org.apache.spark.sgx.SgxMapPartitionsRDD
import org.apache.spark.sgx.SgxIteratorServerBinding

/**
 * An RDD that applies the provided function to every partition of the parent RDD.
 */
private[spark] class MapPartitionsRDD[U: ClassTag, T: ClassTag](
	var prev: RDD[T],
	f: (Int, Iterator[T]) => Iterator[U], // (TaskContext, partition index, iterator)
	preservesPartitioning: Boolean = false)
		extends RDD[U](prev) {

	override val partitioner = if (preservesPartitioning) firstParent[T].partitioner else None

	override def getPartitions: Array[Partition] = firstParent[T].partitions

	override def compute(split: Partition, context: TaskContext): Iterator[U] = {
		val it = firstParent[T].iterator(split, context)
		println(" MapPartitionsRDD.compute(" + f.getClass.getName + ", " + split + ", " + it.getClass.getName + ")")

		// Original call
//		f(split.index, it)

		// Call into enclave, providing the function, the partition index, and the iterator
		it match {
			case x: SgxIteratorServerBinding[T] => (new SgxMapPartitionsRDD[U,T]).computeSpec(f, split.index, x)
			case x: Iterator[T] => (new SgxMapPartitionsRDD[U,T]).computeGen(f, split.index, x)
		}

		//	x
//		f(split.index, it)
	}

	override def clearDependencies() {
		super.clearDependencies()
		prev = null
	}
}
