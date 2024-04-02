// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc

import scala.util.control.NonFatal
import com.datastax.oss.driver.api.core.CqlSession
import com.typesafe.config.ConfigFactory
import org.apache.pekko
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cassandra.CassandraStore
import shared.AppConfig
import scala.jdk.CollectionConverters.*

object Bootstrap {
  val APP_NAME = "safer-chat"

  def run(): Unit = {

    given system: ActorSystem[Nothing] = {
      val cfg = ConfigFactory.load("application.conf").withFallback(ConfigFactory.load())
      val appConf = {
        val app = cfg.getConfig(APP_NAME)
        AppConfig(app.getInt("port"), app.getString("salt"), app.getString("default"))
      }
      ActorSystem(Guardian(appConf), APP_NAME, cfg)
    }

    pekko.management.scaladsl.PekkoManagement(system).start()
    pekko.management.cluster.bootstrap.ClusterBootstrap(system).start()
    // pekko.discovery.Discovery(system).loadServiceDiscovery("config")

    // https://docs.datastax.com/en/developer/java-driver/4.17/manual/core/
    val cps = system
      .settings
      .config
      .getStringList("datastax-java-driver.basic.contact-points")
      .asScala

    try CassandraStore.createTables(CqlSession.builder().build(), system.log)
    catch {
      case NonFatal(ex) =>
        system.log.error(s"A connection to [${cps.mkString(",")}] can't be established", ex)
    }

    // TODO: for local debug only !!!!!!!!!!!!!!!!!!!
    import shared.Extentions.*
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
