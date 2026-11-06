package com.messenger.app.data.history

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException

/** Metadata belongs INSIDE the authenticated archive. A server-provided digest
 * alone is not evidence of authenticity. Bytes are already attachment ciphertext;
 * this layer neither decrypts nor exports attachment keys to the transport. */
@Serializable
data class HistoryMediaEntry(val attachment: String, val blob: String, val bytes: Long, val sha256: String) {
    fun validate() {
        requireHistoryId(attachment); requireHistoryId(blob)
        require(bytes in 1..MAX_FILE_BYTES && sha256.matches(Regex("[0-9a-f]{64}")))
    }
    companion object { const val MAX_FILE_BYTES = 100L * 1024 * 1024 + 4096 }
}

/** Reject a partial set before declaring a backup complete. requiredAttachments
 * must be derived from the FINAL merged, visible archive, not the local page. */
internal fun validateHistoryMedia(entries: List<HistoryMediaEntry>, requiredAttachments: Set<String>, byteLimit: Long): Long {
    require(entries.size <= 50_000 && byteLimit >= 0)
    val attachments = HashSet<String>(); val blobs = HashSet<String>()
    var total = 0L
    entries.forEach {
        it.validate()
        require(attachments.add(it.attachment) && blobs.add(it.blob))
        require(it.bytes <= byteLimit - total)
        total += it.bytes
    }
    require(attachments == requiredAttachments)
    return total
}

/** Blocking IO: use Dispatchers.IO, never Compose's main thread. The supplied
 * root is a PRIVATE account/server directory. Do not point it at public storage.
 * The caller supplies a cancellation/account lease check, run between 64 KiB
 * reads and before promotion. A verified ciphertext file is not a full restore:
 * the existing attachment AEAD decoder must still authenticate it before import. */
internal class HistoryMediaSpool(private val root: File) {
    @Synchronized
    fun receive(entry: HistoryMediaEntry, open: () -> InputStream, assertCurrent: () -> Unit): File {
        entry.validate(); assertCurrent()
        require(root.isDirectory || root.mkdirs())
        require(root.canonicalFile == root.absoluteFile) { "Private spool must not contain symlinks" }
        val destination = File(root, "${entry.blob}-${entry.sha256}.cipher")
        if (destination.exists()) {
            verify(destination, entry, assertCurrent)
            return destination
        }
        val temporary = File.createTempFile("transfer-", ".partial", root)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temporary).use { output ->
                open().use { input ->
                    readBounded(input, entry.bytes, assertCurrent) { buffer, count ->
                        hash.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
                require(hex(hash.digest()) == entry.sha256) { "Backup media checksum mismatch" }
                output.fd.sync()
            }
            assertCurrent()
            // Per-instance calls are serialized. A racing process/owner cannot
            // replace a validated cache entry: adopt it only after verification.
            try { Files.move(temporary.toPath(), destination.toPath()) }
            catch (_: FileAlreadyExistsException) { verify(destination, entry, assertCurrent) }
            return destination
        } finally { temporary.delete() }
    }

    fun verify(file: File, entry: HistoryMediaEntry, assertCurrent: () -> Unit) {
        entry.validate(); assertCurrent()
        require(file.parentFile?.canonicalFile == root.canonicalFile && file.canonicalFile == file.absoluteFile && file.isFile)
        require(file.length() == entry.bytes)
        val hash = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> readBounded(input, entry.bytes, assertCurrent) { b, n -> hash.update(b, 0, n) } }
        require(hex(hash.digest()) == entry.sha256) { "Backup media checksum mismatch" }
        assertCurrent()
    }

    private fun readBounded(input: InputStream, expected: Long, assertCurrent: () -> Unit, consume: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            assertCurrent()
            // Read at most one excess byte to detect a dishonest Content-Length.
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - total + 1).toInt())
            if (count < 0) break
            check(count > 0) { "Media stream made no progress" }
            require(count.toLong() <= expected - total) { "Backup media exceeds declared size" }
            consume(buffer, count); total += count
        }
        require(total == expected) { "Truncated backup media" }
        assertCurrent()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
