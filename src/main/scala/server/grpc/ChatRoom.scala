// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server
package grpc

import scala.concurrent.duration.DurationInt
import com.domain.chat.*

import java.nio.charset.*
import shared.*
import server.grpc.state.ChatState
import org.apache.pekko.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.*
import org.apache.pekko.persistence.typed.state.scaladsl.*
import shared.Domain.{ ChatName, ReplyTo }

import java.nio.ByteBuffer
import cluster.sharding.typed.ShardingMessageExtractor

object ChatRoom {

  type State = ChatState

  val TypeKey = EntityTypeKey[ChatCmd]("chatroom")

  def hexId2Long(hexId: String): Long =
    java.lang.Long.parseUnsignedLong(hexId, 16)

  def hashCmd(line: String): Long = {
    val bts = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8))
    org.apache.pekko.cassandra.CassandraHash.hash(bts, 0, bts.array.length)
  }

  def shardingMessageExtractor2(numOfShards: Int): ShardingMessageExtractor[ChatCmd, ChatCmd] =
    new ShardingMessageExtractor[ChatCmd, ChatCmd] {
      override def entityId(cmd: ChatCmd): String =
        hashCmd(cmd.chat.raw()).toHexString

      override def shardId(entityId: String): String =
        entityId
      // math.abs(hexId2Long(entityId) % numOfShards).toString

      override def unwrapMessage(cmd: ChatCmd): ChatCmd = cmd
    }

  def shardingMessageExtractor(): ShardingMessageExtractor[ChatCmd, ChatCmd] =
    new ShardingMessageExtractor[ChatCmd, ChatCmd] {
      override def entityId(cmd: ChatCmd): String =
        cmd.chat.raw()

      override def shardId(entityId: String): String =
        // one entity per chat|shard to isolate rebalancing. We want to rebalance one chat|shard at a time
        entityId
      // math.abs(entityId.hashCode % numOfShards).toString

      override def unwrapMessage(cmd: ChatCmd): ChatCmd = cmd
    }

  def apply(
      chatId: ChatName,
      appCfg: AppConfig,
    ): Behavior[ChatCmd] =
    Behaviors.setup { ctx =>
      given resolver: ActorRefResolver = ActorRefResolver(ctx.system)

      given strRefResolver: stream.StreamRefResolver = stream.StreamRefResolver(ctx.system)

      given sys: ActorSystem[?] = ctx.system

      given chatRoom: ActorContext[ChatCmd] = ctx

      given sharding: ClusterSharding = ClusterSharding(sys)

      DurableStateBehavior[ChatCmd, ChatState](
        persistence.typed.PersistenceId.ofUniqueId(chatId.raw()),
        emptyState = ChatState(),
        commandHandler = cmdHandler(appCfg),
      )
        .onPersistFailure(SupervisorStrategy.restartWithBackoff(500.millis, 5.seconds, 0.2))
        .receiveSignal {
          case (state, persistence.typed.state.RecoveryCompleted) =>
            val lsn = DurableStateBehavior.lastSequenceNumber(ctx)
            ctx.log.info("Recovery:[{}]. SeqNum:{}", state.toString, lsn)
          case (state, persistence.typed.state.RecoveryFailed(ex)) =>
            ctx.log.error("RecoveryFailed: ", ex)
        }
    }

  def cmdHandler(
      appCfg: AppConfig
    )(using
      sys: ActorSystem[?],
      resolver: actor.typed.ActorRefResolver,
      strRefResolvers: stream.StreamRefResolver,
      ctx: ActorContext[ChatCmd],
      sharding: ClusterSharding,
    ): (ChatState, ChatCmd) => Effect[ChatState] = { (state, cmd) =>
    given ec: scala.concurrent.ExecutionContext = sys.executionContext
    val logger: org.slf4j.Logger = sys.log

    cmd match {
      case Create(chat, replyTo) =>
        state.name match {
          case Some(_) =>
            /*
            import org.apache.pekko.actor.typed.scaladsl.LoggerOps
            logger.info2("{}: Chat({}) already exists", ctx.self.path, chat.raw())
             */
            Effect
              .none[ChatState]
              .thenRun(_ => logger.info("{} already exists", chat.raw()))
              .thenReply(ReplyTo[ChatReply].toBase(replyTo))(_ => ChatReply(chat, ChatReply.StatusCode.ChatExists))

          case None =>
            Effect
              .persist(state.withName(chat, replyTo))
              .thenRun(_ => logger.info("Created Chat({})", chat))
              .thenNoReply()
        }

      case AddUser(chat, user, replyTo) =>
        val replyTo0 = ReplyTo[ChatReply].toBase(replyTo)
        state.name match {
          case Some(chatName) =>
            val rps = state.registeredParticipants
            logger.info(s"Registered-participants:[${rps.mkString(",")}]")

            if (state.registeredParticipants.contains(user)) {
              Effect
                .none[ChatState]
                .thenRun(_ => logger.info("{} already registered", user.raw()))
                .thenReply(replyTo0)(_ => ChatReply(chat, ChatReply.StatusCode.UserExists))
            } else {
              Effect
                .persist(state.withNewUser(user, chat, replyTo))
                .thenRun(_ => logger.info(s"Added ${user.raw()} to ${chat.raw()}"))
                .thenNoReply()
            }
          case None =>
            Effect
              .reply(replyTo0)(ChatReply(chat, ChatReply.StatusCode.UnknownChat))
        }

      case RmUser(chat, user, replyTo) =>
        Effect
          .none[ChatState]
          .thenRun(_ => logger.info("RmUser({}.{}) - unsupported cmd", chat, user))
          .thenStop()

      case AuthUser(chat, user, otp, replyTo) =>
        val replyTo0 = ReplyTo[ChatReply].toBase(replyTo)

        state.name match {
          case Some(chatName) =>
            if (state.registeredParticipants.contains(user)) {
              // if (state.onlineParticipants.contains(user)) Effect.reply(replyTo0)(ChatReply(chat, ChatReply.StatusCode.AlreadyConnected))
              // else {
              import com.bastiaanjansen.otp.*
              val maybeOtp = shared.base64Decode(user.raw()).map { secret =>
                val sBts = appCfg.secretToken.getBytes(StandardCharsets.UTF_8) ++ secret
                new TOTPGenerator.Builder(sBts)
                  .withHOTPGenerator { b =>
                    b.withPasswordLength(8)
                    b.withAlgorithm(HMACAlgorithm.SHA256)
                  }
                  .withPeriod(java.time.Duration.ofSeconds(10))
                  .build()
              }
              if (maybeOtp.map(_.verify(otp.raw())).getOrElse(false)) {
                Effect
                  .reply(replyTo0)(
                    ChatReply(chat, statusCode = ChatReply.StatusCode.Ok)
                  )
              } else {
                Effect
                  .reply(replyTo0)(ChatReply(chat, ChatReply.StatusCode.AuthorizationError))
              }
              // }
            } else {
              Effect.reply(replyTo0)(ChatReply(chat, ChatReply.StatusCode.UnknownUser))
            }
          case None =>
            Effect
              .reply(replyTo0)(ChatReply(chat, ChatReply.StatusCode.UnknownChat))
        }

      case StopChatEntity(chatName) =>
        Effect
          .none[ChatState]
          .thenRun(_ => logger.info("Passivate chat: {} ★ ★ ★", chatName))
          .thenStop()
    }
  }
}
