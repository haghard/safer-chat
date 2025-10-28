// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package org.apache.pekko.cassandra

import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.CqlSession
import org.apache.pekko.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.persistence.state.DurableStateStoreProvider
import org.apache.pekko.persistence.state.scaladsl.*
import org.apache.pekko.stream.*
import com.domain.chat.cdc.v1.CdcEnvelope
import com.domain.chat.cdc.v1.CdcEnvelope.*
import org.apache.pekko.event.LoggingAdapter
import server.grpc.chat.ServerCmd
import server.grpc.ChatRoom

import scala.concurrent.*
import org.apache.pekko.stream.scaladsl.*
import org.slf4j.Logger
import shared.Domain.ChatName

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.util.control.NonFatal
import scala.concurrent.duration.*

object CassandraStore {

  val profileName = "default"

  val formatterMM = DateTimeFormatter.ofPattern("yyyy-MM")
  val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss:SSSSSS Z")
  val UTC = ZoneId.of(java.util.TimeZone.getTimeZone("UTC").getID)
  val SERVER_DEFAULT_TZ = ZoneId.of(java.util.TimeZone.getDefault().getID())

  def createTables(cqlSession: CqlSession, log: Logger): Unit =
    try {
      cqlSession.getMetrics().ifPresent { metrics =>
        // CassandraMetricsRegistry(system).addMetrics(metricsCategory, metrics.getRegistry)
        // metrics.getRegistry()
      }

      val config = cqlSession.getContext().getConfig()
      val profile = config.getDefaultProfile()

      val profileConf =
        s"""
           |${profile.getName}
           |REQUEST_TIMEOUT:${profile.getDuration(DefaultDriverOption.REQUEST_TIMEOUT)}
           |CONNECTION_MAX_REQUESTS:${profile.getInt(DefaultDriverOption.CONNECTION_MAX_REQUESTS)}
           |REQUEST_CONSISTENCY:${profile.getString(DefaultDriverOption.REQUEST_CONSISTENCY)}
           |--------------------------
           |""".stripMargin

      val it = profile.entrySet().iterator()
      log.info("★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★  CASSANDRA settings ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★")
      while it.hasNext() do {
        val kv = it.next()
        println(s"${kv.getKey()}=${kv.getValue()}")
      }
      log.info("★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★")

      // https://github.com/apache/cassandra-java-driver/blob/4.x/examples/src/main/java/com/datastax/oss/driver/examples/basic/ReadTopologyAndSchemaMetadata.java
      log.info("★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★  CASSANDRA: Token ranges ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★")
      val metadata = cqlSession.getMetadata()
      val nodes = metadata.getNodes()
      nodes.forEach { (uuid, node) =>
        metadata.getTokenMap.get().getTokenRanges(node).forEach { tokenRange =>
          log.info(s"${node.getEndPoint()} [${tokenRange.getStart()} ... ${tokenRange.getEnd()}]")
          /*tokenRange.splitEvenly(8).forEach { tr =>
            sys.log.info(s"${node.getEndPoint()} range:[${tr.getStart()} - ${tr.getEnd()}]")
          }*/
        }
      }
      log.info(profileConf)
      log.info("★ ★ " * 10)

      cqlSession.execute(CassandraStore.chatDetailsDDL)
      log.info("Executed \n" + CassandraStore.chatDetailsDDL)

      cqlSession.execute(CassandraStore.chatTimelineDDL)
      log.info("Executed \n" + CassandraStore.chatTimelineDDL)

    } catch {
      case NonFatal(ex) =>
        log.error("Tables creation error", ex)
        cqlSession.close()
        throw ex
    }

  def printStats(
      msg: String,
      duration: FiniteDuration,
    )(using log: LoggingAdapter
    ): Sink[Seq[ServerCmd], NotUsed] =
    Flow[Seq[ServerCmd]]
      .conflateWithSeed(_.size)((sum, batch) => sum + batch.size)
      .zipWith(Source.tick(duration, duration, ()))(Keep.left)
      .scan(0L)((acc, c) => acc + c)
      .to(Sink.foreach(cnt => log.warning(s" ★ ★ ★ ★ $msg NumOfMsg:$cnt in last $duration")))
      .withAttributes(Attributes.inputBuffer(1, 1))

