// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package client.grpc

import scala.concurrent.*
import scala.concurrent.duration.*
import com.bastiaanjansen.otp.*
import com.domain.chat.request.{ ChatReq, UserReq }
import com.google.protobuf.UnsafeByteOperations
import com.typesafe.config.ConfigFactory

import java.nio.charset.StandardCharsets
import java.time.{ Duration, Instant, ZonedDateTime }
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.Logger
import server.grpc.*
import org.apache.pekko.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.stream.scaladsl.Source
import server.grpc.chat.{ ClientCmd, ServerCmd, UserInfo }
import org.apache.pekko.cassandra.ChatRoomCassandraStore.{ SERVER_DEFAULT_TZ, formatter }

import scala.util.control.NonFatal
import _root_.shared.*
import _root_.shared.rsa.*
import _root_.shared.Domain.*

object ChatRoomClient {

  val APP_NAME = "safer-chat"
  val chatName = ChatName("oblivion") // leap_of_faith, oblivion
  sys.props += "APP_VERSION" -> server.grpc.BuildInfo.version
  sys.props += "SERVER_HOSTNAME" -> "127.0.0.2"

  // https://github.com/bcgit/bc-java/blob/main/core/src/test/java/org/bouncycastle/crypto/test/Ed25519Test.java
  // import org.bouncycastle.math.ec.rfc8032.Ed25519
  // Ed25519.Algorithm.Ed25519

  val cnt = new AtomicInteger(0)

  // javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding", new org.bouncycastle.jce.provider.BouncyCastleProvider())
  val cipher =
    javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding", new org.bouncycastle.jce.provider.BouncyCastleProvider())

  def genMsg(userName: String): String =
    s"$userName: hello at " + cnt.getAndIncrement()
  // s"$userName: " + scala.util.Random.alphanumeric.take(260).foldLeft("<")(_ + _)
  // s"$userName: " + scala.util.Random(31).nextString(260)
  // s"$userName: hello at " + System.currentTimeMillis()
  // s"$userName: " + ("a" * 260)

  def postMessages(
      user: ChatUser,
      defaultUsr: ChatUser,
      appConf: AppConfig,
      userName: String,
      userPubKeys: java.util.concurrent.ConcurrentHashMap[String, java.security.interfaces.RSAPublicKey],
    )(using
      sys: ActorSystem[Nothing],
      client: server.grpc.chat.ChatRoomSessionClient,
      logger: Logger,
    ): Future[Done] = {

    logger.warn(s"Performing streaming requests: $userName/${user.handle.toString}")
    val TOTPGen =
      new TOTPGenerator.Builder(appConf.secretToken.getBytes(StandardCharsets.UTF_8) ++ user.handle.bytes) // ++nonce
        .withHOTPGenerator { b =>
          b.withPasswordLength(8)
          b.withAlgorithm(HMACAlgorithm.SHA256)
        }
        .withPeriod(Duration.ofSeconds(10))
        .build()

    /*
      One Time Password (OTP)
      A One Time Password is a form of authentication that is used to grant access to a single login session.
      It requires two inputs, a static value known as a secret key and a moving factor which changes each time an OTP value is generated.
      I use user.handle+salt as a secret key.
     */
    val otp = TOTPGen.now()

    val coords = server.grpc.chat.Coords(43.911806, -80.099738)
    val requests: Source[ClientCmd, NotUsed] =
      // auth message
      Source.single(
        ClientCmd(
          chatName,
          Map.empty,
          UserInfo(
            Participant(user.handle.toString),
            UnsafeByteOperations.unsafeWrap(user.asX509.getBytes(StandardCharsets.UTF_8)),
          ),
          coords,
          Otp(otp),
        )
      ) ++
        Source
          .tick(3.second, 800.millis, ())
          .zipWithIndex
          .map { case (_, i) => i }
          .takeWhile(_ < 100)
          .map { _ =>

            // Each time a message is sent, it is encrypted using each participant's public key and send to the server which knows how to broadcast it to the participants.
            // We encrypt the same message to each user using theirs pub key
            val msgContent = genMsg(userName)

            var content: scala.collection.immutable.Map[String, com.google.protobuf.ByteString] = Map.empty
            userPubKeys.forEach { (usr, usrPubKey) =>
              content = content + (usr -> UnsafeByteOperations.unsafeWrap(cipher.encrypt(msgContent, usrPubKey)))
              // .zip()
            }

            val cmd = ClientCmd(
              chatName,
              content,
              UserInfo(
                Participant(user.handle.toString),
                UnsafeByteOperations.unsafeWrap(user.asX509.getBytes(StandardCharsets.UTF_8)),
              ),
              coords,
              Otp(otp),
            )
            cmd
          }
          .mapMaterializedValue(_ => NotUsed)

    val responseStream: Source[ServerCmd, NotUsed] =
      client.post(requests)

    val done: Future[Done] =
      responseStream.runForeach(onMsg(_, user, defaultUsr, userPubKeys))

    done
  }

