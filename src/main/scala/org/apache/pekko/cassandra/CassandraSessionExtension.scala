// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package org.apache.pekko.cassandra

import org.apache.pekko.actor.*
import com.datastax.oss.driver.api.core.*
import com.codahale.metrics.MetricRegistry

object CassandraSessionExtension extends ExtensionId[CassandraSessionExtension] with ExtensionIdProvider {

  val cntName = "num-of-reqs"

  override def get(system: ActorSystem): CassandraSessionExtension = super.get(system)

  override def lookup: CassandraSessionExtension.type = CassandraSessionExtension

  override def createExtension(system: ExtendedActorSystem): CassandraSessionExtension =
    new CassandraSessionExtension(system)

}

class CassandraSessionExtension(system: ActorSystem) extends Extension {
  // val cloudConfigPath = Paths.get("src/main/resources/schat-cloud.zip")

  val keyspace = system.settings.config.getString("cassandra.keyspace")
  val astraUrl = classOf[CassandraSessionExtension.type].getResource("/astra/schat-cloud.zip")

  // https://docs.datastax.com/en/developer/java-driver/4.17/manual/core/
  lazy val (cqlSession, metricRegistry) = {
    // lazy val cqlSession = {
    // TODO: https://github.com/kbr-/scylla-example-app/blob/main/src/main/java/app/Main.java
    /// Users/vadimbondarev/projects/kafka_projects/scala-kafka-avro/src/main/scala/com/app/ConsumerApp.scala

    val metricRegistry = new MetricRegistry()
    // val cnt = metricRegistry.counter(cntName)
    // metricRegistry.getMetrics().keySet().size())

    val session = CqlSession
      .builder()
      .withCloudSecureConnectBundle(astraUrl)
      // .withTimestampGenerator(new AtomicMonotonicTimestampGenerator())
      .withAuthCredentials(
        system.settings.config.getString("cassandra.username"),
        system.settings.config.getString("cassandra.psw"),
      )
      .withKeyspace(CqlIdentifier.fromCql(keyspace))
      .withMetricRegistry(metricRegistry)
      // .addRequestTracker(new RequestLogger() {})
      .build()

    session.execute(s"USE $keyspace")
    (session, metricRegistry)
    // session
  }
}
