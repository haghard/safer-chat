// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package shared

import java.io.*
import java.nio.charset.*
import java.security.*
import javax.crypto.Cipher
import scala.util.Using

object rsa {

  // https://stackoverflow.com/questions/71268249/how-to-do-rsa-encryption-and-decryption-on-a-large-data-in-java/71268250#71268250
  extension (cipher: Cipher) {

    transparent inline def encrypt(msg: String, pubKey: PublicKey): Array[Byte] = {
      cipher.init(Cipher.ENCRYPT_MODE, pubKey)
      Using.resource(new ByteArrayOutputStream()) { out =>
        val blockSize = cipher.getBlockSize()
        val payload = msg.getBytes(StandardCharsets.UTF_8)
        val payloadLength = payload.length

        var offset = 0
        var end = 0
        while end < payloadLength do {
          end = end + blockSize
          if end > payloadLength then {
            end = payloadLength
          }

          val len = end - offset
          val chunk = cipher.doFinal(payload, offset, len)
          out.write(chunk)
          offset = end
        }
        out.toByteArray()
      }
    }

    transparent inline def decrypt(bts: Array[Byte], privateKey: PrivateKey): String = {
      cipher.init(Cipher.DECRYPT_MODE, privateKey)
      val payloadLength = bts.length
      Using.resource(new StringWriter(payloadLength)) { wrt =>
        val blockSize = cipher.getBlockSize()

        var offset = 0
        var end = 0
        while offset < payloadLength do {
          end = end + blockSize
          if end > payloadLength then end = payloadLength
          val len = end - offset
          val chunk = cipher.doFinal(bts, offset, len)
          wrt.write(new String(chunk, StandardCharsets.UTF_8))
          offset = end
        }
        wrt.toString()
      }
    }

  }
}
