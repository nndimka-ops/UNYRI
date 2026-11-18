package com.messenger.app.data.local

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class FrameFailure { UNREADABLE, INTERRUPTED, UNKNOWN, POSTER }
internal enum class FrameRetry { WAIT, NOW }

/** No timers. Uptime deadlines are examined only on a natural start. */
internal class VideoFrameFailurePolicy(private val capacity: Int = 512) {
    private val held = LinkedHashMap<String, Long>()
    private var resumed = false
    private var pauses = 0L
    private var retryOnResume = false
    @Synchronized fun attempt(): Long? = if (resumed) pauses else null
    @Synchronized fun lifecycle(active: Boolean): Boolean {
        if (active == resumed) return false
        resumed = active
        if (!active) { pauses++; return false }
        if (!retryOnResume) return false
        retryOnResume = false
        held.clear()
        return true
    }
    @Synchronized fun holds(key: String, now: Long): Boolean {
        val until = held[key] ?: return false
        if (now < until) return true
        held.remove(key)
        return false
    }
    @Synchronized fun failed(key: String, kind: FrameFailure, exists: Boolean, attempt: Long?,
        generation: Long, currentGeneration: Long, now: Long): FrameRetry {
        if (generation != currentGeneration) return FrameRetry.WAIT
        if (attempt == null || attempt != pauses || !resumed) {
            if (resumed) return FrameRetry.NOW
            retryOnResume = true
            return FrameRetry.WAIT
        }
        retryOnResume = true
        if (kind == FrameFailure.INTERRUPTED || kind == FrameFailure.POSTER) return FrameRetry.WAIT
        held.remove(key)
        held[key] = if (kind == FrameFailure.UNREADABLE && exists) Long.MAX_VALUE else now + 10_000
        while (held.size > capacity) held.remove(held.keys.first())
        return FrameRetry.WAIT
    }
    @Synchronized fun clear() { held.clear(); retryOnResume = false }
    @Synchronized fun size() = held.size
}

/** Only frame/poster composables collect retryGeneration; photos never subscribe. */
internal object VideoFrameFailures {
    val policy = VideoFrameFailurePolicy()
    private val retry = MutableStateFlow(0L)
    val retryGeneration = retry.asStateFlow()
    fun lifecycle(resumed: Boolean) { if (policy.lifecycle(resumed)) retry.value++ }
    fun clear() = policy.clear()
    fun now() = SystemClock.uptimeMillis()
}
