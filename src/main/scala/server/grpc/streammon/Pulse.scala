// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc.streammon

import org.apache.pekko.stream.*
import org.apache.pekko.stream.stage.*

import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration

final class Pulse[T](interval: FiniteDuration, initiallyOpen: Boolean = false) extends GraphStage[FlowShape[T, T]] {

  val in = Inlet[T]("Pulse.in")
  val out = Outlet[T]("Pulse.out")
  val shape = FlowShape(in, out)

  @nowarn
  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {
      self =>
      setHandlers(in, out, self)

      override def preStart(): Unit = if (!initiallyOpen) startPulsing()

      override def onPush(): Unit = if (isAvailable(out)) push(out, grab(in))

      override def onPull(): Unit = if (!pulsing) {
        pull(in)
        startPulsing()
      }

      override protected def onTimer(timerKey: Any): Unit =
        if (isAvailable(out) && !isClosed(in) && !hasBeenPulled(in)) pull(in)

      private def startPulsing(): Unit = {
        pulsing = true
        scheduleWithFixedDelay("PulseTimer", interval, interval)
      }

      private var pulsing = false
    }

  override def toString = "Pulse"
}
