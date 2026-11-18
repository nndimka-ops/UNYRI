package com.messenger.app.data.message

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    ) = mutex.withLock {
        // A waiter always re-reads the durable snapshot after the running send.
        // Successful ACKs coalesce network I/O; cancellation cannot strand a waiter.
        if (!isCurrent()) return@withLock
        if (epoch != session) { acknowledged.clear(); epoch = session }
        for ((chat, sequence) in load()) {
            if (!isCurrent()) break
            if (sequence <= (acknowledged[chat] ?: 0)) continue
            try {
                send(chat, sequence)
                if (!isCurrent()) break
                acknowledged[chat] = maxOf(sequence, acknowledged[chat] ?: 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Durable cursor remains pending; continue other chats.
            }
        }
    }
}
