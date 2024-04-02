// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package shared

import scala.annotation.tailrec
import scala.util.*
import scala.util.control.NonFatal

import java.io.*

import java.nio.file.*
import java.security.*
import javax.crypto.*
import javax.crypto.spec.IvParameterSpec

object symmetricCrypto {

  val CIPHER = "AES/CBC/PKCS5Padding"
  private val ALGORITHM = "AES"
  private val keyEntryName = "chat"

  final class Encrypter(secretKey: javax.crypto.SecretKey, alg: String) {
    @tailrec final def readByChunk(
        in: ByteArrayInputStream,
        out: CipherOutputStream,
        buffer: Array[Byte],
      ): Unit =
      in.read(buffer) match {
        case -1 => ()
        case n =>
          out.write(buffer, 0, n)
          readByChunk(in, out, buffer)
      }

    def encrypt(content: Array[Byte], bufferSize: Int = 1024): Array[Byte] = {
      val cipher = Cipher.getInstance(alg)
      cipher.init(Cipher.ENCRYPT_MODE, secretKey)
      val initBytes = cipher.getIV
      val in = new ByteArrayInputStream(content)
      val out = new ByteArrayOutputStream()
      val cipherOut = new CipherOutputStream(out, cipher)

      try {
        out.write(initBytes)
        readByChunk(in, cipherOut, new Array[Byte](bufferSize))
      } catch {
        case NonFatal(ex) =>
          throw new Exception("Encryption error", ex)
      } finally {
        if (out != null) {
          out.flush()
          out.close()
        }
        if (cipherOut != null) {
          cipherOut.flush()
          cipherOut.close()
        }
      }
      out.toByteArray
    }
  }

  final class Decrypter(secretKey: javax.crypto.SecretKey, algorithm: String) {
    def decrypt(content: Array[Byte], bufferSize: Int = 1024): Array[Byte] = {
      val cipher = Cipher.getInstance(algorithm)
      val ivBytes = Array.ofDim[Byte](16)
      val buffer = new Array[Byte](bufferSize)
      val in = new ByteArrayInputStream(content)

      in.read(ivBytes)
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(ivBytes))

      val cipherIn = new CipherInputStream(in, cipher)
      val out = new ByteArrayOutputStream()

      @tailrec def readChunk(): Unit = cipherIn.read(buffer) match {
        case -1 => ()
        case n =>
          out.write(buffer, 0, n)
          readChunk()
      }

      try readChunk()
      catch {
        case NonFatal(ex) =>
          throw new Exception("Decryption error", ex)
      } finally {
        in.close()
        cipherIn.close()
        out.flush()
        out.close()
      }
      out.toByteArray
    }
  }

  def getCryptography(jksFilePath: String, jksPassword: String): (Encrypter, Decrypter) = {
    val jks = Paths.get(jksFilePath)

    if (Files.exists(jks)) {
      val password = jksPassword.toCharArray
      val ks: KeyStore = KeyStore.getInstance("pkcs12")
      ks.load(new FileInputStream(jksFilePath), password)

      val secretKey: javax.crypto.SecretKey =
        ks.getKey(keyEntryName, password).asInstanceOf[javax.crypto.SecretKey]

      val encrypter = Encrypter(secretKey, CIPHER)
      val decrypter = Decrypter(secretKey, CIPHER)
      (encrypter, decrypter)
    } else throw new Exception(s"$jks doesn't exist!")

  }

  def genSymmetricSecretKey(): javax.crypto.SecretKey = {
    val secureRandom = new SecureRandom()
    val key = Array.ofDim[Byte](32)
    secureRandom.nextBytes(key)
    val secretKey: javax.crypto.SecretKey = new javax.crypto.spec.SecretKeySpec(key, ALGORITHM)
    // println(base64Encode(new KeyStore.SecretKeyEntry(secretKey).getSecretKey.getEncoded))
    secretKey
  }

  def createJKS(jksFilePath: String, jksPassword: String): Unit = {
    val secureRandom = new SecureRandom()
    val key = Array.ofDim[Byte](32)
    secureRandom.nextBytes(key)

    val secretKey: javax.crypto.SecretKey = new javax.crypto.spec.SecretKeySpec(key, ALGORITHM)
    val ks: KeyStore = KeyStore.getInstance("pkcs12")

    val pwdArray = jksPassword.toCharArray()
    // We tell KeyStore to create a new one by passing null as the first parameter
    ks.load(null, pwdArray)

    val symmetricKey = new KeyStore.SecretKeyEntry(secretKey)
    val password = new KeyStore.PasswordProtection(pwdArray)
    ks.setEntry(keyEntryName, symmetricKey, password)
    Using.resource(new FileOutputStream(jksFilePath))(fos => ks.store(fos, pwdArray))

  }
}
