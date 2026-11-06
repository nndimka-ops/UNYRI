package com.messenger.app.data.local

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Bounded decoded previews only; never stores originals or writes to disk. */
object ChatImageCache {
    private var generation = 0L
    private val decodeSlots = Semaphore(2)
    private val keyLocks = Array(16) { Mutex() }

    /** Bounded work and same-key reuse; cancellation never leaves a retained job. */
    suspend fun load(key: String, decode: () -> Bitmap?): Bitmap? {
        val expected = epoch()
        return keyLocks[(key.hashCode() and Int.MAX_VALUE) % keyLocks.size].withLock {
            if (epoch() != expected) return@withLock null
            peek(key)?.let { return@withLock it }
            decodeSlots.withPermit {
                if (epoch() != expected) return@withPermit null
                val image = withContext(Dispatchers.IO) { decode() }
                if (epoch() != expected) return@withPermit null
                if (image != null) put(key, image, expected)
                image
            }
        }
    }
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount + key.length * 2
    }
    @Synchronized fun peek(key: String): Bitmap? = cache.get(key)
    @Synchronized fun epoch(): Long = generation
    @Synchronized fun put(key: String, bitmap: Bitmap, epoch: Long) {
        if (epoch == generation) cache.put(key, bitmap)
    }
    @Synchronized fun clear() { generation++; cache.evictAll() }
}
