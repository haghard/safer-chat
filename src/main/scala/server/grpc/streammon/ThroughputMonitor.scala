// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.{ Flow, GraphDSL, Sink }
import server.grpc.streammon.Pulse

import scala.concurrent.duration.FiniteDuration
import org.apache.pekko.stream.stage.*
import ThroughputMonitor.Stats

//https://github.com/ruippeixotog/akka-stream-mon/blob/master/src/main/scala/net/ruippeixotog/streammon/ThroughputMonitor.scala
final class ThroughputMonitor[A] extends GraphStage[FanOutShape2[A, A, Stats]] {

  val in = Inlet[A]("ThroughputMonitor.in")
  val out = Outlet[A]("ThroughputMonitor.out")
  val statsOut = Outlet[Stats]("ThroughputMonitor.stats-out")
  val shape = new FanOutShape2[A, A, Stats](in, out, statsOut)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      private var lastStatsPull = System.nanoTime()
      private var count = 0L

      def pushStats(): Unit = {
        val startTime = lastStatsPull
        val endTime = System.nanoTime()
        push(statsOut, Stats((endTime - startTime) / 1000000, count))
        lastStatsPull = endTime
        count = 0L
      }

      setHandler(
        in,
        new InHandler {
          def onPush(): Unit = {
            count += 1
            push(out, grab(in))
          }
        },
      )

      setHandler(
        out,
        new OutHandler {
          def onPull(): Unit = pull(in)
        },
      )

      setHandler(
        statsOut,
        new OutHandler {
          def onPull(): Unit = pushStats()
        },
      )
    }
}

object ThroughputMonitor {

  /** Aggregate throughput metrics of a stream.
    *
    * @param timeElapsed
    *   the time elapsed between the measurement start and its end, in milliseconds
    * @param count
    *   the number of elements that passed through the stream
    */
  final case class Stats(timeElapsed: Long, count: Long) {

    /** The number of elements that passed through the stream per period.
      */
    def throughput(periodInMillis: Long = 1000): Double =
      count.toDouble * periodInMillis / timeElapsed
  }

  def apply[A, Mat](statsSink: Sink[Stats, Mat]): Graph[FlowShape[A, A], Mat] =
    GraphDSL.createGraph(statsSink) { implicit b => sink =>
      import GraphDSL.Implicits.*
      val mon = b.add(new ThroughputMonitor[A])
      mon.out1 ~> sink
      FlowShape(mon.in, mon.out0)
    }

  def apply[A](statsInterval: FiniteDuration, onStats: Stats => Unit): Graph[FlowShape[A, A], NotUsed] =
    apply(
      Flow
        .fromGraph(new Pulse[Stats](statsInterval))
        .to(Sink.foreach(onStats))
        // .withAttributes(Attributes.inputBuffer(1, 1))
        .mapMaterializedValue(_ => NotUsed)
    )

}
