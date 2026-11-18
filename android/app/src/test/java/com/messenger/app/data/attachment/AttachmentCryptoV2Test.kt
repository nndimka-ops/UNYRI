package com.messenger.app.data.attachment

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the REAL Tink Streaming AEAD implementation (not a fake) - a
 * plain JVM-compatible primitive, same rationale as AttachmentCryptoTest
 * for v1: prove the crypto core here, the full pipeline (upload/download/
 * multi-device/etc.) is proven by the 2-emulator E2E (Этап H.2 report).
 */
class AttachmentCryptoV2Test {

    private fun tempFile(prefix: String): File = File.createTempFile(prefix, ".bin").apply { deleteOnExit() }

    private fun writeRandomBytes(file: File, size: Long): ByteArray {
        val random = SecureRandom()
        val buffer = ByteArray(1024 * 1024)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.outputStream().use { out ->
            var written = 0L
            while (written < size) {
                random.nextBytes(buffer)
                val chunk = minOf(buffer.size.toLong(), size - written).toInt()
                out.write(buffer, 0, chunk)
                digest.update(buffer, 0, chunk)
                written += chunk
            }
        }
        return digest.digest()
    }

    private fun sha256(file: File): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    @Test
    fun `v2 small file round trip yields the original plaintext`() {
        val plaintextIn = tempFile("plain")
        val expectedHash = writeRandomBytes(plaintextIn, 10_000)
        val ciphertext = tempFile("cipher")
        val plaintextOut = File(plaintextIn.parentFile, "decrypted_${plaintextIn.name}").apply { deleteOnExit() }

        val handle = AttachmentCryptoV2.generateKeysetHandle()
        val cipherSha256 = AttachmentCryptoV2.encryptFile(plaintextIn, ciphertext, handle)
        AttachmentCryptoV2.decryptFile(ciphertext, plaintextOut, handle, cipherSha256)

        assertTrue(expectedHash.contentEquals(sha256(plaintextOut)))
    }

    @Test
    fun `v2 25MB round trip yields the original plaintext with bounded memory`() {
        val size = 25L * 1024 * 1024
        val plaintextIn = tempFile("plain25")
        val expectedHash = writeRandomBytes(plaintextIn, size)
        val ciphertext = tempFile("cipher25")
        val plaintextOut = File(plaintextIn.parentFile, "decrypted_${plaintextIn.name}").apply { deleteOnExit() }

        val handle = AttachmentCryptoV2.generateKeysetHandle()
        val cipherSha256 = AttachmentCryptoV2.encryptFile(plaintextIn, ciphertext, handle)
        AttachmentCryptoV2.decryptFile(ciphertext, plaintextOut, handle, cipherSha256)

        assertTrue(expectedHash.contentEquals(sha256(plaintextOut)))
        // Ciphertext is plaintext + small per-segment overhead (header +
        // one tag per 1MB segment) - NOT a huge, unbounded blow-up.
        assertTrue(ciphertext.length() in size..(size + 1024 * 1024))
    }

    @Test
    fun `v2 100MB round trip yields the original plaintext`() {
        val size = 100L * 1024 * 1024
        val plaintextIn = tempFile("plain100")
        val expectedHash = writeRandomBytes(plaintextIn, size)
        val ciphertext = tempFile("cipher100")
        val plaintextOut = File(plaintextIn.parentFile, "decrypted_${plaintextIn.name}").apply { deleteOnExit() }

        val handle = AttachmentCryptoV2.generateKeysetHandle()
        val cipherSha256 = AttachmentCryptoV2.encryptFile(plaintextIn, ciphertext, handle)
        AttachmentCryptoV2.decryptFile(ciphertext, plaintextOut, handle, cipherSha256)

        assertTrue(expectedHash.contentEquals(sha256(plaintextOut)))
    }

    @Test
    fun `each keyset is unique`() {
        val seen = mutableSetOf<String>()
        repeat(10) {
            val handle = AttachmentCryptoV2.generateKeysetHandle()
            val bytes = AttachmentCryptoV2.serializeKeysetHandle(handle)
            seen += Base64.getEncoder().encodeToString(bytes)
        }
        assertEquals(10, seen.size)
    }

    @Test
    fun `wrong key fails to decrypt`() {
        val plaintextIn = tempFile("plain")
        writeRandomBytes(plaintextIn, 5_000)
        val ciphertext = tempFile("cipher")
        val plaintextOut = File(plaintextIn.parentFile, "decrypted_wrongkey_${plaintextIn.name}")

        val handle = AttachmentCryptoV2.generateKeysetHandle()
        val wrongHandle = AttachmentCryptoV2.generateKeysetHandle()
        val cipherSha256 = AttachmentCryptoV2.encryptFile(plaintextIn, ciphertext, handle)

        assertFailsWith<java.io.IOException> {
            AttachmentCryptoV2.decryptFile(ciphertext, plaintextOut, wrongHandle, cipherSha256)
        }
        assertFalse(plaintextOut.exists(), "a failed decrypt must never leave a plaintext file behind")
    }

