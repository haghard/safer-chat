// Copyright (c) 2024-26 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc
package api

import com.datastax.oss.driver.api.core.CqlSession

import scala.concurrent.*
import scala.concurrent.duration.*
import org.slf4j.Logger
import org.apache.pekko.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.*
import com.domain.chat.ChatReply.StatusCode
import com.domain.chat.*
import com.domain.chat.session.cassandra.commands.GetRecentHistory
import com.domain.chatRoom.*
import org.apache.pekko.actor.typed.ActorRefResolver
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.cassandra.{ ChatRoomExtension, ExpiringPromise }
import org.apache.pekko.cassandra.CassandraSessionExtension
import server.grpc.api.ChatRoomSessionApi.ChatError
import server.grpc.chat.*
import server.grpc.chat.ServerCmdMessage.SealedValue
import shared.Domain.*
import shared.AppConfig

import scala.util.control.NoStackTrace

object ChatRoomSessionApi {

  final case class ChatError(cause: String) extends Exception(cause) with NoStackTrace
}

final class ChatRoomSessionApi(
    appConf: AppConfig,
    chatRoomRegion: ActorRef[ChatCmd],
    chatRoomSessionRegion: ActorRef[ChatRoomCmd],
  )(using system: ActorSystem[?])
    extends server.grpc.chat.ChatRoomSession {

  given ec: ExecutionContext = system.executionContext
  given logger: Logger = system.log
  given failoverTo: util.Timeout = util.Timeout(5.seconds)
  given replyToResolver: ActorRefResolver = ActorRefResolver(system)
  given streamRefsResolver: stream.StreamRefResolver = stream.StreamRefResolver(system)
  given cqlSession: CqlSession = CassandraSessionExtension(system).cqlSession

  val recentHistoryQueue = ChatRoomExtension(system).readQueue

  def post(in: Source[LiveClientMessage, NotUsed]): Source[LiveServerMessage, NotUsed] =
    in.prefixAndTail(1).flatMapConcat {
      case (Seq(authMsg), source) =>
        Source
          .lazyFutureSource { () =>
            val user = authMsg.userInfo.user
            auth(chatRoomRegion, authMsg.chat, user, authMsg.otp, source).map { authSrc =>
              authSrc.via(postFlow(authMsg, user))
            }
          }
      case _ =>
        Source.empty
    }

  def postFlow(
      authMsg: LiveClientMessage,
      user: Participant,
    ): Flow[LiveClientMessage, LiveServerMessage, NotUsed] =
    RestartFlow
      .withBackoff(
        stream
          .RestartSettings(failoverTo.duration, failoverTo.duration.plus(2.seconds), 0.2)
          .withMaxRestarts(6, 1.minute)
      )(() => Flow.lazyFutureFlow(() => chatRoomFlow(chatRoomSessionRegion, authMsg, user)))

  def auth(
      chatRoomRegion: ActorRef[ChatCmd],
      chat: ChatName,
      user: Participant,
      otp: Otp,
      source: Source[LiveClientMessage, NotUsed],
    ): Future[Source[LiveClientMessage, NotUsed]] =
    chatRoomRegion
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

  def chatRoomFlow(
      chatRoomSessionRegion: ActorRef[ChatRoomCmd],
      authMsg: LiveClientMessage,
      user: Participant,
    ): Future[Flow[LiveClientMessage, LiveServerMessage, NotUsed]] =
    chatRoomSessionRegion
      .ask[ChatReply](replyTo => ConnectRequest(authMsg.chat, user, authMsg.otp, ReplyTo[ChatReply].toCustom(replyTo)))
      .map { reply =>
        reply.statusCode match {
          case StatusCode.Ok =>
            val srcRef: SourceRef[ServerCmd] =
              streamRefsResolver.resolveSourceRef[ServerCmd](reply.sourceRefStr)

            val sinkRef: SinkRef[LiveClientMessage] =
              streamRefsResolver.resolveSinkRef[LiveClientMessage](reply.sinkRefStr)

            Flow
              .fromSinkAndSourceCoupled(
                sinkRef.sink(),
                srcRef
                  .source
                  .mapAsync(1) { cmd =>
                    cmd.asMessage.sealedValue match {
                      case SealedValue.LiveServerMessage(msg) =>
                        Future.successful(Seq(msg))
                      case SealedValue.FetchRecentHistory(msg) =>
                        val p = ExpiringPromise[Seq[LiveServerMessage]](failoverTo.duration)
                        recentHistoryQueue.offer((GetRecentHistory(msg.chat, msg.bucketName), p)).flatMap {
                          case QueueOfferResult.Enqueued =>
                            p.future
                          case QueueOfferResult.Dropped =>
                            logger.warn("read-queue overflow")
                            Future.failed(new Exception("Read overflow"))
                          case result: QueueCompletionResult =>
                            Future.failed(new Exception("Unexpected QueueOfferResult"))
                        }
                      case SealedValue.Empty =>
                        Future.failed(new Exception("Empty"))
                    }
                    /*if (cmd.fetchRecentHistory) {
                      val p = ExpiringPromise[Seq[ServerCmd]](failoverTo.duration)
                      recentHistoryQueue.offer((GetRecentHistory(cmd.chat, cmd.bucketName), p)).flatMap {
                        case QueueOfferResult.Enqueued =>
                          p.future
                        case QueueOfferResult.Dropped =>
                          logger.warn("read-queue overflow")
                          Future.failed(new Exception("Read overflow"))
                        case result: QueueCompletionResult =>
                          Future.failed(new Exception("Unexpected"))
                      }
                    } else
                      Future.successful(Seq(cmd))*/
                  }
                  .mapConcat(identity)
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
              .backpressureTimeout(5.seconds) // automatic cleanup of slow subscribers
              .watchTermination() { (_, done) =>
                logger.info("{}@{} StreamRef connection has been established", authMsg.chat, user)
                done.onComplete { _ =>
                  logger.info("{}@{} StreamRef connection has been closed", authMsg.chat, user)
                  chatRoomSessionRegion.tell(Disconnect(user, authMsg.chat, authMsg.otp))
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
