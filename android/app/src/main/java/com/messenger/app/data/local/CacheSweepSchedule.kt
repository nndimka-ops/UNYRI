package com.messenger.app.data.local

internal object CacheSweepSchedule {
    fun delayMillis(retentionDays: Int, modifiedAt: Collection<Long>, now: Long): Long? {
        if (retentionDays <= 0) return null
        val age = retentionDays.coerceAtMost(365) * 86_400_000L
        // A protected/in-use expired file is retried at the former housekeeping cadence.
        return modifiedAt.minOrNull()?.let { ((it - now) + age).let { wait ->
            if (wait <= 0) 3_600_000L else wait
        } }
    }
}