  val chatDetailsDDL = {
    """
      |CREATE TABLE IF NOT EXISTS chat_details (
      |   chat text,
      |   revision bigint,
      |   participants text,
      |   PRIMARY KEY (chat)
      |);
      |""".stripMargin

    """
      |CREATE TABLE IF NOT EXISTS timeline2 (
      |   chat text,
      |   messageid timeuuid,
      |   message blob,
      |   PRIMARY KEY (chat, messageid)) WITH CLUSTERING ORDER BY (messageid DESC);
      |""".stripMargin
  }

  val chatTimelineDDL =
    """
      |CREATE TABLE IF NOT EXISTS timeline (
      |   chat text,
      |   time_bucket varchar,
      |   message blob,
      |   when timeuuid,
      |   PRIMARY KEY ((chat, time_bucket), when)) WITH CLUSTERING ORDER BY (when DESC);
      |""".stripMargin
}

final class CassandraStore(system: ExtendedActorSystem) extends DurableStateStoreProvider {
  import system.dispatcher

  override def scaladslDurableStateStore(): DurableStateStore[Any] =
    new DurableStateUpdateStore[ChatRoom.State]() {
      given typedSystem: ActorSystem[?] = system.toTyped
      given logger: Logger = typedSystem.log
      given scheduler: org.apache.pekko.actor.Scheduler = system.scheduler
      given cqlSession: CqlSession = CassandraSessionExtension(system).cqlSession

      val to = 3.seconds

      cqlSession.execute(CassandraStore.chatDetailsDDL)
      cqlSession.execute(CassandraStore.chatTimelineDDL)

      val writeQueue = ChatRoomExtension(system).writeChatStateQueue
      val readQueue = ChatRoomExtension(system).readChatStateQueue

      def insert(
          state: ChatRoom.State,
          chatName: ChatName,
          revision: Long,
        ): Future[Done] =
        state.cdc match {
          case CdcEnvelope.Empty =>
            Future.failed(new Exception("Empty"))
          case payload: NonEmpty =>
            writeQueue.offer((revision, payload.asMessage.sealedValue)) match {
              case QueueOfferResult.Enqueued =>
                Future.successful(Done)
              case QueueOfferResult.Dropped =>
                logger.warn("ChatRoomCassandraStore write-queue overflow. Size={}", writeQueue.size())
                // Chat should resent all messages after timeout
                Future.successful(Done)
              case QueueOfferResult.Failure(cause) =>
                logger.warn(cause.getMessage)
                Future.failed(cause)
              case result: QueueCompletionResult =>
                Future.failed(new Exception("Unexpected"))
            }
        }

      override def upsertObject(
          chatName: String,
          revision: Long,
          state: ChatRoom.State,
          tag: String,
        ): Future[org.apache.pekko.Done] =
        insert(state, ChatName(chatName), revision)

      override def getObject(chat: String): Future[GetObjectResult[ChatRoom.State]] = {
        val expP = ExpiringPromise[GetObjectResult[ChatRoom.State]](to)
        readQueue.offer((chat, expP)).flatMap {
          case QueueOfferResult.Enqueued =>
            expP.future
          case QueueOfferResult.Dropped =>
            logger.warn("ChatRoomCassandraStore read-queue overflow. Size={}", writeQueue.size())
            Future.failed(new Exception("Read overflow"))
          case result: QueueCompletionResult =>
            Future.failed(new Exception("Unexpected"))
        }
      }

      override def deleteObject(persistenceId: String): Future[Done] =
        Future.failed(new Exception(s"Deletion $persistenceId"))

      override def deleteObject(persistenceId: String, revision: Long): Future[Done] =
        Future.failed(new Exception(s"Deletion $persistenceId:$revision"))

    }.asInstanceOf[persistence.state.scaladsl.DurableStateStore[Any]]

  override def javadslDurableStateStore(): persistence.state.javadsl.DurableStateStore[AnyRef] = null
}

