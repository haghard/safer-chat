package server.grpc

import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.stream.{ Attributes, FlowShape, Inlet, Outlet }
import org.apache.pekko.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

final class StreamMonitor[T](
    onUpstreamFinishInspection: () => Unit = () => {},
    onUpstreamFailureInspection: Throwable => Unit = _ => {},
    onDownstreamFinishInspection: Throwable => Unit = _ => {},
    onPushInspection: T => Unit = (_: T) => {},
    onPullInspection: () => Unit = () => {})
    extends GraphStage[FlowShape[T, T]] {

  private val in = Inlet[T]("StreamMonitor.in")
  private val out = Outlet[T]("StreamMonitor.out")

  override val shape: FlowShape[T, T] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit = {
            val elem = grab(in)
            onPushInspection(elem)
            push(out, elem)
          }

          override def onUpstreamFailure(ex: Throwable): Unit = {
            onUpstreamFailureInspection(ex)
            super.onUpstreamFailure(ex)
          }

          override def onUpstreamFinish(): Unit = {
            onUpstreamFinishInspection()
            super.onUpstreamFinish()
          }
        },
      )

      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit = {
            onPullInspection()
            pull(in)
          }

          override def onDownstreamFinish(cause: Throwable): Unit = {
            onDownstreamFinishInspection(cause)
            super.onDownstreamFinish(cause: Throwable)
          }
        },
      )
    }
}

object StreamMonitor {

  def apply[T](name: String, showElem: T => String)(using logger: LoggingAdapter): StreamMonitor[T] = {
    val prefix = s"[$name] "
    new StreamMonitor[T](
      () => logger.info(s"$prefix upstream completed"),
      ex => logger.error(ex, s"$prefix upstream failure"),
      ex => logger.error(ex, s"$prefix downstream completed"),
      elem => logger.info(s"$prefix pushed ${showElem(elem)}"),
      () => logger.info(s"$prefix downstream pulled"),
    )
  }
}