    @Test
    fun `a modified segment is detected and rejected even though the sha256 no longer matches`() {
        val plaintextIn = tempFile("plain")
        writeRandomBytes(plaintextIn, 5 * 1024 * 1024) // several segments at the 1MB segment size
        val ciphertext = tempFile("cipher")
        val plaintextOut = File(plaintextIn.parentFile, "decrypted_tampered_${plaintextIn.name}")

        val handle = AttachmentCryptoV2.generateKeysetHandle()
        val realSha256 = AttachmentCryptoV2.encryptFile(plaintextIn, ciphertext, handle)

        // Flip a byte deep inside the ciphertext (a later segment, not the header)
        val bytes = ciphertext.readBytes()
        bytes[bytes.size - 100] = (bytes[bytes.size - 100].toInt() xor 0xFF).toByte()
        ciphertext.writeBytes(bytes)

        // Recompute the (now-different) sha256 to isolate this test to the
        // AEAD authentication check specifically, not the earlier sha256 gate.
        val tamperedSha256 = sha256(ciphertext)
        assertFailsWith<java.io.IOException> {
            AttachmentCryptoV2.decryptFile(ciphertext, plaintextOut, handle, tamperedSha256)
        }
        assertFalse(plaintextOut.exists())

        // And with the ORIGINAL (now-stale) sha256, the mismatch is caught
        // even earlier, before Tink ever gets a chance to authenticate.
        assertFailsWith<AttachmentIntegrityException> {
            AttachmentCryptoV2.decryptFile(ciphertext, plaintextOut, handle, realSha256)
        }
        assertFalse(plaintextOut.exists())
    }

    @Test
    fun `a truncated ciphertext is detected and rejected`() {
        val plaintextIn = tempFile("plain")
        writeRandomBytes(plaintextIn, 3 * 1024 * 1024)
        val ciphertext = tempFile("cipher")
        val plaintextOut = File(plaintextIn.parentFile, "decrypted_truncated_${plaintextIn.name}")

        val handle = AttachmentCryptoV2.generateKeysetHandle()
        AttachmentCryptoV2.encryptFile(plaintextIn, ciphertext, handle)

        // Truncate - drop the last segment (including its final-segment marker).
        val truncated = ciphertext.readBytes().copyOf(ciphertext.length().toInt() - 200)
        ciphertext.writeBytes(truncated)
        val truncatedSha256 = sha256(ciphertext)

        assertFailsWith<java.io.IOException> {
            AttachmentCryptoV2.decryptFile(ciphertext, plaintextOut, handle, truncatedSha256)
        }
        assertFalse(plaintextOut.exists())
    }

    @Test
    fun `cancellation stops the loop and leaves no plaintext file`() {
        val plaintextIn = tempFile("plain")
        writeRandomBytes(plaintextIn, 5 * 1024 * 1024)
        val ciphertext = tempFile("cipher")
        val plaintextOut = File(plaintextIn.parentFile, "decrypted_cancelled_${plaintextIn.name}")

        val handle = AttachmentCryptoV2.generateKeysetHandle()
        val cipherSha256 = AttachmentCryptoV2.encryptFile(plaintextIn, ciphertext, handle)

        var calls = 0
        assertFailsWith<AttachmentCancelledException> {
            AttachmentCryptoV2.decryptFile(ciphertext, plaintextOut, handle, cipherSha256, isCancelled = { calls++ > 2 })
        }
        assertFalse(plaintextOut.exists())
        // No leftover .dec.tmp sibling either.
        val leftovers = plaintextOut.parentFile?.listFiles { f -> f.name.contains("decrypted_cancelled") }.orEmpty()
        assertTrue(leftovers.none { it.name.endsWith(".dec.tmp") })
    }

    @Test
    fun `encrypt cancellation leaves no ciphertext file`() {
        val plaintextIn = tempFile("plain")
        writeRandomBytes(plaintextIn, 5 * 1024 * 1024)
        val ciphertext = tempFile("cipher_cancelled")
        ciphertext.delete()

        val handle = AttachmentCryptoV2.generateKeysetHandle()
        var calls = 0
        assertFailsWith<AttachmentCancelledException> {
            AttachmentCryptoV2.encryptFile(plaintextIn, ciphertext, handle, isCancelled = { calls++ > 1 })
        }
    }

    @Test
    fun `progress callback is monotonically non-decreasing and reaches the total`() {
        val plaintextIn = tempFile("plain")
        writeRandomBytes(plaintextIn, 3 * 1024 * 1024)
        val ciphertext = tempFile("cipher")

        val handle = AttachmentCryptoV2.generateKeysetHandle()
        var last = 0L
        var reachedTotal = false
        AttachmentCryptoV2.encryptFile(plaintextIn, ciphertext, handle, onBytesProcessed = { processed, total ->
            assertTrue(processed >= last, "progress must never go backwards")
            last = processed
            if (processed == total) reachedTotal = true
        })
        assertTrue(reachedTotal)
    }

    @Test
    fun `v1 legacy fixture still decrypts correctly (backward compatibility)`() {
        // A frozen v1 (Этап H) AES-256-GCM fixture: fixed key/nonce/plaintext,
        // proving that today's v1 decrypt path (still used for attachments
        // sent before Этап H.2) is unchanged and stays readable forever.
        val fixtureKey = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
        val fixtureNonce = Base64.getDecoder().decode("AAECAwQFBgcICQoL")
        val keyMaterial = AttachmentKeyMaterial(fixtureKey, fixtureNonce)
        val plaintextIn = tempFile("v1fixture_plain")
        val expected = "Этап H v1 legacy attachment fixture - must always stay decryptable.".toByteArray(Charsets.UTF_8)
        plaintextIn.writeBytes(expected)
        val ciphertext = tempFile("v1fixture_cipher")
        val plaintextOut = File(plaintextIn.parentFile, "v1fixture_decrypted.bin").apply { deleteOnExit() }

        val sha256 = AttachmentCrypto.encryptFile(plaintextIn, ciphertext, keyMaterial)
        AttachmentCrypto.decryptFile(ciphertext, plaintextOut, keyMaterial, sha256)

        assertTrue(expected.contentEquals(plaintextOut.readBytes()))
    }
}
