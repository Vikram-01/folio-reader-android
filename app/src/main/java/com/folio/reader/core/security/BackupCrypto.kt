package com.folio.reader.core.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : IOException("Wrong password or damaged backup.")

/**
 * Password-based encryption for backup files.
 *
 * Format: "FOLIOENC" | version(1) | iterations(int) | salt(16) | noncePrefix(8)
 * followed by chunks: length(int) | AES-256-GCM(ciphertext+tag).
 * Each chunk's 12-byte nonce is noncePrefix + chunk counter, and the AAD marks
 * the final chunk, so chunks cannot be reordered, dropped or truncated unnoticed.
 * Chunking keeps memory flat for multi-gigabyte backups.
 */
object BackupCrypto {
    private val MAGIC = "FOLIOENC".toByteArray()
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 210_000
    private const val CHUNK = 1 shl 20

    fun isEncrypted(header: ByteArray): Boolean =
        header.size >= MAGIC.size && MAGIC.indices.all { header[it] == MAGIC[it] }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(prefix: ByteArray, counter: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).array()

    private fun aad(counter: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(5).putInt(counter).put(if (last) 1 else 0).array()

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val prefix = ByteArray(8).also(rnd::nextBytes)
        val key = deriveKey(password, salt, ITERATIONS)
        val out = DataOutputStream(output)
        out.write(MAGIC); out.writeByte(VERSION.toInt()); out.writeInt(ITERATIONS); out.write(salt); out.write(prefix)

        var counter = 0
        var current = readChunk(input)
        while (true) {
            val next = if (current.size == CHUNK) readChunk(input) else ByteArray(0)
            val last = next.isEmpty()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter)))
            cipher.updateAAD(aad(counter, last))
            val sealed = cipher.doFinal(current)
            out.writeInt(sealed.size); out.write(sealed)
            if (last) break
            counter++
            current = next
        }
        out.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val inp = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also { inp.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("Not an encrypted ReadStash backup.")
        if (inp.readByte() != VERSION) throw IOException("Unsupported backup version.")
        val iterations = inp.readInt()
        if (iterations !in 10_000..5_000_000) throw IOException("Corrupt backup header.")
        val salt = ByteArray(16).also { inp.readFully(it) }
        val prefix = ByteArray(8).also { inp.readFully(it) }
        val key = deriveKey(password, salt, iterations)

        var counter = 0
        var pending: ByteArray = readSealed(inp) ?: throw WrongPasswordException()
        while (true) {
            val next = readSealed(inp)
            val last = next == null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter)))
            cipher.updateAAD(aad(counter, last))
            val plain = try { cipher.doFinal(pending) } catch (e: AEADBadTagException) { throw WrongPasswordException() }
            output.write(plain)
            if (last) break
            counter++
            pending = next!!
        }
        output.flush()
    }

    private fun readChunk(input: InputStream): ByteArray {
        val buf = ByteArray(CHUNK)
        var off = 0
        while (off < CHUNK) {
            val n = input.read(buf, off, CHUNK - off)
            if (n < 0) break
            off += n
        }
        return if (off == CHUNK) buf else buf.copyOf(off)
    }

    private fun readSealed(inp: DataInputStream): ByteArray? {
        val len = try { inp.readInt() } catch (e: EOFException) { return null }
        if (len < 16 || len > CHUNK + 16) throw IOException("Corrupt backup chunk.")
        return ByteArray(len).also { inp.readFully(it) }
    }
}
