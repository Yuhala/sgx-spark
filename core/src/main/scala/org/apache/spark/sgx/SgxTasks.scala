package org.apache.spark.sgx

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.ClassTag

import org.apache.spark.util.collection.ExternalSorter
import org.apache.spark.util.random.RandomSampler

import org.apache.spark.internal.Logging

import org.apache.spark.serializer.Serializer

import org.apache.spark.deploy.SparkApplication
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.TaskContext
import org.apache.spark.Partition
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.AccumulatorV2
import org.apache.spark.util.AccumulatorMetadata

abstract class SgxExecuteInside[R] extends Serializable with Logging {
	def executeInsideEnclave(): R = {
		logDebug(this + ".executeInsideEnclave()");
		val x = ClientHandle.sendRecv[R](this)
		logDebug(this + ".executeInsideEnclave() returned: " + x);
		x
	}

	def apply(): R
}

object SgxAccumulatorV2Fct {

	def register(
		acc: AccumulatorV2[_, _],
		name: Option[String] = None) = new SgxTaskAccumulatorRegister(acc, name).executeInsideEnclave()
}

private case class SgxTaskAccumulatorRegister[T,U](
		acc: AccumulatorV2[T,U],
		name: Option[String]) extends SgxExecuteInside[AccumulatorMetadata] {

	def apply() = {
		if (name == null) acc.register(SgxMain.sparkContext)
		else acc.register(SgxMain.sparkContext, name)
		acc.metadata
	}

	override def toString = this.getClass.getSimpleName + "()"
}

