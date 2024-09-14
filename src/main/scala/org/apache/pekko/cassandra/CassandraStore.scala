// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package org.apache.pekko.cassandra

import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.*
import com.datastax.oss.driver.api.core.uuid.Uuids.unixTimestamp
import com.domain.chat.*
import org.apache.pekko.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ ActorRefResolver, ActorSystem }
import org.apache.pekko.actor.{ ExtendedActorSystem, Scheduler }
import org.apache.pekko.cassandra.CassandraStore.*
import org.apache.pekko.persistence.state.DurableStateStoreProvider
import org.apache.pekko.persistence.state.scaladsl.*
import org.apache.pekko.stream.*
import com.domain.chat.cdc.v1.*
import com.domain.chat.cdc.v1.CdcEnvelope
import com.domain.chat.cdc.v1.CdcEnvelope.*
import com.domain.chat.cdc.v1.CdcEnvelopeMessage.SealedValue
import org.apache.pekko.event.LoggingAdapter
import server.grpc.chat.ServerCmd
import server.grpc.state.ChatState
import server.grpc.{ ChatRoom, StreamMonitor, ThroughputMonitor }

import scala.collection.immutable.HashSet
import scala.concurrent.*
import org.apache.pekko.stream.scaladsl.*
import org.apache.pekko.util.FutureConverters.*
import org.slf4j.Logger
import shared.Domain.{ ChatName, ReplyTo }

