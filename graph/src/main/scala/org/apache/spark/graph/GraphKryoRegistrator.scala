package org.apache.spark.graph

import com.esotericsoftware.kryo.Kryo

import org.apache.spark.graph.impl.MessageToPartition
import org.apache.spark.serializer.KryoRegistrator
import org.apache.spark.graph.impl._
import scala.collection.mutable.BitSet

class GraphKryoRegistrator extends KryoRegistrator {

  def registerClasses(kryo: Kryo) {
    kryo.register(classOf[Edge[Object]])
    kryo.register(classOf[MutableTuple2[Object, Object]])
    kryo.register(classOf[MessageToPartition[Object]])
    kryo.register(classOf[(Vid, Object)])
    kryo.register(classOf[EdgePartition[Object]])
    kryo.register(classOf[BitSet])

    // This avoids a large number of hash table lookups.
    kryo.setReferences(false)
  }
}
