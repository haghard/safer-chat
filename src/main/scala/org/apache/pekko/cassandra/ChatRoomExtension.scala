package org.apache.pekko.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.{ PreparedStatement, SimpleStatement }
import com.datastax.oss.driver.api.core.uuid.Uuids.unixTimestamp
import com.domain.chat.ChatReply
import com.domain.chat.cdc.v1.CdcEnvelopeMessage
import org.apache.pekko.Done
import org.apache.pekko.actor.*
import org.apache.pekko.actor.typed.ActorRefResolver
import org.apache.pekko.cluster.*
import org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import org.apache.pekko.persistence.state.scaladsl.GetObjectResult
import org.apache.pekko.stream.{ ActorAttributes, BoundedSourceQueue, OverflowStrategy, Supervision }
import org.apache.pekko.stream.scaladsl.{ Keep, Sink, Source, SourceQueueWithComplete }
import server.grpc.chat.ServerCmd
import server.grpc.state.ChatState
import shared.Domain.{ ChatName, ReplyTo }

import java.time.{ Instant, ZonedDateTime }
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.control.NonFatal
import scala.collection.immutable.HashSet
import CassandraStore.*
import server.grpc.ChatRoom

object ChatRoomExtension extends ExtensionId[ChatRoomExtension] with ExtensionIdProvider {

  override def get(system: ActorSystem): ChatRoomExtension = super.get(system)

  override def lookup: ChatRoomExtension.type = ChatRoomExtension

  override def createExtension(system: ExtendedActorSystem): ChatRoomExtension =
    new ChatRoomExtension(system)
}

class ChatRoomExtension(system: ActorSystem) extends Extension {
  val profileName = "default"
  val logger = system.log

  type WriteOp = (Long, CdcEnvelopeMessage.SealedValue)

  given system0: org.apache.pekko.actor.typed.ActorSystem[?] = system.toTyped

  given ex: ExecutionContext = system0.executionContext

  given refResolver: ActorRefResolver = ActorRefResolver(system0)

  given cqlSession: CqlSession = CassandraSessionExtension(system).cqlSession

  private val parallelism = system.settings.config.getInt("cassandra.parallelism")
  private val maxBatchSize = system.settings.config.getInt("cassandra.max-batch-size")
  private val cDetails = Cluster(system).selfMember.details3()

  val getChatDetails =
    cqlSession.prepare(
      SimpleStatement
        .builder("SELECT participants, revision FROM chat_details WHERE chat=?")
        .setExecutionProfileName(profileName)
        .build()
    )

  val getRecent: PreparedStatement =
    cqlSession.prepare(
      SimpleStatement
        .builder("SELECT chat, when, message FROM timeline WHERE chat=? AND time_bucket=? LIMIT ?")
        .setExecutionProfileName(profileName)
        .build()
    )

  val writeChatStateQueue =
    writeQueueImpl(cDetails)

  val readChatStateQueue =
    readChatStateQueueImpl(getChatDetails, cDetails)

  val readResentHistoryQueue =
    readChatRecentHistoryImpl(getRecent, cDetails)

  private def getRecentHistory(
      cmd: ServerCmd,
      getRecent: PreparedStatement,
      limit: Int = 15,
    )(using
      cqlSession: CqlSession
    ): Future[Seq[ServerCmd]] = {
    val chat = cmd.chat.raw()
    val ts = cmd.timeUuid.toUnixTs()
    val bucket = formatterMM.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), UTC))

    cqlSession
      .executeAsync(getRecent.bind(chat, bucket, limit).setPageSize(limit))
      .asScala
      .map { asyncResultSet =>
        var mostRecentMsgs = List.empty[ServerCmd]
        val sb = new StringBuilder()
        val iter = asyncResultSet.currentPage().iterator()
        while (iter.hasNext) {
          val row = iter.next()
          val timeuud = row.getUuid(1)
          val ts = unixTimestamp(timeuud)
          val cmd = ServerCmd.parseFrom(row.getByteBuffer(2).array())
          mostRecentMsgs = cmd :: mostRecentMsgs
          sb.append(
            s"$timeuud / $ts / ${cmd.userInfo.user.raw()} / ${formatter
                .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), SERVER_DEFAULT_TZ))}"
          ).append("\n")
        }
        logger.debug(s"""
             |Last ${mostRecentMsgs.length}. $chat / $bucket
             |${sb.toString()}
             |""".stripMargin)
        mostRecentMsgs
      }(ExecutionContext.parasitic)
  }

  private def readChatRecentHistoryImpl(
      stmt: PreparedStatement,
      cDetails: String,
    ): SourceQueueWithComplete[(ServerCmd, Promise[Seq[ServerCmd]])] =
    Source
      .queue[(ServerCmd, Promise[Seq[ServerCmd]])](maxBatchSize * 2, OverflowStrategy.backpressure)
      .mapMaterializedValue { q =>
        logger.info(s"ReadChatRecentHistory($cDetails) materialization")
        q
      }
      .mapAsyncUnordered(parallelism) { (cmd, p) =>
        val f = getRecentHistory(cmd, stmt)
        f.onComplete(p.tryComplete(_))(ExecutionContext.parasitic)
        f
      }
      .addAttributes(
        ActorAttributes.supervisionStrategy {
          case NonFatal(cause) =>
            logger
              .info(s"${classOf[CassandraStore].getName}(ReadChatRecentHistory) failed and resumed", cause)
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
      // .toMat(Sink.foreach((p, res) => p.tryComplete(res)))(Keep.left)
      .run()

  private def extractPartition(e: WriteOp): ChatName =
    e._2 match {
      case CdcEnvelopeMessage.SealedValue.Created(cdc) =>
        cdc.chat
      case CdcEnvelopeMessage.SealedValue.AddedV2(cdc) =>
        cdc.chat
      case CdcEnvelopeMessage.SealedValue.Empty =>
        throw new Exception(s"Unsupported partition")
    }

  private def writeQueueImpl(clusterMemberDetails: String): BoundedSourceQueue[WriteOp] = {
    val stmt: PreparedStatement =
      cqlSession.prepare(
        SimpleStatement
          .builder("INSERT INTO chat_details (chat, revision, participants) VALUES (?, ?, ?)")
          .setExecutionProfileName(profileName)
          .build()
      )

    val queue =
      Source
        .queue[WriteOp](maxBatchSize * 2)
        .mapAsyncPartitioned(parallelism)(extractPartition) { (out: WriteOp, _: ChatName) =>
          val (revision, cdc) = out
          // TODO: error handling
          updateChatRoom(revision, cdc, stmt)
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
      chatDetailsAction: CdcEnvelopeMessage.SealedValue,
      ps: PreparedStatement,
    )(using
      resolver: ActorRefResolver,
      session: CqlSession,
    ): Future[Done] =
    chatDetailsAction match {
      case CdcEnvelopeMessage.SealedValue.Created(cdc) =>
        session
          .executeAsync(ps.bind(cdc.chat.raw(), Long.box(revision), ""))
          .asScala
          .map { _ =>
            ReplyTo[ChatReply].toBase(cdc.replyTo).tell(ChatReply(cdc.chat))
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
            Done
          }
      case CdcEnvelopeMessage.SealedValue.Empty =>
        Future.failed(new Exception(s"Unsupported SealedValue.Empty"))
    }
}
