package com.messenger.app.data.identity

/** Event-driven refresh budget. Attempts survive session changes for the same server/account. */
internal class IdentityRefreshPolicy(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val attempts = mutableMapOf<String, Long>()

    fun now(): Long = clock()

    fun foregroundDue(account: String, legacy: Boolean, missing: Boolean, refreshedAt: Long?): Boolean {
        val now = now()
        val lastAttempt = attempts[account]
        if (lastAttempt != null && now - lastAttempt < INTERVAL_MILLIS) return false
        return legacy || missing || refreshedAt == null || now - refreshedAt >= INTERVAL_MILLIS
    }

    fun attempted(account: String) { attempts[account] = now() }

    companion object {
        const val INTERVAL_MILLIS = 5 * 60_000L
    }
}
