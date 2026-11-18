package com.messenger.app.data.local

import java.io.File
import java.io.IOException
import java.util.UUID

/** Private, provider-approved, accounted exports. No timer; prune on creation/recovery. */
class MediaExportStore(private val cacheRoot: File, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val DIRECTORY = "media-exports"
        const val MAX_FILES = 8
        const val MAX_BYTES = 64L * 1024 * 1024
        const val RETENTION_MS = 10L * 60 * 1000
        private val process = UUID.randomUUID().toString()
    }
    private val directory get() = File(cacheRoot, DIRECTORY)
    fun recover() = synchronized(exportLock) {
        directory.listFiles().orEmpty().filter { !it.name.startsWith(process + "-") || it.name.endsWith(".part") }
            .forEach { MediaCacheUse.deleteUnlessUsed(it, "export") }
        legacyFiles(cacheRoot).forEach { MediaCacheUse.deleteUnlessUsed(it, "export") }
        prune()
    }
    fun prune() = synchronized(exportLock) {
        directory.listFiles().orEmpty().filter { it.isFile && now() - it.lastModified() >= RETENTION_MS }
            .forEach { MediaCacheUse.deleteUnlessUsed(it, "export") }
    }
    fun create(write: (File) -> Unit): OwnedMediaExport = synchronized(exportLock) {
        recover()
        check(directory.isDirectory || directory.mkdirs()) { "Export directory unavailable" }
        val files = directory.listFiles().orEmpty().filter { it.isFile }
        if (files.size >= MAX_FILES || files.sumOf { it.length() } >= MAX_BYTES) throw IOException("Export capacity reached")
        val part = File(directory, "$process-" + UUID.randomUUID() + ".jpg.part")
        val output = File(directory, part.name.removeSuffix(".part"))
        try {
            write(part)
            if (files.sumOf { it.length() } + part.length() > MAX_BYTES) throw IOException("Export capacity reached")
            check(part.renameTo(output)) { "Cannot publish export" }
            OwnedMediaExport(output, MediaCacheUse.acquire(listOf(output)), now)
        } catch (error: Throwable) { part.delete(); output.delete(); throw error }
    }
}
private val exportLock = Any()
internal fun legacyFiles(root: File): List<File> = root.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("markup-") && it.extension == "jpg" }

class OwnedMediaExport internal constructor(val file: File, private val lease: AutoCloseable, private val now: () -> Long) : AutoCloseable {
    private var shared = false
    /** Call only after a successful chooser launch; TTL/capacity/explicit clear own its remaining lifetime. */
    fun shared() { shared = true }
    override fun close() { lease.close(); if (shared) file.setLastModified(now()) else file.delete() }
}
