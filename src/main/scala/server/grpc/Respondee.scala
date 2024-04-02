// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import server.grpc.chat.ServerCmd

import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.concurrent.duration.FiniteDuration

object Respondee {

  def apply(
      reqId: String,
      p: Promise[ServerCmd],
      timeout: FiniteDuration = 5.seconds,
    ): Behavior[ServerCmd] =
    Behaviors
      .setup[ServerCmd] { ctx =>
        val logger = ctx.log
        val start = System.currentTimeMillis()
        Behaviors.withTimers { timers =>
          timers.startSingleTimer(ServerCmd.defaultInstance, timeout)
          Behaviors
            .receiveMessage[ServerCmd] {
              case ServerCmd.defaultInstance =>
                logger.warn("Write timeout [{}] after {}ms", reqId, System.currentTimeMillis() - start)
                p.tryFailure(new Exception(s"$reqId timeout"))
                Behaviors.stopped
              case cmd: ServerCmd =>
                // logger.warn("Write([{}]) took: {}ms", reqId, System.currentTimeMillis() - start)
                p.trySuccess(cmd)
                Behaviors.stopped
            }
        }
      }
      .narrow
}
