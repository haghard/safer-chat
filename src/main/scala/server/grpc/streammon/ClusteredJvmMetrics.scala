// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc.streammon

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.TypedActorRefOps
import org.apache.pekko.cluster.metrics.StandardMetrics.HeapMemory
import org.apache.pekko.util.ByteString
import spray.json.*
import org.apache.pekko.cluster.metrics.*
import org.apache.pekko.stream.BoundedSourceQueue
import org.apache.pekko.stream.scaladsl.Source

import java.time.{ Instant, ZoneId, ZonedDateTime }
import java.time.format.DateTimeFormatter

//curl --no-buffer -k https://127.0.0.1:8443/jvm
//curl --cacert ./src/main/resources/fsa/fullchain.pem https://127.0.0.1:8443/jvm
object ClusteredJvmMetrics {

  def apply(output: BoundedSourceQueue[ByteString]): Behavior[Nothing] =
    Behaviors
      .setup[ClusterMetricsEvent] { ctx =>
        val defaultTZ = ZoneId.of(java.util.TimeZone.getDefault.getID)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
        val ex = ClusterMetricsExtension(ctx.system)
        ex.subscribe(ctx.self.toClassic)
        active(output, ex, 1024 * 1024, defaultTZ, formatter)
      }
      .narrow

  def active(
      output: BoundedSourceQueue[ByteString],
      ex: ClusterMetricsExtension,
      divider: Long,
      defaultTZ: ZoneId,
      formatter: DateTimeFormatter,
    ): Behavior[ClusterMetricsEvent] =
    Behaviors
      .receive[ClusterMetricsEvent] {
        case (ctx, _ @ClusterMetricsChanged(clusterMetrics)) =>
          clusterMetrics.foreach {
            case HeapMemory(address, timestamp, used, _, max) =>
              val now = formatter.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), defaultTZ))
              val json = JsObject(
                Map(
                  "node" -> JsString(address.toString),
                  "metric" -> JsString("heap"),
                  "when" -> JsString(now),
                  "committed" -> JsString((used / divider).toString + "mb"),
                  "reserved" -> JsString((max.getOrElse(0L) / divider).toString + "mb"),
                )
              ).prettyPrint

              /*if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < .1)
                ctx
                  .log
                  .info(s"""
                    |${server.grpc.jvm.JvmUtils.logNativeMemory()}
                    |★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★
                    |""".stripMargin)
               */

              output.offer(ByteString(json))
            case other =>
              ctx.log.warn("Unexpected metric: {}", other.getClass.getName)
          }
          Behaviors.same
        case (ctx, other) =>
          ctx.log.warn("Unexpected metric: {}", other.getClass.getName)
          Behaviors.ignore
      }
      .receiveSignal {
        case (ctx, PostStop) =>
          ex.unsubscribe(ctx.self.toClassic)
          Behaviors.stopped
      }

  def jvmMetricsSrc(
      src: Source[ByteString, NotUsed],
      clientId: Long,
    )(using sys: ActorSystem[?]
    ): Source[ByteString, NotUsed] =
    src
      .watchTermination() { (_, done) =>
        // done.onComplete(_ => println(s"Disconnected $clientId"))(sys.executionContext)
        NotUsed
      }
  /*.map { bts =>
        println(s" -> $clientId")
        bts
      }*/
}
