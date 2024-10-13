package org.apache.pekko.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.{ AsyncResultSet, BatchStatement, PreparedStatement, SimpleStatement }
import org.apache.pekko.{ NotUsed, pattern }
import org.apache.pekko.actor.*
import org.apache.pekko.cluster.*
import org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.stream.{ Attributes, KillSwitch, KillSwitches, OverflowStrategy }
import org.apache.pekko.stream.scaladsl.{ Keep, MergeHub, Sink }
import server.grpc.{ StreamMonitor, ThroughputMonitor }
import server.grpc.chat.ServerCmd

import java.time.{ Instant, ZonedDateTime }
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.DurationInt
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.{ Failure, Success }
import CassandraStore.*

object ChatSessionExtension extends ExtensionId[ChatSessionExtension] with ExtensionIdProvider {

  override def get(system: ActorSystem): ChatSessionExtension = super.get(system)

  override def lookup: ChatSessionExtension.type = ChatSessionExtension

  override def createExtension(system: ExtendedActorSystem): ChatSessionExtension =
    new ChatSessionExtension(system)
}

class ChatSessionExtension(system: ActorSystem) extends Extension {
  given typedSystem: org.apache.pekko.actor.typed.ActorSystem[?] = system.toTyped

  private val profileName = "default"
  private val cDetails = Cluster(system).selfMember.details3()
  private val parallelism = system.settings.config.getInt("cassandra.parallelism")
  private val maxBatchSize = system.settings.config.getInt("cassandra.max-batch-size")

  given ord: scala.math.Ordering[ServerCmd] with {
    def compare(x: ServerCmd, y: ServerCmd): Int =
      x.timeUuid.toUnixTs().compareTo(y.timeUuid.toUnixTs())
  }

  given logger: LoggingAdapter = system.log

  given sch: Scheduler = system.scheduler

  given ec: ExecutionContext = system.dispatcher

  given cqlSession: CqlSession = CassandraSessionExtension(system).cqlSession

  // Shared sink to be used by all local grpc connections.
  val chatSessionSharedSink = chatSessionsSinkImpl(cDetails)

  private def writeSingleMsg(
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

  private def writeBatch(
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

  /*def chatSessionsSinkImpl0(
    clusterMemberDetails: String
  )(using system: ActorSystem[?]
  ): (Sink[ServerCmd, NotUsed], KillSwitch) = {
    val parallelism = system.settings.config.getInt("cassandra.parallelism")
    val maxBatchSize = system.settings.config.getInt("cassandra.max-batch-size")
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

    // stops consuming from the tcp-receive buffer as soon as this buffer fills up.
    MergeHub
      .source[ServerCmd](perProducerBufferSize = 1)
      .mapMaterializedValue { sink =>
        logger.info(s"MergeHub(c*-hub) materialization")
        sink
      }
      .via(StreamMonitor("c*-hub", cmd => s"${cmd.chat.raw()}.${cmd.timeUuid.toUnixTs()}"))
      .buffer(maxBatchSize, OverflowStrategy.backpressure)
      .withAttributes(Attributes.logLevels(org.apache.pekko.event.Logging.InfoLevel))
      /*.withAttributes(
        Attributes
          .inputBuffer(maxBatchSize, maxBatchSize)
          .and(Attributes.logLevels(org.apache.pekko.event.Logging.InfoLevel))
      )*/
      //  https://github.com/jaceksokol/akka-stream-map-async-partition/blob/main/src/test/scala/com/github/jaceksokol/akka/stream/MapAsyncPartitionSpec.scala
      .mapAsyncPartitioned(parallelism)(_.chat.raw()) { (cmd, _) =>
        val fn = () => writeSingleMsg(cmd)
        pattern.retry(fn, Int.MaxValue, 3.seconds).map(_ => cmd)(ExecutionContext.parasitic)
      }
      .via(
        ThroughputMonitor(
          20.seconds,
          state => logger.warning(s"c*-throughput($clusterMemberDetails):${state.throughput()}"),
        )
      )
      .viaMat(KillSwitches.single)(Keep.both)
      .toMat(Sink.ignore)(Keep.left)
      .run()
  }*/

  /** Imagine you would have a high number of users connected to a local node, and they all write messages. We need a
    * way to backpressure (flow control) this traffic all the way from the tcp receive buffer to Cassandra. (GRPC
    * server) -> Cassandra
    *
    * More specifically, we don't want to read from the tcp socket (receive buffer) if the Cassandra client it not
    * writing the messages quickly enough.
    *
    * Creates a shared sink to be used by all connected to this node users to be able to consume and write message to
    * Cassandra in a backpressure-aware manner using fixed memory.
    *
    * In addition to that, it's being used to limit a number of concurrent writes to Cassandra.
    */
  private def chatSessionsSinkImpl(
      clusterMemberDetails: String
    ): (Sink[ServerCmd, NotUsed], KillSwitch) = {

    given PreparedStatement = cqlSession.prepare(
      SimpleStatement
        .builder("INSERT INTO chat.timeline(chat, time_bucket, message, when) VALUES (?,?,?,?)")
        .setExecutionProfileName(profileName)
        .build()
    )

    // keeps consuming from the the receive-buffer and aggregate state in memory
    MergeHub
      .source[ServerCmd](perProducerBufferSize = 1) // TODO: MergeHub -> head-of-line blocking ???
      // .log("cassandra-hub", cmd => s"${cmd.chat.raw()}.${cmd.timeUuid.toUnixTs()}")(logger)
      .buffer(maxBatchSize, OverflowStrategy.backpressure)
      .withAttributes(Attributes.logLevels(org.apache.pekko.event.Logging.InfoLevel))
      .via(
        StreamMonitor("c*-hub", cmd => s"${cmd.chat.raw()}.${cmd.userInfo.user.raw()} at ${cmd.timeUuid.toUnixTs()}")
      )
      .viaMat(KillSwitches.single)(Keep.both)
      .groupedWithin(maxBatchSize, 100.millis) // puts upper cap on write latency
      // .wireTap(printStats("CassandraSink.stats:", 30.seconds))
      .via(
        ThroughputMonitor(
          20.seconds,
          state => logger.warning(s"c*-throughput($clusterMemberDetails):${state.throughput()}"),
        )
      )
      .to(
        Sink.foreachAsync(1) { (messages: Seq[ServerCmd]) =>
          // It safe to use Future.traverse because of maxBatchSize
          Future
            .traverse(messages.groupBy(_.chat.raw()).values) { batchPerChat =>
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
  }

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
