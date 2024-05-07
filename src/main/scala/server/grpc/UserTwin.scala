// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
/*
package server.grpc

import com.domain.user.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.*
import shared.Domain.*

object UserTwin {

  val TypeKey = EntityTypeKey[UsrTwinCmd]("usr")

  val Sep = '@'

  def key(chat: ChatName, usr: Participant): String =
    s"${chat.raw()}$Sep${usr.raw()}"

  // https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html#colocate-shards
  def shardingMessageExtractor() =
    new org.apache.pekko.cluster.sharding.typed.ShardingMessageExtractor[UsrTwinCmd, UsrTwinCmd] {
      override def entityId(cmd: UsrTwinCmd): String =
        key(cmd.chat, cmd.user)

      override def shardId(entityId: String): String = {
        // Use same shardId as the Chat to colocate Chat and UserTwin
        // we have the buildingId as prefix in the entityId
        val chatId = entityId.split(Sep).head
        chatId
        // math.abs(chatId.hashCode % numOfShards).toString
      }

      override def unwrapMessage(cmd: UsrTwinCmd): UsrTwinCmd = cmd
    }

  def apply(): Behavior[UsrTwinCmd] =
    Behaviors.setup { ctx =>
      Behaviors
        .receiveMessage[UsrTwinCmd] {
          case c: com.domain.user.UsrTwinCmd =>
            c.status match {
              case UsrStatus.CONNECTED =>
                ctx.log.info("★ ★ ★  Start otp:{} ★ ★ ★", c.otp)
                Behaviors.same
              case UsrStatus.DISCONNECTED =>
                ctx.log.info("★ ★ ★ End otp:{} ★ ★ ★", c.otp)
                Behaviors.stopped
              case UsrStatus.Unrecognized(term) =>
                ctx.log.error("★ ★ ★ Unrecognized term: {} ★ ★ ★", term)
                Behaviors.stopped
            }
        }
        .receiveSignal {
          case (_, PostStop) =>
            Behaviors.stopped
        }
    }
}
 */
