package com.messenger.app.data.directtransfer

/** Account-owned monotonic deadlines. A null deadline waits for an event, with no timer. */
internal class DirectPollRuntime {
    private data class State(val at: Long?, val retries: Int)
    private val states = java.util.concurrent.ConcurrentHashMap<String, State>()

    fun clear() = states.clear()
    fun wake(id: String) { states.compute(id) { _, old -> State(0, old?.retries ?: 0) } }
    fun deadline(record: DirectTransferRecord): Long? {
        if (record.mailboxClosed) return null
        val state = states[record.offer.transferId] ?: return 0L
        return state.at
    }

    fun settled(record: DirectTransferRecord, plan: DirectPollPlan, startedAt: Long, now: Long, wallSeconds: Long) {
        val id = record.offer.transferId
        if (record.mailboxClosed) { states.remove(id); return }
        if (plan.retryOnly) retry(record, now, wallSeconds)
        else states[id] = State(plan.cadenceMillis?.let { maxOf(now, startedAt + it) }, 0)
    }

    fun retry(record: DirectTransferRecord, now: Long, wallSeconds: Long, retryAfterMillis: Long = 0) {
        val id = record.offer.transferId
        val count = ((states[id]?.retries ?: 0) + 1).coerceAtMost(9)
        val delay = maxOf(retryAfterMillis, minOf(300_000L, 2_000L shl (count - 1)))
        // Expiry wakes only an already retrying chain, and performs local cleanup without HTTP.
        val untilExpiry = (record.offer.expiresAt - wallSeconds).coerceIn(0, Long.MAX_VALUE / 1000) * 1000
        states[id] = State(now + minOf(delay, untilExpiry), count)
    }
}
