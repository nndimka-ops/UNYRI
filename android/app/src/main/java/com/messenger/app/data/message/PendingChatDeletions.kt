package com.messenger.app.data.message

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The durable tombstone is written only after cleanup finishes. Serialize HTTP/WS replays. */
internal class PendingChatDeletions {
    private val mutex = Mutex()

    suspend fun apply(
        ids: List<String>,
        isApplied: suspend (String) -> Boolean,
        delete: suspend (List<String>) -> Boolean,
    ): Boolean = mutex.withLock {
        val pending = ids.distinct().filterNot { isApplied(it) }
        if (pending.isEmpty()) true else delete(pending)
    }
}
