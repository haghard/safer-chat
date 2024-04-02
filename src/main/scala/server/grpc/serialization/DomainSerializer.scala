// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc.serialization

import server.grpc.chat.*
import com.domain.chat.*
import java.io.NotSerializableException

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.*

final class DomainSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest {

  override val identifier: Int = 999

  override def manifest(o: AnyRef): String =
    o.getClass().getName()

  override def toBinary(obj: AnyRef): Array[Byte] =
    obj match {
      case c: ChatCmd                       => c.toByteArray
      case r: ChatReply                     => r.toByteArray
      case cc: ClientCmd                    => cc.toByteArray
      case sc: ServerCmd                    => sc.toByteArray
      case utc: com.domain.user.UserTwinCmd => utc.toByteArray

      case _ =>
        throw new IllegalArgumentException(s"Unable to serialize to bytes, class was: ${obj.getClass}!")
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    if (manifest == classOf[com.domain.chat.ConnectRequest].getName)
      com.domain.chat.ConnectRequest.parseFrom(bytes)
    else if (manifest == classOf[com.domain.chat.AuthUser].getName)
      com.domain.chat.AuthUser.parseFrom(bytes)
    else if (manifest == classOf[com.domain.chat.PostMessage].getName)
      com.domain.chat.PostMessage.parseFrom(bytes)
    else if (manifest == classOf[com.domain.chat.Create].getName) com.domain.chat.Create.parseFrom(bytes)
    else if (manifest == classOf[com.domain.chat.AddUser].getName) com.domain.chat.AddUser.parseFrom(bytes)
    else if (manifest == classOf[com.domain.chat.RmUser].getName) com.domain.chat.RmUser.parseFrom(bytes)
    else if (manifest == classOf[com.domain.chat.Disconnect].getName)
      com.domain.chat.Disconnect.parseFrom(bytes)
    else if (manifest == classOf[com.domain.chat.StopChatEntity].getName)
      com.domain.chat.StopChatEntity.parseFrom(bytes)
    else if (manifest == classOf[ChatReply].getName) com.domain.chat.ChatReply.parseFrom(bytes)
    else if (manifest == classOf[ClientCmd].getName) ClientCmd.parseFrom(bytes)
    else if (manifest == classOf[ServerCmd].getName) ServerCmd.parseFrom(bytes)
    /*
     */
    else if (manifest == classOf[com.domain.user.ConnectUsr].getName) com.domain.user.ConnectUsr.parseFrom(bytes)
    else if (manifest == classOf[com.domain.user.DisconnectUsr].getName) com.domain.user.DisconnectUsr.parseFrom(bytes)
    else
      throw new NotSerializableException(
        s"Unable to deserialize from bytes, manifest was: $manifest! Bytes length: ${bytes.length}"
      )

}
