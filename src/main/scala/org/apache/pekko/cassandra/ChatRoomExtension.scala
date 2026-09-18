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
import server.grpc.chat.{ LiveServerMessage, ServerCmd }
import server.grpc.state.ChatState
import shared.Domain.*

import java.time.*
import scala.concurrent.*
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.control.NonFatal
import scala.collection.immutable.{ HashSet, SortedSet }
import CassandraStore.*
import com.codahale.metrics.Counter
import server.grpc.ChatRoom
import com.domain.chat.session.cassandra.commands.CassandraCmd
import com.domain.chat.session.cassandra.commands.CassandraCmdMessage.SealedValue
import ChatRoomExtension.*

object ChatRoomExtension extends ExtensionId[ChatRoomExtension] with ExtensionIdProvider {

  sealed trait CmdResult[T] {
    type Out

    def cast(p: Promise[?]): Promise[Out]
  }

  given CmdResult[SealedValue.GetLastBucket] with {
    type Out = (String, Seq[ServerCmd])

    def cast(p: Promise[?]) = p.asInstanceOf[Promise[Out]]
  }

  /*given CmdResult[SealedValue.GetRecentHistory] with {
    type Out = Seq[ServerCmd]

    def cast(p: Promise[?]) = p.asInstanceOf[Promise[Out]]
  }*/

  override def get(system: ActorSystem): ChatRoomExtension = super.get(system)

  override def lookup: ChatRoomExtension.type = ChatRoomExtension

  override def createExtension(system: ExtendedActorSystem): ChatRoomExtension =
    new ChatRoomExtension(system)
}

class ChatRoomExtension(system: ActorSystem) extends Extension {
  val profileName = "default"
  val logger = system.log

  type WriteOp = (Long, CdcEnvelopeMessage.SealedValue)

  val sessionExt = CassandraSessionExtension(system)

  given system0: org.apache.pekko.actor.typed.ActorSystem[?] = system.toTyped

  given ExecutionContext = system0.executionContext

  given ActorRefResolver = ActorRefResolver(system0)

  given Ordering[LiveServerMessage] = Ordering.by[LiveServerMessage, Long](_.timeUuid.toUnixTs())

  given cqlSession: CqlSession = sessionExt.cqlSession

  val cntr: Counter = sessionExt.metricRegistry.counter(CassandraSessionExtension.cntName)

  private val parallelism = system.settings.config.getInt("cassandra.parallelism")
  private val maxBatchSize = system.settings.config.getInt("cassandra.max-batch-size")
  private val numOfBuckets = system.settings.config.getInt("cassandra.num-of-buckets")
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

  val getLastNBucketsStmt: PreparedStatement =
    cqlSession.prepare(
      SimpleStatement
        .builder("SELECT time_bucket FROM timeline_buckets where chat = ? LIMIT ?")
        .setExecutionProfileName(profileName)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
        .build()
    )

  val writeChatStateQueue =
    writeQueueImpl(clusterMemberDetails, cntr)

  val readChatStateQueue =
    readChatStateQueueImpl(getChatDetailsStmt, clusterMemberDetails)

  val readQueue =
    readHistoryQueue(clusterMemberDetails, numOfBuckets)

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
      numOfBucket: Int,
      pageSize: Int = 15,
    ): SourceQueueWithComplete[(CassandraCmd, Promise[?])] =
    Source
      .queue[(CassandraCmd, Promise[?])](maxBatchSize * 2, OverflowStrategy.backpressure)
      .mapMaterializedValue { q =>
        logger.info(s"ReadQueue($cDetails) materialization")
        q
      }
      .mapAsyncUnordered(parallelism) { (cmd, p) =>
        cmd.asMessage.sealedValue match {
          case SealedValue.GetLastBucket(c) =>
            val f =
              getBucketNames(getLastNBucketsStmt.bind(c.chat.raw(), numOfBucket)).flatMap { bucketNames =>
                fetchRecentHistory(bucketNames, SortedSet.empty[LiveServerMessage], c.chat.raw(), pageSize).map { rows =>
                  (bucketNames.headOption.getOrElse(""), rows)
                }
              }
            f.onComplete(summon[CmdResult[SealedValue.GetLastBucket]].cast(p).tryComplete(_))(
              ExecutionContext.parasitic
            )
            f
          /*case SealedValue.GetRecentHistory(c) =>
            val f =
              getRecentHistory(getRecentHistStmt.bind(c.chat.raw(), c.bucketName, pageSize)).flatMap { rows =>
                if (rows.size < pageSize) {
                  getBucketNames(getLastNBucketsStmt.bind(c.chat.raw(), numOfBucket)).flatMap { bucketNames =>
                    fetchRecentHistory(bucketNames, SortedSet.empty[LiveServerMessage], c.chat.raw(), pageSize)
                  }
                } else
                  Future.successful(rows)
              }
            f.onComplete(summon[CmdResult[SealedValue.GetRecentHistory]].cast(p).tryComplete(_))(
              ExecutionContext.parasitic
            )
            f*/
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
                  val r =
                    GetObjectResult(
                      Some(
                        ChatState(
                          name = Some(ChatName(chat)),
                          registeredParticipants =
                            HashSet.from(participants.split(",").map(shared.Domain.Participant(_))),
                        )
                      ),
                      detailsRevision,
                    )
                  r
                case None =>
                  GetObjectResult(None, 0)
              }
            }

        f.onComplete(p.tryComplete(_))(ExecutionContext.parasitic)
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
        .mapMaterializedValue { q =>
          system.log.info(s"ChatRoomWriteQueue($clusterMemberDetails) materialization")
          q
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
