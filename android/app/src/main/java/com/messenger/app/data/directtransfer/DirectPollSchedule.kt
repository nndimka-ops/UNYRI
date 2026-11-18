package com.messenger.app.data.directtransfer

internal data class DirectPollWake(val at: Long, val auxiliary: Boolean)

/** In-flight chains wake the dispatcher when they finish; full lanes need no timer. */
internal object DirectPollSchedule {
    fun delayMillis(now: Long, candidates: List<DirectPollWake>, tasks: Int, auxiliaryTasks: Int): Long? {
        if (tasks >= 32) return null
        return candidates.asSequence().filter { !it.auxiliary || auxiliaryTasks < 16 }
            .minOfOrNull { it.at }?.let { (it - now).coerceAtLeast(1) }
    }
}
