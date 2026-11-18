package com.messenger.app.data.attachment

import java.io.File
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Exercises the REAL platform AES-256-GCM implementation (JCA, not a fake)
 * against real files on disk - no Android framework needed, so these run
 * as plain fast JVM unit tests (same rationale as SignalCryptoTest.kt for
 * the Signal Protocol layer: prove the crypto core in isolation here, the
 * full pipeline is proven by the 2-emulator E2E).
 */
class AttachmentCryptoTest {

    private fun tempFile(prefix: String): File = File.createTempFile(prefix, ".bin").apply { deleteOnExit() }

    private fun writeRandomBytes(file: File, size: Int): ByteArray {
        val bytes = ByteArray(size)
        java.security.SecureRandom().nextBytes(bytes)
        file.writeBytes(bytes)
        return bytes
    }

    @Test
    fun `legacy multi megabyte decrypt can be cancelled before publication`() {
        val input = tempFile("largeplain")
        writeRandomBytes(input, 3 * 1024 * 1024)
        val cipher = tempFile("largecipher")
        val output = tempFile("largeout").apply { delete() }
        val key = AttachmentCrypto.generateKeyMaterial()
        val hash = AttachmentCrypto.encryptFile(input, cipher, key)
        var chunks = 0
        kotlin.test.assertFailsWith<AttachmentCancelledException> {
            AttachmentCrypto.decryptFile(cipher, output, key, hash,
                onBytesProcessed = { _, _ -> chunks++ }, isCancelled = { chunks >= 1 })
        }
        assertEquals(1, chunks)
        kotlin.test.assertFalse(output.exists())
        kotlin.test.assertFalse(File(output.parentFile, output.name + ".tmp").exists())
        AttachmentCrypto.decryptFile(cipher, output, key, hash)
        assertTrue(input.readBytes().contentEquals(output.readBytes()))
    }

    @Test
    fun `round trip encrypt then decrypt yields the original plaintext`() {
        val plaintextIn = tempFile("plain")
        val original = writeRandomBytes(plaintextIn, 10_000)
        val ciphertext = tempFile("cipher")
        val plaintextOut = tempFile("decrypted")

        val keyMaterial = AttachmentCrypto.generateKeyMaterial()
        val sha256 = AttachmentCrypto.encryptFile(plaintextIn, ciphertext, keyMaterial)
        AttachmentCrypto.decryptFile(ciphertext, plaintextOut, keyMaterial, sha256)

        assertTrue(original.contentEquals(plaintextOut.readBytes()))
    }

    @Test
    fun `each call generates a unique key and nonce`() {
        val seen = mutableSetOf<String>()
        repeat(20) {
            val km = AttachmentCrypto.generateKeyMaterial()
            assertEquals(32, km.key.size)
            assertEquals(12, km.nonce.size)
            seen += java.util.Base64.getEncoder().encodeToString(km.key) + java.util.Base64.getEncoder().encodeToString(km.nonce)
        }
        assertEquals(20, seen.size, "every generated key+nonce pair must be unique")
    }

    @Test
    fun `ciphertext is not the plaintext bytes`() {
        val plaintextIn = tempFile("plain")
        val original = writeRandomBytes(plaintextIn, 5_000)
        val ciphertext = tempFile("cipher")
        AttachmentCrypto.encryptFile(plaintextIn, ciphertext, AttachmentCrypto.generateKeyMaterial())
        assertNotEquals(original.toList(), ciphertext.readBytes().toList())
    }

    @Test
    fun `tampered ciphertext byte fails the SHA-256 check before decrypt is ever attempted`() {
        val plaintextIn = tempFile("plain")
        writeRandomBytes(plaintextIn, 2_000)
        val ciphertext = tempFile("cipher")
        val plaintextOut = tempFile("decrypted")
        val keyMaterial = AttachmentCrypto.generateKeyMaterial()
        val sha256 = AttachmentCrypto.encryptFile(plaintextIn, ciphertext, keyMaterial)

        val bytes = ciphertext.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        ciphertext.writeBytes(bytes)

        assertFailsWith<AttachmentIntegrityException> {
            AttachmentCrypto.decryptFile(ciphertext, plaintextOut, keyMaterial, sha256)
        }
        assertTrue(!plaintextOut.exists() || plaintextOut.length() == 0L, "a failed integrity check must never leave a usable plaintext file")
    }

