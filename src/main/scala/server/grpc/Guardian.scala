// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc

import scala.collection.immutable
import scala.concurrent.*
import scala.concurrent.duration.*
import com.domain.chat.*
import com.domain.chatRoom.*

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.Logger
import server.grpc.api.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*
import org.apache.pekko.cluster.Member
import org.apache.pekko.cluster.sharding.LeastShardAllocationStrategyWithLogger
import org.apache.pekko.cluster.sharding.typed.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.*
import org.apache.pekko.cluster.typed.*
import org.apache.pekko.grpc.scaladsl.*
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.*
import server.grpc.admin.ChatRoomHandler
import server.grpc.chat.ChatRoomSessionHandler
import shared.AppConfig
import shared.Domain.ChatName

import java.net.InetAddress
import java.time.LocalDateTime
import java.util.TimeZone
import scala.jdk.CollectionConverters.*

object Guardian {

  val chatDC = "chat-DC"
  val sessionDC = "session-DC"

  enum Protocol {
    // case SelfUpMsg(mbs: immutable.SortedSet[Member]) extends Protocol
    case SelfUpMsgMultiDc(mbs: Map[String, immutable.SortedSet[Member]]) extends Protocol
  }

  def apply(appCfg: AppConfig): Behavior[Protocol] =
    Behaviors
      .setup[Protocol] { ctx =>
        given sys: ActorSystem[?] = ctx.system
        given cluster: Cluster = Cluster(sys)
        given logger: Logger = ctx.log // sys.log

        cluster
          .subscriptions
          .tell(
            Subscribe(
              ctx.messageAdapter[SelfUp] {
                case m: SelfUp =>
                  Protocol.SelfUpMsgMultiDc(
                    m.currentClusterState
                      .members
                      .groupBy[String](_.dataCenter)
                      .view
                      .mapValues(immutable.SortedSet.from(_)(Member.ageOrdering))
                      .toMap
                  )

                // Protocol.SelfUpMsg(immutable.SortedSet.from(m.currentClusterState.members)(Member.ageOrdering))
              },
              classOf[SelfUp],
            )
          )

        Behaviors.receive[Protocol] {
          case (ctx, _ @Protocol.SelfUpMsgMultiDc(membersByAgeDc)) =>
            import org.apache.pekko.cluster.*

            cluster.subscriptions ! Unsubscribe(ctx.self)

            val membersByAge = membersByAgeDc(cluster.selfMember.dataCenter)
            membersByAge.headOption.foreach { shardCoordinator =>
              val totalMemory = ManagementFactory
                .getOperatingSystemMXBean()
                .asInstanceOf[com.sun.management.OperatingSystemMXBean]
                .getTotalMemorySize()

              val rntm = scala.sys.runtime
              val jvmInfo =
                s"Cores:${rntm.availableProcessors()} Memory:[Total=${rntm.totalMemory() / 1000000}Mb, Max=${rntm
                    .maxMemory() / 1000000}Mb, Free=${rntm.freeMemory() / 1000000}Mb, RAM=${totalMemory / 1000000} ]"

              val isUpd = if (cluster.selfMember.appVersion.compareTo(shardCoordinator.appVersion) > 0) "✅" else "❌"

              ctx
                .log
                .info(
                  s"""
                     |--------------------------------------------------------------------------------
                     |Member:${cluster.selfMember.details()}🧪ShCoord:${shardCoordinator
                      .details()}🧪Leader:[${cluster.state.leader.getOrElse("")}]🧪Rolling update:$isUpd
                     |Members:[${membersByAgeDc.values.map(_.map(_.details()).mkString(",")).mkString(", ")}
                     |
                     |Env
                     |Hostname:${InetAddress.getLocalHost().getHostName()},
                     |PID:${ProcessHandle.current().pid()}. Start time:${LocalDateTime
                      .now()} / ${TimeZone.getDefault().getID()}
                     |★ ★ ★ ★ ★ ★ JVM vendor/version: ${scala
                      .sys
                      .props("java.vm.name")}/${scala.sys.props("java.version")} ★ ★ ★ ★ ★ ★
                     |$jvmInfo
                     |${ManagementFactory
                      .getMemoryPoolMXBeans()
                      .asScala
                      .map(p => s"${p.getName()} / ${p.getType()} / ${p.getPeakUsage()}")
                      .mkString("\n")}
                     |Args:${ManagementFactory.getRuntimeMXBean().getInputArguments()}
                     |★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★
                     |👍✅🚀🧪❌😄📣🔥🐳🚨😱🥳💰⚡️🚨😱🥳
                     |🚶(leave) 🙄(roll eyes) 🔫 ("say that again, I double dare you") 👩‍💻😇
                     |---------------------------------------------------------------------------------
                     |""".stripMargin
                )
            }

