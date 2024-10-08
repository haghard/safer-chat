// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc.api

import scala.concurrent.Future
import scala.concurrent.duration.*
import com.domain.chat.*

import com.domain.chat.request.*

import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import shared.AppConfig
import shared.Domain.*
import org.apache.pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem

final class ChatRoomApi(appConf: AppConfig, chatRoomRegion: ActorRef[ChatCmd])(using system: ActorSystem[?])
    extends server.grpc.admin.ChatRoom {

  given to: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(3.seconds)
  given resolver: ActorRefResolver = ActorRefResolver(system)

  def addChat(req: ChatReq): Future[ChatReply] =
    chatRoomRegion.ask[ChatReply](replyTo => Create(req.chat, ReplyTo[ChatReply].toCustom(replyTo)))

  def addUser(req: UserReq): Future[ChatReply] =
    chatRoomRegion.ask[ChatReply](replyTo => AddUser(req.chat, req.user, ReplyTo[ChatReply].toCustom(replyTo)))

  def rmUser(in: UserReq): Future[ChatReply] = ???
}
