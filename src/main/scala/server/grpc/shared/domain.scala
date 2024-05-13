// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package shared

import com.datastax.oss.driver.api.core.uuid.Uuids
import org.apache.pekko.actor.typed.{ ActorRef, ActorRefResolver }
import scalapb.TypeMapper

import scala.compiletime.asMatchable
import scala.util.*
import java.io.*
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.*
import java.security.*
import java.security.interfaces.*
import java.security.spec.*
import java.util
import java.util.*
import spray.json.*
import scala.concurrent.duration.*
import java.time.Duration as JavaDuration

extension (duration: JavaDuration) {
  def asScala: FiniteDuration = FiniteDuration(duration.toNanos, NANOSECONDS)
}

def sha256(bts: Array[Byte]): Array[Byte] =
  MessageDigest.getInstance("SHA-256").digest(bts)

def sha3_256(bts: Array[Byte]): Array[Byte] =
  MessageDigest.getInstance("SHA3-256").digest(bts)

def base64Encode(bs: Array[Byte]): String =
  new String(Base64.getUrlEncoder.withoutPadding.encode(bs))

def base64Decode(s: String): Option[Array[Byte]] =
  Try(Base64.getUrlDecoder.decode(s)).toOption

abstract class Base64EncodedBytes {
  def bytes: Array[Byte]

  final override def toString: String =
    base64Encode(bytes)

  override def equals(that: Any): Boolean = that.asMatchable match {
    case bs: Base64EncodedBytes => bs.bytes.sameElements(bytes)
    case _                      => false
  }

  override def hashCode(): Int = util.Arrays.hashCode(bytes)
}

class Handle protected (val bytes: Array[Byte]) extends Base64EncodedBytes

object Handle {
  def apply(bs: Array[Byte]): Try[Handle] = Try(new Handle(bs))

  def fromEncoded(s: String): Option[Handle] = base64Decode(s).map(new Handle(_))

  private def toBigEndianBytes(bi: BigInt): Array[Byte] = {
    val bs = bi.toByteArray
    if (bs.length > 1 && bs.head == 0.toByte) bs.tail else bs
  }

  def ofModulus(n: BigInt): Handle =
    new Handle(sha256(toBigEndianBytes(n)))

  def ofKey(k: RSAKey): Handle = ofModulus(k.getModulus)
}

object ChatUser {
  private val ALG = "RSA"
  private val publicExponent = new BigInteger((Math.pow(2, 16) + 1).toInt.toString)

  private val settings =
    JsonParserSettings.default.withMaxNumberCharacters(1500)

  def generate(keySize: Int = 2048): ChatUser = {
    val kpg = KeyPairGenerator.getInstance(ALG)
    kpg.initialize(new RSAKeyGenParameterSpec(keySize, publicExponent), new SecureRandom())
    val kp = kpg.generateKeyPair()
    ChatUser(kp.getPublic.asInstanceOf[RSAPublicKey], kp.getPrivate.asInstanceOf[RSAPrivateCrtKey])
  }

  def recoverFromPubKey(pubKeyStr: String): Option[RSAPublicKey] =
    base64Decode(pubKeyStr).map(bts =>
      KeyFactory
        .getInstance(ALG)
        .generatePublic(new java.security.spec.X509EncodedKeySpec(bts))
        .asInstanceOf[RSAPublicKey]
    )

  def backup(chatUser: ChatUser, filename: String): Try[Path] =
    Try(Files.write(Paths.get(filename), chatUser.toString().getBytes(UTF_8)))

  def loadFromDisk(s: scala.io.Source): Option[ChatUser] =
    for {
      str <- Try(s.mkString).toOption
      a <- parse(str.parseJson(settings).convertTo[ChatUserSnapshot]).toOption
    } yield a

  private def parse(a: ChatUserSnapshot): Try[ChatUser] = Try {
    val kf = KeyFactory.getInstance(ALG)
    ChatUser(
      kf.generatePublic(new RSAPublicKeySpec(a.n.bigInteger, a.e.bigInteger)).asInstanceOf[RSAPublicKey],
      kf.generatePrivate(
        new RSAPrivateCrtKeySpec(
          a.n.bigInteger,
          a.e.bigInteger,
          a.d.bigInteger,
          a.p.bigInteger,
          a.q.bigInteger,
          a.dp.bigInteger,
          a.dq.bigInteger,
          a.qi.bigInteger,
        )
      ).asInstanceOf[RSAPrivateCrtKey],
    )
  }
}

