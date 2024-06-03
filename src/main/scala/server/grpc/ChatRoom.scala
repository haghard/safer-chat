package server.grpc

import com.datastax.oss.driver.api.core.uuid.Uuids
import com.domain.chatRoom.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRefResolver, Behavior }
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import org.apache.pekko.stream.KillSwitch
import org.apache.pekko.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.*
import shared.Domain.{ ChatName, ReplyTo }
import server.grpc.chat.*

import scala.collection.immutable.HashSet
import com.domain.chat.{ ChatCmd, * }
import shared.Domain.*
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.scaladsl.adapter.TypedActorSystemOps
import org.apache.pekko.event.LoggingAdapter

import java.util.concurrent.ConcurrentHashMap
import cluster.sharding.typed.ShardingMessageExtractor

object ChatRoom {

  val TypeKey = EntityTypeKey[ChatRoomCmd]("chat-room")

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
      given sys: ActorSystem[?] = ctx.system
      given logger: LoggingAdapter = ctx.system.toClassic.log
      given resolver: ActorRefResolver = ActorRefResolver(ctx.system)
      given strRefResolver: stream.StreamRefResolver = stream.StreamRefResolver(ctx.system)
      active(ChatRoomState(chat, cassandraSink), chatUserRegion, kss)
    }

  def active(
      state: ChatRoomState,
      chatUserRegion: ActorRef[ChatCmd],
      kss: ConcurrentHashMap[ChatName, KillSwitch],
    )(using
      sys: ActorSystem[?],
      resolver: ActorRefResolver,
      strRefResolver: stream.StreamRefResolver,
      logger: LoggingAdapter,
    ): Behavior[ChatRoomCmd] =
    Behaviors.receiveMessage[ChatRoomCmd] {
      case ConnectRequest(chatName, user, otp, replyTo) =>
        logger.info("ConnectRequest from {}. Online: [{}]", user.raw(), state.online.mkString(","))
        val replyTo0 = ReplyTo[ChatReply].toBase(replyTo)
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
            // TODO: log session user, otp,
            active(state.copy(online = state.online + user), chatUserRegion, kss)

          case None =>
            val ((sink, ks), src) =
              // TODO: try this https://github.com/haghard/akka-pq/blob/master/src/main/scala/sample/blog/processes/StatefulProcess.scala
              MergeHub
                .source[ClientCmd](perProducerBufferSize = 1)
                // .throttle(128, 1.second, 128, ThrottleMode.shaping)
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
                .toMat(BroadcastHub.sink[ServerCmd](bufferSize = 1))(Keep.both)
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

            val srcRef =
              (Source.single(getRecentHistory) ++ chatRoomHub.src).runWith(StreamRefs.sourceRef[ServerCmd]())
            val sinkRef = chatRoomHub.sink.runWith(StreamRefs.sinkRef[ClientCmd]())
            replyTo0.tell(
              ChatReply(
                chat = chatName,
                sourceRefStr = strRefResolver.toSerializationFormat(srcRef),
                sinkRefStr = strRefResolver.toSerializationFormat(sinkRef),
              )
            )
            active(
              state.copy(online = state.online + user, ks = Some(ks), maybeHub = Some(chatRoomHub)),
              chatUserRegion,
              kss,
            )
        }

      case Disconnect(user, chat, otp) =>
        val updated = state.online - user
        logger.info(s"Disconnect: $user - Online: [${state.online.mkString(",")}]")
        if (updated.isEmpty) {
          state.ks.foreach(_.shutdown())
          Option(kss.remove(chat)).foreach(_.shutdown())
          logger.info("★ ★ ★ Stopped: {} ★ ★ ★", state.chatName)
          chatUserRegion.tell(StopChatEntity(chat))
          Behaviors.stopped
        } else {
          active(state.copy(online = updated), chatUserRegion, kss)
        }
    }
}