import java.time.{ Instant, ZoneId, ZonedDateTime }
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.collection.mutable
import scala.util.{ Failure, Success }
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
      /*
      cqlSession.getMetrics.ifPresent(metrics => {
        //CassandraMetricsRegistry(system).addMetrics(metricsCategory, metrics.getRegistry)
        metrics.getRegistry()
      })
       */

      val config = cqlSession.getContext().getConfig()
      // val profile = config.getProfile(profileName)
      val profile = config.getDefaultProfile()

      val profileConf =
        s"""
           |${profile.getName}
           |REQUEST_TIMEOUT:${profile.getDuration(DefaultDriverOption.REQUEST_TIMEOUT)}
           |CONNECTION_MAX_REQUESTS:${profile.getInt(DefaultDriverOption.CONNECTION_MAX_REQUESTS)}
           |REQUEST_CONSISTENCY:${profile.getString(DefaultDriverOption.REQUEST_CONSISTENCY)}
           |""".stripMargin

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

      cqlSession.execute(CassandraStore.chatDetailsTable)
      log.info("Executed \n" + CassandraStore.chatDetailsTable)

      cqlSession.execute(CassandraStore.chatTimelineTable)
      log.info("Executed \n" + CassandraStore.chatTimelineTable)

      cqlSession.execute(CassandraStore.leasesTable)
      log.info("Executed \n" + CassandraStore.leasesTable)

    } catch {
      case NonFatal(ex) =>
        log.error("Tables creation error", ex)
        cqlSession.close()
        throw ex
    }

  def getRecentHistory(
      cmd: ServerCmd,
      limit: Int = 15,
    )(using
      cqlSession: CqlSession,
      logger: Logger,
    ): Future[Seq[ServerCmd]] = {
    val chat = cmd.chat.raw()
    val ts = cmd.timeUuid.toUnixTs()
    val bucket = formatterMM.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), UTC))

    val getRecent: PreparedStatement =
      cqlSession.prepare(
        SimpleStatement
          .builder("SELECT chat, when, message FROM timeline WHERE chat=? AND time_bucket=? LIMIT ?")
          .setExecutionProfileName(profileName)
          .build()
      )

    cqlSession
      .executeAsync(getRecent.bind(chat, bucket, limit).setPageSize(limit))
      .asScala
      .map { asyncResultSet =>
        var buffer = List.empty[ServerCmd]
        val sb = new StringBuilder()
        val iter = asyncResultSet.currentPage().iterator()
        while (iter.hasNext) {
          val row = iter.next()
          val timeuud = row.getUuid(1)
          val ts = unixTimestamp(timeuud)
          val cmd = ServerCmd.parseFrom(row.getByteBuffer(2).array())
          buffer = cmd :: buffer
          sb.append(
            s"$timeuud / $ts / ${cmd.userInfo.user.raw()} / ${formatter
                .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), SERVER_DEFAULT_TZ))}"
          ).append("\n")
        }
        logger.debug(s"""
             |Last ${buffer.length}. $chat / $bucket
             |${sb.toString()}
             |""".stripMargin)
        buffer
      }(ExecutionContext.parasitic)
  }

  def writeSingleMsg(
      cmd: ServerCmd
    )(using
      cqlSession: CqlSession,
      ps: PreparedStatement,
      logger: LoggingAdapter,
    ): Future[AsyncResultSet] = {
    val chatName = cmd.chat.raw()
    val tbu: UUID = cmd.timeUuid.toUUID()
    val bts = cmd.toByteArray
    val ts = cmd.timeUuid.toUnixTs()
    val bucket = formatterMM.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), UTC))
    cqlSession
      .executeAsync(
        ps.bind(
          chatName,
          bucket,
          java.nio.ByteBuffer.wrap(bts),
          tbu,
        )
      )
      .asScala
      .transform { asyncResult =>
        asyncResult match {
          case Success(value) =>
            logger.info(s"${Thread.currentThread().getName}: $chatName.$ts.")
            asyncResult
          case Failure(ex) =>
            logger.error(s"Write error $chatName: $ts. Error:${ex.getMessage()}")
            asyncResult
        }
      }(ExecutionContext.parasitic)
  }

  def writeBatch(
      cmds: mutable.SortedSet[ServerCmd]
    )(using
      cqlSession: CqlSession,
      ps: PreparedStatement,
      logger: LoggingAdapter,
    ): Future[AsyncResultSet] = {

    val chatName = cmds.head.chat.raw()
    val revisions = cmds.map(_.timeUuid.toUnixTs()).mkString(",")
    val timeBucket =
      formatterMM.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(cmds.head.timeUuid.toUnixTs()), UTC))

    var batchStmts = BatchStatement.newInstance(com.datastax.oss.driver.api.core.cql.DefaultBatchType.UNLOGGED)
    cmds.foreach { cmd =>
      val tbu = cmd.timeUuid.toUUID()
      batchStmts = batchStmts.add(
        ps.bind(
          chatName,
          timeBucket,
          java.nio.ByteBuffer.wrap(cmd.toByteArray),
          tbu,
        )
      )
    }

    cqlSession
      .executeAsync(batchStmts)
      .asScala
      .transform { asyncResult =>
        asyncResult match {
          case Success(ar) =>
            logger.info(s"${Thread.currentThread().getName}: Written batch: $chatName: [$revisions]")
            asyncResult
          case Failure(ex) =>
            // UnavailableException, WriteTimeoutException, NoNodeAvailableException
            logger.error(s"WriteBatch error: $chatName: [$revisions]. Error:${ex.getMessage()}")
            asyncResult
        }
      }(ExecutionContext.parasitic)

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

  def mkCassandraSink(memberDetails: String)(using system: ActorSystem[?]): (Sink[ServerCmd, NotUsed], KillSwitch) = {
    val classicSystem: org.apache.pekko.actor.ActorSystem = system.toClassic
    given logger: LoggingAdapter = classicSystem.log
    given sch: Scheduler = classicSystem.scheduler
    given ec: ExecutionContext = system.executionContext
    given cqlSession: CqlSession = CassandraSessionExtension(classicSystem).cqlSession

    given ps: PreparedStatement = cqlSession.prepare(
      SimpleStatement
        .builder("INSERT INTO chat.timeline(chat, time_bucket, message, when) VALUES (?,?,?,?)")
        .setExecutionProfileName(profileName)
        .build()
    )

    given ord: scala.math.Ordering[ServerCmd] with {
      def compare(x: ServerCmd, y: ServerCmd): Int =
        x.timeUuid.toUnixTs().compareTo(y.timeUuid.toUnixTs())
    }

    val writeParallelism = system.settings.config.getInt("cassandra.parallelism")
    val maxBatchSize = system.settings.config.getInt("cassandra.max-batch-size") // 8

    MergeHub
      .source[ServerCmd](perProducerBufferSize = 1)
      // .log("cassandra-hub", cmd => s"${cmd.chat.raw()}.${cmd.timeUuid.toUnixTs()}")(logger)
      .withAttributes(
        Attributes
          .inputBuffer(maxBatchSize, maxBatchSize * 4)
          .and(Attributes.logLevels(org.apache.pekko.event.Logging.InfoLevel))
      )
      .via(StreamMonitor("chub", cmd => s"${cmd.chat.raw()}.${cmd.timeUuid.toUnixTs()}"))
      .viaMat(KillSwitches.single)(Keep.both)
      .groupedWithin(maxBatchSize, 50.millis)
      // .wireTap(printStats("CassandraSink.stats:", 30.seconds))
      .via(
        ThroughputMonitor(
          30.seconds,
          state => logger.warning(s"throughput($memberDetails):${state.throughput()}"),
        )
      )
      .to(
        Sink.foreachAsync(1) { (batch: Seq[ServerCmd]) =>
          Future
            .traverse(batch.groupBy(_.chat.raw()).values) { batchPerChat =>
              val writeFunc =
                batchPerChat.size match {
                  case 1 =>
                    () => writeSingleMsg(batchPerChat.head)
                  case n =>
                    () => writeBatch(mutable.SortedSet.from(batchPerChat))
                }
              pattern.retry(writeFunc, Int.MaxValue, 3.seconds)
            }
            .map(_ => ())(ExecutionContext.parasitic)
        }
      )
      .run()

    // keeps consuming from the receive-buffer and aggregate state in memory
    /*MergeHub
      .source[ServerCmd](perProducerBufferSize = 1)
      // .log("cassandra-hub", cmd => s"${cmd.chat.raw()}.${cmd.timeUuid.toUnixTs()}")(logger)
      .withAttributes(Attributes.logLevels(org.apache.pekko.event.Logging.InfoLevel))
      .via(StreamMonitor("c-hub", cmd => s"${cmd.chat.raw()}.${cmd.timeUuid.toUnixTs()}"))
      .viaMat(KillSwitches.single)(Keep.both)
      .conflateWithSeed { cmd =>
        mutable.HashMap[ChatName, mutable.SortedSet[ServerCmd]](cmd.chat -> mutable.SortedSet(cmd))
      } { (state: mutable.HashMap[ChatName, mutable.SortedSet[ServerCmd]], cmd: ServerCmd) =>
        val updated = state.getOrElseUpdate(cmd.chat, mutable.SortedSet.empty[ServerCmd]).+=(cmd)
        state.put(cmd.chat, updated)
        state
      }
      .to(
        Sink.foreachAsync(1) { (batch: mutable.HashMap[ChatName, mutable.SortedSet[ServerCmd]]) =>
          Future
            .traverse(batch.values) { batchPerChat =>
              val writeFn =
                batchPerChat.size match {
                  case 1 =>
                    () => writeSingleMsg(batchPerChat.head)
                  case n =>
                    () => writeBatch(batchPerChat)

                }
              pattern.retry(writeFn, Int.MaxValue, 3.seconds)
            }
            .map(_ => ())(ExecutionContext.parasitic)
        }
      )
      .run()*/
  }

  val chatDetailsTable =
    """
      |CREATE TABLE IF NOT EXISTS chat_details (
      |   chat text,
      |   revision bigint,
      |   participants text,
      |   PRIMARY KEY (chat)
      |);
      |""".stripMargin

  val chatDetailsTable3 =
    """
      |CREATE TYPE user(login text,firstname text,lastname text);
      |CREATE TABLE IF NOT EXISTS chat_details2(
      |   chat text,
      |   revision bigint,
      |   participants set<frozen<user>>,
      |   PRIMARY KEY (chat)
      |);
      |""".stripMargin

  val chatTimelineTable =
    """
      |CREATE TABLE IF NOT EXISTS timeline (
      |   chat text,
      |   time_bucket varchar,
      |   message blob,
      |   when timeuuid,
      |   PRIMARY KEY ((chat, time_bucket), when)) WITH CLUSTERING ORDER BY (when DESC);
      |""".stripMargin

  val leasesTable =
    "CREATE TABLE IF NOT EXISTS leases (name text PRIMARY KEY, owner text) with default_time_to_live = 15"

  type StreamElement = (Long, CdcEnvelopeMessage.SealedValue)

  def updateChatDetails(
      session: CqlSession,
      revision: Long,
      chatDetailsOps: ChatCreated | ParticipantAdded | ParticipantAddedV2,
      ps: PreparedStatement,
    )(using
      ec: ExecutionContext,
      resolver: ActorRefResolver,
    ): Future[Done] =
    chatDetailsOps match {
      case cdc: ChatCreated =>
        session
          .executeAsync(ps.bind(cdc.chat.raw(), Long.box(revision), ""))
          .asScala
          .map { _ =>
            ReplyTo[ChatReply].toBase(cdc.replyTo).tell(ChatReply(cdc.chat))
            Done
          }
      case cdc: ParticipantAdded =>
        session
          .executeAsync(
            ps.bind(cdc.chat.raw(), Long.box(revision), cdc.participants)
          )
          .asScala
          .map { _ =>
            ReplyTo[ChatReply].toBase(cdc.replyTo).tell(ChatReply(cdc.chat))
            Done
          }
      case cdc: ParticipantAddedV2 =>
        session
          .executeAsync(
            ps.bind(cdc.chat.raw(), Long.box(revision), cdc.participants.mkString(","))
          )
          .asScala
          .map { _ =>
            ReplyTo[ChatReply].toBase(cdc.replyTo).tell(ChatReply(cdc.chat))
            Done
          }
    }
}

