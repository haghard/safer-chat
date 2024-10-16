// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc

import scala.util.control.NonFatal
import com.typesafe.config.ConfigFactory
import org.apache.pekko
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cassandra.*
import shared.*

object Bootstrap {
  val APP_NAME = "safer-chat"

  def run(): Unit = {
    sys.props += "APP_VERSION" -> server.grpc.BuildInfo.version
    // https://www.slf4j.org/faq.html#explicitProvider
    sys.props += "slf4j.provider" -> classOf[ch.qos.logback.classic.spi.LogbackServiceProvider].getName

    given system: ActorSystem[Nothing] = {
      val cfg = ConfigFactory.load("application.conf").withFallback(ConfigFactory.load())
      val appConf = {
        val app = cfg.getConfig(APP_NAME)
        AppConfig(
          app.getInt("grpc-port"),
          app.getString("secret-token"),
          app.getInt("http-port"),
          app.getString("default"),
        )
      }
      ActorSystem(Guardian(appConf), APP_NAME, cfg)
    }

    pekko.management.scaladsl.PekkoManagement(system).start()

    // http 127.0.0.1:8558/cluster/members "Authorization:Basic QWxhZGRpbjpPcGVuU2VzYW1l"
    /*management.start(_.withAuth({ (credentials: Credentials) =>
      credentials match {
        case p @ Credentials.Provided(id) =>
          Future.successful { if ((id == "Aladdin") && p.verify("OpenSesame")) Some(id) else None }
        case _ =>
          Future.successful(None)
      }
    }))*/

    pekko.management.cluster.bootstrap.ClusterBootstrap(system).start()
    // pekko.discovery.Discovery(system).loadServiceDiscovery("config")

    try {
      val cqlSession = CassandraSessionExtension(system).cqlSession
      CassandraStore.createTables(cqlSession, system.log)
    } catch {
      case NonFatal(ex) =>
        system.log.error(s"CassandraSession error", ex)
    }

    // TODO: for local debug only !!!!!!!!!!!!!!!!!!!
    val _ = scala.io.StdIn.readLine()
    system.log.warn("★ ★ ★ ★ ★ ★  Shutting down ... ★ ★ ★ ★ ★ ★")
    system.terminate()
    scala
      .concurrent
      .Await
      .result(
        system.whenTerminated,
        system.settings.config.getDuration("pekko.coordinated-shutdown.default-phase-timeout").asScala,
      )
  }
}
