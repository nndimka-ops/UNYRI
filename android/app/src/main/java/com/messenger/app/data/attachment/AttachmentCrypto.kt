package com.messenger.app.data.attachment

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A fresh, random per-attachment secret - NEVER reused across attachments,
 *  NEVER sent to the backend (only inside the Signal-encrypted message
 *  content the recipient's own device decrypts - see [MessageContentCodec]). */
data class AttachmentKeyMaterial(val key: ByteArray, val nonce: ByteArray)

class AttachmentIntegrityException(message: String) : Exception(message)

/**
 * Standard AEAD (AES-256-GCM via the platform JCA provider - never
 * homemade crypto) for bulk attachment blobs. Everything here is
 * file-to-file processing. Legacy GCM providers may buffer plaintext until
 * authentication; new v2 attachments use bounded-memory Streaming AEAD.
 */
object AttachmentCrypto {
    private const val KEY_SIZE_BYTES = 32
    private const val NONCE_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val STREAM_BUFFER_SIZE = 64 * 1024

    fun generateKeyMaterial(): AttachmentKeyMaterial {
        val random = SecureRandom()
        return AttachmentKeyMaterial(
            key = ByteArray(KEY_SIZE_BYTES).also(random::nextBytes),
            nonce = ByteArray(NONCE_SIZE_BYTES).also(random::nextBytes),
        )
    }

    /** Streams [plaintextFile] -> AES-256-GCM -> [ciphertextFile]. Returns
     *  the SHA-256 digest of the ciphertext (tag included) - an
     *  independent integrity check carried alongside the AEAD tag itself
     *  inside the Signal-encrypted attachment metadata (Этап H п.11). */
    fun encryptFile(plaintextFile: File, ciphertextFile: File, keyMaterial: AttachmentKeyMaterial): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyMaterial.key, "AES"), GCMParameterSpec(GCM_TAG_BITS, keyMaterial.nonce))
        FileInputStream(plaintextFile).use { plainIn ->
            FileOutputStream(ciphertextFile).use { cipherFileOut ->
                CipherOutputStream(cipherFileOut, cipher).use { cipherOut ->
                    val buffer = ByteArray(STREAM_BUFFER_SIZE)
                    while (true) {
                        val read = plainIn.read(buffer)
                        if (read < 0) break
                        cipherOut.write(buffer, 0, read)
                    }
                }
            }
        }
        // Hash the real ciphertext bytes that landed on disk (tag included) -
        // a second bounded-memory pass, not the plaintext.
        return sha256File(ciphertextFile)
    }

    /** Verifies [expectedCiphertextSha256] against the REAL bytes on disk
     *  BEFORE ever attempting to decrypt (Этап H п.11 - never show a file
     *  as valid unless both the independent hash AND the AEAD tag check
     *  out). Throws [AttachmentIntegrityException] on hash mismatch;
     *  decrypt itself throws `javax.crypto.AEADBadTagException` on AEAD
     *  failure - callers map both to a DOWNLOAD_CORRUPT state, never a
     *  crash and never a partially-written plaintext file left behind. */
    fun decryptFile(
        ciphertextFile: File,
        plaintextFile: File,
        keyMaterial: AttachmentKeyMaterial,
        expectedCiphertextSha256: ByteArray,
        onBytesProcessed: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val actual = sha256File(ciphertextFile)
        if (!actual.contentEquals(expectedCiphertextSha256)) {
            throw AttachmentIntegrityException("Ciphertext SHA-256 mismatch - the downloaded file is corrupt or tampered.")
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyMaterial.key, "AES"), GCMParameterSpec(GCM_TAG_BITS, keyMaterial.nonce))
        val tempOut = File(plaintextFile.parentFile, "${plaintextFile.name}.tmp")
        val totalSize = ciphertextFile.length()
        var processed = 0L
        try {
            FileInputStream(ciphertextFile).use { cipherIn ->
                FileOutputStream(tempOut).use { plainOut ->
                    // CipherInputStream feeds only tiny chunks to some Android
                    // GCM providers, repeatedly copying their authentication buffer.
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (isCancelled()) throw AttachmentCancelledException()
                        val read = cipherIn.read(buffer)
                        if (read < 0) break
                        cipher.update(buffer, 0, read)?.let { plainOut.write(it) }
                        processed += read
                        onBytesProcessed(processed, totalSize)
                    }
                    if (isCancelled()) throw AttachmentCancelledException()
                    plainOut.write(cipher.doFinal()) // authenticate before publication
                }
            }
        } catch (e: Exception) {
            tempOut.delete()
            throw e
        }
        // Only now (both the hash AND the AEAD tag verified) does the
        // caller-visible plaintext file come into existence - a partially
        // decrypted/corrupt file is never left where a caller might read it.
        if (!tempOut.renameTo(plaintextFile)) {
            tempOut.copyTo(plaintextFile, overwrite = true)
            tempOut.delete()
        }
    }

    private fun sha256File(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}