    @Test
    fun `a hash that matches a tampered blob but fails AEAD still never produces plaintext`() {
        // Simulates an attacker who also recomputed the (now-matching) sha256
        // for their tampered blob - the independent hash check alone isn't
        // the only guard, the AEAD tag itself must also verify.
        val plaintextIn = tempFile("plain")
        writeRandomBytes(plaintextIn, 2_000)
        val ciphertext = tempFile("cipher")
        val plaintextOut = tempFile("decrypted")
        val keyMaterial = AttachmentCrypto.generateKeyMaterial()
        AttachmentCrypto.encryptFile(plaintextIn, ciphertext, keyMaterial)

        val bytes = ciphertext.readBytes()
        bytes[0] = (bytes[0] + 1).toByte()
        ciphertext.writeBytes(bytes)
        val recomputedHashOfTamperedBlob = java.security.MessageDigest.getInstance("SHA-256").digest(ciphertext.readBytes())

        assertFailsWith<AEADBadTagException> {
            AttachmentCrypto.decryptFile(ciphertext, plaintextOut, keyMaterial, recomputedHashOfTamperedBlob)
        }
    }

    @Test
    fun `wrong key fails to decrypt`() {
        val plaintextIn = tempFile("plain")
        writeRandomBytes(plaintextIn, 2_000)
        val ciphertext = tempFile("cipher")
        val plaintextOut = tempFile("decrypted")
        val keyMaterial = AttachmentCrypto.generateKeyMaterial()
        val sha256 = AttachmentCrypto.encryptFile(plaintextIn, ciphertext, keyMaterial)

        val wrongKey = keyMaterial.copy(key = AttachmentCrypto.generateKeyMaterial().key)
        assertFailsWith<AEADBadTagException> {
            AttachmentCrypto.decryptFile(ciphertext, plaintextOut, wrongKey, sha256)
        }
    }

    @Test
    fun `wrong nonce fails to decrypt`() {
        val plaintextIn = tempFile("plain")
        writeRandomBytes(plaintextIn, 2_000)
        val ciphertext = tempFile("cipher")
        val plaintextOut = tempFile("decrypted")
        val keyMaterial = AttachmentCrypto.generateKeyMaterial()
        val sha256 = AttachmentCrypto.encryptFile(plaintextIn, ciphertext, keyMaterial)

        val wrongNonce = keyMaterial.copy(nonce = AttachmentCrypto.generateKeyMaterial().nonce)
        assertFailsWith<AEADBadTagException> {
            AttachmentCrypto.decryptFile(ciphertext, plaintextOut, wrongNonce, sha256)
        }
    }

    @Test
    fun `sha256 mismatch is rejected even with the correct key and nonce`() {
        val plaintextIn = tempFile("plain")
        writeRandomBytes(plaintextIn, 2_000)
        val ciphertext = tempFile("cipher")
        val plaintextOut = tempFile("decrypted")
        val keyMaterial = AttachmentCrypto.generateKeyMaterial()
        AttachmentCrypto.encryptFile(plaintextIn, ciphertext, keyMaterial)

        val wrongHash = ByteArray(32) { 0 }
        assertFailsWith<AttachmentIntegrityException> {
            AttachmentCrypto.decryptFile(ciphertext, plaintextOut, keyMaterial, wrongHash)
        }
    }

    @Test
    fun `a larger file round trips correctly via bounded-memory streaming`() {
        // 8MB - large enough to exercise many STREAM_BUFFER_SIZE (64KB)
        // iterations; correctness here plus the fixed-size buffer used
        // internally (never a whole-file ByteArray) is what keeps a real
        // 100MB attachment's memory usage bounded.
        val plaintextIn = tempFile("plain")
        val original = writeRandomBytes(plaintextIn, 8 * 1024 * 1024)
        val ciphertext = tempFile("cipher")
        val plaintextOut = tempFile("decrypted")

        val keyMaterial = AttachmentCrypto.generateKeyMaterial()
        val sha256 = AttachmentCrypto.encryptFile(plaintextIn, ciphertext, keyMaterial)
        AttachmentCrypto.decryptFile(ciphertext, plaintextOut, keyMaterial, sha256)

        assertTrue(original.contentEquals(plaintextOut.readBytes()))
        // Ciphertext = plaintext + GCM tag (16 bytes) - confirms nothing was
        // buffered/truncated/duplicated across the many streaming chunks.
        assertEquals(original.size + 16, ciphertext.length().toInt())
    }
}
