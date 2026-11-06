package com.messenger.app.data.local

import java.io.File
import kotlinx.coroutines.launch

/** Leases span transfers and consumers. Deletion checks and file removal share this monitor. */
object MediaCacheUse {
    private val accessScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    fun touchCache(file: File) {
        if (file.parentFile?.name == "attachments") accessScope.launch { file.setLastModified(System.currentTimeMillis()) }
    }
    private val references = mutableMapOf<String, Int>()
    private fun path(file: File) = "path:" + file.canonicalPath
    fun acquire(files: List<File> = emptyList(), attachmentId: String? = null): AutoCloseable {
        val keys = files.map(::path) + listOfNotNull(attachmentId?.let { "id:$it" })
        synchronized(references) { keys.forEach { references[it] = (references[it] ?: 0) + 1 } }
        var closed = false
        return AutoCloseable { synchronized(references) {
            if (!closed) { closed = true; keys.forEach { key -> val count = (references[key] ?: 1) - 1; if (count == 0) references.remove(key) else references[key] = count } }
        } }
    }
    private val external = mutableSetOf<String>()
    fun retainExternal(file: File) { synchronized(references) { val key = path(file); if (external.add(key)) references[key] = (references[key] ?: 0) + 1 }; touchCache(file) }
    fun isUsed(file: File, attachmentId: String): Boolean = synchronized(references) {
        (references[path(file)] ?: 0) > 0 || (references["id:$attachmentId"] ?: 0) > 0
    }
    fun deleteUnlessUsed(file: File, attachmentId: String): Boolean? = synchronized(references) {
        if ((references[path(file)] ?: 0) > 0 || (references["id:$attachmentId"] ?: 0) > 0) null else !file.exists() || file.delete()
    }
}

data class CachePolicy(val maxBytes: Long = 0, val retentionDays: Int = 0)
object CacheEvictionPolicy {
    data class Entry(val id: String, val bytes: Long, val usedAt: Long)
    fun select(entries: List<Entry>, policy: CachePolicy, now: Long, batch: Int = 100, protectedBytes: Long = 0): Set<String> {
        val oldest = entries.sortedBy { it.usedAt }
        val cutoff = if (policy.retentionDays > 0) now - policy.retentionDays * 86_400_000L else Long.MIN_VALUE
        var total = entries.sumOf { it.bytes } + protectedBytes.coerceAtLeast(0)
        val ids = linkedSetOf<String>()
        for (entry in oldest) {
            if (ids.size >= batch) break
            if (entry.usedAt < cutoff || policy.maxBytes > 0 && total > policy.maxBytes) {
                ids.add(entry.id); total -= entry.bytes
            }
        }
        return ids
    }
}
