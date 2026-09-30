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
import com.domain.chatRoom.*
import org.apache.pekko.actor.typed.ActorRefResolver
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import server.grpc.api.ChatRoomSessionApi.refineMsg
import org.apache.pekko.cassandra.*
import org.apache.pekko.pattern.AskTimeoutException
import server.grpc.api.ChatRoomSessionApi.ChatError
import server.grpc.chat.*
import server.grpc.chat.ServerCmdMessage.SealedValue
import shared.Domain.*
import shared.AppConfig

import scala.util.control.*
import com.google.rpc.{ Code, Status }

object ChatRoomSessionApi {

  final case class ChatError(cause: String) extends Exception(cause) with NoStackTrace

  private def refineMsg(
      msg: LiveServerMessage,
      user: Participant,
      defaultKey: String,
    )(using logger: Logger
    ) =
    msg.content.get(defaultKey) match {
      case Some(defaultBts) =>
        msg.content.get(user.raw()) match {
          case Some(usrMsg) =>
            /* Here we send back only 2 pairs:
             * 1. The sender's pub_key along with the encoded content
             * 2. The default pub_key along with the encoded by that key content. (i.e. encode(pub_key(msg)))
             */
            msg.withContent(Map(user.raw() -> usrMsg, defaultKey -> defaultBts))
          case None =>
            // Here we send back only 1 pairs
            msg.withContent(Map(defaultKey -> defaultBts))
        }
      case None =>
        logger.error(s"$user. Default content not found !")
        msg
    }
}