/*
  val str = Uuids.timeBased().toString
  UUID.fromString(str)
  unixTimestamp(Uuids.timeBased())


  def cassandraFlow(
      settings: CassandraWriteSettings,
      partitionSize: Int,
    )(using session: CassandraSession
    ): Flow[StreamElement, StreamElement, NotUsed] = {

    def chatDetails: Flow[StreamElement, StreamElement, NotUsed] =
      CassandraFlow.create[StreamElement](
        writeSettings = settings,
        cqlStatement = "INSERT INTO chat.chat_details(chat, revision, participants, partition) VALUES (?, ?, ?, ?)",
        statementBinder = {
          case (
                 (state: Chat.State, revision: Long, msg: ChangeDataCapture.Payload),
                 prepStmt: PreparedStatement,
               ) =>
            val prNum = Long.box(revision) / partitionSize
            prepStmt
              .bind(
                state.name.getOrElse(""),
                Long.box(revision),
                msg.create.map(c => "").orElse(msg.add.map(_.participantStr)).getOrElse(""),
                prNum,
              )
        },
      )

    def startNewPartition: Flow[StreamElement, StreamElement, NotUsed] =
      CassandraFlow.create[StreamElement](
        writeSettings = settings,
        cqlStatement = """
            | BEGIN BATCH
            |  UPDATE chat.chat_details SET partition = ? WHERE chat = ?
            |  INSERT INTO chat.timeline(chat, partition, revision, when, message) VALUES (?,?,?,?,?)
            | APPLY BATCH;
            |""".stripMargin,
        statementBinder = {
          case (
                 (state: Chat.State, revision: Long, msg: ChangeDataCapture.Payload),
                 prepStmt: PreparedStatement,
               ) =>
            val prNum = Long.box(revision) / partitionSize
            val name = state.name.getOrElse(throw new Exception("Unexpected: Empty chatName !"))
            prepStmt.bind(
              prNum,
              name,
              name,
              prNum,
              Long.box(revision),
              Uuids.timeBased(),
              java.nio.ByteBuffer.wrap(msg.postMsg.get.toByteArray),
            )
        },
      )

    def restOfPartition: Flow[StreamElement, StreamElement, NotUsed] =
      CassandraFlow.create[StreamElement](
        writeSettings = settings,
        cqlStatement = "INSERT INTO chat.timeline(chat, partition, revision, when, message) VALUES (?,?,?,?,?)",
        statementBinder = {
          case (
                 (state: Chat.State, revision: Long, msg: ChangeDataCapture.Payload),
                 prepStmt: PreparedStatement,
               ) =>
            prepStmt.bind(
              state.name.getOrElse(throw new Exception("Unexpected: Empty chatName !")),
              Long.box(revision) / partitionSize,
              Long.box(revision),
              Uuids.timeBased(),
              java.nio.ByteBuffer.wrap(msg.postMsg.get.toByteArray),
            )
        },
      )

    cassandraWriteFlow(
      element =>
        element._3 match {
          case Payload.Create(_) => 2
          case Payload.Add(_)    => 2
          case Payload.PostMsg(_) =>
            val revision = element._2
            // Long.box(revision) / partitionSize
            val i = revision % partitionSize
            if (i >= 0 && i <= 5) 0 else 1
          case other =>
            throw new Exception(s"Unsupported term $other")
        },
      startNewPartition,
      restOfPartition,
      chatDetails,
    )
  }



          .via(cassandraFlow(settings, partitionSize))
          .to(Sink.foreach[StreamElement] {
            case (state, revision, msg) =>
              msg match {
                case Payload.Create(value) =>
                  ReplyTo[ChatReply].toBase(state.replyTo).tell(ChatReply(value.chat))
                case Payload.Add(_) =>
                  ReplyTo[ChatReply]
                    .toBase(state.replyTo)
                    .tell(ChatReply(state.name.getOrElse(throw new Exception("ChatName is empty!"))))
                case Payload.PostMsg(msg) =>
                  val name = state.name.getOrElse(throw new Exception("ChatName is empty!"))
                  logger.info("Wrote {}:{}", name, revision)
                  ReplyTo[ServerCmd]
                    .toBase(state.replyTo)
                    .tell(ServerCmd(name, msg.content, msg.userInfo))
                case other =>
                  throw new Exception(s"Unsupported $other")
              }
              org.apache.pekko.Done
          })



    def cassandraWriteFlow(
      toBuckets: StreamElement => Int, // [0,1,2]
      first: Flow[StreamElement, StreamElement, NotUsed],
      rest: Flow[StreamElement, StreamElement, NotUsed],
      opsFlow: Flow[StreamElement, StreamElement, NotUsed],
    ): Flow[StreamElement, StreamElement, NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() { implicit b =>

        import GraphDSL.Implicits.*
        val buf = Attributes.inputBuffer(1, 1)
        val partitioner =
          b.add(Partition[StreamElement](3, a => toBuckets(a)).withAttributes(buf))

        val merger =
          b.add(Merge[StreamElement](inputPorts = 3, eagerComplete = false).withAttributes(buf))

        partitioner.out(0) ~> first ~> merger.in(0)
        partitioner.out(1) ~> rest ~> merger.in(1)
        partitioner.out(2) ~> opsFlow ~> merger.in(2)
        FlowShape(partitioner.in, merger.out)
      }
    )
 */
