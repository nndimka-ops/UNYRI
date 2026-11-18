package com.messenger.app.data.directtransfer

internal enum class DirectRequestClass { LIVE_READ, NORMAL_READ, PRIORITY_WRITE, NORMAL_WRITE }

/** Installation-runtime API pacing only. No protocol cursor, journal, consent or Signal budget. */
internal class DirectRequestBudget {
    private data class Entry(val at: Long, val normal: Boolean)
    private val reads = ArrayDeque<Entry>()
    private val writes = ArrayDeque<Entry>()
    private var observedAt = 0L
    private var readRefilledAt = 0L
    private var readCredit = READ_SPACING_MILLIS * READ_BURST
    private var retryNotBefore = 0L

    /** Caller supplies one monotonic elapsed clock; wall-clock time is never used to create credits. */
    @Synchronized fun delayMillis(now: Long, kind: DirectRequestClass): Long {
        advance(now)
        val read = kind == DirectRequestClass.LIVE_READ || kind == DirectRequestClass.NORMAL_READ
        val normal = kind == DirectRequestClass.NORMAL_READ || kind == DirectRequestClass.NORMAL_WRITE
        val entries = if (read) reads else writes
        val maximum = if (read) READ_LIMIT else WRITE_LIMIT
        val normalMaximum = if (read) NORMAL_READ_LIMIT else NORMAL_WRITE_LIMIT
        var delay = (retryNotBefore - now).coerceAtLeast(0)
        if (entries.size >= maximum) delay = maxOf(delay, WINDOW_MILLIS - (now - entries.first().at))
        if (normal && entries.count { it.normal } >= normalMaximum)
            delay = maxOf(delay, WINDOW_MILLIS - (now - entries.first { it.normal }.at))
        if (read) delay = maxOf(delay, (READ_SPACING_MILLIS - readCredit).coerceAtLeast(0))
        return delay
    }

    /** Read-only admission delay: keep one credit after a competing read starts. */
    @Synchronized fun readHeadroomDelayMillis(now: Long): Long {
        advance(now)
        return (2 * READ_SPACING_MILLIS - readCredit).coerceAtLeast(0)
    }

    /** Exactly one debit per actual HTTP start, including retries, metadata, capabilities and inbox. */
    @Synchronized fun consume(now: Long, kind: DirectRequestClass) {
        require(delayMillis(now, kind) == 0L) { "Direct API request is not yet eligible" }
        val read = kind == DirectRequestClass.LIVE_READ || kind == DirectRequestClass.NORMAL_READ
        val normal = kind == DirectRequestClass.NORMAL_READ || kind == DirectRequestClass.NORMAL_WRITE
        if (read) { readCredit -= READ_SPACING_MILLIS; reads.addLast(Entry(now, normal)) }
        else writes.addLast(Entry(now, normal))
    }

    @Synchronized fun deferAfter429(now: Long, retryAfterMillis: Long) {
        advance(now)
        val delay = if (retryAfterMillis <= 0) 120_000L else retryAfterMillis.coerceIn(1_000, 3_600_000)
        retryNotBefore = maxOf(retryNotBefore, if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay)
    }

    private fun advance(now: Long) {
        require(now >= observedAt && now >= 0) { "Monotonic API clock regressed" }
        observedAt = now
        val refill = (now - readRefilledAt).coerceAtMost(READ_SPACING_MILLIS * READ_BURST)
        readCredit = (readCredit + refill).coerceAtMost(READ_SPACING_MILLIS * READ_BURST)
        readRefilledAt = now
        if (now >= WINDOW_MILLIS) {
            val expired = now - WINDOW_MILLIS
            while (reads.isNotEmpty() && reads.first().at <= expired) reads.removeFirst()
            while (writes.isNotEmpty() && writes.first().at <= expired) writes.removeFirst()
        }
    }

    companion object {
        const val WINDOW_MILLIS = 60_000L
        const val READ_LIMIT = 144
        const val NORMAL_READ_LIMIT = 84
        const val WRITE_LIMIT = 96
        const val NORMAL_WRITE_LIMIT = 80
        const val READ_SPACING_MILLIS = 500L
        const val READ_BURST = 2L
    }
}