            /*
              https://pekko.apache.org/docs/pekko-grpc/current/server/pekko-http.html#pekko-http-authentication-route
              val customErrorMapping: PartialFunction[Throwable, Trailers] = {
                case ex: IllegalArgumentException =>
                  Trailers(Status.INVALID_ARGUMENT.withDescription(ex.getMessage))
              }
             */

            val kss = new ConcurrentHashMap[ChatName, KillSwitch]()
            val sharding = ClusterSharding(sys)

            /*
            val numOfShards = sys.settings.config.getInt("pekko.cluster.sharding.number-of-shards")
            new DynamicLeastShardAllocationStrategy(1, 10, 2, 0.0)
            val oldAllocationStrategy = new org.apache.pekko.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy(32, 1)
             */

            val allocationStrategy = new LeastShardAllocationStrategyWithLogger(8, 1)

            // to keep chat and chatSession actor on the same node
            // val allocationStrategy = new org.apache.pekko.cluster.sharding.ConsistentAllocation(2)
            // val allocationStrategy = new LeastShardNoRebalancingAllocationStrategy(logger)

            // on session-DC it's valid shardRegion, on chat-DC it acts as shardProxy
            val chatRoomSessionRegionOrProxy: ActorRef[ChatRoomCmd] =
              sharding.init(
                Entity(ChatRoomSession.TypeKey) { entityCtx =>
                  ChatRoomSession(ChatName(entityCtx.entityId), kss)
                }
                  .withSettings(
                    ClusterShardingSettings(sys)
                      .withPassivationStrategy(
                        ClusterShardingSettings
                          .PassivationStrategySettings
                          .defaults
                          // .withActiveEntityLimit(256) TODO:
                          // .withIdleEntityPassivation(30.seconds)
                      )
                  )
                  .withDataCenter(sessionDC)
                  .withMessageExtractor(ChatRoomSession.shardingMessageExtractor())
                  .withAllocationStrategy(allocationStrategy)
              )

            if (cluster.selfMember.dataCenter == chatDC) {

              // on chat-DC it's valid shardRegion, on sessionDC it acts as shardProxy
              val chatRoomRegionOrProxy: ActorRef[ChatCmd] =
                sharding.init(
                  Entity(ChatRoom.TypeKey)(entityCtx => ChatRoom(ChatName(entityCtx.entityId), appCfg, 30.seconds))
                    // .withDataCenter(chatDC)
                    .withMessageExtractor(ChatRoom.shardingMessageExtractor())
                    .withAllocationStrategy(allocationStrategy)
                )

              val grpcService: HttpRequest => Future[HttpResponse] =
                ServiceHandler.concatOrNotFound(
                  ChatRoomHandler.partial(new ChatRoomApi(appCfg, chatRoomRegionOrProxy)),
                  ChatRoomSessionHandler.partial(
                    new ChatRoomSessionApi(appCfg, chatRoomRegionOrProxy, chatRoomSessionRegionOrProxy, kss)
                  ),
                  ServerReflection.partial(List(server.grpc.admin.ChatRoom, server.grpc.chat.ChatRoomSession)),
                )
              AppBootstrap.grpc(appCfg, grpcService, kss)
            }

            AppBootstrap.http(appCfg)
            AppBootstrap.onShutdown(kss)
            Behaviors.same
        }
      }
      .narrow
}
