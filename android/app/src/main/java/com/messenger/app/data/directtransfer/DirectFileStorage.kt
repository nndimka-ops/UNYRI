package com.messenger.app.data.directtransfer

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class DirectFileStorage(private val context: Context, owner: String, private val ensureOwner: () -> Unit) {
    private val ownerRoot = File(context.filesDir, "direct-transfers/" + digest(owner.toByteArray(Charsets.UTF_8)))
    data class Staged(val filename: String, val mimeType: String, val size: Long, val sha256: String)

    fun file(id: String, kind: String): File {
        require(canonicalUuid(id) && kind in setOf("source", "source.tmp", "partial", "complete"))
        val directory = File(ownerRoot, id)
        val expected = File(directory, kind)
        // Labels from the peer never participate in path construction.
        require(expected.canonicalPath.startsWith(ownerRoot.canonicalPath + File.separator))
        return expected
    }

    fun describe(uri: Uri): DirectFileSourceDisplay {
        ensureOwner()
        val (name, size) = metadata(uri)
        ensureOwner()
        if (size > DirectFileWire.MAX_FILE_BYTES) throw DirectStorageError("too_large")
        return DirectFileSourceDisplay(safeFilename(name), size.takeIf { it >= 0 })
    }

    suspend fun stage(id: String, uri: Uri, sourceHandle: DirectSourceReadHandle = DirectSourceReadHandle(), beforeWrite: suspend (Long) -> Unit = {}, afterWrite: (Long) -> Unit = {}): Staged {
        ensureOwner()
        val (name, declaredSize) = metadata(uri)
        if (declaredSize > DirectFileWire.MAX_FILE_BYTES) throw DirectStorageError("too_large")
        val target = file(id, "source.tmp")
        check(!target.exists() && !file(id, "source").exists()) { "Transfer source exists" }
        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
        requireSpace(declaredSize.takeIf { it > 0 } ?: 1)
        val sha = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            val input = if (uri.scheme == "file") FileInputStream(File(requireNotNull(uri.path))) else context.contentResolver.openInputStream(uri)
            val source = input ?: throw DirectStorageError("source_missing")
            sourceHandle.attach(source)
            try {
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive(); ensureOwner(); sourceHandle.checkCurrent()
                        val size = source.read(buffer)
                        if (size < 0) break
                        if (size == 0) continue
                        count += size
                        if (count > DirectFileWire.MAX_FILE_BYTES) throw DirectStorageError("too_large")
                        beforeWrite(count)
                        currentCoroutineContext().ensureActive(); ensureOwner(); sourceHandle.checkCurrent()
                        if (count % (1024 * 1024) < buffer.size) requireSpace(buffer.size.toLong())
                        output.write(buffer, 0, size); sha.update(buffer, 0, size); afterWrite(count)
                        if (count % (1024 * 1024) < buffer.size) kotlinx.coroutines.yield()
                    }
                    output.fd.sync()
                }
            } finally { sourceHandle.finish(source) }
            currentCoroutineContext().ensureActive(); sourceHandle.checkCurrent(); ensureOwner()
            if (count <= 0) throw DirectStorageError("empty_file")
            val ready = file(id, "source")
            check(target.renameTo(ready)) { "Unable to preserve transfer original" }
            check(ready.setReadOnly()) { "Unable to protect transfer original" }
            syncDirectory(ready.parentFile!!)
            return Staged(safeFilename(name), safeMime(runCatching { context.contentResolver.getType(uri) }.getOrNull()), count, hex(sha.digest()))
        } catch (error: Exception) {
            target.delete()
            file(id, "source").delete()
            throw error
        }
    }

    suspend fun hash(file: File, limit: Long = file.length(), checkCurrent: () -> Unit = {}): String {
        ensureOwner()
        require(limit >= 0 && limit <= file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = limit
            while (remaining > 0) {
                currentCoroutineContext().ensureActive(); ensureOwner(); checkCurrent()
                val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read <= 0) throw DirectStorageError("source_changed")
                digest.update(buffer, 0, read); remaining -= read
                if (remaining % (1024 * 1024) < buffer.size) kotlinx.coroutines.yield()
            }
        }
        currentCoroutineContext().ensureActive(); ensureOwner(); checkCurrent()
        return hex(digest.digest())
    }

    fun openPartial(id: String, offset: Long, total: Long): RandomAccessFile {
        ensureOwner()
        val partial = file(id, "partial")
        check(partial.parentFile?.mkdirs() == true || partial.parentFile?.isDirectory == true)
        require(offset in 0..total && total <= DirectFileWire.MAX_FILE_BYTES)
        if (offset > partial.length()) throw DirectStorageError("prefix_mismatch")
        requireSpace(total - offset)
        return RandomAccessFile(partial, "rw").also { it.setLength(offset); it.seek(offset) }
    }

    fun promote(id: String): File {
        ensureOwner()
        val complete = file(id, "complete")
        check(!complete.exists()) { "Received file already exists" }
        check(file(id, "partial").renameTo(complete)) { "Unable to finish received file" }
        syncDirectory(complete.parentFile!!)
        return complete
    }
    fun deleteIncomplete(id: String) { for (kind in listOf("source.tmp", "source", "partial")) file(id, kind).delete() }
    fun deleteSource(id: String) { file(id, "source").delete() }
    fun availableBytes(): Long = StatFs((if (ownerRoot.exists()) ownerRoot else context.filesDir).absolutePath).availableBytes
    fun deleteStagingSource(id: String) { for (kind in listOf("source.tmp", "source")) file(id, kind).delete() }
    fun requireSpace(bytes: Long) {
        val storage = if (ownerRoot.exists()) ownerRoot else context.filesDir
        if (bytes < 0 || StatFs(storage.absolutePath).availableBytes < bytes + 32 * 1024 * 1024) throw DirectStorageError("disk_full")
    }

    private fun metadata(uri: Uri): Pair<String?, Long> {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).let { it.name to it.length() }
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null to -1L else {
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                (if (name >= 0 && !cursor.isNull(name)) cursor.getString(name) else null) to
                    (if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else -1L)
            }
        } ?: (null to -1L)
    }
    private fun safeFilename(value: String?): String = DirectFileDisplayName.safeFilename(value)
    private fun safeMime(value: String?): String = value?.takeIf { it.length in 1..127 && DIRECT_MIME.matches(it) } ?: "application/octet-stream"
    private fun syncDirectory(directory: File) {
        check(directory.isDirectory)
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
    companion object {
        fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
        fun rawHash(hex: String): ByteArray { require(validHash(hex)); return ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
    }
}

internal class DirectStorageError(val reason: String) : Exception("Unable to prepare direct transfer ($reason)")