final case class ChatUser(pub: RSAPublicKey, priv: RSAPrivateCrtKey) {
  val handle = Handle.ofKey(pub)

  // public key
  val asX509: String = base64Encode(pub.getEncoded())
  // private key
  val asPKCS8: Array[Byte] = priv.getEncoded()

  def toSnapshot(): ChatUserSnapshot =
    ChatUserSnapshot(
      pub.getPublicExponent,
      pub.getModulus,
      priv.getPrivateExponent,
      priv.getPrimeP,
      priv.getPrimeQ,
      priv.getPrimeExponentP,
      priv.getPrimeExponentQ,
      priv.getCrtCoefficient,
    )

  override def toString() =
    toSnapshot().toJson.prettyPrint
}

case class ChatUserSnapshot(
    e: BigInt,
    n: BigInt,
    d: BigInt,
    p: BigInt,
    q: BigInt,
    dp: BigInt,
    dq: BigInt,
    qi: BigInt)

object ChatUserSnapshot extends DefaultJsonProtocol {

  implicit object UserSnapshotJsonFormat extends JsonFormat[ChatUserSnapshot] {
    override def write(c: ChatUserSnapshot) = JsObject(
      "e" -> JsNumber(c.e.toString),
      "n" -> JsNumber(c.n.toString),
      "d" -> JsNumber(c.d.toString),
      "p" -> JsNumber(c.p.toString),
      "q" -> JsNumber(c.q.toString),
      "dp" -> JsNumber(c.dp.toString),
      "dq" -> JsNumber(c.dq.toString),
      "qi" -> JsNumber(c.qi.toString),
    )

    override def read(json: JsValue): ChatUserSnapshot =
      json.asJsObject.getFields("e", "n", "d", "p", "q", "dp", "dq", "qi") match {
        case Seq(
               JsNumber(e),
               JsNumber(n),
               JsNumber(d),
               JsNumber(p),
               JsNumber(q),
               JsNumber(dp),
               JsNumber(dq),
               JsNumber(qi),
             ) =>
          ChatUserSnapshot(
            e.toBigInt,
            n.toBigInt,
            d.toBigInt,
            p.toBigInt,
            q.toBigInt,
            dp.toBigInt,
            dq.toBigInt,
            qi.toBigInt,
          )
      }
  }
}

final case class AppConfig(
    port: Int,
    secretToken: String,
    default: String)

// https://github.com/keynmol/cloudflare-functions-scalajs/blob/main/src/main/scala/app.scala
trait Opq[T, E](using ev: T =:= E) { self =>

  def apply(v: E): T =
    ev.flip(v)

  // def empty(v: E) = self.apply(v)
  val empty = self.apply(null.asInstanceOf[E])

  extension (v: T) {
    def raw(): E = ev(v)

    def into[T1](other: Opq[T1, E]): T1 = {
      val v: E = raw()
      other.apply(v)
    }
  }
}

object Domain {

  opaque type ChatName = String
  object ChatName extends Opq[ChatName, String] {
    given mapper: scalapb.TypeMapper[String, ChatName] =
      TypeMapper[String, ChatName](ChatName(_))(_.raw())
  }

  opaque type Participant = String
  object Participant extends Opq[Participant, String] {
    given mapper: scalapb.TypeMapper[String, Participant] =
      TypeMapper[String, Participant](Participant(_))(_.raw())
  }

  opaque type Otp = String
  object Otp extends Opq[Otp, String] {
    given mapper: scalapb.TypeMapper[String, Otp] =
      TypeMapper[String, Otp](Otp(_))(_.raw())
  }

  opaque type ReplyTo = String
  object ReplyTo extends Opq[ReplyTo, String] {

    given apply[T](using resolver: ActorRefResolver): scalapb.TypeMapper[ActorRef[T], ReplyTo] =
      TypeMapper[ActorRef[T], ReplyTo](ref => ReplyTo(resolver.toSerializationFormat(ref)))(replyTo =>
        resolver.resolveActorRef(replyTo.raw())
      )

    given mapper: scalapb.TypeMapper[String, ReplyTo] =
      TypeMapper[String, ReplyTo](ReplyTo(_))(_.raw())
  }

  opaque type CassandraTimeUUID = String

  object CassandraTimeUUID extends Opq[CassandraTimeUUID, String] {
    given mapper: scalapb.TypeMapper[String, CassandraTimeUUID] =
      scalapb.TypeMapper[String, CassandraTimeUUID](CassandraTimeUUID(_))(_.raw())

    extension (timeUuid: CassandraTimeUUID) {
      // version
      def toUnixTs(): Long =
        Uuids.unixTimestamp(UUID.fromString(timeUuid.raw()))

      def toUUID(): UUID =
        UUID.fromString(timeUuid.raw())
    }
  }

}
