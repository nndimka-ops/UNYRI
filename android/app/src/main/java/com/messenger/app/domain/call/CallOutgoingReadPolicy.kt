package com.messenger.app.domain.call

/** Monotonic request-issue times, matching iOS CallOutgoingReadPolicy. */
internal class CallOutgoingReadPolicy(val createdAt: Long) {
    enum class Read { CREATED, CHECK, REFRESH, BACKSTOP }
    data class Due(val read: Read, val at: Long)
    val deadline get() = createdAt + CallRingingIndicatorPolicy.confirmDeadlineMs
    private var polls = 0
    private var lastReadAt: Long? = null
    private var backstopIssued = false

    fun issued(read: Read, now: Long) {
        lastReadAt = now
        when (read) {
            Read.CREATED, Read.CHECK -> polls++
            Read.BACKSTOP -> backstopIssued = true
            Read.REFRESH -> Unit
        }
    }

    fun next(awaitingConfirmation: Boolean): Due? {
        if (backstopIssued) return null
        var due: Due? = Due(Read.BACKSTOP, createdAt + 46_000)
        if (awaitingConfirmation && polls < 8) {
            val check = lastReadAt?.takeIf { it >= deadline }
                ?.plus(CallRingingIndicatorPolicy.recheckIntervalMs) ?: deadline
            if (due == null || check < due.at) due = Due(Read.CHECK, check)
        }
        return due
    }
}
