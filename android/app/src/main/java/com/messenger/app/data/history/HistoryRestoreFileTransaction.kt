package com.messenger.app.data.history

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stages a private projection before entering the Room transaction. The caller
 * invokes commit from inside Room, then returns only after Room has committed.
 * Ordinary write/Room failures restore the previous projection without allocating
 * more disk space. This is not a crash transaction across SQLite and the file.
 */
internal class HistoryRestoreFileTransaction(private val target: File, bytes: ByteArray) {
    private val staged: File
    private val previous: File?
    private var committed = false

    init {
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        require(parent.canonicalFile == parent.absoluteFile && target.canonicalFile == target.absoluteFile)
        check(!target.exists() || target.isFile)
        staged = File.createTempFile("restore-next-", ".tmp", parent)
        var backup: File? = null
        try {
            FileOutputStream(staged).use { it.write(bytes); it.fd.sync() }
            if (target.exists()) {
                backup = File.createTempFile("restore-previous-", ".tmp", parent)
                FileOutputStream(backup).use { output ->
                    target.inputStream().use { it.copyTo(output) }; output.fd.sync()
                }
            }
            previous = backup
        } catch (failure: Throwable) {
            staged.delete(); backup?.delete(); throw failure
        }
    }

    suspend fun <T> apply(block: suspend (commit: suspend () -> Unit) -> T): T {
        var keepPrevious = false
        try {
            val result = block {
                check(!committed)
                move(staged, target)
                committed = true
            }
            check(committed) { "Call restore was not committed with messages" }
            return result
        } catch (failure: Throwable) {
            if (committed) {
                try {
                    if (previous != null) move(previous, target)
                    else check(!target.exists() || target.delete()) { "Could not roll back call restore" }
                } catch (rollback: Throwable) {
                    // Preserve the last known copy if even rollback is rejected.
                    keepPrevious = true; failure.addSuppressed(rollback)
                }
            }
            throw failure
        } finally { staged.delete(); if (!keepPrevious) previous?.delete() }
    }

    private fun move(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
