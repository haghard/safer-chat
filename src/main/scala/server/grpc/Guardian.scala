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
import shared.rsa.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*
import org.apache.pekko.cassandra.CassandraStore
import org.apache.pekko.cluster.Member
import org.apache.pekko.cluster.sharding.typed.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.*
import org.apache.pekko.cluster.typed.*
import org.apache.pekko.grpc.scaladsl.*
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.Sink
import shared.AppConfig
import shared.Domain.ChatName

import java.time.LocalDateTime
import java.util.TimeZone

object Guardian {

  enum Protocol {
    case SelfUpMsg(mba: immutable.SortedSet[Member]) extends Protocol
  }

  def apply(appCfg: AppConfig): Behavior[Protocol] =
    Behaviors
      .setup[Protocol] { ctx =>
        given sys: ActorSystem[?] = ctx.system
        given cluster: Cluster = Cluster(sys)
        given logger: Logger = sys.log

        cluster
          .subscriptions
          .tell(
            Subscribe(
              ctx.messageAdapter[SelfUp] {
                case m: SelfUp =>
                  Protocol.SelfUpMsg(immutable.SortedSet.from(m.currentClusterState.members)(Member.ageOrdering))
              },
              classOf[SelfUp],
            )
          )

        Behaviors.receive[Protocol] {
          case (ctx, _ @Protocol.SelfUpMsg(membersByAge)) =>
            cluster.subscriptions ! Unsubscribe(ctx.self)

            import org.apache.pekko.cluster.*
            membersByAge.headOption.foreach { singleton =>
              val totalMemory = ManagementFactory
                .getOperatingSystemMXBean()
                .asInstanceOf[com.sun.management.OperatingSystemMXBean]
                .getTotalMemorySize()

              val rntm = Runtime.getRuntime()
              val jvmInfo =
                s"Cores:${rntm.availableProcessors()} Memory:[Total=${rntm.totalMemory() / 1000000}Mb, Max=${rntm
                    .maxMemory() / 1000000}Mb, Free=${rntm.freeMemory() / 1000000}Mb, RAM=${totalMemory / 1000000} ]"

              ctx
                .log
                .info(
                  s"""
                     |------------- Started: ${cluster.selfMember.details()}  ------------------
                     |Singleton: [${singleton.details2()}]/Leader:[${cluster.state.leader.getOrElse("")}]
                     |Members:[${membersByAge.map(_.details()).mkString(", ")}]
                     |${server.grpc.BuildInfo.toString}
                     |Environment: [TZ:${TimeZone.getDefault.getID}. Start time:${LocalDateTime.now()}]
                     |PID:${ProcessHandle.current().pid()} JVM: $jvmInfo
                     |👍✅🚀🧪
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

            val allocationStrategy = new org.apache.pekko.cluster.sharding.ConsistentHashingAllocation(4)

            val chatUserRegion: ActorRef[ChatCmd] = sharding.init(
              Entity(Chat.TypeKey)(entityCtx => Chat(ChatName(entityCtx.entityId), appCfg))
                .withSettings(
                  ClusterShardingSettings(sys)
                    .withPassivationStrategy(
                      ClusterShardingSettings
                        .PassivationStrategySettings
                        .defaults
                        .withIdleEntityPassivation(3.minutes)
                    )
                )
                .withMessageExtractor(Chat.shardingMessageExtractor())
                .withStopMessage(StopChatEntity())
                .withAllocationStrategy(allocationStrategy)
            )

            val (cassandraSink, cks) = CassandraStore.mkSink
            kss.put(ChatName("cassandra.0"), cks)

            val chatRoomRegion: ActorRef[ChatRoomCmd] =
              sharding.init(
                Entity(ChatRoom.TypeKey)(entityCtx =>
                  ChatRoom(ChatName(entityCtx.entityId), chatUserRegion, kss, cassandraSink)
                )
                  .withSettings(
                    ClusterShardingSettings(sys)
                      .withPassivationStrategy(
                        ClusterShardingSettings
                          .PassivationStrategySettings
                          .defaults
                          // .withActiveEntityLimit(256) TODO:
                          .withIdleEntityPassivation(30.seconds)
                      )
                  )
                  .withMessageExtractor(ChatRoom.shardingMessageExtractor())
                  .withAllocationStrategy(allocationStrategy)
              )

            val grpcService: HttpRequest => Future[HttpResponse] =
              ServiceHandler.concatOrNotFound(
                server
                  .grpc
                  .chat
                  .ChatRoomHandler
                  .partial(new ChatRoomApi(appCfg, chatUserRegion, chatRoomRegion, kss)),
                server.grpc.admin.AdminHandler.partial(new AdminApi(appCfg, chatUserRegion)),
                ServerReflection.partial(List(server.grpc.chat.ChatRoom, server.grpc.admin.Admin)),
              )

            AppBootstrap(appCfg, grpcService, kss)
            Behaviors.same
        }
      }
      .narrow
}
