// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server
package grpc

import scala.concurrent.duration.DurationInt
import com.domain.chat.*

import scala.concurrent.*
import java.nio.charset.*
import java.util.concurrent.ConcurrentHashMap
import shared.*
import server.grpc.chat.ChangeDataCapture.*
import server.grpc.state.ChatState
import org.apache.pekko.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.*
import org.apache.pekko.persistence.typed.state.scaladsl.*
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.*
import org.apache.pekko.util.Timeout
import shared.Domain.{ ChatName, ReplyTo }
import server.grpc.chat.*
import org.apache.pekko.actor.typed.scaladsl.adapter.TypedSchedulerOps

object Chat {

  type State = ChatState

  val TypeKey = EntityTypeKey[ChatCmd]("chat")

  final case class ChatRoomHub(sink: Sink[ClientCmd, NotUsed], src: Source[ServerCmd, NotUsed])

  def shardingMessageExtractor(numOfShards: Int) =
    new cluster.sharding.typed.ShardingMessageExtractor[ChatCmd, ChatCmd] {
      override def entityId(cmd: ChatCmd): String =
        cmd.chat.raw()

      override def shardId(entityId: String): String =
        math.abs(entityId.hashCode % numOfShards).toString

      override def unwrapMessage(cmd: ChatCmd): ChatCmd = cmd
    }

  def writeSingleMsg(
      requestId: String,
      clientCmd: ClientCmd,
    )(using
      sharding: ClusterSharding,
      sys: ActorSystem[?],
      resolver: ActorRefResolver,
    ): Future[ServerCmd] = {
    val p = Promise[ServerCmd]()
    val respondee = sys.systemActorOf(Respondee(requestId, p), requestId)
    val cmd = ServerCmd(clientCmd.chat, clientCmd.content, clientCmd.userInfo)
    sharding
      .entityRefFor(Chat.TypeKey, clientCmd.chat.raw())
      .tell(PostMessage(cmd.chat, cmd.content, cmd.userInfo, ReplyTo[ServerCmd].toCustom(respondee)))
    p.future
  }

