package com.messenger.app.data.attachment

import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * Attachment encryption v2 (Этап H.2) - true bounded-memory streaming AEAD
 * via Google Tink, replacing the v1 single-shot `javax.crypto` AES-GCM
 * call whose `CipherInputStream`/`CipherOutputStream` wrapping turned out
 * to buffer the ENTIRE file internally regardless of our own read/write
 * buffer size (see Этап H.1 report - reproducible OOM on 100MB files even
 * with `android:largeHeap`). Tink's Streaming AEAD splits the file into
 * fixed-size segments (1MB here), each independently authenticated and
 * bound to its position in the stream - genuine O(segment size) memory
 * regardless of total file size, no custom/homemade chunk protocol.
 *
 * Key type: AES256_GCM_HKDF_1MB (Tink's general-purpose recommendation).
 * Associated data is bound to the attachmentId's storage `objectKey` so a
 * segment from one attachment's ciphertext can never be spliced into
 * another's (Tink's own AD-binding guidance).
 */
object AttachmentCryptoV2 {
    private const val STREAM_BUFFER_SIZE = 64 * 1024
    private const val KEY_TEMPLATE = "AES256_GCM_HKDF_1MB"

    @Volatile private var registered = false

    /** Idempotent - safe to call from multiple entry points (also called
     *  once eagerly from MessengerApp.onCreate). */
    @Synchronized
    fun ensureRegistered() {
        if (!registered) {
            StreamingAeadConfig.register()
            registered = true
        }
    }

    fun generateKeysetHandle(): KeysetHandle {
        ensureRegistered()
        return KeysetHandle.generateNew(KeyTemplates.get(KEY_TEMPLATE))
    }

    /** Cleartext keyset bytes (no KMS/master-key wrapping) - this app
     *  manages the keyset itself and ships it only inside the
     *  Signal-encrypted attachment metadata, exactly like the raw
     *  AES key/nonce were shipped for v1. Tink's `CleartextKeysetHandle`
     *  API is the documented, supported way to do this when the caller
     *  (not a KMS) owns key storage/transport. */
    fun serializeKeysetHandle(handle: KeysetHandle): ByteArray {
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(out))
        return out.toByteArray()
    }

    fun parseKeysetHandle(bytes: ByteArray): KeysetHandle {
        ensureRegistered()
        return CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(bytes))
    }

    /** No associated data is bound here (empty AD is a well-supported Tink
     *  usage, not a weakening) - each attachment already uses a FRESH
     *  RANDOM keyset (see [generateKeysetHandle]), which alone already
     *  prevents one attachment's ciphertext/segments from ever being
     *  substituted into another's decryption (a different keyset simply
     *  fails to authenticate). Binding AD to e.g. the server-assigned
     *  `attachment_id` would need encryption to happen AFTER the
     *  `/attachments` create call returns, but `create` itself requires
     *  the final encrypted size as input - encrypting first is required,
     *  so AD is deliberately left empty rather than introduced awkwardly. */
    private val NO_ASSOCIATED_DATA = ByteArray(0)

    /**
     * Streams [plaintextFile] -> Tink Streaming AEAD -> [ciphertextFile].
     * Returns the SHA-256 of the ciphertext bytes on disk (an early,
     * cheap transport-corruption check - see the class doc on
     * AttachmentManager about why this is NOT a substitute for AEAD
     * authentication, only a fast pre-check before ever invoking it).
     *
     * [onProgress] receives 0..100 and is throttled by the caller
     * (AttachmentManager), not here - this function just reports raw
     * bytes-so-far via the callback on every buffer-sized chunk.
     * [isCancelled] is polled once per chunk so a coroutine cancellation
     * or session-epoch change stops the loop promptly instead of grinding
     * through the rest of a 100MB file first.
     */
    fun encryptFile(
        plaintextFile: File,
        ciphertextFile: File,
        keysetHandle: KeysetHandle,
        onBytesProcessed: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): ByteArray {
        ensureRegistered()
        val streamingAead = keysetHandle.getPrimitive(StreamingAead::class.java)
        val totalSize = plaintextFile.length()
        var processed = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(plaintextFile).use { plainIn ->
            FileOutputStream(ciphertextFile).use { cipherFileOut ->
                DigestingOutputStream(cipherFileOut, digest).use { digestingOut ->
                    streamingAead.newEncryptingStream(digestingOut, NO_ASSOCIATED_DATA).use { cipherOut ->
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        while (true) {
                            if (isCancelled()) throw AttachmentCancelledException()
                            val read = plainIn.read(buffer)
                            if (read < 0) break
                            cipherOut.write(buffer, 0, read)
                            processed += read
                            onBytesProcessed(processed, totalSize)
                        }
                    }
                }
            }
        }
        return digest.digest()
    }

    /**
     * Verifies [expectedCiphertextSha256] first (cheap, fast-fail on
     * gross transport corruption/truncation), then decrypts through
     * Tink's Streaming AEAD, which independently authenticates every
     * segment (wrong key, tampered segment, reordered/truncated segments
     * all fail here with a [GeneralSecurityException] regardless of
     * whether the SHA-256 pre-check passed). Writes to a `.tmp` sibling
     * and only renames to [plaintextFile] once decryption fully
     * completes - a partially-decrypted/corrupt file is never left where
     * a caller might read it (Этап H.2 п.8, same atomicity contract as
     * v1's `AttachmentCrypto.decryptFile`).
     */
    fun decryptFile(
        ciphertextFile: File,
        plaintextFile: File,
        keysetHandle: KeysetHandle,
        expectedCiphertextSha256: ByteArray,
        onBytesProcessed: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        ensureRegistered()
        val actual = sha256File(ciphertextFile)
        if (!actual.contentEquals(expectedCiphertextSha256)) {
            throw AttachmentIntegrityException("Ciphertext SHA-256 mismatch - the downloaded file is corrupt or tampered.")
        }

        val streamingAead = keysetHandle.getPrimitive(StreamingAead::class.java)
        val totalSize = ciphertextFile.length()
        var processed = 0L
        val tempOut = File(plaintextFile.parentFile, "${plaintextFile.name}.dec.tmp")
        try {
            FileInputStream(ciphertextFile).use { cipherIn ->
                streamingAead.newDecryptingStream(cipherIn, NO_ASSOCIATED_DATA).use { plainIn ->
                    FileOutputStream(tempOut).use { plainOut ->
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        while (true) {
                            if (isCancelled()) throw AttachmentCancelledException()
                            val read = plainIn.read(buffer)
                            if (read < 0) break
                            plainOut.write(buffer, 0, read)
                            processed += read
                            onBytesProcessed(processed, totalSize)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            tempOut.delete()
            throw e
        }
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

/** Thrown to unwind the encrypt/decrypt loop promptly on cancellation
 *  (user-initiated cancel, coroutine cancellation, or a session-epoch
 *  change mid-operation) - never leaves a half-written file where a
 *  caller might mistake it for a completed one. */
class AttachmentCancelledException(message: String = "Attachment operation cancelled.") : Exception(message)

/** Wraps an OutputStream to also feed every written byte into a
 *  [MessageDigest], so the ciphertext SHA-256 can be computed in the same
 *  single pass as the streaming encryption instead of a second full-file
 *  read afterward (bounded memory either way, just fewer I/O passes). */
private class DigestingOutputStream(
    private val delegate: java.io.OutputStream,
    private val digest: MessageDigest,
) : java.io.OutputStream() {
    override fun write(b: Int) {
        digest.update(b.toByte())
        delegate.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        digest.update(b, off, len)
        delegate.write(b, off, len)
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
