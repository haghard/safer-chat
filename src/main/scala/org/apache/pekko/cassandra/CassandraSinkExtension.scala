package org.apache.pekko.cassandra

import org.apache.pekko.actor.*
import org.apache.pekko.cluster.*
import org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps

object CassandraSinkExtension extends ExtensionId[CassandraSinkExtension] with ExtensionIdProvider {

  override def get(system: ActorSystem): CassandraSinkExtension = super.get(system)

  override def lookup: CassandraSinkExtension.type = CassandraSinkExtension

  override def createExtension(system: ExtendedActorSystem): CassandraSinkExtension =
    new CassandraSinkExtension(system)
}

class CassandraSinkExtension(system: ActorSystem) extends Extension {
  given system0: org.apache.pekko.actor.typed.ActorSystem[?] = system.toTyped

  // Shared sink to be used by all local grpc connections.
  val chatSessionSharedSink =
    ChatRoomCassandraStore.chatRoomSessionsSink(Cluster(system).selfMember.details3())

  // TODO:
  // val chatSharedSink = ???

}
