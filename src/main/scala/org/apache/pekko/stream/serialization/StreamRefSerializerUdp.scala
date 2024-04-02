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

final class StreamRefSerializerUdp(val system: ExtendedActorSystem)
    extends org.apache.pekko.serialization.SerializerWithStringManifest
    with SerializationSupport
    with ByteBufferSerializer {

  // akka.stream.serialization.StreamRefSerializer
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
      case o: StreamRefsProtocol.SequencedOnNext[?] =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeSequencedOnNext(o).writeTo(_))

      case d: StreamRefsProtocol.CumulativeDemand =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeCumulativeDemand(d).writeTo(_))

      case h: StreamRefsProtocol.OnSubscribeHandshake =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeOnSubscribeHandshake(h).writeTo(_))

      case d: StreamRefsProtocol.RemoteStreamFailure =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeRemoteSinkFailure(d).writeTo(_))

      case d: StreamRefsProtocol.RemoteStreamCompleted =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeRemoteSinkCompleted(d).writeTo(_))

      case ref: SinkRefImpl[?] =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeSinkRef(ref).writeTo(_))

      case ref: SourceRefImpl[?] =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(serializeSourceRef(ref).writeTo(_))

      case StreamRefsProtocol.Ack =>
        directByteBuffer.get(Array.emptyByteArray)

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

  def deserializeSequencedOnNext(bytes: ByteBuffer): StreamRefsProtocol.SequencedOnNext[AnyRef] = {
    val o = StreamRefMessages.SequencedOnNext.parseFrom(bytes)
    val p = o.getPayload()
    val payload =
      serialization.deserialize(
        p.getEnclosedMessage.toByteArray(),
        p.getSerializerId,
        p.getMessageManifest.toStringUtf8,
      )

    val nextMsg = StreamRefsProtocol.SequencedOnNext(o.getSeqNr, payload.get)
    // onNext(3, ServerCmd, 1596)
    // println(s"onNext(${o.getSeqNr}, ${p.getMessageManifest.toStringUtf8}/${p.getSerializerId}, ${o.getSerializedSize})")
    // println(s"onNext(${o.getSeqNr}, ${o.getSerializedSize} bts)")
    nextMsg
  }

  def deserializeOnSubscribeHandshake(bytes: ByteBuffer): StreamRefsProtocol.OnSubscribeHandshake = {
    val handshake = StreamRefMessages.OnSubscribeHandshake.parseFrom(bytes)
    val targetRef = serialization.system.provider.resolveActorRef(handshake.getTargetRef.getPath)
    // println(s"OnHandshake(${handshake.getTargetRef.getPath})")
    StreamRefsProtocol.OnSubscribeHandshake(targetRef)
  }

  def deserializeCumulativeDemand(bytes: ByteBuffer): StreamRefsProtocol.CumulativeDemand = {
    val d = StreamRefMessages.CumulativeDemand.parseFrom(bytes)
    // println(s"CumulativeDemand(${d.getSeqNr})")
    StreamRefsProtocol.CumulativeDemand(d.getSeqNr)
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

  def serializeSequencedOnNext(o: StreamRefsProtocol.SequencedOnNext[?]): StreamRefMessages.SequencedOnNext = {
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
