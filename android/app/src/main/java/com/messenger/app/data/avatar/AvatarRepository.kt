package com.messenger.app.data.avatar

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-scoped avatar cache. Every screen resolves avatar bytes through this one
 * repository - screens never download avatars independently.
 *
 * Cache identity = (server tag, user id, avatar_version):
 *   - same avatar_version  -> reuse the cached file (no network);
 *   - new avatar_version   -> invalidate the old file and fetch the new one;
 *   - removed (has_avatar=false / version 0) -> delete the cached file and
 *     fall back to the letter;
 *   - download failure     -> fall back to the letter, never a broken image.
 *
 * [downloader] is the only network dependency (injected for JVM testability);
 * the Android wiring supplies the real presigned-GET flow via [UsersApi].
 */
class AvatarRepository(
    private val cacheDir: File,
    private val serverTag: String,
    private val downloader: suspend (userId: String) -> ByteArray?,
) {
    private val cacheLock = Any()
    private val generations = mutableMapOf<String, Long>()
    private val downloads = Array(32) { Mutex() }
    private val memory = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var memoryBytes = 0

    fun cached(userId: String, version: Long, hasAvatar: Boolean): ByteArray? = synchronized(cacheLock) {
        if (hasAvatar && version > 0) memory[fileFor(userId, version).name] else null
    }

    private fun remember(file: File, bytes: ByteArray): ByteArray {
        memory.remove(file.name)?.let { memoryBytes -= it.size }
        if (bytes.size <= 8 * 1024 * 1024) {
            memory[file.name] = bytes
            memoryBytes += bytes.size
            while (memoryBytes > 8 * 1024 * 1024) {
                val first = memory.entries.iterator()
                memoryBytes -= first.next().value.size
                first.remove()
            }
        }
        return bytes
    }
    init {
        cacheDir.mkdirs()
    }

    /** Returns cached bytes for (userId, version), downloading+caching on a
     *  cache miss. Null means "no avatar available -> render the fallback". */
    suspend fun resolve(userId: String, avatarVersion: Long, hasAvatar: Boolean): ByteArray? =
        withContext(Dispatchers.IO) {
          downloads[(userId.hashCode() and Int.MAX_VALUE) % downloads.size].withLock {
            if (!hasAvatar || avatarVersion <= 0) {
                clearUser(userId)
                return@withContext null
            }
            val file = fileFor(userId, avatarVersion)
            val generation = synchronized(cacheLock) {
                memory[file.name]?.let { return@withContext it }
                if (file.exists()) file.readBytesOrNull()?.let { return@withContext remember(file, it) }
                val next = (generations[userId] ?: 0) + 1
                generations[userId] = next
                next
            }
            val bytes = downloader(userId) ?: return@withContext null
            synchronized(cacheLock) {
                if (generations[userId] != generation) return@withContext null
                clearUser(userId)
                runCatching { file.writeBytes(bytes) }
                remember(file, bytes)
            }
          }
        }

    /** Optimistically stores freshly-uploaded bytes under a known version so
     *  the profile screen renders instantly (no re-download after upload). */
    fun put(userId: String, avatarVersion: Long, bytes: ByteArray) = synchronized(cacheLock) {
        clearUser(userId)
        runCatching { fileFor(userId, avatarVersion).apply { writeBytes(bytes) } }
        remember(fileFor(userId, avatarVersion), bytes)
    }

    /** Drops every cached version for a user (used on avatar removal). */
    fun invalidate(userId: String) {
        clearUser(userId)
    }

    fun clearMemory() = synchronized(cacheLock) {
        memory.clear()
        memoryBytes = 0
        generations.keys.toList().forEach { generations[it] = (generations[it] ?: 0) + 1 }
    }

    fun eraseCache() = synchronized(cacheLock) {
        clearMemory()
        for (file in cacheDir.listFiles().orEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("Could not erase avatar cache")
        }
    }

    fun fileFor(userId: String, version: Long): File =
        File(cacheDir, "avatar_${serverTag}_${userId}_$version.jpg")

    private fun clearUser(userId: String) = synchronized(cacheLock) {
        generations[userId] = (generations[userId] ?: 0) + 1
        val prefix = "avatar_${serverTag}_${userId}_"
        val entries = memory.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key.startsWith(prefix)) { memoryBytes -= entry.value.size; entries.remove() }
        }
        cacheDir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { runCatching { it.delete() } }
    }

    private fun File.readBytesOrNull(): ByteArray? =
        try {
            readBytes()
        } catch (_: IOException) {
            null
        }
}
