// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc.api

import scala.concurrent.Future
import scala.concurrent.duration.*
import com.domain.chat.*
import server.grpc.admin.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import shared.AppConfig
import shared.Domain.*
import org.apache.pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem

final class AdminApi(appConf: AppConfig, chatRegion: ActorRef[ChatCmd])(using system: ActorSystem[?])
    extends server.grpc.admin.Admin {

  given to: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(3.seconds)
  given resolver: ActorRefResolver = ActorRefResolver(system)

  // import shared.Conversions.given

  def addUser(req: AddUserReq): Future[ChatReply] =
    chatRegion.ask[ChatReply](r => AddUser(req.chat, req.user, ReplyTo[ChatReply].toCustom(r)))

  def addChat(req: CreateChat): Future[ChatReply] =
    chatRegion.ask[ChatReply](r => Create(req.chat, ReplyTo[ChatReply].toCustom(r)))

  def rmUser(in: RmUserReq): Future[ChatReply] = ???
}
