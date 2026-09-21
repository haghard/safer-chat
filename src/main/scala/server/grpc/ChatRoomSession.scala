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

import java.util.concurrent.*
import cluster.sharding.typed.ShardingMessageExtractor
import com.domain.chat.session.cassandra.commands.CassandraCmd
import org.apache.pekko.cassandra.*
import server.grpc.chat.ServerCmdMessage.SealedValue

import java.time.*
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

  final case class ChatRoomSessionState[T: scala.reflect.ClassTag] private (
      buffer: RingBuffer[T],
      activeBucket: String,
      isHistoryRequestedBy: Option[Participant],
      updatedBy: Option[Participant]) {

    def this(capacity: Int, lastSeenBucket: String) =
      this(new RingBuffer[T](capacity), lastSeenBucket, None, None)

    def fetchRequested(p: Participant): ChatRoomSessionState[T] =
      copy(isHistoryRequestedBy = Some(p))

    def add(element: T): ChatRoomSessionState[T] = {
      buffer.add(element)
      copy(isHistoryRequestedBy = None)
    }

    def add(element: T, p: Participant): ChatRoomSessionState[T] = {
      buffer.add(element)
      copy(isHistoryRequestedBy = None, updatedBy = Some(p))
    }

    def add(
        bucket: String,
        element: T,
        p: Participant,
      ): ChatRoomSessionState[T] = {
      buffer.add(element)
      copy(isHistoryRequestedBy = None, activeBucket = bucket, updatedBy = Some(p))
    }

    def messages() = buffer.messages()

    def mostRecent() = buffer.mostRecent()
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

        given steamRefSettings: Attributes = StreamRefAttributes
          .subscriptionTimeout(4.seconds)
          .and(org.apache.pekko.stream.Attributes.inputBuffer(8, 32))

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
      settings: Attributes,
    ): Behavior[ChatRoomCmd] =
    Behaviors.receiveMessage[ChatRoomCmd] {
      case RecentHistoryResult(lastSeenBucket, chatName, user, recentMessages, replyTo) =>
        given sys: ActorSystem[?] = ctx.system

        val chatName = state.chatName
        val refReplyTo = ReplyTo[ChatReply].toBase(replyTo)

        val (chatRoomSessionsSink, ks0) = ChatSessionExtension(ctx.system).chatSessionSharedSink
        kss.putIfAbsent(ChatName("session-sinks"), ks0)

        val sessionState = new ChatRoomSessionState[LiveServerMessage](1 << 4, lastSeenBucket)
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
            .mergeMat(Source.queue[ServerCmd](1 << 7, OverflowStrategy.backpressure))(Keep.both)
            .scan(sessionState) {
              case (sessionState, cmd) =>
                cmd.asMessage.sealedValue match {
                  case SealedValue.LiveServerMessage(cmd) =>
                    val candidateBucket = CassandraStore
                      .formatterMM
                      .format(
                        ZonedDateTime.ofInstant(Instant.ofEpochMilli(cmd.timeUuid.toUnixTs()), CassandraStore.UTC)
                      )

                    if (sessionState.activeBucket != candidateBucket)
                      sessionState.add(
                        candidateBucket,
                        cmd.withBucketName(BucketName(candidateBucket)).withIsNewBucketStarted(true),
                        cmd.userInfo.user,
                      )
                    else
                      sessionState.add(cmd.withBucketName(BucketName(candidateBucket)), cmd.userInfo.user)

                  case SealedValue.FlushRecentHistory(cmd) =>
                    sessionState.fetchRequested(cmd.user)
                  case _: SealedValue.RecentHistoryMessage =>
                    sessionState
                  case SealedValue.Empty =>
                    sessionState
                }
            }
            .map { sessionState =>
              sessionState.isHistoryRequestedBy match {
                case Some(user) =>
                  Some(RecentHistoryMessage(user, sessionState.messages()))
                case None =>
                  sessionState.updatedBy.flatMap(_ => sessionState.mostRecent())
              }
            }
            .collect { case Some(msg) => msg }
            .alsoTo(chatRoomSessionsSink)
            .withAttributes(Attributes.logLevels(org.apache.pekko.event.Logging.InfoLevel))
            .viaMat(KillSwitches.single)(Keep.both)
            .toMat(BroadcastHub.sink[ServerCmd](bufferSize = 1))(Keep.both)
            .run()

        kss.put(chatName, ks)
        val chatRoomHub = ChatRoomHub(sink, src)

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
      settings: Attributes,
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

        if (state.onlineUsers.contains(user)) {
          refReplyTo.tell(ChatReply(chat = chatName, statusCode = ChatReply.StatusCode.AlreadyConnected))
          Behaviors.same
        } else {
          given sys: ActorSystem[?] = ctx.system

          state.chatRoomHub match {
            case Some(hub) =>
              val srcRef = hub.src.runWith(StreamRefs.sourceRef[ServerCmd]().withAttributes(settings))
              val sinkRef = hub.sink.runWith(StreamRefs.sinkRef[LiveClientMessage]().withAttributes(settings))
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
                .offer((com.domain.chat.session.cassandra.commands.GetRecentHistory(state.chatName), readP))
                .flatMap {
                  case QueueOfferResult.Enqueued =>
                    readP.future
                  case QueueOfferResult.Dropped =>
                    Future.failed(new Exception("GetLatestBucketName read overflow"))
                  case result: QueueCompletionResult =>
                    Future.failed(new Exception("Unexpected"))
                }(ctx.executionContext)

              ctx.pipeToSelf(f) {
                case Success((bucket, recentHistory)) =>
                  RecentHistoryResult(bucket, chatName, user, recentHistory, replyTo)
                case Failure(ex) =>
                  throw new Exception(s"Read last seen bucket error for ${chatName.raw()}")
              }
              await(state, readQueue, kss)
          }
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
