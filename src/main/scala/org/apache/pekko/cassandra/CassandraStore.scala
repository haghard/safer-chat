// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package org.apache.pekko.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.*
import com.datastax.oss.driver.api.core.uuid.Uuids
import com.domain.chat.*
import org.apache.pekko.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ ActorRefResolver, ActorSystem }
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.cassandra.CassandraStore.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.persistence.state.DurableStateStoreProvider
import org.apache.pekko.persistence.state.scaladsl.*
import org.apache.pekko.stream.*
import server.grpc.chat.ChangeDataCapture.Payload
import server.grpc.chat.{ ChangeDataCapture, ServerCmd }
import server.grpc.state.ChatState
import server.grpc.{ Chat, UserTwin }

import scala.collection.immutable.HashSet
import scala.concurrent.*
import com.datastax.oss.driver.api.core.uuid.Uuids.*
import org.apache.pekko.stream.scaladsl.*
import org.apache.pekko.util.FutureConverters.*
import org.slf4j.Logger
import shared.Domain.{ ChatName, ReplyTo }

import scala.util.control.NonFatal

object CassandraStore {

  def createTables(cqlSession: CqlSession, log: Logger): Unit =
    try {
      /*
      cqlSession.getMetrics.ifPresent(metrics => {
        //CassandraMetricsRegistry(system).addMetrics(metricsCategory, metrics.getRegistry)
        metrics.getRegistry()
      })
       */

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
      log.info("★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★")

      log.info(CassandraStore.chatDetailsTable)
      log.info(CassandraStore.timelineTable)

      cqlSession.execute(CassandraStore.createKeyspace)
      cqlSession.execute(CassandraStore.chatDetailsTable)
      cqlSession.execute(CassandraStore.timelineTable)
    } catch {
      case NonFatal(ex) =>
        log.error(s"Table creation error", ex)
    } finally cqlSession.close()

  val createKeyspace =
    "CREATE KEYSPACE IF NOT EXISTS chat WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3 };"

  val chatDetailsTable =
    """
      |CREATE TABLE IF NOT EXISTS chat.chat_details (
      |   chat text,
      |   revision bigint,
      |   participants text,
      |   partition bigint,
      |   PRIMARY KEY (chat)
      |);
      |""".stripMargin

  val timelineTable =
    """
      |CREATE TABLE IF NOT EXISTS chat.timeline (
      |   chat text,
      |   partition bigint,
      |   revision bigint,
      |   message blob,
      |   when timeuuid,
      |   PRIMARY KEY ((chat, partition), revision)) WITH CLUSTERING ORDER BY (revision DESC);
      |""".stripMargin

  type StreamElement = (Chat.State, Long, ChangeDataCapture.Payload)

  def writeDetails(
      session: CqlSession,
      state: Chat.State,
      revision: Long,
      createOrAdd: server.grpc.chat.ChangeDataCapture.CreateChat | server.grpc.chat.ChangeDataCapture.AddParticipant,
      ps: PreparedStatement,
      partitionSize: Long,
    )(using
      ec: ExecutionContext,
      resolver: ActorRefResolver,
    ): Future[Done] =
    createOrAdd match {
      case cdc: server.grpc.chat.ChangeDataCapture.CreateChat =>
        session
          .executeAsync(ps.bind(cdc.chat.raw(), Long.box(revision), "", Long.box(revision) / partitionSize))
          .asScala
          .map { _ =>
            ReplyTo[ChatReply].toBase(state.replyTo).tell(ChatReply(cdc.chat))
            Done
          }
      case cdc: server.grpc.chat.ChangeDataCapture.AddParticipant =>
        session
          .executeAsync(
            ps.bind(cdc.chat.raw(), Long.box(revision), cdc.participantStr, Long.box(revision) / partitionSize)
          )
          .asScala
          .map { _ =>
            ReplyTo[ChatReply].toBase(state.replyTo).tell(ChatReply(cdc.chat))
            Done
          }
    }

