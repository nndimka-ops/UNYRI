package com.messenger.app.data.history

import java.io.File
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.util.UUID

/** Non-secret stable upload IDs let interrupted attempts reuse server reservations.
 * Kept under private filesDir, outside disposable ciphertext spool directories. */
internal class HistoryUploadPlan(private val directory: File) {
    fun retain(entries: List<HistoryMediaEntry>) {
        val names = entries.map { "${it.attachment}-${it.bytes}-${it.sha256}.plan" }.toSet()
        if (!directory.isDirectory || directory.canonicalFile != directory.absoluteFile) return
        for (file in directory.listFiles().orEmpty()) {
            if (file.name.endsWith(".plan") && file.name !in names && file.isFile && file.canonicalFile == file.absoluteFile) file.delete()
        }
    }
    fun blob(attachment: String, bytes: Long, sha256: String): String {
        requireHistoryId(attachment)
        require(bytes in 1..HistoryMediaEntry.MAX_FILE_BYTES && sha256.matches(Regex("[0-9a-f]{64}")))
        check(directory.isDirectory || directory.mkdirs())
        check(directory.canonicalFile == directory.absoluteFile)
        val file = File(directory, "$attachment-$bytes-$sha256.plan")
        if (file.exists()) return read(file)
        check(directory.list().orEmpty().size < 50_000) { "Upload plan limit reached" }
        val temporary = File.createTempFile("plan-", ".tmp", directory)
        try {
            java.io.FileOutputStream(temporary).use { output -> output.write(UUID.randomUUID().toString().toByteArray(Charsets.US_ASCII)); output.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath()) } catch (_: FileAlreadyExistsException) { }
            return read(file)
        } finally { temporary.delete() }
    }
    private fun read(file: File): String {
        check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() == 36L)
        return file.readText(Charsets.US_ASCII).also(::requireHistoryId)
    }
}
