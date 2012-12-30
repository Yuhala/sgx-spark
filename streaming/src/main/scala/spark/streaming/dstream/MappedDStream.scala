package spark.streaming.dstream

import spark.streaming.{DStream, Time}
import spark.RDD

private[streaming]
class MappedDStream[T: ClassManifest, U: ClassManifest] (
    parent: DStream[T],
    mapFunc: T => U
  ) extends DStream[U](parent.ssc) {

  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[U]] = {
    parent.getOrCompute(validTime).map(_.map[U](mapFunc))
  }
}

