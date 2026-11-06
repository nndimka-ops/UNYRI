package com.messenger.app.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Serializes the two operations that must NEVER interleave across an account
 * switch: a durable Room write (guarded by a captured session epoch) and the
 * Room clear that follows logout.
 *
 * Deterministic guarantee (the P0 race fix):
 *  - [write] runs [block] IFF the session epoch still equals [epochAtStart],
 *    checked atomically under the same [Mutex] that [clear] uses;
 *  - [clear] runs [block] under that mutex.
 * Therefore, once the epoch has been bumped and [clear] has completed, every
 * later [write] observes the new epoch and is skipped (returns null), and any
 * [write] that was already in progress finishes BEFORE [clear] removes its row
 * - so a stale old-account row can never appear after the switch.
 */
class SessionWriteGate(private val currentEpoch: () -> Int) {
    private val mutex = Mutex()
    private val leaseKey = object : CoroutineContext.Key<Lease> {}
    private class Lease(key: CoroutineContext.Key<Lease>, val epoch: Int) : AbstractCoroutineContextElement(key)

    /** Returns the result of [block], or null if the session changed. */
    suspend fun <T> write(epochAtStart: Int, block: suspend () -> T): T? {
        // Signal trust persistence is nested inside outbox/ingestion writes.
        // Reuse only this operation's still-held lease, including across
        // dispatcher switches. Other operations must still wait for the mutex.
        val inherited = coroutineContext[leaseKey]
        if (inherited != null && mutex.holdsLock(inherited)) {
            return if (inherited.epoch == epochAtStart && currentEpoch() == epochAtStart) block() else null
        }
        val lease = Lease(leaseKey, epochAtStart)
        return mutex.withLock(lease) {
            if (currentEpoch() != epochAtStart) null else withContext(lease) { block() }
        }
    }

    suspend fun clear(block: suspend () -> Unit) {
        mutex.withLock { block() }
    }
}