  def apply(
      chatId: ChatName,
      kss: ConcurrentHashMap[ChatName, KillSwitch],
      appCfg: AppConfig,
    ): Behavior[ChatCmd] =
    Behaviors.setup { ctx =>
      given resolver: ActorRefResolver = ActorRefResolver(ctx.system)
      given streamRes: stream.StreamRefResolver = stream.StreamRefResolver(ctx.system)
      given sys: ActorSystem[?] = ctx.system
      given chatRoom: ActorContext[ChatCmd] = ctx
      given to: util.Timeout = util.Timeout(3.seconds)

      DurableStateBehavior[ChatCmd, ChatState](
        persistence.typed.PersistenceId.ofUniqueId(chatId.raw()),
        ChatState(),
        cmdHandler(kss, appCfg),
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
      kss: ConcurrentHashMap[ChatName, KillSwitch],
      appCfg: AppConfig,
    )(using
      sys: ActorSystem[?],
      resolver: actor.typed.ActorRefResolver,
      streamRefs: stream.StreamRefResolver,
      ctx: ActorContext[ChatCmd],
      to: util.Timeout,
    ): (ChatState, ChatCmd) => Effect[ChatState] = { (state, cmd) =>
    val logger = ctx.log

    given sharding: ClusterSharding = ClusterSharding(sys)
    given ec: scala.concurrent.ExecutionContext = sys.executionContext
    given sch: org.apache.pekko.actor.Scheduler = sys.scheduler.toClassic

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
        state.name match {
          case Some(chatName) =>
            val rps = state.registeredParticipants
            logger.info(s"Participants:[${rps.mkString(",")}]")

            if (state.registeredParticipants.contains(user)) {
              Effect
                .none[ChatState]
                .thenRun(_ => logger.info("{} already registered", user.raw()))
                .thenReply(ReplyTo[ChatReply].toBase(replyTo))(_ => ChatReply(chat, ChatReply.StatusCode.UserExists))

            } else {
              Effect
                .persist(state.withNewUser(user, chat, replyTo))
                .thenRun(_ => logger.info(s"Added ${user.raw()} to ${chat.raw()}"))
                .thenNoReply()
            }
          case None =>
            Effect
              .reply(ReplyTo[ChatReply].toBase(replyTo))(ChatReply(chat, ChatReply.StatusCode.UnknownChat))
        }

      case RmUser(chat, user, replyTo) =>
        Effect
          .none[ChatState]
          .thenRun(_ => logger.info("RmUser({}.{}) - unsupported cmd", chat, user))
          .thenStop()

      case AuthUser(chat, user, otp, replyTo) =>
        state.name match {
          case Some(chatName) =>
            if (state.registeredParticipants.contains(user)) {
              if (state.onlineParticipants.contains(user))
                Effect
                  .reply(ReplyTo[ChatReply].toBase(replyTo))(ChatReply(chat, ChatReply.StatusCode.AlreadyConnected))
              else {
                import com.bastiaanjansen.otp.*
                val maybeOtp = shared.base64Decode(user.raw()).map { secret =>
                  val sBts = appCfg.salt.getBytes(StandardCharsets.UTF_8) ++ secret
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
                    .reply(ReplyTo[ChatReply].toBase(replyTo))(ChatReply(chat, ChatReply.StatusCode.Ok))
                } else {
                  Effect
                    .reply(ReplyTo[ChatReply].toBase(replyTo))(ChatReply(chat, ChatReply.StatusCode.AuthorizationError))
                }
              }
            } else {
              Effect.reply(ReplyTo[ChatReply].toBase(replyTo))(ChatReply(chat, ChatReply.StatusCode.UnknownUser))
            }
          case None =>
            Effect
              .reply(ReplyTo[ChatReply].toBase(replyTo))(ChatReply(chat, ChatReply.StatusCode.UnknownChat))
        }

      case ConnectRequest(chat, user, otp, replyTo) =>
        // val settings = StreamRefAttributes.subscriptionTimeout(3.seconds).and(stream.Attributes.inputBuffer(2, 4))
        logger.info("ConnectRequest {} - Online:[{}]", user.raw(), state.onlineParticipants.mkString(","))

        state.maybeActiveHub match {
          case Some(hub) =>
            val srcRef = hub.src.runWith(StreamRefs.sourceRef[ServerCmd]())
            val sinkRef = hub.sink.runWith(StreamRefs.sinkRef[ClientCmd]())
            Effect
              .persist(state.withUsrConnected(user, otp))
              .thenReply(ReplyTo[ChatReply].toBase(replyTo))(_ =>
                ChatReply(
                  chat = chat,
                  sourceRefStr = streamRefs.toSerializationFormat(srcRef),
                  sinkRefStr = streamRefs.toSerializationFormat(sinkRef),
                )
              )

          case None =>
            val ((sink, ks), src) =
              MergeHub
                .source[ClientCmd](1)
                .mapAsync(1) { clientCmd =>
                  // Uuids.timeBased().toString
                  val requestId = wvlet.airframe.ulid.ULID.newULID.toString
                  pattern.retry(
                    () => writeSingleMsg(requestId, clientCmd),
                    attempts = Int.MaxValue,
                    delay = 2.seconds,
                  )
                }
                .viaMat(KillSwitches.single)(Keep.both)
                .toMat(BroadcastHub.sink[ServerCmd](1))(Keep.both)
                // .addAttributes(stream.ActorAttributes.supervisionStrategy { case NonFatal(ex) =>  stream.Supervision.Resume })
                .run()

            val chatRoomHub = ChatRoomHub(sink, src)
            kss.put(chat, ks)

            val srcRef = chatRoomHub.src.runWith(StreamRefs.sourceRef[ServerCmd]())
            val sinkRef = chatRoomHub.sink.runWith(StreamRefs.sinkRef[ClientCmd]())

            Effect
              .persist(state.withFirstUsrConnected(chatRoomHub, user, otp))
              .thenReply(ReplyTo[ChatReply].toBase(replyTo))(_ =>
                ChatReply(
                  chat,
                  sourceRefStr = streamRefs.toSerializationFormat(srcRef),
                  sinkRefStr = streamRefs.toSerializationFormat(sinkRef),
                )
              )
        }

      case Disconnect(user, chat, otp, maybeLastMsg) =>
        Effect
          .persist(state.withDisconnected(user, otp))
          .thenNoReply()

      case PostMessage(chat, content, userInfo, replyTo) =>
        // DurableStateBehavior.lastSequenceNumber(ctx)
        Effect
          .persist(state.withMsgPosted(content, userInfo, replyTo))
          .thenNoReply()

      case StopChatEntity(chat) =>
        Effect
          .none[ChatState]
          .thenRun { _ =>
            logger.info("Passivate: {} ★ ★ ★", chat)

            state.onlineParticipants.foreach { ps =>
              sharding
                .entityRefFor(UserTwin.TypeKey, UserTwin.key(chat, ps))
                .!(com.domain.user.DisconnectUsr(chat, ps))
            }
            state.maybeActiveHub.foreach { _ =>
              Option(kss.remove(chat)).foreach(_.shutdown())
            }
          }
          .thenStop()
    }
  }
}
