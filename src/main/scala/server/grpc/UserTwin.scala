// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc

import com.domain.user.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.*
import shared.Domain.{ ChatName, Participant }

object UserTwin {

  val TypeKey = EntityTypeKey[UserTwinCmd]("usr")

  def key(chat: ChatName, usr: Participant): String =
    s"${chat.raw()}@${usr.raw()}"

  def shardingMessageExtractor(numOfShards: Int) =
    new org.apache.pekko.cluster.sharding.typed.ShardingMessageExtractor[UserTwinCmd, UserTwinCmd] {
      override def entityId(cmd: UserTwinCmd): String =
        cmd match {
          case c: com.domain.user.ConnectUsr =>
            key(c.chat, c.user)
          case c: com.domain.user.DisconnectUsr =>
            key(c.chat, c.user)
        }

      override def shardId(entityId: String): String = {
        val chatId = entityId.split('@').head
        math.abs(chatId.hashCode % numOfShards).toString
      }

      override def unwrapMessage(cmd: UserTwinCmd): UserTwinCmd = cmd
    }

  def apply(): Behavior[UserTwinCmd] =
    Behaviors.setup { ctx =>
      Behaviors
        .receiveMessage[UserTwinCmd] {
          case c: com.domain.user.ConnectUsr =>
            ctx.log.info("★ ★ ★  Start otp:{} ★ ★ ★", c.otp)
            Behaviors.same
          case c: com.domain.user.DisconnectUsr =>
            ctx.log.info("★ ★ ★ End otp:{} ★ ★ ★", c.otp)
            Behaviors.stopped
        }
        .receiveSignal {
          case (_, PostStop) =>
            Behaviors.stopped
        }
    }
}
