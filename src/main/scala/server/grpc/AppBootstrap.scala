// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.*
import java.util.concurrent.ConcurrentHashMap
import org.apache.pekko.Done
import org.apache.pekko.actor.{ Address, CoordinatedShutdown }
import org.apache.pekko.actor.CoordinatedShutdown.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cassandra.CassandraSessionExtension
import org.apache.pekko.cluster.typed.Cluster
import org.apache.pekko.http.scaladsl.*
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.pki.pem.{ DERPrivateKeyLoader, PEMDecoder }

import java.io.FileOutputStream
import java.security.{ KeyStore, SecureRandom }
import java.security.cert.Certificate
import javax.net.ssl.{ KeyManagerFactory, SSLContext }
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.apache.pekko.stream.KillSwitch
import shared.Domain.ChatName
import shared.*

import java.security.cert.CertificateFactory

object AppBootstrap {

  case object BindFailure extends Reason

  def leaseOwnerFromAkkaMember(classicSystemName: String, addr: Address): String = {
    val sb = new java.lang.StringBuilder().append(classicSystemName)
    if (addr.host.isDefined) sb.append('@').append(addr.host.get)
    if (addr.port.isDefined) sb.append(':').append(addr.port.get)
    sb.toString
  }

  def serverHttpContext(log: org.slf4j.Logger): HttpsConnectionContext = {
    val keyPath = "fsa/privkey.key"
    val certPath = "/fsa/fullchain.pem"
    val privateKey = DERPrivateKeyLoader.load(PEMDecoder.decode(scala.io.Source.fromResource(keyPath).mkString))
    val fact = CertificateFactory.getInstance("X.509")
    val certificate = fact.generateCertificate(classOf[AppBootstrap.type].getResourceAsStream(certPath))
    log.info(s"$certificate")

    //
    val jksPsw = "123456".toCharArray

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null)
    ks.setKeyEntry("safer-chat-pk", privateKey, jksPsw, Array[Certificate](certificate))

