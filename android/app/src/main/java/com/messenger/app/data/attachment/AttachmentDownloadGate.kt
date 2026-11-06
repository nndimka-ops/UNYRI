package com.messenger.app.data.attachment

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes one attachment's writers while unrelated downloads remain parallel. */
internal class AttachmentDownloadGate {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> run(key: String, beforeStart: () -> Unit = {}, block: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { beforeStart(); block() }
        } finally {
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }
}

/** An automatic consumer was deferred; manual consumers remain eligible. */
internal class AutomaticDownloadDeferred : java.io.IOException("Automatic download policy changed")
