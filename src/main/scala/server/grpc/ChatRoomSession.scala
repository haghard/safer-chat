// Copyright (c) 2024-26 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

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
import com.domain.chat.*
import shared.Domain.*
import org.apache.pekko.NotUsed

import java.util.concurrent.ConcurrentHashMap
import cluster.sharding.typed.ShardingMessageExtractor
import com.domain.chat.session.cassandra.commands.CassandraCmd
import org.apache.pekko.cassandra.*

import java.time.{ Instant, ZonedDateTime }
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

object ChatRoomSession {

  val TypeKey = EntityTypeKey[ChatRoomCmd]("session")

  def shardingMessageExtractor(numberOfShards: Int): ShardingMessageExtractor[ChatRoomCmd, ChatRoomCmd] =
    new ShardingMessageExtractor[ChatRoomCmd, ChatRoomCmd] {
      override def entityId(cmd: ChatRoomCmd): String =
        cmd.chat.raw()

      override def shardId(entityId: String): String =
        math.abs(entityId.hashCode % numberOfShards).toString

      override def unwrapMessage(cmd: ChatRoomCmd): ChatRoomCmd =
        cmd
    }

  final case class ChatRoomHub(
      sink: Sink[LiveClientMessage, NotUsed],
      src: Source[ServerCmd, NotUsed])

  final case class ChatRoomState(
      chatName: ChatName,
      onlineUsers: HashSet[Participant] = HashSet.empty[Participant],
      ks: Option[KillSwitch] = None,
      maybeHub: Option[ChatRoomHub] = None)

  def apply(
      chatName: ChatName,
      kss: ConcurrentHashMap[ChatName, KillSwitch],
    ): Behavior[ChatRoomCmd] =
    Behaviors.setup { ctx =>
      Behaviors.withStash(64) { sb =>
        given ActorRefResolver = ActorRefResolver(ctx.system)
        given stream.StreamRefResolver = stream.StreamRefResolver(ctx.system)
        given ActorContext[ChatRoomCmd] = ctx
        given StashBuffer[ChatRoomCmd] = sb
        active(
          ChatRoomState(chatName),
          ChatRoomExtension(ctx.system).readQueue,
          kss,
        )
      }
    }

