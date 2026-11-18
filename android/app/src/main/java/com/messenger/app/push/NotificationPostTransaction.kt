package com.messenger.app.push

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One post and its durable dedup write finish before another claimant can post. */
internal class NotificationPostTransaction(
    private val load: (String) -> List<String>,
    private val save: (String, List<String>) -> Unit,
    private val mutex: Mutex = Mutex(),
    private val capacity: Int = 1024,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun post(owner: String, id: String, emit: suspend () -> Boolean): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val before = load(owner)
            if (id in before) return@withLock false
            currentCoroutineContext().ensureActive()
            // Include the Main post/IO return in the critical operation. Otherwise
            // prompt cancellation on that dispatcher hop could lose a successful post.
            withContext(NonCancellable) {
                if (!emit()) return@withContext false
                save(owner, (before.filter { it.isNotBlank() && it != id } + id).takeLast(capacity))
                true
            }
        }
    }
}
