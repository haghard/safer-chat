// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package org.apache.pekko.stream.serialization

import scala.util.Using
import scala.util.Using.Releasable

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.cluster.ddata.protobuf.SerializationSupport
import org.apache.pekko.protobufv3.internal.*
import org.apache.pekko.serialization.*
import org.apache.pekko.stream.StreamRefMessages
import org.apache.pekko.stream.impl.streamref.*

/*
org.apache.pekko.stream.serialization.StreamRefSerializerUdp
 replaces
org.apache.pekko.stream.serialization.StreamRefSerializer
 */

/*
actor {

    serializers {
      pekko-stream-ref = "org.apache.pekko.stream.serialization.StreamRefSerializer"
    }

    serialization-bindings {
      "org.apache.pekko.stream.SinkRef"                           = pekko-stream-ref
      "org.apache.pekko.stream.SourceRef"                         = pekko-stream-ref
      "org.apache.pekko.stream.impl.streamref.StreamRefsProtocol" = pekko-stream-ref
    }

    serialization-identifiers {
      "org.apache.pekko.stream.serialization.StreamRefSerializer" = 30
    }
  }
}
 */

final class StreamRefSerializerBBufer(val system: ExtendedActorSystem)
    extends org.apache.pekko.serialization.SerializerWithStringManifest
    with SerializationSupport
    with ByteBufferSerializer {

  // org.apache.pekko.stream.serialization.StreamRefSerializer
  val SequencedOnNextManifest = "A"
  val CumulativeDemandManifest = "B"

  val RemoteSinkFailureManifest = "C"
  val RemoteSinkCompletedManifest = "D"
  val SourceRefManifest = "E"
  val SinkRefManifest = "F"
  val OnSubscribeHandshakeManifest = "G"
  val AckManifest = "H"

  implicit val releasable0: Releasable[org.apache.pekko.protobufv3.internal.CodedOutputStream] = _.flush()

  private val internal = org.apache.pekko.stream.serialization.StreamRefSerializer(system)
  override val identifier: Int = internal.identifier

  override def manifest(o: AnyRef): String = internal.manifest(o)

  // internal.toBinary(o)
  override def toBinary(o: AnyRef): Array[Byte] =
    throw new UnsupportedOperationException(s"toBinary(${o.getClass().getSimpleName()}) !")

  // internal.fromBinary(bytes, manifest)
  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    throw new UnsupportedOperationException(s"fromBinary($manifest) !")

  override def toBinary(msg: AnyRef, directByteBuffer: ByteBuffer): Unit =
    msg match {
      case o: StreamRefsProtocol =>
        o match {
          case m: StreamRefsProtocol.SequencedOnNext[?] =>
            Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeSequencedOnNext(m).writeTo(_))
          case h: StreamRefsProtocol.OnSubscribeHandshake =>
            Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeOnSubscribeHandshake(h).writeTo(_))
          case d: StreamRefsProtocol.RemoteStreamFailure =>
            Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeRemoteSinkFailure(d).writeTo(_))
          case d: StreamRefsProtocol.RemoteStreamCompleted =>
            Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeRemoteSinkCompleted(d).writeTo(_))
          case d: StreamRefsProtocol.CumulativeDemand =>
            Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeCumulativeDemand(d).writeTo(_))
          case StreamRefsProtocol.Ack =>
            directByteBuffer.get(Array.emptyByteArray)
        }

      case ref: SinkRefImpl[?] =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeSinkRef(ref).writeTo(_))

      case ref: SourceRefImpl[?] =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeSourceRef(ref).writeTo(_))

      case unknown =>
        throw new IllegalArgumentException(s"Unsupported object ${unknown.getClass}")
    }

  override def fromBinary(directByteBuffer: ByteBuffer, manifest: String): AnyRef =
    // println(s"fromBinary($manifest)  ${directByteBuffer.isDirect}")
    manifest match {
      case OnSubscribeHandshakeManifest =>
        deserializeOnSubscribeHandshake(directByteBuffer)
      case SequencedOnNextManifest =>
        deserializeSequencedOnNext(directByteBuffer)
      case CumulativeDemandManifest =>
        deserializeCumulativeDemand(directByteBuffer)
      case RemoteSinkCompletedManifest =>
        deserializeRemoteStreamCompleted(directByteBuffer)
      case RemoteSinkFailureManifest =>
        deserializeRemoteStreamFailure(directByteBuffer)
      // refs
      case SinkRefManifest =>
        deserializeSinkRef(directByteBuffer)
      case SourceRefManifest =>
        deserializeSourceRef(directByteBuffer)
      case AckManifest =>
        StreamRefsProtocol.Ack
      case unknown =>
        throw new IllegalArgumentException(s"Unsupported manifest '$unknown''")
    }

  // custom implementation
  def deserializeSequencedOnNext(directByteBuffer: ByteBuffer): StreamRefsProtocol.SequencedOnNext[AnyRef] = {
    import server.grpc.chat.*

    val o = StreamRefMessages.SequencedOnNext.parseFrom(directByteBuffer)
    val payload = o.getPayload()
    val bytes = payload.getEnclosedMessage().toByteArray()

    // val ser = serialization.serializerByIdentity(p.getSerializerId)
    // println(s"next(${o.getSeqNr}, ${p.getMessageManifest.toStringUtf8}/${p.getSerializerId}, ${o.getSerializedSize} bts)")
    // println(s"next(${o.getSeqNr()}, ${payload.getSerializerId()},${o.getSerializedSize()} bts)")

    payload.getSerializerId() match {
      case 0 =>
        StreamRefsProtocol.SequencedOnNext(o.getSeqNr, ClientCmd.parseFrom(bytes))
      case 1 =>
        StreamRefsProtocol.SequencedOnNext(o.getSeqNr, ServerCmd.parseFrom(bytes))
      case n =>
        throw new UnsupportedOperationException(s"Unsupported fromBinary custom-ser-id($n) !")
    }

    /*
    if (manifest == server.grpc.chat.ClientCmd.scalaDescriptor.fullName) {
      val payload = server.grpc.chat.ClientCmd.parseFrom(bytes)
      StreamRefsProtocol.SequencedOnNext(o.getSeqNr, payload)
    } else if (manifest == server.grpc.chat.ServerCmd.scalaDescriptor.fullName) {
      val payload = server.grpc.chat.ServerCmd.parseFrom(bytes)
      StreamRefsProtocol.SequencedOnNext(o.getSeqNr, payload)
    } else {
      throw new UnsupportedOperationException(s"Unsupported fromBinary($manifest) !")
    }
     */
  }

  def deserializeOnSubscribeHandshake(bytes: ByteBuffer): StreamRefsProtocol.OnSubscribeHandshake = {
    val handshake = StreamRefMessages.OnSubscribeHandshake.parseFrom(bytes)
    val targetRef = serialization.system.provider.resolveActorRef(handshake.getTargetRef.getPath)
    // println(s"OnHandshake(${handshake.getTargetRef.getPath})")
    StreamRefsProtocol.OnSubscribeHandshake(targetRef)
  }

  def deserializeCumulativeDemand(bytes: ByteBuffer): StreamRefsProtocol.CumulativeDemand = {
    val d = StreamRefMessages.CumulativeDemand.parseFrom(bytes)
    // println(s"Demand(seqNr=${d.getSeqNr()})")
    StreamRefsProtocol.CumulativeDemand(d.getSeqNr())
  }

  def deserializeRemoteStreamCompleted(bytes: ByteBuffer): StreamRefsProtocol.RemoteStreamCompleted = {
    val d = StreamRefMessages.RemoteStreamCompleted.parseFrom(bytes)
    StreamRefsProtocol.RemoteStreamCompleted(d.getSeqNr)
  }

  def deserializeRemoteStreamFailure(bytes: ByteBuffer): AnyRef = {
    val d = StreamRefMessages.RemoteStreamFailure.parseFrom(bytes)
    StreamRefsProtocol.RemoteStreamFailure(d.getCause.toStringUtf8)
  }

  def deserializeSinkRef(bytes: ByteBuffer): org.apache.pekko.stream.impl.streamref.SinkRefImpl[Any] = {
    val ref = StreamRefMessages.SinkRef.parseFrom(bytes)
    val initialTargetRef = serialization.system.provider.resolveActorRef(ref.getTargetRef.getPath)
    org.apache.pekko.stream.impl.streamref.SinkRefImpl[Any](initialTargetRef)
  }

  def deserializeSourceRef(bytes: ByteBuffer): SourceRefImpl[Any] = {
    val ref = StreamRefMessages.SourceRef.parseFrom(bytes)
    val initialPartnerRef = serialization.system.provider.resolveActorRef(ref.getOriginRef.getPath)
    org.apache.pekko.stream.impl.streamref.SourceRefImpl[Any](initialPartnerRef)
  }

  def serializeSequencedOnNext_old(o: StreamRefsProtocol.SequencedOnNext[?]): StreamRefMessages.SequencedOnNext = {
    val p = o.payload.asInstanceOf[AnyRef]
    val msgSerializer = serialization.findSerializerFor(p)

    val payloadBuilder = StreamRefMessages
      .Payload
      .newBuilder()
      .setEnclosedMessage(UnsafeByteOperations.unsafeWrap(msgSerializer.toBinary(p)))
      .setSerializerId(msgSerializer.identifier)

    val ms = org.apache.pekko.serialization.Serializers.manifestFor(msgSerializer, p)
    if (ms.nonEmpty) payloadBuilder.setMessageManifest(ByteString.copyFromUtf8(ms))

    StreamRefMessages.SequencedOnNext.newBuilder().setSeqNr(o.seqNr).setPayload(payloadBuilder.build()).build()
  }

  def serializeSequencedOnNext(o: StreamRefsProtocol.SequencedOnNext[?]): StreamRefMessages.SequencedOnNext = {
    val pb = StreamRefMessages.Payload.newBuilder()
    val payload =
      o.payload match {
        case c: server.grpc.chat.ClientCmd =>
          pb
            .setEnclosedMessage(UnsafeByteOperations.unsafeWrap(c.toByteArray))
            .setSerializerId(0) // ClientCmd
            .build()
        case c: server.grpc.chat.ServerCmd =>
          pb
            .setEnclosedMessage(UnsafeByteOperations.unsafeWrap(c.toByteArray))
            .setSerializerId(1) // ServerCmd
            .build()
      }

    StreamRefMessages
      .SequencedOnNext
      .newBuilder()
      .setSeqNr(o.seqNr)
      .setPayload(payload)
      .build()
  }

  def serializeCumulativeDemand(d: StreamRefsProtocol.CumulativeDemand): StreamRefMessages.CumulativeDemand =
    StreamRefMessages.CumulativeDemand.newBuilder().setSeqNr(d.seqNr).build()

  def serializeOnSubscribeHandshake(o: StreamRefsProtocol.OnSubscribeHandshake)
      : StreamRefMessages.OnSubscribeHandshake =
    StreamRefMessages
      .OnSubscribeHandshake
      .newBuilder()
      .setTargetRef(
        StreamRefMessages
          .ActorRef
          .newBuilder()
          .setPath(Serialization.serializedActorPath(o.targetRef))
      )
      .build()

  def serializeRemoteSinkCompleted(
      d: StreamRefsProtocol.RemoteStreamCompleted
    ): StreamRefMessages.RemoteStreamCompleted =
    StreamRefMessages.RemoteStreamCompleted.newBuilder().setSeqNr(d.seqNr).build()

  def serializeRemoteSinkFailure(
      d: StreamRefsProtocol.RemoteStreamFailure
    ): StreamRefMessages.RemoteStreamFailure =
    StreamRefMessages
      .RemoteStreamFailure
      .newBuilder()
      .setCause {
        val msg = scala.Option(d.msg).getOrElse(d.getClass.getName)
        UnsafeByteOperations.unsafeWrap(msg.getBytes(StandardCharsets.UTF_8))
      }
      .build()

  def serializeSinkRef(sink: SinkRefImpl[?]): StreamRefMessages.SinkRef =
    StreamRefMessages
      .SinkRef
      .newBuilder()
      .setTargetRef(
        StreamRefMessages.ActorRef.newBuilder().setPath(Serialization.serializedActorPath(sink.initialPartnerRef))
      )
      .build()

  def serializeSourceRef(source: SourceRefImpl[?]): StreamRefMessages.SourceRef =
    StreamRefMessages
      .SourceRef
      .newBuilder()
      .setOriginRef(
        StreamRefMessages.ActorRef.newBuilder().setPath(Serialization.serializedActorPath(source.initialPartnerRef))
      )
      .build()
}
