package org.apache.pekko.cassandra

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.actor.Extension
import org.apache.pekko.actor.ExtensionId
import org.apache.pekko.actor.ExtensionIdProvider
import com.datastax.oss.driver.api.core.{ CqlIdentifier, CqlSession }

import java.nio.file.Paths

object CassandraSessionExtension extends ExtensionId[CassandraSessionExtension] with ExtensionIdProvider {

  override def get(system: ActorSystem): CassandraSessionExtension = super.get(system)

  override def lookup: CassandraSessionExtension.type = CassandraSessionExtension

  override def createExtension(system: ExtendedActorSystem): CassandraSessionExtension =
    new CassandraSessionExtension(system)
}

class CassandraSessionExtension(system: ActorSystem) extends Extension {
  val cloudConfigPath = Paths.get("./src/main/resources/schat-cloud.zip")
  val keyspaceName = system.settings.config.getString("cassandra.keyspace")

  val cqlSession =
    CqlSession
      .builder()
      .withCloudSecureConnectBundle(cloudConfigPath)
      .withAuthCredentials(
        system.settings.config.getString("cassandra.username"),
        system.settings.config.getString("cassandra.psw"),
      )
      .withKeyspace(CqlIdentifier.fromCql(keyspaceName))
      .build()
}
