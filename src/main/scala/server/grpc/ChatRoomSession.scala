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
import server.grpc.chat.ServerCmdMessage.SealedValue

import java.time.{ Instant, ZonedDateTime }
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

object ChatRoomSession {

  val TypeKey = EntityTypeKey[ChatRoomCmd]("session")

  case class SessionState[T: scala.reflect.ClassTag] private (
      buffer: RingBuffer[T],
      lastSeenBucket: String,
      isRequestedBy: Option[Participant]) {

    def this(capacity: Int, lastSeenBucket: String) =
      this(new RingBuffer[T](capacity), lastSeenBucket, None)

    def fetchRequested(user: Participant): SessionState[T] =
      copy(isRequestedBy = Some(user))

    def add(element: T): SessionState[T] = {
      buffer.add(element)
      copy(isRequestedBy = None)
    }

    def add(bucket: String, element: T): SessionState[T] = {
      buffer.add(element)
      copy(isRequestedBy = None, lastSeenBucket = bucket)
    }

    def messages() = buffer.messages()

    def mostRecent() = buffer.mostRecent()
  }

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
      chatRoomHub: Option[ChatRoomHub] = None,
      recentHistoryRequestQueue: Option[SourceQueueWithComplete[ServerCmd]] = None)

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
      case RecentHistoryResult(lastSeenBucket, chatName, user, recentMessages, replyTo) =>
        given sys: ActorSystem[?] = ctx.system

        val chatName = state.chatName
        val refReplyTo = ReplyTo[ChatReply].toBase(replyTo)

        val (chatRoomSessionsSink, ks0) = ChatSessionExtension(ctx.system).chatSessionSharedSink
        kss.putIfAbsent(ChatName("session-sinks"), ks0)

        val sessionState = new SessionState[LiveServerMessage](1 << 4, lastSeenBucket)
        recentMessages.foreach(sessionState.add)

        val (((sink, recentHistoryRequestQueue), ks), src) =
          MergeHub
            .source[LiveClientMessage](perProducerBufferSize = 1)
            .map[ServerCmd](clientCmd =>
              LiveServerMessage(
                clientCmd.chat,
                clientCmd.content,
                clientCmd.userInfo,
                CassandraTimeUUID(Uuids.timeBased().toString),
              )
            )
            .mergeMat(Source.queue[ServerCmd](64, OverflowStrategy.backpressure))(Keep.both)
            .scan(sessionState) {
              case (sessionState, cmd) =>
                cmd.asMessage.sealedValue match {
                  case SealedValue.LiveServerMessage(cmd) =>
                    val ts = cmd.timeUuid.toUnixTs()
                    val currentBucket = CassandraStore
                      .formatterMM
                      .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), CassandraStore.UTC))

                    if (sessionState.lastSeenBucket != currentBucket)
                      sessionState.add(
                        currentBucket,
                        cmd.withBucketName(BucketName(currentBucket)).withIsNewBucketStarted(true),
                      )
                    else
                      sessionState.add(cmd.withBucketName(BucketName(currentBucket)))

                  case SealedValue.FlushRecentHistory(cmd) =>
                    sessionState.fetchRequested(cmd.user)
                  case SealedValue.RecentHistoryMessage(_) =>
                    sessionState
                  case SealedValue.Empty =>
                    sessionState
                }
            }
            .map { state =>
              state.isRequestedBy match {
                case Some(user) =>
                  RecentHistoryMessage(user, state.messages())
                case None =>
                  state.mostRecent()
              }
            }
            .alsoTo(chatRoomSessionsSink)
            .withAttributes(Attributes.logLevels(org.apache.pekko.event.Logging.InfoLevel))
            .viaMat(KillSwitches.single)(Keep.both)
            .toMat(BroadcastHub.sink[ServerCmd](bufferSize = 1))(Keep.both)
            .run()

        kss.put(chatName, ks)
        val chatRoomHub = ChatRoomHub(sink, src)

        val settings = StreamRefAttributes
          .subscriptionTimeout(2.seconds)
          .and(org.apache.pekko.stream.Attributes.inputBuffer(4, 4))
        val srcRef: SourceRef[ServerCmd] =
          chatRoomHub.src.runWith(StreamRefs.sourceRef[ServerCmd]().withAttributes(settings))
        val sinkRef: SinkRef[LiveClientMessage] =
          chatRoomHub.sink.runWith(StreamRefs.sinkRef[LiveClientMessage]().withAttributes(settings))

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
              chatRoomHub = Some(chatRoomHub),
              recentHistoryRequestQueue = Some(recentHistoryRequestQueue),
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
        state.chatRoomHub match {
          case Some(hub) =>
            val srcRef = hub.src.runWith(StreamRefs.sourceRef[ServerCmd]())
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
            val readP = ExpiringPromise[(String, Seq[LiveServerMessage])](5.seconds)
            val f = readQueue
              .offer((com.domain.chat.session.cassandra.commands.GetLatestBucketName(state.chatName), readP))
              .flatMap {
                case QueueOfferResult.Enqueued =>
                  readP.future
                case QueueOfferResult.Dropped =>
                  Future.failed(new Exception("GetLatestBucketName read overflow"))
                case result: QueueCompletionResult =>
                  Future.failed(new Exception("Unexpected"))
              }(ctx.executionContext)

            ctx.pipeToSelf(f) {
              case Success((lastSeenBucket, recent)) =>
                RecentHistoryResult(lastSeenBucket, chatName, user, recent, replyTo)
              case Failure(ex) =>
                throw new Exception(s"Read last seen bucket error for ${chatName.raw()}")
            }
            await(state, readQueue, kss)
        }

      case RequestRecentHistory(chatName, user, replyTo) =>
        ctx.log.warn("RequestRecentHistory {}@{}", chatName.raw(), user.raw())
        // TODO: make sure it is queued up.
        state.recentHistoryRequestQueue.foreach(_.offer(FlushRecentHistory(chatName, user)))
        ReplyTo[ChatReply].toBase(replyTo).tell(ChatReply(chat = chatName))
        Behaviors.same

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

      case _: com.domain.chatRoom.RecentHistoryResult =>
        Behaviors.same
    }
}