    val out = new FileOutputStream("./ks/safer-chat.jks")
    ks.store(out, jksPsw)
    out.flush()
    out.close()

    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, jksPsw)

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers, null, new SecureRandom())
    ConnectionContext.httpsServer(context)
  }

  def http(appCfg: AppConfig)(using sys: ActorSystem[?]): Unit = {
    import sys.executionContext
    val shutdown = CoordinatedShutdown(sys)
    val logger = sys.log
    val terminationDeadline =
      sys.settings.config.getDuration("pekko.coordinated-shutdown.default-phase-timeout").asScala

    Http()(sys)
      .newServerAt(sys.settings.config.getString("pekko.remote.artery.canonical.hostname"), appCfg.httpPort)
      .enableHttps(serverHttpContext(sys.log))
      .bind(new RestApi().jvm)
      .onComplete {
        case Failure(cause) =>
          shutdown.run(BindFailure)
        case Success(binding) =>
          logger.info("{} (HTTP on {})", Bootstrap.APP_NAME, appCfg.httpPort)
          shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "terminate-http-server") { () =>
            binding.terminate(terminationDeadline).map { _ =>
              Done
            }
          }
      }
  }

  def grpc(
      appCfg: AppConfig,
      grpcService: HttpRequest => Future[HttpResponse],
      kss: ConcurrentHashMap[ChatName, KillSwitch],
    )(using sys: ActorSystem[?]
    ): Unit = {
    import sys.executionContext

    val ua = Cluster(sys).selfMember.uniqueAddress
    val logger = sys.log
    val host = sys.settings.config.getString("pekko.remote.artery.canonical.hostname")
    val phaseTimeout =
      sys.settings.config.getDuration("pekko.coordinated-shutdown.default-phase-timeout").asScala

    val shutdown = CoordinatedShutdown(sys)

    Http()(sys)
      .newServerAt(host, appCfg.grpcPort)
      .enableHttps(serverHttpContext(sys.log))
      .bind(grpcService)
      .onComplete {
        case Failure(ex) =>
          logger.error(s"Shutting down. Couldn't bind to $host:${appCfg.grpcPort}", ex)
          shutdown.run(BindFailure)
        case Success(binding) =>
          logger.info("{} GRPC on {}", Bootstrap.APP_NAME, appCfg.grpcPort)
          logger.info(s"""
               |★ ★ ★ ★ ★ ★ ★ ★ ★ ActorSystem(${sys.name}) ★ ★ ★ ★ ★ ★ ★ ★ ★
               |${sys.printTree}
               |★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★
               |""".stripMargin)

          // https://github.com/nolangrace/akka-playground/blob/d5459a555c78fbcf886f1ef38b0011abde47cd33/src/main/scala/com/example/AkkaStreamsMaterializerState.scala#L58
          /*
          import org.apache.pekko.stream.snapshot.MaterializerState
          import org.apache.pekko.actor.typed.scaladsl.adapter.TypedActorSystemOps
          MaterializerState.streamSnapshots(sys.toClassic).onComplete { snap =>
            snap.map { interpreters =>
              interpreters.map { streamSn =>
                streamSn.activeInterpreters.map { interpreters =>
                  import com.diogonunes.jcolor.*
                  interpreters.logics.map(lsn => println(Ansi.colorize(s"Label:${lsn.label}", Attribute.YELLOW_TEXT())))
                  interpreters
                    .connections
                    .map(csn => println(Ansi.colorize(s"${csn.in} ~> ${csn.out}", Attribute.RED_BACK())))
                }
              }
            }
          }*/

          shutdown.addTask(PhaseBeforeServiceUnbind, "before-unbind") { () =>
            Future.successful {
              logger.info(s"★ ★ ★ CoordinatedShutdown.0 [shutdown.${kss.values().size()} hubs]  ★ ★ ★")
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
              logger.info("★ ★ ★ CoordinatedShutdown.1 [http-api.unbind] ★ ★ ★")
              done
            }
          }

          shutdown.addTask(PhaseServiceUnbind, "management.stop") { () =>
            PekkoManagement(sys).stop().map { done =>
              logger.info("★ ★ ★ CoordinatedShutdown.2 [management.stop] ★ ★ ★")
              done
            }
          }

          // graceful termination request being handled on this connection
          shutdown.addTask(PhaseServiceRequestsDone, "http-terminate") { () =>
            /** It doesn't accept new connection but it drains the existing connections Until the `terminationDeadline`
              * all the req that have been accepted will be completed and only than the shutdown will continue
              */
            binding.terminate(phaseTimeout - 2.second).map { _ =>
              logger.info("★ ★ ★ CoordinatedShutdown.3 [http-api.terminate]  ★ ★ ★")
              Done
            }
          }

          // PhaseServiceRequestsDone - process in-flight requests
          shutdown.addTask(PhaseServiceRequestsDone, "kss.shutdown") { () =>
            Future {
              kss.values().forEach(_.abort(new Exception("abort")))
              logger.info(s"★ ★ ★ CoordinatedShutdown.4 [kss.shutdown.${kss.size()} ]  ★ ★ ★")
              Done
            }
          }

          // forcefully kills connections that are still open
          shutdown.addTask(PhaseServiceStop, "close.connections") { () =>
            Http().shutdownAllConnectionPools().map { _ =>
              logger.info("★ ★ ★ CoordinatedShutdown.5 [close.connections] ★ ★ ★")
              Done
            }
          }

          shutdown.addTask(PhaseBeforeClusterShutdown, "before-cluster-shutdown.0") { () =>
            Http()
              .shutdownAllConnectionPools() // forcefully kils connections that are still open
              .flatMap(_ =>
                scala
                  .jdk
                  .javaapi
                  .FutureConverters
                  .asScala(CassandraSessionExtension(sys).cqlSession.forceCloseAsync())
                  .map { _ =>
                    logger.info(s"★ ★ ★ CoordinatedShutdown.6 [before-cluster-shutdown]  ★ ★ ★")
                    Done
                  }
              )
          }

          shutdown.addTask(PhaseActorSystemTerminate, "actor-system-terminate.0") { () =>
            Future.successful {
              logger.info("★ ★ ★ CoordinatedShutdown.7 [actor-system-terminate] ★ ★ ★")
              Done
            }
          }
      }
  }
}
