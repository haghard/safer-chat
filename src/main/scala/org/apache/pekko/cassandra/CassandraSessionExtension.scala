package org.apache.pekko.cassandra

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.actor.Extension
import org.apache.pekko.actor.ExtensionId
import org.apache.pekko.actor.ExtensionIdProvider
import com.datastax.oss.driver.api.core.{ CqlIdentifier, CqlSession }

object CassandraSessionExtension extends ExtensionId[CassandraSessionExtension] with ExtensionIdProvider {

  override def get(system: ActorSystem): CassandraSessionExtension = super.get(system)

  override def lookup: CassandraSessionExtension.type = CassandraSessionExtension

  override def createExtension(system: ExtendedActorSystem): CassandraSessionExtension =
    new CassandraSessionExtension(system)
}

//https://github.com/akka/akka-samples/blob/2.5/akka-sample-cqrs-scala/src/main/scala/sample/cqrs/CassandraSessionExtension.scala
class CassandraSessionExtension(system: ActorSystem) extends Extension {
  val profileName = "local"
  val cqlSession =
    CqlSession
      .builder()
      .withKeyspace(CqlIdentifier.fromCql("chat"))
      .build()
}
