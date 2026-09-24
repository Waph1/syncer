package io.github.waph1.syncer.format

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown for unreadable files or a wrong database password. */
class KdbxException(message: String) : Exception(message)

/**
 * KeePass database files, format KDBX 4.0 (https://keepass.info/help/kb/kdbx_4.html):
 * AES-256-CBC encryption, Argon2id key derivation, GZip compression, HMAC-SHA-256 block stream
 * and ChaCha20 protection of in-XML secrets. Readable by KeePass, KeePassXC, KeePassDX, etc.
 */
object Kdbx {
    /** Argon2id cost: 64 MiB, 2 passes, 2 lanes (KeePassXC-like; a few seconds on a phone). */
    data class KdfParams(val memoryBytes: Long = 64L * 1024 * 1024, val iterations: Long = 2, val parallelism: Int = 2)

    /** XORs protected values with the inner ChaCha20 stream, in document order. */
    fun interface Protector {
        fun protect(plain: ByteArray): ByteArray
    }

    private const val SIGNATURE_1 = 0x9AA2D903.toInt()
    private const val SIGNATURE_2 = 0xB54BFB67.toInt()
    private const val VERSION_4_0 = 0x00040000
    private const val BLOCK_SIZE = 1024 * 1024

    private val CIPHER_AES256 = hex("31c1f2e6bf714350be5805216afc5aff")
    private val KDF_ARGON2D = hex("ef636ddf8c29444b91f7a9a403e30a0c")
    private val KDF_ARGON2ID = hex("9e298b1956db4773b23dfc3ec6f0a1e6")

    private const val FIELD_END = 0
    private const val FIELD_CIPHER_ID = 2
    private const val FIELD_COMPRESSION = 3
    private const val FIELD_MASTER_SEED = 4
    private const val FIELD_ENCRYPTION_IV = 7
    private const val FIELD_KDF_PARAMETERS = 11

    private const val INNER_END = 0
    private const val INNER_STREAM_ID = 1
    private const val INNER_STREAM_KEY = 2
    private const val INNER_STREAM_CHACHA20 = 3

    /**
     * Creates a database. [xml] receives a [Protector] and must return the KeePass XML document,
     * protecting secrets through it in the order they appear in the document.
     */
    fun create(password: String, params: KdfParams = KdfParams(), random: SecureRandom = SecureRandom(), xml: (Protector) -> String): ByteArray {
        val streamKey = ByteArray(64).also(random::nextBytes)
        val cipher = innerStream(streamKey)
        val document = xml { plain -> ByteArray(plain.size).also { cipher.processBytes(plain, 0, plain.size, it, 0) } }

        val inner = ByteArrayOutputStream()
        writeField(inner, INNER_STREAM_ID, le32(INNER_STREAM_CHACHA20))
        writeField(inner, INNER_STREAM_KEY, streamKey)
        writeField(inner, INNER_END, ByteArray(0))
        inner.write(document.toByteArray(Charsets.UTF_8))
        return encrypt(gzip(inner.toByteArray()), password, params, random)
    }