  def onMsg(
      serverCmd: ServerCmd,
      user: ChatUser,
      default: ChatUser,
      userPubKeys: java.util.concurrent.ConcurrentHashMap[String, java.security.interfaces.RSAPublicKey],
    )(using logger: Logger
    ): Unit = {
    val sender = serverCmd.userInfo.user.raw()
    ChatUser.recoverFromPubKey(serverCmd.userInfo.pubKey.toStringUtf8()) match {
      case Some(pubKey) =>
        if (userPubKeys.putIfAbsent(sender, pubKey) == null) {
          logger.warn(s"★ ★ ★ ★ ★ ★ $sender joined ★ ★ ★ ★ ★ ★")
        }
      case None =>
        logger.warn(s"★ ★ ★ Got invalid PubKey($sender)  ★ ★ ★")
    }

    // Example: alice wrote this msg to bob using bob's pub key
    serverCmd.content.get(user.handle.toString) match {
      case Some(msgBts) =>
        try {
          val msg = cipher.decrypt(msgBts.toByteArray, user.priv)
          val when = formatter.format(
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(serverCmd.timeUuid.toUnixTs()), SERVER_DEFAULT_TZ)
          )
          logger.info(
            s"$sender: [$msg] at $when. ${serverCmd.serializedSize}bts."
          )
        } catch {
          case NonFatal(ex) =>
            ex.printStackTrace()
            throw ex
        }
      case None =>
        val msgBts = serverCmd.content(default.handle.toString)
        val msg = cipher.decrypt(msgBts.toByteArray, default.priv)
        val when = formatter.format(
          ZonedDateTime.ofInstant(Instant.ofEpochMilli(serverCmd.timeUuid.toUnixTs()), SERVER_DEFAULT_TZ)
        )
        // val msg = cipher.decrypt(msgBts.toByteArray, user.priv) boom
        logger.info(
          s"Default_pub_key/$sender: [$msg] at $when. ${serverCmd.serializedSize}bts."
        )
    }
  }

  @main def main(args: String*): Unit = {
    val userName = if (args.isEmpty) throw new Exception("Expected <username> !") else args(0)
    val cfg = ConfigFactory.load("client.conf")

    given sys: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "client", cfg)
    given ec: ExecutionContext = sys.executionContext
    given logger: Logger = sys.log

    println(sys.settings.config.getConfig("pekko.grpc.client").toString)

    given crClient: server.grpc.admin.ChatRoomClient =
      server
        .grpc
        .admin
        .ChatRoomClient(
          GrpcClientSettings.fromConfig("server.grpc.ChatRoom").withUserAgent(userName)
        )

    given crSessionClient: server.grpc.chat.ChatRoomSessionClient =
      server
        .grpc
        .chat
        .ChatRoomSessionClient(
          GrpcClientSettings.fromConfig("server.grpc.ChatRoomSession").withUserAgent(userName)
        )

    /*
    val u = ChatUser.generate()
    ChatUser.backup(u, "filename.txt")
     */

    val chatUsr: ChatUser =
      ChatUser
        .loadFromDisk(scala.io.Source.fromFile(s"./users/$userName/$userName"))
        .getOrElse(throw new Exception(s"Failed to recover $userName"))

    val defaultUsr: ChatUser = ChatUser
      .loadFromDisk(scala.io.Source.fromFile(s"./users/$userName/default"))
      .getOrElse(throw new Exception(s"Failed to recover $userName"))

    val userPubKeys = new java.util.concurrent.ConcurrentHashMap[String, java.security.interfaces.RSAPublicKey]()
    userPubKeys.put(chatUsr.handle.toString, chatUsr.pub)
    userPubKeys.put(defaultUsr.handle.toString, defaultUsr.pub)

    val appConf = {
      val app = cfg.getConfig(APP_NAME)
      AppConfig(
        app.getInt("grpc-port"),
        app.getString("secret-token"),
        app.getInt("http-port"),
        app.getString("default"),
      )
    }

    val doneF =
      for {
        _ <- crClient.addChat(ChatReq(chatName))
        _ <- crClient.addUser(UserReq(chatName, Participant(chatUsr.handle.toString)))
        done <- postMessages(chatUsr, defaultUsr, appConf, userName, userPubKeys)
      } yield done

    doneF.onComplete { code =>
      sys.log.warn(s"Exit: $code")
      crSessionClient.close().onComplete { _ =>
        sys.log.warn(s"========= Participants =========")
        userPubKeys.keySet().forEach(key => sys.log.warn(s"User($key)"))
        sys.log.warn(s"========= Participants =========")
        sys.log.warn(s"★ ★ ★ ★ ★ ★  Completed($userName) ★ ★ ★ ★ ★ ★")
        sys.terminate()
      }
    }

    val terminationDeadline =
      sys.settings.config.getDuration("pekko.coordinated-shutdown.default-phase-timeout").asScala

    val _ = scala.io.StdIn.readLine()
    sys.log.warn(s"★ ★ ★ ★ ★ ★  $userName Stopped ★ ★ ★ ★ ★ ★")
    sys.terminate()
    scala
      .concurrent
      .Await
      .result(
        sys.whenTerminated,
        terminationDeadline,
      )
  }
}