  def await(
      state: ChatRoomState,
      readQueue: SourceQueueWithComplete[(CassandraCmd, Promise[?])],
      kss: ConcurrentHashMap[ChatName, KillSwitch],
    )(using
      resolver: ActorRefResolver,
      strRefResolver: stream.StreamRefResolver,
      ctx: ActorContext[ChatRoomCmd],
      sb: StashBuffer[ChatRoomCmd],
    ): Behavior[ChatRoomCmd] =
    Behaviors.receiveMessage[ChatRoomCmd] {
      case LastSeenBucket(lastSeenBucket, chatName, user, recentHistory, replyTo) =>
        given sys: ActorSystem[?] = ctx.system

        val chatName = state.chatName
        val refReplyTo = ReplyTo[ChatReply].toBase(replyTo)

        val (chatRoomSessionsSink, ks0) = ChatSessionExtension(ctx.system).chatSessionSharedSink
        kss.putIfAbsent(ChatName("names"), ks0)

        val ((sink, ks), src) =
          MergeHub
            .source[LiveClientMessage](perProducerBufferSize = 1)
            .mapMaterializedValue { sink =>
              ctx.log.info(s"MergeHub(${ctx.self.path.toString})")
              sink
            }
            .map(clientCmd =>
              LiveServerMessage(
                clientCmd.chat,
                clientCmd.content,
                clientCmd.userInfo,
                CassandraTimeUUID(Uuids.timeBased().toString),
              )
            )
            .scan[(String, Option[LiveServerMessage])]((lastSeenBucket, None)) {
              case ((lastSeenBucket, lastCmd), cmd) =>
                val ts = cmd.timeUuid.toUnixTs()
                val currentBucket = CassandraStore
                  .formatterMM
                  .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), CassandraStore.UTC))
                if (lastSeenBucket != currentBucket)
                  (currentBucket, Some(cmd.withBucketName(BucketName(currentBucket)).withIsNewBucketStarted(true)))
                else
                  (currentBucket, Some(cmd.withBucketName(BucketName(currentBucket))))
            }
            .collect { case (currentBucket, Some(cmd)) => cmd }
            // .log(s"$chatName.hub", cmd => s"${cmd.chat.raw()}.${cmd.timeUuid.toUnixTs()}")(sys.toClassic.log)
            // .via(StreamMonitor(s"$chatName.grpc-hub", cmd => s"${cmd.chat.raw()}.${cmd.timeUuid.toUnixTs()}"))
            .withAttributes(Attributes.logLevels(org.apache.pekko.event.Logging.InfoLevel))
            .alsoTo(chatRoomSessionsSink)
            .viaMat(KillSwitches.single)(Keep.both)
            .toMat(
              BroadcastHub
                .sink[LiveServerMessage](bufferSize = 1)
                .mapMaterializedValue { src =>
                  ctx.log.info(s"BroadcastHub(${ctx.self.path.toString})")
                  src
                }
            )(Keep.both)
            // .addAttributes(stream.ActorAttributes.supervisionStrategy { case NonFatal(ex) =>  stream.Supervision.Resume })
            .run()

        kss.put(chatName, ks)

        val chatRoomHub = ChatRoomHub(sink, src)
        val srcRef: SourceRef[ServerCmd] =
          (Source(recentHistory) ++ chatRoomHub.src).runWith(StreamRefs.sourceRef[ServerCmd]())
        val sinkRef: SinkRef[LiveClientMessage] =
          chatRoomHub.sink.runWith(StreamRefs.sinkRef[LiveClientMessage]())

        refReplyTo.tell(
          ChatReply(
            chat = chatName,
            sourceRefStr = strRefResolver.toSerializationFormat(srcRef),
            sinkRefStr = strRefResolver.toSerializationFormat(sinkRef),
          )
        )

        ctx.log.info(s"User({}) started session", user.raw())
        sb.unstashAll(
          active(
            state.copy(
              onlineUsers = state.onlineUsers + user,
              ks = Some(ks),
              maybeHub = Some(chatRoomHub),
            ),
            readQueue,
            kss,
          )
        )

      case other =>
        sb.stash(other)
        Behaviors.same
    }

  def active(
      state: ChatRoomState,
      readQueue: SourceQueueWithComplete[(CassandraCmd, Promise[?])],
      kss: ConcurrentHashMap[ChatName, KillSwitch],
    )(using
      resolver: ActorRefResolver,
      strRefResolver: stream.StreamRefResolver,
      ctx: ActorContext[ChatRoomCmd],
      sb: StashBuffer[ChatRoomCmd],
    ): Behavior[ChatRoomCmd] =
    Behaviors.receiveMessage[ChatRoomCmd] {
      case ConnectRequest(chatName, user, otp, replyTo) =>
        // import org.apache.pekko.actor.typed.scaladsl.LoggerOps
        // logger.info2("{}: Chat({}) already exists", ctx.self.path, chat.raw())
        ctx
          .log
          .warn(
            "{}: Connection request from User({}). Online: [{}]",
            ctx.self.path,
            user.raw(),
            state.onlineUsers.mkString(","),
          )
        val refReplyTo = ReplyTo[ChatReply].toBase(replyTo)
        given sys: ActorSystem[?] = ctx.system
        state.maybeHub match {
          case Some(hub) =>

            val nowTs = CassandraTimeUUID(Uuids.timeBased().toString)
            val currentBucket = CassandraStore
              .formatterMM
              .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowTs.toUnixTs()), CassandraStore.UTC))

            // LiveClientMessage, LiveServerMessage

            // Option1: Send getRecentHistory to all clients
            // Source.single(ClientCmd(chat = chatName, userInfo = UserInfo(user = user))).runWith(hub.sink)
            // val srcRef = hub.src.runWith(StreamRefs.sourceRef[ServerCmd]())

            // Option2: Send getRecentHistory  only for this client
            val srcRef = (Source.single(FetchRecentHistory(chatName, BucketName(currentBucket))) ++ hub.src)
              .runWith(StreamRefs.sourceRef[ServerCmd]())
            val sinkRef = hub.sink.runWith(StreamRefs.sinkRef[LiveClientMessage]())
            refReplyTo.tell(
              ChatReply(
                chat = chatName,
                sourceRefStr = strRefResolver.toSerializationFormat(srcRef),
                sinkRefStr = strRefResolver.toSerializationFormat(sinkRef),
              )
            )
            ctx.log.info("User({}) Start session:{}", user.raw(), otp.raw())
            active(state.copy(onlineUsers = state.onlineUsers + user), readQueue, kss)

          case None =>
            val p = ExpiringPromise[(String, Seq[ServerCmd])](3.seconds)
            val f = readQueue
              .offer((com.domain.chat.session.cassandra.commands.GetLatestBucketName(state.chatName), p))
              .flatMap {
                case QueueOfferResult.Enqueued =>
                  p.future
                case QueueOfferResult.Dropped =>
                  Future.failed(new Exception("GetLatestBucketName read overflow"))
                case result: QueueCompletionResult =>
                  Future.failed(new Exception("Unexpected"))
              }(ctx.executionContext)

            ctx.pipeToSelf(f) {
              case Success((lastSeenBucket, recent)) =>
                LastSeenBucket(lastSeenBucket, chatName, user, recent, replyTo)
              case Failure(ex) =>
                throw new Exception(s"Read last seen bucket error for ${chatName.raw()}")
            }
            await(state, readQueue, kss)
        }

      case Disconnect(user, chatName, otp) =>
        val updatedOnlineUsers = state.onlineUsers - user
        ctx.log.info(s"User({}). Closed session:{}", user.raw(), otp.raw())
        if updatedOnlineUsers.isEmpty then {
          state.ks.foreach(_.shutdown())
          Option(kss.remove(chatName)).foreach(_.shutdown())
          Behaviors.stopped
        } else {
          active(state.copy(onlineUsers = updatedOnlineUsers), readQueue, kss)
        }

      case _: com.domain.chatRoom.LastSeenBucket =>
        Behaviors.same
    }
}