    /** Decrypts a database written by [create] and returns its XML (protected values stay protected). */
    fun readXml(file: ByteArray, password: String): String {
        val inner = gunzip(decryptPayload(file, password))
        val buffer = ByteBuffer.wrap(inner).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            val type = buffer.get().toInt()
            val size = buffer.int
            buffer.position(buffer.position() + size)
            if (type == INNER_END) break
        }
        return String(inner, buffer.position(), inner.size - buffer.position(), Charsets.UTF_8)
    }

    /** Re-encrypts [file] under a new password (fresh salt, seed and IV), keeping its content. */
    fun changePassword(file: ByteArray, oldPassword: String, newPassword: String, params: KdfParams = KdfParams(), random: SecureRandom = SecureRandom()): ByteArray =
        encrypt(decryptPayload(file, oldPassword), newPassword, params, random)

    // ---- outer layer --------------------------------------------------------------------

    private fun encrypt(payload: ByteArray, password: String, params: KdfParams, random: SecureRandom): ByteArray {
        val masterSeed = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(16).also(random::nextBytes)
        val salt = ByteArray(32).also(random::nextBytes)

        val kdf = VariantDictionary()
            .bytes("\$UUID", KDF_ARGON2ID)
            .bytes("S", salt)
            .uint32("P", params.parallelism)
            .uint64("M", params.memoryBytes)
            .uint64("I", params.iterations)
            .uint32("V", Argon2Parameters.ARGON2_VERSION_13)

        val header = ByteArrayOutputStream()
        header.write(le32(SIGNATURE_1))
        header.write(le32(SIGNATURE_2))
        header.write(le32(VERSION_4_0))
        writeField(header, FIELD_CIPHER_ID, CIPHER_AES256)
        writeField(header, FIELD_COMPRESSION, le32(1)) // GZip
        writeField(header, FIELD_MASTER_SEED, masterSeed)
        writeField(header, FIELD_ENCRYPTION_IV, iv)
        writeField(header, FIELD_KDF_PARAMETERS, kdf.toBytes())
        writeField(header, FIELD_END, byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A))
        val headerBytes = header.toByteArray()

        val transformed = argon2(Argon2Parameters.ARGON2_id, compositeKey(password), salt, params.iterations, params.memoryBytes, params.parallelism)
        val keys = Keys(masterSeed, transformed)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), IvParameterSpec(iv))
        }
        val encrypted = cipher.doFinal(payload)

        val out = ByteArrayOutputStream(headerBytes.size + encrypted.size + 1024)
        out.write(headerBytes)
        out.write(sha256(headerBytes))
        out.write(hmac(keys.blockKey(-1L), headerBytes))
        var index = 0L
        var offset = 0
        while (true) {
            val size = minOf(BLOCK_SIZE, encrypted.size - offset)
            val block = encrypted.copyOfRange(offset, offset + size)
            out.write(hmac(keys.blockKey(index), le64(index) + le32(size) + block))
            out.write(le32(size))
            out.write(block)
            if (size == 0) break
            offset += size
            index++
        }
        return out.toByteArray()
    }

    private fun decryptPayload(file: ByteArray, password: String): ByteArray {
        val buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        if (file.size < 12 || buffer.int != SIGNATURE_1 || buffer.int != SIGNATURE_2) throw KdbxException("Non è un database KeePass")
        val version = buffer.int
        if (version ushr 16 != 4) throw KdbxException("Versione del database non supportata")

        var cipherId: ByteArray? = null
        var compressed = false
        var masterSeed: ByteArray? = null
        var iv: ByteArray? = null
        var kdf: Map<String, Any>? = null
        while (true) {
            val type = buffer.get().toInt()
            val data = ByteArray(buffer.int).also(buffer::get)
            when (type) {
                FIELD_END -> break
                FIELD_CIPHER_ID -> cipherId = data
                FIELD_COMPRESSION -> compressed = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int == 1
                FIELD_MASTER_SEED -> masterSeed = data
                FIELD_ENCRYPTION_IV -> iv = data
                FIELD_KDF_PARAMETERS -> kdf = VariantDictionary.parse(data)
            }
        }
        val headerBytes = file.copyOfRange(0, buffer.position())
        if (!cipherId.contentEquals(CIPHER_AES256)) throw KdbxException("Cifratura del database non supportata")
        if (masterSeed == null || iv == null || kdf == null) throw KdbxException("Intestazione del database incompleta")

        val kdfId = kdf["\$UUID"] as? ByteArray
        val variant = when {
            kdfId.contentEquals(KDF_ARGON2ID) -> Argon2Parameters.ARGON2_id
            kdfId.contentEquals(KDF_ARGON2D) -> Argon2Parameters.ARGON2_d
            else -> throw KdbxException("Derivazione della chiave non supportata")
        }
        val transformed = argon2(
            variant, compositeKey(password), kdf["S"] as ByteArray,
            (kdf["I"] as Number).toLong(), (kdf["M"] as Number).toLong(), (kdf["P"] as Number).toInt(),
        )
        val keys = Keys(masterSeed, transformed)

        val storedHash = ByteArray(32).also(buffer::get)
        if (!MessageDigest.isEqual(storedHash, sha256(headerBytes))) throw KdbxException("Database danneggiato")
        val storedHmac = ByteArray(32).also(buffer::get)
        if (!MessageDigest.isEqual(storedHmac, hmac(keys.blockKey(-1L), headerBytes))) throw KdbxException("Password del database errata")

        val encrypted = ByteArrayOutputStream()
        var index = 0L
        while (true) {
            val blockHmac = ByteArray(32).also(buffer::get)
            val size = buffer.int
            val block = ByteArray(size).also(buffer::get)
            if (!MessageDigest.isEqual(blockHmac, hmac(keys.blockKey(index), le64(index) + le32(size) + block))) {
                throw KdbxException("Database danneggiato (blocco $index)")
            }
            if (size == 0) break
            encrypted.write(block)
            index++
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), IvParameterSpec(iv))
        }
        val payload = cipher.doFinal(encrypted.toByteArray())
        return if (compressed) payload else gzip(payload)
    }

    private class Keys(masterSeed: ByteArray, transformed: ByteArray) {
        val encryption: ByteArray = sha256(masterSeed + transformed)
        private val hmacBase: ByteArray = MessageDigest.getInstance("SHA-512").digest(masterSeed + transformed + byteArrayOf(1))

        /** Per-block HMAC key; the header uses index 2^64-1 (i.e. -1L). */
        fun blockKey(index: Long): ByteArray = MessageDigest.getInstance("SHA-512").digest(le64(index) + hmacBase)
    }

    // ---- primitives ---------------------------------------------------------------------

    private fun compositeKey(password: String): ByteArray = sha256(sha256(password.toByteArray(Charsets.UTF_8)))

    private fun argon2(variant: Int, key: ByteArray, salt: ByteArray, iterations: Long, memoryBytes: Long, parallelism: Int): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(variant)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withIterations(iterations.toInt())
                .withMemoryAsKB((memoryBytes / 1024).toInt())
                .withParallelism(parallelism)
                .build(),
        )
        return ByteArray(32).also { generator.generateBytes(key, it) }
    }

    private fun innerStream(streamKey: ByteArray): ChaCha7539Engine {
        val hash = MessageDigest.getInstance("SHA-512").digest(streamKey)
        return ChaCha7539Engine().apply { init(true, ParametersWithIV(KeyParameter(hash.copyOfRange(0, 32)), hash.copyOfRange(32, 44))) }
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    private fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }

    private fun writeField(out: ByteArrayOutputStream, type: Int, data: ByteArray) {
        out.write(type)
        out.write(le32(data.size))
        out.write(data)
    }

    internal fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    internal fun le64(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** KeePass "VariantDictionary" (typed key/value map) used for the KDF parameters. */
    private class VariantDictionary {
        private val out = ByteArrayOutputStream().apply { write(byteArrayOf(0x00, 0x01)) } // version 1.0

        fun uint32(name: String, value: Int) = item(0x04, name, le32(value))
        fun uint64(name: String, value: Long) = item(0x05, name, le64(value))
        fun bytes(name: String, value: ByteArray) = item(0x42, name, value)

        private fun item(type: Int, name: String, value: ByteArray): VariantDictionary {
            val key = name.toByteArray(Charsets.UTF_8)
            out.write(type)
            out.write(le32(key.size))
            out.write(key)
            out.write(le32(value.size))
            out.write(value)
            return this
        }

        fun toBytes(): ByteArray = out.toByteArray() + byteArrayOf(0)

        companion object {
            fun parse(data: ByteArray): Map<String, Any> {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                buffer.short // version
                val result = mutableMapOf<String, Any>()
                while (buffer.hasRemaining()) {
                    val type = buffer.get().toInt() and 0xFF
                    if (type == 0) break
                    val name = String(ByteArray(buffer.int).also(buffer::get), Charsets.UTF_8)
                    val value = ByteArray(buffer.int).also(buffer::get)
                    val wrapped = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                    result[name] = when (type) {
                        0x04, 0x0C -> wrapped.int.toLong() and (if (type == 0x04) 0xFFFFFFFFL else -1L)
                        0x05, 0x0D -> wrapped.long
                        0x08 -> value.firstOrNull()?.toInt() != 0
                        0x18 -> String(value, Charsets.UTF_8)
                        else -> value
                    }
                }
                return result
            }
        }
    }
}
