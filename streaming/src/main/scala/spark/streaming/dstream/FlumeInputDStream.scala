package spark.streaming.dstream

import spark.streaming.StreamingContext

import spark.Utils
import spark.storage.StorageLevel

import org.apache.flume.source.avro.AvroSourceProtocol
import org.apache.flume.source.avro.AvroFlumeEvent
import org.apache.flume.source.avro.Status
import org.apache.avro.ipc.specific.SpecificResponder
import org.apache.avro.ipc.NettyServer

import scala.collection.JavaConversions._

import java.net.InetSocketAddress
import java.io.{ObjectInput, ObjectOutput, Externalizable}
import java.nio.ByteBuffer

private[streaming]
class FlumeInputDStream[T: ClassManifest](
  @transient ssc_ : StreamingContext,
  host: String,
  port: Int,
  storageLevel: StorageLevel
) extends NetworkInputDStream[SparkFlumeEvent](ssc_) {

  override def createReceiver(): NetworkReceiver[SparkFlumeEvent] = {
    new FlumeReceiver(host, port, storageLevel)
  }
}

/**
 * A wrapper class for AvroFlumeEvent's with a custom serialization format.
 *
 * This is necessary because AvroFlumeEvent uses inner data structures
 * which are not serializable.
 */
class SparkFlumeEvent() extends Externalizable {
  var event : AvroFlumeEvent = new AvroFlumeEvent()

  /* De-serialize from bytes. */
  def readExternal(in: ObjectInput) {
    val bodyLength = in.readInt()
    val bodyBuff = new Array[Byte](bodyLength)
    in.read(bodyBuff)

    val numHeaders = in.readInt()
    val headers = new java.util.HashMap[CharSequence, CharSequence]

    for (i <- 0 until numHeaders) {
      val keyLength = in.readInt()
      val keyBuff = new Array[Byte](keyLength)
      in.read(keyBuff)
      val key : String = Utils.deserialize(keyBuff)

      val valLength = in.readInt()
      val valBuff = new Array[Byte](valLength)
      in.read(valBuff)
      val value : String = Utils.deserialize(valBuff)

      headers.put(key, value)
    }

    event.setBody(ByteBuffer.wrap(bodyBuff))
    event.setHeaders(headers)
  }

  /* Serialize to bytes. */
  def writeExternal(out: ObjectOutput) {
    val body = event.getBody.array()
    out.writeInt(body.length)
    out.write(body)

    val numHeaders = event.getHeaders.size()
    out.writeInt(numHeaders)
    for ((k, v) <- event.getHeaders) {
      val keyBuff = Utils.serialize(k.toString)
      out.writeInt(keyBuff.length)
      out.write(keyBuff)
      val valBuff = Utils.serialize(v.toString)
      out.writeInt(valBuff.length)
      out.write(valBuff)
    }
  }
}

private[streaming] object SparkFlumeEvent {
  def fromAvroFlumeEvent(in : AvroFlumeEvent) : SparkFlumeEvent = {
    val event = new SparkFlumeEvent
    event.event = in
    event
  }
}

/** A simple server that implements Flume's Avro protocol. */
private[streaming]
class FlumeEventServer(receiver : FlumeReceiver) extends AvroSourceProtocol {
  override def append(event : AvroFlumeEvent) : Status = {
    receiver.blockGenerator += SparkFlumeEvent.fromAvroFlumeEvent(event)
    Status.OK
  }

  override def appendBatch(events : java.util.List[AvroFlumeEvent]) : Status = {
    events.foreach (event =>
      receiver.blockGenerator += SparkFlumeEvent.fromAvroFlumeEvent(event))
    Status.OK
  }
}

/** A NetworkReceiver which listens for events using the
  * Flume Avro interface.*/
private[streaming]
class FlumeReceiver(
    host: String,
    port: Int,
    storageLevel: StorageLevel
  ) extends NetworkReceiver[SparkFlumeEvent] {

  lazy val blockGenerator = new BlockGenerator(storageLevel)

  protected override def onStart() {
    val responder = new SpecificResponder(
      classOf[AvroSourceProtocol], new FlumeEventServer(this));
    val server = new NettyServer(responder, new InetSocketAddress(host, port));
    blockGenerator.start()
    server.start()
    logInfo("Flume receiver started")
  }

  protected override def onStop() {
    blockGenerator.stop()
    logInfo("Flume receiver stopped")
  }

  override def getLocationPreference = Some(host)
}