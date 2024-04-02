// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc
package state

import scala.collection.immutable.HashSet
import server.grpc.Chat.ChatRoomHub
import server.grpc.chat.ChangeDataCapture
import server.grpc.chat.ChangeDataCapture.Payload
import server.grpc.chat.ChangeDataCapture.*
import shared.Domain.*

final case class ChatState(
    name: Option[ChatName] = None,
    registeredParticipants: HashSet[Participant] = HashSet.empty[Participant],
    onlineParticipants: HashSet[Participant] = HashSet.empty[Participant],
    maybeActiveHub: Option[ChatRoomHub] = None,
    changeDataCapture: server.grpc.chat.ChangeDataCapture = server.grpc.chat.ChangeDataCapture.defaultInstance,
    // confirm: scala.concurrent.Promise[_]
    replyTo: ReplyTo = ReplyTo.empty) {
  self =>

  def withName(chatName: ChatName, replyTo: ReplyTo) =
    self.copy(
      name = Some(chatName),
      changeDataCapture = ChangeDataCapture(Payload.Create(CreateChat(chatName))),
      replyTo = replyTo,
    )

  def withNewUser(
      user: Participant,
      chat: ChatName,
      replyTo: ReplyTo,
    ) = {
    val allUsers = self.registeredParticipants + user
    self.copy(
      registeredParticipants = allUsers,
      changeDataCapture = ChangeDataCapture(Payload.Add(AddParticipant(allUsers.mkString(","), chat))),
      replyTo = replyTo,
    )
  }

  def withMsgPosted(
      content: Map[String, com.google.protobuf.ByteString],
      usrInfo: server.grpc.chat.UserInfo,
      replyTo: ReplyTo,
    ) =
    self.copy(
      changeDataCapture = ChangeDataCapture(Payload.PostMsg(PostMsg(content, usrInfo))),
      replyTo = replyTo,
    )

  def withDisconnected(user: Participant, otp: Otp) =
    self.copy(
      onlineParticipants = self.onlineParticipants - user,
      changeDataCapture = ChangeDataCapture(Payload.Discon(Disconnected(user, otp))),
    )

  def withUsrConnected(user: Participant, otp: Otp) =
    self.copy(
      onlineParticipants = self.onlineParticipants + user,
      changeDataCapture = ChangeDataCapture(Payload.Con(Connected(user, otp))),
    )

  def withFirstUsrConnected(
      hub: ChatRoomHub,
      user: Participant,
      otp: Otp,
    ) =
    self.copy(
      maybeActiveHub = Some(hub),
      onlineParticipants = self.onlineParticipants + user,
      changeDataCapture = ChangeDataCapture(Payload.Con(Connected(user, otp))),
    )

  override def toString: String =
    s"ChatState(${name.getOrElse("")}, participants=[${registeredParticipants.mkString(",")}])"
}
