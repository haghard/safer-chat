// Copyright (c) 2024-26 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package org.apache.pekko.cassandra

import com.datastax.oss.driver.api.core.*
import com.datastax.oss.driver.api.core.cql.*
import com.datastax.oss.driver.api.core.uuid.Uuids.unixTimestamp
import com.domain.chat.ChatReply
import com.domain.chat.cdc.v1.CdcEnvelopeMessage
import org.apache.pekko.Done
import org.apache.pekko.actor.*
import org.apache.pekko.actor.typed.ActorRefResolver
import org.apache.pekko.cluster.*
import org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import org.apache.pekko.persistence.state.scaladsl.GetObjectResult
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.*
import server.grpc.chat.*
import server.grpc.state.ChatState
import shared.Domain.*

import java.time.*
import scala.concurrent.*
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.control.NonFatal
import scala.collection.immutable.*
import CassandraStore.*
import com.codahale.metrics.Counter
import server.grpc.ChatRoom
import com.domain.chat.session.cassandra.commands.*
import com.domain.chat.session.cassandra.commands.CassandraCmdMessage.SealedValue
import ChatExtension.*

object ChatExtension extends ExtensionId[ChatExtension] with ExtensionIdProvider {

  trait CmdKey[T <: CassandraCmd] {
    type Out
    def safeCast(p: Promise[?]): Promise[Out]
  }

  given CmdKey[GetRecentHistory] with {
    type Out = (String, Seq[ServerCmd])
    def safeCast(p: Promise[?]): Promise[Out] = p.asInstanceOf[Promise[Out]]
  }

  given CmdKey[GetHistory] with {
    type Out = Seq[ServerCmd]
    def safeCast(p: Promise[?]): Promise[Out] = p.asInstanceOf[Promise[Out]]
  }

  def mapCmdResult[C <: CassandraCmd](cmd: C, p: Promise[?])(using cmdKey: CmdKey[C]): Promise[cmdKey.Out] =
    cmdKey.safeCast(p)

  override def get(system: ActorSystem): ChatExtension = super.get(system)

  override def lookup: ChatExtension.type = ChatExtension

  override def createExtension(system: ExtendedActorSystem): ChatExtension =
    new ChatExtension(system)
}

class ChatExtension(system: ActorSystem) extends Extension {
  val profileName = "default"

  val logger = system.log

  type WriteOp = (Long, CdcEnvelopeMessage.SealedValue)

  val sessionExt = CassandraSessionExtension(system)

  given system0: org.apache.pekko.actor.typed.ActorSystem[?] = system.toTyped

  given ExecutionContext = system0.executionContext

  given ActorRefResolver = ActorRefResolver(system0)

  given Ordering[LiveServerMessage] = Ordering.by[LiveServerMessage, Long](_.timeUuid.toUnixTs())

  given cqlSession: CqlSession = sessionExt.cqlSession

  val counter: Counter = sessionExt.metricRegistry.counter(CassandraSessionExtension.cntName)

  private val parallelism = system.settings.config.getInt("cassandra.parallelism")
  private val maxBatchSize = system.settings.config.getInt("cassandra.max-batch-size")
  private val clusterMemberDetails = Cluster(system).selfMember.clusterMemberDetails()

  val getChatDetailsStmt: PreparedStatement =
    cqlSession.prepare(
      SimpleStatement
        .builder("SELECT participants, revision FROM chat_details WHERE chat=?")
        .setExecutionProfileName(profileName)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
        .build()
    )

  val getRecentHistStmt: PreparedStatement =
    cqlSession.prepare(
      SimpleStatement
        .builder("SELECT chat, when, message FROM timeline WHERE chat=? AND time_bucket=? LIMIT ?")
        .setExecutionProfileName(profileName)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
        .build()
    )

  val getHistoryStmt: PreparedStatement =
    cqlSession.prepare(
      SimpleStatement
        .builder("SELECT chat, when, message FROM timeline WHERE chat=? AND time_bucket=? AND when < ? LIMIT ?")
        .setExecutionProfileName(profileName)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
        .build()
    )

  val getLastNBucketsStmt: PreparedStatement =
    cqlSession.prepare(
      SimpleStatement
        .builder("SELECT time_bucket FROM timeline_buckets where chat = ? LIMIT ?")
        .setExecutionProfileName(profileName)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
        .build()
    )

  val getBucketsStmt: PreparedStatement =
    cqlSession.prepare(
      SimpleStatement
        .builder("SELECT time_bucket FROM timeline_buckets where chat = ? and time_bucket <= ? LIMIT ?")
        .setExecutionProfileName(profileName)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
        .build()
    )

  val writeChatStateQueue =
    writeQueueImpl(clusterMemberDetails, counter)

  val readChatStateQueue =
    readChatStateQueueImpl(getChatDetailsStmt, clusterMemberDetails)

