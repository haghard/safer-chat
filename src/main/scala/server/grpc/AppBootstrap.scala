// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.*
import java.util.concurrent.ConcurrentHashMap
import shared.Extentions.*
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.CoordinatedShutdown.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cassandra.CassandraSessionExtension
import org.apache.pekko.http.scaladsl.*
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.KillSwitch
import shared.AppConfig
import shared.Domain.ChatName

object AppBootstrap {

  case object BindFailure extends Reason

  def apply(
      appCfg: AppConfig,
      grpcService: HttpRequest => Future[HttpResponse],
      kss: ConcurrentHashMap[ChatName, KillSwitch],
    )(using sys: ActorSystem[?]
    ): Unit = {
    import sys.executionContext

    val logger = sys.log

    val host = sys.settings.config.getString("pekko.remote.artery.canonical.hostname")
    val port = appCfg.port

    val phaseTimeout =
      sys.settings.config.getDuration("pekko.coordinated-shutdown.default-phase-timeout").asScala

    val shutdown = CoordinatedShutdown(sys)

    Http()(sys)
      .newServerAt(host, port)
      .bind(grpcService)
      .onComplete {
        case Failure(ex) =>
          logger.error(s"Shutting down. Couldn't bind to $host:$port", ex)
          shutdown.run(BindFailure)
        case Success(binding) =>
          logger.info("{} (GRPC)", Bootstrap.APP_NAME)
          logger.info(s"""
              |★ ★ ★ ★ ★ ★ ★ ★ ★ ActorSystem(${sys.name}) ★ ★ ★ ★ ★ ★ ★ ★ ★
              |${sys.printTree}
              |★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★
              |""".stripMargin)

          shutdown.addTask(PhaseBeforeServiceUnbind, "before-unbind") { () =>
            Future.successful {
              logger.info(s"★ ★ ★ before-unbind [shutdown.${kss.values().size()} hubs]  ★ ★ ★")
              kss.forEach { (chat, ks) =>
                ks.shutdown()
              }
              Done
            }
          }

          // Next 2 tasks(PhaseServiceUnbind, PhaseServiceRequestsDone) makes sure that during shutdown
          // no more requests are accepted and
          // all in-flight requests have been processed
          shutdown.addTask(PhaseServiceUnbind, "http-unbind") { () =>
            // No new connections are accepted. Existing connections are still allowed to perform request/response cycles
            binding.unbind().map { done =>
              logger.info("★ ★ ★ CoordinatedShutdown [http-api.unbind] ★ ★ ★")
              done
            }
          }

          // graceful termination request being handled on this connection
          shutdown.addTask(PhaseServiceRequestsDone, "http-terminate") { () =>
            /** It doesn't accept new connection but it drains the existing connections Until the `terminationDeadline`
              * all the req that have been accepted will be completed and only than the shutdown will continue
              */
            binding.terminate(phaseTimeout - 2.second).map { _ =>
              logger.info("★ ★ ★ CoordinatedShutdown [http-api.terminate]  ★ ★ ★")
              Done
            }
          }

          // forcefully kills connections that are still open
          shutdown.addTask(PhaseServiceStop, "close.connections") { () =>
            Http().shutdownAllConnectionPools().map { _ =>
              logger.info("★ ★ ★ CoordinatedShutdown [close.connections] ★ ★ ★")
              Done
            }
          }

          // PhaseServiceRequestsDone - process in-flight requests
          shutdown.addTask(PhaseServiceRequestsDone, "kss.shutdown") { () =>
            Future {
              kss.values().forEach(_.abort(new Exception("abort")))
              logger.info(s"★ ★ ★ CoordinatedShutdown [kss.shutdown.${kss.size()} ]  ★ ★ ★")
              Done
            }
          }

          shutdown.addTask(PhaseBeforeClusterShutdown, "before-cluster-shutdown.0") { () =>
            Http()
              .shutdownAllConnectionPools()
              .flatMap(_ =>
                org
                  .apache
                  .pekko
                  .pattern
                  .after(2.seconds)(Future.successful {
                    logger.info(s"★ ★ ★ CoordinatedShutdown [before-cluster-shutdown.0]  ★ ★ ★")
                    Done
                  })
              )
          }

          shutdown.addTask(PhaseActorSystemTerminate, "actor-system-terminate.0") { () =>
            Http()
              .shutdownAllConnectionPools()
              .flatMap(_ =>
                org
                  .apache
                  .pekko
                  .pattern
                  .after(2.seconds)(
                    scala
                      .jdk
                      .javaapi
                      .FutureConverters
                      .asScala(CassandraSessionExtension(sys).cqlSession.forceCloseAsync())
                      .map { _ =>
                        logger.info("★ ★ ★ CoordinatedShutdown [actor-system-terminate.0] ★ ★ ★")
                        Done
                      }
                  )
              )
          }
      }
  }
}
