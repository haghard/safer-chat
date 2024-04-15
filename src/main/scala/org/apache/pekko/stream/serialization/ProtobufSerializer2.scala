package org.apache.pekko.stream.serialization

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.ByteBufferSerializer

import java.nio.ByteBuffer
import scala.util.Using

class ProtobufSerializer2(val system: ExtendedActorSystem)
    extends org.apache.pekko.serialization.SerializerWithStringManifest
    with ByteBufferSerializer {

  given r: scala.util.Using.Releasable[com.google.protobuf.CodedOutputStream] with {
    def release(resource: com.google.protobuf.CodedOutputStream): Unit =
      resource.flush()
  }

  private val internalPbSerializer = org.apache.pekko.remote.serialization.ProtobufSerializer(system)
  override val identifier: Int = internalPbSerializer.identifier // 2

  override def manifest(o: AnyRef): String =
    o match {
      case pb: scalapb.GeneratedMessage =>
        pb.companion.scalaDescriptor.fullName
      case _ =>
        throw new UnsupportedOperationException(s"Unsupported manifest(${o.getClass().getName()}) !")
    }

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case a: server.grpc.chat.ClientCmd =>
        a.toByteArray
      case a: server.grpc.chat.ServerCmd =>
        a.toByteArray
      case _ =>
        throw new UnsupportedOperationException(s"Unsupported toBinary(${o.getClass().getSimpleName()}) !")
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    // println("fromBinary: " + manifest)
    if (manifest == server.grpc.chat.ClientCmd.scalaDescriptor.fullName) {
      server.grpc.chat.ClientCmd.parseFrom(bytes)
    } else if (manifest == server.grpc.chat.ServerCmd.scalaDescriptor.fullName) {
      server.grpc.chat.ServerCmd.parseFrom(bytes)
    } else {
      throw new UnsupportedOperationException(s"Unsupported fromBinary($manifest) !")
    }

  override def toBinary(o: AnyRef, directByteBuffer: ByteBuffer): Unit =
    o match {
      case pb: scalapb.GeneratedMessage =>
        Using.resource(com.google.protobuf.CodedOutputStream.newInstance(directByteBuffer))(pb.writeTo(_))
      case _ =>
        throw new IllegalArgumentException(s"Unsupported object toBinary(${o.getClass().getName()})")
    }

  override def fromBinary(directByteBuffer: ByteBuffer, manifest: String): AnyRef = {
    println("fromBinary2: " + manifest)

    val in = com.google.protobuf.CodedInputStream.newInstance(directByteBuffer)
    if (manifest == com.domain.chat.Create.scalaDescriptor.fullName) {
      com.domain.chat.Create.parseFrom(in)
    } else if (manifest == com.domain.chat.AuthUser.scalaDescriptor.fullName) {
      com.domain.chat.AuthUser.parseFrom(in)
    } else if (manifest == com.domain.chat.RmUser.scalaDescriptor.fullName) {
      com.domain.chat.RmUser.parseFrom(in)
    } else if (manifest == com.domain.chat.ChatReply.scalaDescriptor.fullName) {
      com.domain.chat.ChatReply.parseFrom(in)
    } else if (manifest == com.domain.chat.ConnectRequest.scalaDescriptor.fullName) {
      com.domain.chat.ConnectRequest.parseFrom(in)
    } else if (manifest == com.domain.chat.Disconnect.scalaDescriptor.fullName) {
      com.domain.chat.Disconnect.parseFrom(in)
    } else if (manifest == com.domain.chat.StopChatEntity.scalaDescriptor.fullName) {
      com.domain.chat.StopChatEntity.parseFrom(in)
    } else {
      throw new IllegalArgumentException(s"Unsupported object fromBinary2($manifest)")
    }

    /*else if (manifest == com.domain.chat.PostMessage.scalaDescriptor.fullName) {
      com.domain.chat.PostMessage.parseFrom(in)
    } else if (manifest == server.grpc.chat.ClientCmd.scalaDescriptor.fullName) {
      server.grpc.chat.ClientCmd.parseFrom(in)
    } else if (manifest == server.grpc.chat.ServerCmd.scalaDescriptor.fullName) {
      server.grpc.chat.ServerCmd.parseFrom(in)
    }*/

    /*
    val bytes = new Array[Byte](directByteBuffer.remaining)
    directByteBuffer.get(bytes)
    // internal.fromBinary(bytes)
    internal.fromBinary(bytes, Some(manifest))
     */

  }
}