final class ChatRoomSessionApi(
    appConf: AppConfig,
    chatRoomRegion: ActorRef[ChatCmd],
    chatRoomSessionRegion: ActorRef[ChatRoomCmd],
  )(using system: ActorSystem[?])
    extends server.grpc.chat.ChatRoomSession {

  given ec: ExecutionContext = system.executionContext
  given logger: Logger = system.log
  given askTimeout: util.Timeout = util.Timeout(5.seconds)
  given replyToResolver: ActorRefResolver = ActorRefResolver(system)
  given streamRefsResolver: stream.StreamRefResolver = stream.StreamRefResolver(system)
  given cqlSession: CqlSession = CassandraSessionExtension(system).cqlSession

  private def connect(
      chatRoomRegion: ActorRef[ChatCmd],
      chatRoomSessionRegion: ActorRef[ChatRoomCmd],
      authMsg: LiveClientMessage,
      retryThreshold: Int,
    ): Future[ChatReply] =
    if (retryThreshold > 0) {
      chatRoomRegion
        .ask[ChatReply](replyTo =>
          AuthUser(authMsg.chat, authMsg.userInfo.user, authMsg.otp, ReplyTo[ChatReply].toCustom(replyTo))
        )
        .flatMap { reply =>
          reply.statusCode match {
            case StatusCode.Ok =>
              chatRoomSessionRegion
                .ask[ChatReply](replyTo =>
                  ConnectRequest(authMsg.chat, authMsg.userInfo.user, authMsg.otp, ReplyTo[ChatReply].toCustom(replyTo))
                )
                .map { reply =>
                  reply.statusCode match {
                    case StatusCode.Ok =>
                      reply
                    case other =>
                      throw ChatError(other.toString)
                  }
                }
            case other =>
              Future.failed(ChatError(other.toString))
          }
        }
        .recoverWith {
          case e: AskTimeoutException =>
            org
              .apache
              .pekko
              .pattern
              .after(askTimeout.duration)(connect(chatRoomRegion, chatRoomSessionRegion, authMsg, retryThreshold - 1))
          case NonFatal(ex) =>
            val status: Status = Status.newBuilder().setCode(Code.INTERNAL.getNumber).setMessage(ex.getMessage).build()
            Future.failed(_root_.io.grpc.protobuf.StatusProto.toStatusRuntimeException(status))
        }
    } else {
      Future.failed(
        _root_
          .io
          .grpc
          .protobuf
          .StatusProto
          .toStatusRuntimeException(
            Status.newBuilder().setCode(Code.INTERNAL.getNumber).setMessage("Failed to connect").build()
          )
      )
    }

  /*
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
   */
  def post(in: Source[LiveClientMessage, NotUsed]): Source[LiveServerMessage, NotUsed] =
    in.prefixAndTail(1).flatMapConcat {
      case (Seq(authMsg), source) =>
        Source
          .future(
            connect(chatRoomRegion, chatRoomSessionRegion, authMsg, 3)
          )
          .flatMapConcat(chatReply => source.via(mkChatRoomFlow(authMsg, chatReply)))
      case _ =>
        Source.empty
    }

  def mkChatRoomFlow(
      authMsg: LiveClientMessage,
      reply: ChatReply,
    ): Flow[LiveClientMessage, LiveServerMessage, NotUsed] = {

    val srcRef: SourceRef[ServerCmd] =
      streamRefsResolver.resolveSourceRef[ServerCmd](reply.sourceRefStr)

    val sinkRef: SinkRef[LiveClientMessage] =
      streamRefsResolver.resolveSinkRef[LiveClientMessage](reply.sinkRefStr)

    def mkGrpcFlow() =
      Flow
        .fromSinkAndSourceCoupled(
          sinkRef.sink(),
          Source
            .futureSource {
              chatRoomSessionRegion
                .ask[ChatReply](replyTo =>
                  RequestRecentHistory(authMsg.chat, authMsg.userInfo.user, ReplyTo[ChatReply].toCustom(replyTo))
                )
                .map(_ => srcRef.source)
            }
            .mapConcat { cmd =>
              cmd.asMessage.sealedValue match {
                case SealedValue.RecentHistoryMessage(msg) =>
                  if (msg.user.raw() == authMsg.userInfo.user.raw())
                    msg.recentHistory.map(refineMsg(_, authMsg.userInfo.user, appConf.default))
                  else Seq.empty
                case SealedValue.LiveServerMessage(msg) =>
                  Seq(refineMsg(msg, authMsg.userInfo.user, appConf.default))
                case _: SealedValue.FlushRecentHistory | SealedValue.Empty =>
                  Seq.empty
              }
            },
        )
        .backpressureTimeout(askTimeout.duration) // automatic cleanup of slow subscribers
        .watchTermination() { (_, done) =>
          logger.info("{}@{} StreamRef connection has been established", authMsg.chat, authMsg.userInfo.user)
          done.onComplete { _ =>
            logger.info("{}@{} StreamRef connection has been closed", authMsg.chat, authMsg.userInfo.user)
            chatRoomSessionRegion.tell(Disconnect(authMsg.userInfo.user, authMsg.chat, authMsg.otp))
          }
          NotUsed
        }

    mkGrpcFlow()
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

            /*https://akka.io/blog/building-real-time-video-ai-service-with-google-gemini
            val setupResponseSeen = Promise[LiveClientMessage]()
            chatRoomSessionRegion
              .ask[ChatReply](replyTo =>
                RequestRecentHistory(authMsg.chat, user, ReplyTo[ChatReply].toCustom(replyTo))
              )

            Source.fromMaterializer((m,a) =>
              Source.future(setupResponseSeen.future) ++ srcRef.source
            )*/

            Flow
              .fromSinkAndSourceCoupled(
                sinkRef.sink(),
                Source
                  .futureSource {
                    chatRoomSessionRegion
                      .ask[ChatReply](replyTo =>
                        RequestRecentHistory(authMsg.chat, user, ReplyTo[ChatReply].toCustom(replyTo))
                      )
                      .map(_ => srcRef.source)
                  }
                  .mapConcat { cmd =>
                    cmd.asMessage.sealedValue match {
                      case SealedValue.RecentHistoryMessage(msg) =>
                        if (msg.user.raw() == user.raw()) msg.recentHistory.map(refineMsg(_, user, appConf.default))
                        else Seq.empty
                      case SealedValue.LiveServerMessage(msg) =>
                        Seq(refineMsg(msg, user, appConf.default))
                      case _: SealedValue.FlushRecentHistory | SealedValue.Empty =>
                        Seq.empty
                    }
                  },
              )
              .backpressureTimeout(askTimeout.duration) // automatic cleanup of slow subscribers
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

  override def getHistory(req: GetHistoryRequest): Source[LiveServerMessage, NotUsed] = {
    val p = ExpiringPromise[Seq[LiveServerMessage]](askTimeout.duration)
    val f = ChatExtension(system)
      .readChatHistoryQueue
      .offer((com.domain.chat.session.cassandra.commands.GetHistory(req.chat, req.fromTimeUuid, req.pageSige), p))
      .flatMap {
        case QueueOfferResult.Enqueued =>
          p.future
        case QueueOfferResult.Dropped =>
          Future.failed(ChatError("GetHistory overflow"))
        case result: QueueCompletionResult =>
          Future.failed(ChatError("Unexpected result"))
      }(ec)

    Source.future(f).mapConcat(identity).map(refineMsg(_, req.userInfo.user, appConf.default))
  }

  /*
    def postFlow(
        authMsg: LiveClientMessage,
        user: Participant,
      ): Flow[LiveClientMessage, LiveServerMessage, NotUsed] =
      RestartFlow
        .withBackoff(
          stream
            .RestartSettings(timeout.duration, timeout.duration.plus(1.seconds), 0.2)
            .withMaxRestarts(3, 20.seconds)
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
              logger.error(msg)
              throw ChatError(msg)
          }
        }
   */
}
