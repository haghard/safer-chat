// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc
package state

import scala.collection.immutable.HashSet
import server.grpc.Chat.ChatRoomHub
import com.domain.chat.cdc.v1.*
import com.domain.chat.cdc.v1.CdcEnvelope
import com.domain.chat.cdc.v1.CdcEnvelope.*

import shared.Domain.*

final case class ChatState(
    name: Option[ChatName] = None,
    registeredParticipants: HashSet[Participant] =
      HashSet.empty[Participant], // Replace registered and online with Map[Participant, isOnline] FROM ONE-NIO
    onlineParticipants: HashSet[Participant] = HashSet.empty[Participant],
    maybeActiveHub: Option[ChatRoomHub] = None,
    cdc: CdcEnvelope = CdcEnvelope.defaultInstance) { self =>

  def withName(chatName: ChatName, replyTo: ReplyTo) =
    self.copy(
      name = Some(chatName),
      cdc = CdcEnvelope(Payload.Created(ChatCreated(chatName, replyTo))),
    )

  def withNewUser(
      newUser: Participant,
      chat: ChatName,
      replyTo: ReplyTo,
    ) = {
    val allUsers = self.registeredParticipants + newUser
    self.copy(
      registeredParticipants = allUsers,
      cdc = CdcEnvelope(Payload.Added(ParticipantAdded(allUsers.mkString(","), chat, replyTo))),
    )
  }

  def withMsgPosted(
      chat: ChatName,
      content: Map[String, com.google.protobuf.ByteString],
      usrInfo: server.grpc.chat.UserInfo,
      replyTo: ReplyTo,
    ) =
    self
      .copy(cdc = CdcEnvelope(Payload.Posted(MsgPosted(chat, content, usrInfo, replyTo))))

  def withDisconnected(user: Participant, otp: Otp) =
    self.copy(
      onlineParticipants = self.onlineParticipants - user,
      cdc = CdcEnvelope(Payload.DisCntd(Disconnected(user, otp))),
    )

  def withUsrConnected(user: Participant, otp: Otp) =
    self.copy(
      onlineParticipants = self.onlineParticipants + user,
      cdc = CdcEnvelope(Payload.Cntd(Connected(user, otp))),
    )

  def withFirstUsrConnected(
      hub: ChatRoomHub,
      user: Participant,
      otp: Otp,
    ) =
    self.copy(
      maybeActiveHub = Some(hub),
      onlineParticipants = self.onlineParticipants + user,
      cdc = CdcEnvelope(Payload.Cntd(Connected(user, otp))),
    )

  override def toString: String =
    s"ChatState(${name.getOrElse("")}, participants=[${registeredParticipants.mkString(",")}])"
}
