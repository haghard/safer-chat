// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc
package state

import scala.collection.immutable.HashSet
import com.domain.chat.cdc.v1.*
import com.domain.chat.cdc.v1.CdcEnvelope
import shared.Domain.*

final case class ChatState(
    name: Option[ChatName] = None,
    registeredParticipants: HashSet[Participant] = HashSet.empty[Participant],
    cdc: CdcEnvelope = CdcEnvelope.defaultInstance) { self =>

  def withName(chatName: ChatName, replyTo: ReplyTo): ChatState =
    self.copy(
      name = Some(chatName),
      cdc = ChatCreated(chatName, replyTo),
    )

  def withNewUser(
      newUser: Participant,
      chat: ChatName,
      replyTo: ReplyTo,
    ): ChatState = {
    val allUsers = self.registeredParticipants + newUser
    self.copy(
      registeredParticipants = allUsers,
      cdc = ParticipantAddedV2(allUsers.map(_.raw()), chat, replyTo),
    )
  }

  override def toString: String =
    s"ChatState(${name.getOrElse("")}, participants=[${registeredParticipants.mkString(",")}])"
}
