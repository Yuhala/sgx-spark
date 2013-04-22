package spark.streaming.examples

import spark.streaming._
import spark.streaming.StreamingContext._

/**
 * Counts words in UTF8 encoded, '\n' delimited text received from the network every second.
 * Usage: NetworkWordCumulativeCountUpdateStateByKey <master> <hostname> <port>
 *   <master> is the Spark master URL. In local mode, <master> should be 'local[n]' with n > 1.
 *   <hostname> and <port> describe the TCP server that Spark Streaming would connect to receive data.
 *
 * To run this on your local machine, you need to first run a Netcat server
 *    `$ nc -lk 9999`
 * and then run the example
 *    `$ ./run spark.streaming.examples.NetworkWordCumulativeCountUpdateStateByKey local[2] localhost 9999`
 */
object NetworkWordCumulativeCountUpdateStateByKey {
  private def className[A](a: A)(implicit m: Manifest[A]) = m.toString

  def main(args: Array[String]) {
    if (args.length < 3) {
      System.err.println("Usage: NetworkWordCountUpdateStateByKey <master> <hostname> <port>\n" +
        "In local mode, <master> should be 'local[n]' with n > 1")
      System.exit(1)
    }

    val updateFunc = (values: Seq[Int], state: Option[Int]) => {
      val currentCount = values.foldLeft(0)(_ + _)
      //println("currentCount: " + currentCount)

      val previousCount = state.getOrElse(0)
      //println("previousCount: " + previousCount)

      val cumulative = Some(currentCount + previousCount)
      //println("Cumulative: " + cumulative)

      cumulative
    }

    // Create the context with a 10 second batch size
    val ssc = new StreamingContext(args(0), "NetworkWordCumulativeCountUpdateStateByKey", Seconds(10),
      System.getenv("SPARK_HOME"), Seq(System.getenv("SPARK_EXAMPLES_JAR")))
    ssc.checkpoint(".")

    // Create a NetworkInputDStream on target ip:port and count the
    // words in input stream of \n delimited test (eg. generated by 'nc') 
    val lines = ssc.socketTextStream(args(1), args(2).toInt)
    val words = lines.flatMap(_.split(" "))
    val wordDstream = words.map(x => (x, 1))

    // Update the cumulative count using updateStateByKey
    // This will give a Dstream made of state (which is the cumulative count of the words)
    val stateDstream = wordDstream.updateStateByKey[Int](updateFunc)

    stateDstream.foreach(rdd => {
      rdd.foreach(rddVal => {
        println("Current Count: " + rddVal)
      })
    })

    ssc.start()
  }
}