  def postMsg(
      session: CqlSession,
      state: Chat.State,
      rev: Long,
      postMsg: server.grpc.chat.ChangeDataCapture.PostMsg,
      ps: PreparedStatement,
      psBatch: PreparedStatement,
      partitionSize: Long,
    )(using
      ec: ExecutionContext,
      resolver: ActorRefResolver,
    ): Future[Done] = {

    val i = rev % partitionSize
    val prNum: Long = Long.box(rev) / partitionSize
    val name = state.name.getOrElse(throw new Exception("Empty chatName !"))

    val f: Future[Done] =
      (if (i >= 0 && i <= 10) { // first 10 rows
         session.executeAsync(
           psBatch.bind(
             prNum,
             name,
             name,
             prNum,
             Long.box(rev),
             Uuids.timeBased(),
             java.nio.ByteBuffer.wrap(postMsg.toByteArray),
           )
         )
       } else {
         session
           .executeAsync(
             ps.bind(
               name,
               prNum,
               Long.box(rev),
               Uuids.timeBased(),
               java.nio.ByteBuffer.wrap(postMsg.toByteArray),
             )
           )
       })
        .asScala
        .map { _ =>
          ReplyTo[ServerCmd]
            .toBase(state.replyTo)
            .tell(ServerCmd(state.name.get, postMsg.content, postMsg.userInfo))
          Done
        }
        .recover {
          case NonFatal(ex) =>
            // ignore error here because we know the same message will be resent
            // that's why gaps in seqNum are possible
            Done
        }

    f
  }
}

final class CassandraStore(system: ExtendedActorSystem) extends DurableStateStoreProvider {

