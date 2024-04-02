// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package shared

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.control.NonFatal

import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.*
import java.security.*
import java.time.Duration as JavaDuration
import java.util.zip.*
import javax.crypto.Cipher

object Extentions {

  // https://stackoverflow.com/questions/71268249/how-to-do-rsa-encryption-and-decryption-on-a-large-data-in-java/71268250#71268250
  extension (cipher: javax.crypto.Cipher) {
    def encrypt(msg: String, pubKey: PublicKey): Array[Byte] = {
      cipher.init(Cipher.ENCRYPT_MODE, pubKey)
      val blockSize = cipher.getBlockSize()

      val payload = msg.getBytes(UTF_8)
      val payloadLength = payload.length

      scala.util.Using.resource(new ByteArrayOutputStream()) { out =>
        var offset = 0
        var end = 0
        while (end < payloadLength) {
          end = end + blockSize
          if (end > payloadLength) end = payloadLength

          val len = end - offset
          val chunk = cipher.doFinal(payload, offset, len)
          out.write(chunk)
          offset = end
        }
        out.toByteArray()
      }
    }

    def decrypt(bts: Array[Byte], privateKey: PrivateKey): String = {
      cipher.init(Cipher.DECRYPT_MODE, privateKey)
      val blockSize = cipher.getBlockSize()
      val payloadLength = bts.length
      scala.util.Using.resource(new StringWriter(payloadLength)) { wrt =>
        var offset = 0
        var end = 0
        while (offset < payloadLength) {
          end = end + blockSize
          if (end > payloadLength) end = payloadLength
          val len = end - offset
          val chunk = cipher.doFinal(bts, offset, len)
          wrt.write(new String(chunk, UTF_8))
          offset = end
        }
        wrt.toString()
      }
    }

  }

  extension (duration: JavaDuration) def asScala: FiniteDuration = FiniteDuration(duration.toNanos, NANOSECONDS)

  extension (bytes: Array[Byte]) {
    def zip(): Array[Byte] = {
      val outBts = new ByteArrayOutputStream(bytes.size)
      val outZip = new ZipOutputStream(outBts)
      outZip.setLevel(9)
      try {
        outZip.putNextEntry(new ZipEntry("data.dat"))
        outZip.write(bytes)
        outZip.closeEntry()
        outBts.flush()
        outBts.toByteArray
      } catch {
        case NonFatal(ex) => throw new Exception(s"Zip error", ex)
      } finally {
        outZip.finish()
        outZip.close()
      }
    }

    def unzip(): Array[Byte] = {
      val buffer = new Array[Byte](1024 * 1)
      val in = new ZipInputStream(new ByteArrayInputStream(bytes))
      val out = new ByteArrayOutputStream

      @tailrec def readWriteChunk(): Unit =
        in.read(buffer) match {
          case -1 => ()
          case n =>
            out.write(buffer, 0, n)
            readWriteChunk()
        }

      in.getNextEntry()
      try readWriteChunk()
      finally in.close()
      out.toByteArray()
    }

    def gzip(): Array[Byte] = {
      val bos = new ByteArrayOutputStream(bytes.size)
      val gzip = new GZIPOutputStream(bos)
      try gzip.write(bytes)
      catch { case NonFatal(ex) => throw new Exception(s"GZip error", ex) }
      finally gzip.close()
      bos.toByteArray()
    }

    def unGzip(): Array[Byte] = {
      val buffer = new Array[Byte](1024 * 1)
      val out = new ByteArrayOutputStream()
      val in = new GZIPInputStream(new ByteArrayInputStream(bytes))

      @tailrec def readChunk(): Unit =
        in.read(buffer) match {
          case -1 => ()
          case n =>
            out.write(buffer, 0, n)
            readChunk()
        }

      try readChunk()
      catch { case NonFatal(ex) => throw new Exception("unGzip error", ex) }
      finally in.close()
      out.toByteArray()
    }
  }
}
