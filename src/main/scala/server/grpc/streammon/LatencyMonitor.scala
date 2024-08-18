package server.grpc

import java.util.concurrent.ConcurrentLinkedQueue
import org.apache.pekko.stream.*
import org.apache.pekko.stream.stage.*
import LatencyMonitor.{ Stats, TimerContext }
import org.apache.pekko.stream.scaladsl.{ Flow, GraphDSL, Keep, Sink }
import server.grpc.streammon.Pulse

import scala.concurrent.duration.FiniteDuration

final class LatencyMonitor[A](ctx: TimerContext) extends GraphStage[FanOutShape2[A, A, Stats]] {

  val in = Inlet[A]("LatencyMonitor.in")
  val out = Outlet[A]("LatencyMonitor.out")
  val statsOut = Outlet[Stats]("LatencyMonitor.statsOut")
  val shape = new FanOutShape2[A, A, Stats](in, out, statsOut)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      private var lastStatsPull = System.nanoTime()
      private var count = 0L
      private var sumLatency = 0L

      def pushStats(): Unit = {
        val startTime = lastStatsPull
        val endTime = System.nanoTime()
        push(statsOut, Stats((endTime - startTime) / 1_000_000, count, sumLatency))
        lastStatsPull = endTime
        count = 0L
        sumLatency = 0L
      }

      setHandler(
        in,
        new InHandler {
          def onPush(): Unit = {
            count += 1
            sumLatency += ctx.stop()
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

object LatencyMonitor {

  private class TimerContext {
    private val queue = new ConcurrentLinkedQueue[Long]

    def start(): Unit = queue.add(System.nanoTime())

    def stop(): Long = (System.nanoTime() - queue.poll()) / 1000000
  }

  /** Aggregate latency metrics of a flow.
    *
    * @param timeElapsed
    *   the time elapsed between the measurement start and its end, in milliseconds
    * @param count
    *   the number of elements that passed through the flow
    * @param sumLatency
    *   the sum of the latencies of all the elements that passed through the flow, in milliseconds
    */
  final case class Stats(
      timeElapsed: Long,
      count: Long,
      sumLatency: Long) {
    def avgLatency: Double = sumLatency.toDouble / count
  }

  def apply[A, B, Mat](flow: Flow[A, B, Mat]): Graph[FanOutShape2[A, B, Stats], Mat] =
    GraphDSL.createGraph(flow) { implicit b => fl =>
      import GraphDSL.Implicits.*
      val ctx = new TimerContext
      val ctxStart = b.add(Flow[A].map { a =>
        ctx.start()
        a
      })
      val ctxEnd = b.add(new LatencyMonitor[B](ctx))

      ctxStart.out ~> fl ~> ctxEnd.in
      new FanOutShape2(ctxStart.in, ctxEnd.out0, ctxEnd.out1)
    }

  def apply[A, B, Mat, Mat2, Mat3](
      flow: Flow[A, B, Mat],
      statsSink: Sink[Stats, Mat2],
    )(
      combineMat: (Mat, Mat2) => Mat3
    ): Graph[FlowShape[A, B], Mat3] =
    GraphDSL.createGraph(apply(flow), statsSink)(combineMat) { implicit b => (mon, sink) =>
      import GraphDSL.Implicits.*
      mon.out1 ~> sink
      FlowShape(mon.in, mon.out0)
    }

  def apply[A, B, Mat](
      flow: Flow[A, B, Mat],
      statsInterval: FiniteDuration,
      onStats: Stats => Unit,
    ): Graph[FlowShape[A, B], Mat] =
    apply(flow, Flow.fromGraph(new Pulse[Stats](statsInterval)).to(Sink.foreach(onStats)))(Keep.left)
}
