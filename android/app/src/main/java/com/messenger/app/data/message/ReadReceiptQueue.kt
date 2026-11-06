package com.messenger.app.data.message

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** The durable local cursors are the queue. Restart safely replays idempotent receipts. */
internal class ReadReceiptQueue {
    private val mutex = Mutex()
    private var epoch: Int? = null
    private val acknowledged = mutableMapOf<String, Long>()

    suspend fun flush(
        session: Int,
        isCurrent: () -> Boolean,
        load: suspend () -> Map<String, Long>,
        send: suspend (String, Long) -> Unit,
    ) {
        if (!mutex.tryLock()) return
        try {
            if (!isCurrent()) return
            if (epoch != session) { acknowledged.clear(); epoch = session }
            for ((chat, sequence) in load()) {
                if (!isCurrent()) return
                if (sequence <= (acknowledged[chat] ?: 0)) continue
                try {
                    send(chat, sequence)
                    if (!isCurrent()) return
                    acknowledged[chat] = sequence
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Leave this cursor pending; one unavailable chat must not block the rest.
                }
            }
        } finally { mutex.unlock() }
    }
}