  override def scaladslDurableStateStore(): DurableStateStore[Any] =
    new DurableStateUpdateStore[Chat.State]() {
      val logger = system.log
      import system.dispatcher

      val writeParallelism = system.settings.config.getInt("cassandra.parallelism")
      val bufferSize = system.settings.config.getInt("cassandra.write-buffer-size")

      given refResolver: ActorRefResolver = ActorRefResolver(system.toTyped)
      given mat: Materializer = Materializer.matFromSystem(system)
      given scheduler: org.apache.pekko.actor.Scheduler = system.scheduler
      given typedSystem: ActorSystem[?] = system.toTyped

      val sharding = ClusterSharding(system.toTyped)

      // https://docs.datastax.com/en/developer/java-driver/4.17/manual/core/
      val cqlSession: CqlSession = CqlSession.builder().build()

      // schema
      cqlSession.execute(CassandraStore.createKeyspace)
      cqlSession.execute(CassandraStore.chatDetailsTable)
      cqlSession.execute(CassandraStore.timelineTable)

      val getTimeLimeVersion =
        cqlSession.prepare("SELECT revision FROM chat.timeline WHERE chat=? AND partition=? LIMIT 1")

      val getDetailsVersion =
        cqlSession.prepare("SELECT participants, revision, partition FROM chat.chat_details WHERE chat=?")

      val partitionSize = system.settings.config.getInt("cassandra.partition-size")

      val writePS =
        cqlSession.prepare("INSERT INTO chat.timeline(chat, partition, revision, when, message) VALUES (?,?,?,?,?)")

      val writeBatchPS =
        cqlSession.prepare(
          """
            | BEGIN BATCH
            |  UPDATE chat.chat_details SET partition = ? WHERE chat = ?
            |  INSERT INTO chat.timeline(chat, partition, revision, when, message) VALUES (?,?,?,?,?)
            | APPLY BATCH;
            |""".stripMargin
        )

      val writeDetails =
        cqlSession.prepare("INSERT INTO chat.chat_details(chat, revision, participants, partition) VALUES (?, ?, ?, ?)")

      //
      val (buffer, src) = Source.queue[StreamElement](bufferSize).preMaterialize()

      src
        .mapAsync(writeParallelism) {
          case (state: Chat.State, rev: Long, payload: ChangeDataCapture.Payload) =>
            payload match {
              case Payload.Create(cdc) =>
                CassandraStore.writeDetails(cqlSession, state, rev, cdc, writeDetails, partitionSize)
              case Payload.Add(cdc) =>
                CassandraStore.writeDetails(cqlSession, state, rev, cdc, writeDetails, partitionSize)
              case Payload.PostMsg(postMsg) =>
                CassandraStore.postMsg(
                  cqlSession,
                  state,
                  rev,
                  postMsg,
                  writePS,
                  writeBatchPS,
                  partitionSize,
                )
              case other =>
                Future.failed(new Exception(s"Unsupported cmd $other"))
            }
        }
        .addAttributes(
          ActorAttributes.supervisionStrategy {
            case NonFatal(cause) =>
              if (logger.isErrorEnabled) logger.error(cause, "CassandraStateStore failed and resumes")
              Supervision.Resume
          }
        )
        .runWith(Sink.ignore)

      def insert(
          state: Chat.State,
          chatName: ChatName,
          revision: Long,
        ): Future[Done] = {

        if (bufferSize - buffer.size() < 5)
          logger.warning("CassandraStateStore.buffer({}) close to overflow", buffer.size())

        state.changeDataCapture.payload match {
          case _ @(Payload.Create(_) | Payload.Add(_) | Payload.PostMsg(_)) =>
            val payload = state.changeDataCapture.payload
            buffer.offer((state, revision, payload)) match {
              case QueueOfferResult.Enqueued =>
                Future.successful(Done)
              case QueueOfferResult.Dropped =>
                logger.warning("CassandraStateStore overflow queue.size={}", buffer.size())
                // Chat should resent all messages after timeout
                Future.successful(Done)
              case QueueOfferResult.Failure(cause) =>
                logger.warning(cause.getMessage)
                Future.failed(cause)
              case result: QueueCompletionResult =>
                Future.failed(new Exception("Unexpected"))
            }

          case Payload.Con(info) =>
            Future.successful {
              sharding
                .entityRefFor(UserTwin.TypeKey, UserTwin.key(chatName, info.user))
                .tell(com.domain.user.ConnectUsr(chatName, info.user, info.otp))
              org.apache.pekko.Done
            }

          case Payload.Discon(info) =>
            Future.successful {
              sharding
                .entityRefFor(UserTwin.TypeKey, UserTwin.key(chatName, info.user))
                .tell(com.domain.user.DisconnectUsr(chatName, info.user, info.otp))

              if (state.onlineParticipants.isEmpty)
                sharding.entityRefFor(Chat.TypeKey, chatName.raw()).tell(StopChatEntity(chatName))

              org.apache.pekko.Done
            }

          case Payload.Empty =>
            Future.failed(new Exception("Empty"))
        }
      }

      override def upsertObject(
          chatName: String,
          revision: Long,
          state: Chat.State,
          tag: String,
        ): Future[org.apache.pekko.Done] =
        insert(state, ChatName(chatName), revision)

      override def getObject(chat: String): Future[GetObjectResult[Chat.State]] =
        cqlSession
          .executeAsync(getDetailsVersion.bind(chat))
          .asScala
          .flatMap { rs =>
            Option(rs.one()) match {
              case Some(row) =>
                val participants = row.getString(0)
                val detailsRevision = row.getLong(1)
                val partition = row.getLong(2)
                cqlSession
                  .executeAsync(getTimeLimeVersion.bind(chat, Long.box(partition)))
                  .asScala
                  .map { rs =>
                    val timelineRevision = Option(rs.one()).map(_.getLong(0)).getOrElse(0L)
                    GetObjectResult(
                      Some(
                        ChatState(
                          name = Some(ChatName(chat)),
                          registeredParticipants =
                            HashSet.from(participants.split(",").map(shared.Domain.Participant(_))),
                        )
                      ),
                      timelineRevision max detailsRevision,
                    )
                  }

              case None =>
                Future.successful(GetObjectResult(None, 0))
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
