package com.messenger.app.ui.chats

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Subscribe before history lookup; a cached miss must not end a slow live sync. */
internal object NotificationTargetResolver {
    suspend fun <T> await(snapshots: Flow<T>, target: (T) -> String?, searchHistory: suspend () -> Unit,
        timeoutMillis: Long = 60_000): String? = withTimeoutOrNull(timeoutMillis) {
        coroutineScope {
            val durable = async(start = CoroutineStart.UNDISPATCHED) { snapshots.mapNotNull { target(it) }.first() }
            val history = launch {
                try { searchHistory() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Live sync may still supply the target. */ }
            }
            try { durable.await() } finally { history.cancel(); durable.cancel() }
        }
    }
}
