package server.grpc

import com.datastax.oss.driver.api.core.uuid.Uuids
import com.domain.chatRoom.*
import org.apache.pekko.actor.typed.scaladsl.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import org.apache.pekko.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.*
import server.grpc.chat.*

import scala.collection.immutable.HashSet
import com.domain.chat.{ ChatCmd, * }
import shared.Domain.*
import org.apache.pekko.NotUsed

import java.util.concurrent.ConcurrentHashMap
import cluster.sharding.typed.ShardingMessageExtractor

object ChatRoomSession {

  val TypeKey = EntityTypeKey[ChatRoomCmd]("session")

  def shardingMessageExtractor(): ShardingMessageExtractor[ChatRoomCmd, ChatRoomCmd] =
    new ShardingMessageExtractor[ChatRoomCmd, ChatRoomCmd] {
      override def entityId(cmd: ChatRoomCmd): String =
        cmd.chat.raw()
      // one entity per chat|shard to isolate rebalancing. We want to rebalance one chat|shard at a time
      override def shardId(entityId: String): String =
        entityId
      override def unwrapMessage(cmd: ChatRoomCmd): ChatRoomCmd =
        cmd
    }

  final case class ChatRoomHub(
      sink: Sink[ClientCmd, NotUsed],
      src: Source[ServerCmd, NotUsed])

  final case class ChatRoomState(
      chatName: ChatName,
      cassandraMergeHubSink: Sink[ServerCmd, NotUsed],
      online: HashSet[Participant] = HashSet.empty[Participant],
      recentHistory: Seq[ServerCmd] = Seq.empty,
      ks: Option[KillSwitch] = None,
      maybeHub: Option[ChatRoomHub] = None)

  def apply(
      chat: ChatName,
      chatUserRegion: ActorRef[ChatCmd],
      kss: ConcurrentHashMap[ChatName, KillSwitch],
      cassandraSink: Sink[ServerCmd, NotUsed],
    ): Behavior[ChatRoomCmd] =
    Behaviors.setup { ctx =>
      given resolver: ActorRefResolver = ActorRefResolver(ctx.system)
      given strRefResolver: stream.StreamRefResolver = stream.StreamRefResolver(ctx.system)
      given ac: ActorContext[ChatRoomCmd] = ctx
      active(ChatRoomState(chat, cassandraSink), chatUserRegion, kss)
    }

  def active(
      state: ChatRoomState,
      chatRegion: ActorRef[ChatCmd],
      kss: ConcurrentHashMap[ChatName, KillSwitch],
    )(using
      resolver: ActorRefResolver,
      strRefResolver: stream.StreamRefResolver,
      ctx: ActorContext[ChatRoomCmd],
    ): Behavior[ChatRoomCmd] =
    Behaviors.receiveMessage[ChatRoomCmd] {
      case ConnectRequest(chatName, user, otp, replyTo) =>
        // import org.apache.pekko.actor.typed.scaladsl.LoggerOps
        // logger.info2("{}: Chat({}) already exists", ctx.self.path, chat.raw())
        ctx.log.info("Connection request from User({}). Online: [{}]", user.raw(), state.online.mkString(","))
        val replyTo0 = ReplyTo[ChatReply].toBase(replyTo)
        given sys: ActorSystem[?] = ctx.system
        state.maybeHub match {
          case Some(hub) =>
            val getRecentHistory =
              ServerCmd(
                chatName,
                timeUuid = CassandraTimeUUID(Uuids.timeBased().toString),
                tag = server.grpc.chat.CmdTag.GET,
              )

            val srcRef = (Source.single(getRecentHistory) ++ hub.src).runWith(StreamRefs.sourceRef[ServerCmd]())
            val sinkRef = hub.sink.runWith(StreamRefs.sinkRef[ClientCmd]())
            replyTo0.tell(
              ChatReply(
                chat = chatName,
                sourceRefStr = strRefResolver.toSerializationFormat(srcRef),
                sinkRefStr = strRefResolver.toSerializationFormat(sinkRef),
              )
            )
            ctx.log.info("User({}) Start session:{}", user.raw(), otp.raw())
            active(state.copy(online = state.online + user), chatRegion, kss)

          case None =>
            val ((sink, ks), src) =
              // TODO: try this https://github.com/haghard/akka-pq/blob/master/src/main/scala/sample/blog/processes/StatefulProcess.scala
              MergeHub
                .source[ClientCmd](perProducerBufferSize = 1)
                .mapMaterializedValue { sink =>
                  ctx.log.info(s"MergeHub($chatName) materialization")
                  sink
                }
                .map(clientCmd =>
                  ServerCmd(
                    clientCmd.chat,
                    clientCmd.content,
                    clientCmd.userInfo,
                    CassandraTimeUUID(Uuids.timeBased().toString),
                  )
                )
                // .log(s"$chatName.hub", cmd => s"${cmd.chat.raw()}.${cmd.timeUuid.toUnixTs()}")(sys.toClassic.log)
                // .via(StreamMonitor(s"$chatName.grpc-hub", cmd => s"${cmd.chat.raw()}.${cmd.timeUuid.toUnixTs()}"))
                .withAttributes(Attributes.logLevels(org.apache.pekko.event.Logging.InfoLevel))
                .alsoTo(state.cassandraMergeHubSink)
                .viaMat(KillSwitches.single)(Keep.both)
                .toMat(
                  BroadcastHub
                    .sink[ServerCmd](bufferSize = 1)
                    .mapMaterializedValue { src =>
                      ctx.log.info("BroadcastHub($chatName) materialization"); src
                    }
                )(Keep.both)
                // .addAttributes(stream.ActorAttributes.supervisionStrategy { case NonFatal(ex) =>  stream.Supervision.Resume })
                .run()

            kss.put(chatName, ks)
            val chatRoomHub = ChatRoomHub(sink, src)

            val getRecentHistory =
              ServerCmd(
                chatName,
                timeUuid = CassandraTimeUUID(Uuids.timeBased().toString),
                tag = server.grpc.chat.CmdTag.GET,
              )

            val srcRef = (Source.single(getRecentHistory) ++ chatRoomHub.src).runWith(StreamRefs.sourceRef[ServerCmd]())
            val sinkRef = chatRoomHub.sink.runWith(StreamRefs.sinkRef[ClientCmd]())

            replyTo0.tell(
              ChatReply(
                chat = chatName,
                sourceRefStr = strRefResolver.toSerializationFormat(srcRef),
                sinkRefStr = strRefResolver.toSerializationFormat(sinkRef),
              )
            )

            ctx.log.info(s"User({}). Start session:{}", user.raw(), otp.raw())
            active(
              state.copy(online = state.online + user, ks = Some(ks), maybeHub = Some(chatRoomHub)),
              chatRegion,
              kss,
            )
        }

      case Disconnect(user, chatName, otp) =>
        val updatedOnlineUsers = state.online - user
        ctx.log.info(s"User({}). End session:{}", user.raw(), otp.raw())
        if (updatedOnlineUsers.isEmpty) {
          state.ks.foreach(_.shutdown())
          Option(kss.remove(chatName)).foreach(_.shutdown())
          ctx.log.info("★ ★ ★ Passivate chat-room: {} ★ ★ ★", state.chatName)
          chatRegion.tell(StopChatEntity(chatName))
          Behaviors.stopped
        } else {
          active(state.copy(online = updatedOnlineUsers), chatRegion, kss)
        }
    }
}
