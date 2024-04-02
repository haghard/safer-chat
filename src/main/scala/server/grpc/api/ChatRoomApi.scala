// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc
package api

import scala.concurrent.*
import scala.concurrent.duration.*
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.Logger
import org.apache.pekko.*
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.CoordinatedShutdown.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.*
import com.domain.chat.ChatCmd
import com.domain.chat.ChatReply.StatusCode
import com.domain.chat.{ ChatReply, * }
import org.apache.pekko.actor.typed.ActorRefResolver
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{ Askable, schedulerFromActorSystem }
import server.grpc.api.ChatRoomApi.ChatError
import server.grpc.chat.{ ClientCmd, ServerCmd }
import shared.Domain.{ ChatName, Otp, Participant, ReplyTo }
import shared.AppConfig

import scala.util.control.NoStackTrace

// import org.apache.pekko.actor.typed.scaladsl.adapter.{ ClassicActorSystemOps, TypedActorRefOps }

object ChatRoomApi {

  final case class ChatError(cause: String) extends Exception(cause) with NoStackTrace
}

final class ChatRoomApi(
    appConf: AppConfig,
    chatRegion: ActorRef[ChatCmd],
    kss: ConcurrentHashMap[ChatName, stream.KillSwitch],
  )(using system: ActorSystem[?])
    extends server.grpc.chat.ChatRoom {

  given ec: ExecutionContext = system.executionContext
  given logger: Logger = system.log
  given failoverTo: util.Timeout = util.Timeout(5.seconds)
  given replyToResolver: ActorRefResolver = ActorRefResolver(system)
  given streamRefsResolver: stream.StreamRefResolver = stream.StreamRefResolver(system)

  def post(in: Source[ClientCmd, NotUsed]): Source[ServerCmd, NotUsed] =
    in.prefixAndTail(1).flatMapConcat {
      case (Seq(authMsg), source) =>
        Source
          .lazyFutureSource { () =>
            val user = authMsg.userInfo.user
            auth(chatRegion, authMsg.chat, user, authMsg.otp, source).map { authSrc =>
              authSrc.via(flow(chatRegion, authMsg, user))
            }
          }
    }

  private def flow(
      chatRegion: ActorRef[ChatCmd],
      authMsg: ClientCmd,
      user: Participant,
    ): Flow[ClientCmd, ServerCmd, NotUsed] =
    RestartFlow.withBackoff(stream.RestartSettings(failoverTo.duration, failoverTo.duration.plus(2.seconds), 0.2))(() =>
      Flow.lazyFutureFlow(() => chatFlow(chatRegion, authMsg, user))
    )

  private def auth(
      chatRegion: ActorRef[ChatCmd],
      chat: ChatName,
      user: Participant,
      otp: Otp,
      source: Source[ClientCmd, NotUsed],
    ): Future[Source[ClientCmd, NotUsed]] =
    chatRegion
      .ask[ChatReply](replyTo => AuthUser(chat, user, otp, ReplyTo[ChatReply].toCustom(replyTo)))
      .map { reply =>
        reply.statusCode match {
          case StatusCode.Ok =>
            source
          case code =>
            val msg = s"AuthUsr error: $code"
            logger.warn(msg)
            throw ChatError(msg)
        }
      }

  private def chatFlow(
      chatRegion: ActorRef[ChatCmd],
      authMsg: ClientCmd,
      user: Participant,
    ): Future[Flow[ClientCmd, ServerCmd, NotUsed]] =
    chatRegion
      .ask[ChatReply](replyTo => ConnectRequest(authMsg.chat, user, authMsg.otp, ReplyTo[ChatReply].toCustom(replyTo)))
      .map { reply =>
        reply.statusCode match {
          case StatusCode.Ok =>
            val srcRef: SourceRef[ServerCmd] =
              streamRefsResolver.resolveSourceRef[ServerCmd](reply.sourceRefStr)

            val sinkRef: SinkRef[ClientCmd] =
              streamRefsResolver.resolveSinkRef[ClientCmd](reply.sinkRefStr)

            Flow
              .fromSinkAndSourceCoupled(
                sinkRef.sink(),
                srcRef
                  .source
                  .map { msg =>
                    msg.content.get(appConf.default) match {
                      case Some(defaultBts) =>
                        msg.content.get(user.raw()) match {
                          case Some(usrMsg) =>
                            /* We send back only 2 pairs:
                             * 1. The sender's pub_key + the encoded content
                             * 2. The default pub_key + the encoded content pub_key(msg)
                             */
                            msg.withContent(Map(user.raw() -> usrMsg, appConf.default -> defaultBts))
                          case None =>
                            msg.withContent(Map(appConf.default -> defaultBts))
                        }
                      case None =>
                        logger.error(s"$user. Default content not found !")
                        msg
                    }
                  },
              )
              .backpressureTimeout(8.seconds) // automatic cleanup of slow subscribers
              .watchTermination() { (_, done) =>
                logger.info("{}@{} connection has been established", authMsg.chat, user)
                done.onComplete { _ =>
                  logger.info("{}@{} connection has been closed", authMsg.chat, user)
                  chatRegion.tell(Disconnect(user, authMsg.chat, authMsg.otp))
                }
                NotUsed
              }

          case errorCode =>
            val msg = s"${authMsg.chat.raw()} Error: $errorCode"
            logger.info(msg)
            throw ChatError(msg)
        }
      }
}