  val readChatHistoryQueue =
    readHistoryQueue(clusterMemberDetails)(using ExecutionContext.parasitic)

  private def getRecentHistory(
      bs: BoundStatement
    )(using
      cqlSession: CqlSession
    ): Future[Seq[LiveServerMessage]] =
    cqlSession
      .executeAsync(bs)
      .asScala
      .map { asyncResultSet =>
        var mostRecentMsgs = List.empty[LiveServerMessage]
        val sb = new StringBuilder()
        val iter = asyncResultSet.currentPage().iterator()
        while iter.hasNext() do {
          val row = iter.next()
          val timeuud = row.getUuid(1)
          val ts = unixTimestamp(timeuud)
          val msg = LiveServerMessage.parseFrom(row.getByteBuffer(2).array())
          mostRecentMsgs = msg :: mostRecentMsgs
          sb.append(
            s"$timeuud / $ts / ${msg.userInfo.user.raw()} / ${formatter
                .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), SERVER_DEFAULT_TZ))}"
          ).append("\n")
        }
        logger.debug(s"""
             |RecentHistory: ${mostRecentMsgs.length}. ${mostRecentMsgs.headOption.map(r => r.chat.raw())}
             |${sb.toString()}
             |""".stripMargin)
        mostRecentMsgs
      }(ExecutionContext.parasitic)

  private def getBucketName(
      bs: BoundStatement
    )(using cqlSession: CqlSession
    ): Future[Option[String]] =
    cqlSession
      .executeAsync(bs)
      .asScala
      .map(rs => Option(rs.one()).map(_.getString("time_bucket")))

  private def getBucketNames(
      bs: BoundStatement
    )(using cqlSession: CqlSession
    ): Future[List[String]] =
    cqlSession
      .executeAsync(bs)
      .asScala
      .map { asyncResultSet =>
        var buckets = List.empty[String]
        val iter = asyncResultSet.currentPage().iterator()
        while iter.hasNext() do {
          val row = iter.next()
          buckets = row.getString("time_bucket") :: buckets
        }
        buckets.reverse
      }(ExecutionContext.parasitic)

  def fetchRecentHistory(
      bucketNames: List[String],
      recentHistory: SortedSet[LiveServerMessage],
      chat: String,
      pageSize: Int,
    ): Future[Seq[LiveServerMessage]] =
    bucketNames match {
      case bucketName :: othersBucketNames =>
        getRecentHistory(getRecentHistStmt.bind(chat, bucketName, pageSize).setPageSize(pageSize)).flatMap { rows =>
          val allRecentHistory = recentHistory ++ rows
          if (allRecentHistory.size < pageSize)
            fetchRecentHistory(othersBucketNames, allRecentHistory, chat, pageSize)
          else
            Future.successful(allRecentHistory.toSeq)
        }
      case Nil =>
        Future.successful(recentHistory.toSeq)
    }

  def readHistoryQueue(
      cDetails: String,
      numOfMessages: Int = 15,
    )(using ex: ExecutionContext
    ): SourceQueueWithComplete[(CassandraCmd, Promise[?])] =
    Source
      .queue[(CassandraCmd, Promise[?])](maxBatchSize * 2, OverflowStrategy.backpressure)
      .mapMaterializedValue { queue =>
        logger.info(s"ReadQueue($cDetails) materialization")
        queue
      }
      .mapAsyncUnordered(parallelism) { (cmd, p) =>
        cmd.asMessage.sealedValue match {
          case SealedValue.GetRecentHistory(cmd) =>
            val f =
              getBucketNames(getLastNBucketsStmt.bind(cmd.chat.raw(), numOfMessages)).flatMap { bucketNames =>
                fetchRecentHistory(bucketNames, SortedSet.empty[LiveServerMessage], cmd.chat.raw(), numOfMessages).map {
                  rows =>
                    (bucketNames.headOption.getOrElse(""), rows)
                }
              }
            f.onComplete(mapCmdResult(cmd, p).tryComplete)
            f

          case SealedValue.GetHistory(cmd) =>
            val limit = Option.when(cmd.pageSige > 0)(cmd.pageSige).getOrElse(numOfMessages)
            val cmdTimeBucket = CassandraStore
              .formatterYYMM
              .format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(cmd.fromTimeUuid.toUnixTs()), CassandraStore.UTC)
              )

            val f =
              getBucketNames(getBucketsStmt.bind(cmd.chat.raw(), cmdTimeBucket, limit)).flatMap { bucketNames =>
                fetchRecentHistory(bucketNames, SortedSet.empty[LiveServerMessage], cmd.chat.raw(), limit)
              }
            f.onComplete(mapCmdResult(cmd, p).tryComplete)
            f
          case SealedValue.Empty =>
            Future.failed(new Exception("CassandraCmd.Empty"))
        }
      }
      .addAttributes(
        ActorAttributes.supervisionStrategy {
          case NonFatal(cause) =>
            logger.info(s"${classOf[CassandraStore].getName}(Read) failed and resumed", cause)
            Supervision.Resume
        }
      )
      .toMat(Sink.ignore)(Keep.left)
      .run()

  private def readChatStateQueueImpl(
      getChatDetails: PreparedStatement,
      cDetails: String,
    ): SourceQueueWithComplete[(String, Promise[GetObjectResult[ChatRoom.State]])] =
    Source
      .queue[(String, Promise[GetObjectResult[ChatRoom.State]])](maxBatchSize * 2, OverflowStrategy.backpressure)
      .mapMaterializedValue { q =>
        logger.info(s"ReadChatStateQueue($cDetails) materialization")
        q
      }
      .mapAsyncUnordered(parallelism) { (chat, p) =>
        val f =
          cqlSession
            .executeAsync(getChatDetails.bind(chat))
            .asScala
            .map { rs =>
              Option(rs.one()) match {
                case Some(row) =>
                  val participants = row.getString(0)
                  val detailsRevision = row.getLong(1)
                  GetObjectResult(
                    Some(
                      ChatState(
                        name = Some(ChatName(chat)),
                        registeredParticipants = HashSet.from(participants.split(",").map(shared.Domain.Participant(_))),
                      )
                    ),
                    detailsRevision,
                  )

                case None =>
                  GetObjectResult(None, 0)
              }
            }

        f.onComplete(p.tryComplete)(ExecutionContext.parasitic)
        f
      }
      .addAttributes(
        ActorAttributes.supervisionStrategy {
          case NonFatal(cause) =>
            logger
              .error(s"${classOf[CassandraStore].getName}(ReadChatStateQueue) failed and resumed", cause)
            Supervision.Resume
        }
      )
      .toMat(Sink.ignore)(Keep.left)
      .run()

  private def writeQueueImpl(clusterMemberDetails: String, cntr: Counter): BoundedSourceQueue[WriteOp] = {

    def extractPartition(e: WriteOp): ChatName =
      e._2 match {
        case CdcEnvelopeMessage.SealedValue.Created(cdc) =>
          cdc.chat
        case CdcEnvelopeMessage.SealedValue.AddedV2(cdc) =>
          cdc.chat
        case CdcEnvelopeMessage.SealedValue.Empty =>
          throw new Exception(s"Unsupported partition")
      }

    val stmt: PreparedStatement =
      cqlSession.prepare(
        SimpleStatement
          .builder("INSERT INTO chat_details (chat, revision, participants) VALUES (?, ?, ?)")
          .setExecutionProfileName(profileName)
          .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
          .build()
      )

    val queue =
      Source
        .queue[WriteOp](maxBatchSize * 2)
        .mapAsyncPartitioned(parallelism)(extractPartition) { (out: WriteOp, _: ChatName) =>
          val (revision, cdc) = out
          // TODO: error handling
          updateChatRoom(revision, cdc, stmt, cntr)
        }
        .addAttributes(
          ActorAttributes.supervisionStrategy {
            case NonFatal(cause) =>
              // triggers if mapAsyncPartitioned(f) fails
              system.log.error(s"${classOf[CassandraStore].getName} failed and resumed", cause)
              Supervision.Resume
          }
        )
        .mapMaterializedValue { queue =>
          system.log.info(s"ChatRoomWriteQueue($clusterMemberDetails) materialization")
          queue
        }
        .toMat(Sink.ignore)(Keep.left)
        .run()

    queue
  }

  def updateChatRoom(
      revision: Long,
      chatDetailsCmd: CdcEnvelopeMessage.SealedValue,
      ps: PreparedStatement,
      cntr: Counter,
    )(using
      resolver: ActorRefResolver,
      session: CqlSession,
    ): Future[Done] =
    chatDetailsCmd match {
      case CdcEnvelopeMessage.SealedValue.Created(cdc) =>
        session
          .executeAsync(ps.bind(cdc.chat.raw(), Long.box(revision), ""))
          .asScala
          .map { _ =>
            ReplyTo[ChatReply].toBase(cdc.replyTo).tell(ChatReply(cdc.chat))
            cntr.inc()
            Done
          }
      case CdcEnvelopeMessage.SealedValue.AddedV2(cdc) =>
        session
          .executeAsync(
            ps.bind(cdc.chat.raw(), Long.box(revision), cdc.participants.mkString(","))
          )
          .asScala
          .map { _ =>
            ReplyTo[ChatReply].toBase(cdc.replyTo).tell(ChatReply(cdc.chat))
            cntr.inc()
            Done
          }
      case CdcEnvelopeMessage.SealedValue.Empty =>
        Future.failed(new Exception(s"Unsupported SealedValue.Empty"))
    }
}