final class CassandraStore(system: ExtendedActorSystem) extends DurableStateStoreProvider {

  import system.dispatcher

  override def scaladslDurableStateStore(): DurableStateStore[Any] =
    new DurableStateUpdateStore[ChatRoom.State]() {
      val writeParallelism = system.settings.config.getInt("cassandra.parallelism")

      given typedSystem: ActorSystem[?] = system.toTyped

      given logger: Logger = typedSystem.log

      given refResolver: ActorRefResolver = ActorRefResolver(typedSystem)

      given mat: Materializer = Materializer.matFromSystem(system)

      given scheduler: org.apache.pekko.actor.Scheduler = system.scheduler

      val cqlSession: CqlSession = CassandraSessionExtension(system).cqlSession

      cqlSession.execute(CassandraStore.chatDetailsTable)
      cqlSession.execute(CassandraStore.chatTimelineTable)

      val getDetailsRevision =
        cqlSession.prepare(
          SimpleStatement
            .builder("SELECT participants, revision FROM chat_details WHERE chat=?")
            .setExecutionProfileName(profileName)
            .build()
        )

      val writeDetails =
        cqlSession.prepare(
          SimpleStatement
            .builder("INSERT INTO chat_details(chat, revision, participants) VALUES (?, ?, ?)")
            .setExecutionProfileName(profileName)
            .build()
        )

      //
      val (queue, queueSrc) = Source.queue[StreamElement](1 << 7).preMaterialize()
      queueSrc
        .mapAsync(writeParallelism) {
          case (revision: Long, cdc: CdcEnvelopeMessage.SealedValue) =>
            cdc match {
              case SealedValue.Created(chatCreated) =>
                CassandraStore.updateChatDetails(cqlSession, revision, chatCreated, writeDetails)
              case SealedValue.Added(participantAdded) =>
                CassandraStore.updateChatDetails(cqlSession, revision, participantAdded, writeDetails)
              case SealedValue.AddedV2(participantAddedV2) =>
                CassandraStore.updateChatDetails(cqlSession, revision, participantAddedV2, writeDetails)
              case SealedValue.Empty =>
                Future.failed(new Exception(s"Unsupported cdc.SealedValue.Empty"))
            }
        }
        .addAttributes(
          ActorAttributes.supervisionStrategy {
            case NonFatal(cause) =>
              if (logger.isErrorEnabled) logger.error("ChatDetailsStore failed and resumed", cause)
              Supervision.Resume
          }
        )
        .runWith(Sink.ignore)

      def insert(
          state: ChatRoom.State,
          chatName: ChatName,
          revision: Long,
        ): Future[Done] =
        state.cdc match {
          case CdcEnvelope.Empty =>
            Future.failed(new Exception("Empty"))
          case payload: NonEmpty =>
            queue.offer((revision, payload.asMessage.sealedValue)) match {
              case QueueOfferResult.Enqueued =>
                Future.successful(Done)
              case QueueOfferResult.Dropped =>
                logger.warn("ChatDetailsStore overflow queue.size={}", queue.size())
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

      override def getObject(chat: String): Future[GetObjectResult[ChatRoom.State]] =
        cqlSession
          .executeAsync(getDetailsRevision.bind(chat))
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
