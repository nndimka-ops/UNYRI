package com.messenger.app.data.api

/** Event-driven frame accounting: wall-clock silence is never a slow rendered frame. */
internal class RenderedFrameWindow(refreshRate: Float) {
    val maximumFps = refreshRate.takeIf { it.isFinite() && it > 0f }?.coerceAtLeast(30f)?.toDouble() ?: 60.0
    val budgetMillis = 1000.0 / maximumFps
    data class Summary(val frames: Int, val lateFrames: Int, val maximumDurationMillis: Double)
    data class Observation(val durationMillis: Double, val stalled: Boolean, val summary: Summary?)
    private var startNanos: Long? = null
    private var lastStallNanos: Long? = null
    private var frames = 0
    private var late = 0
    private var maximumDuration = 0.0

    /** [durationNanos] is FrameMetrics.TOTAL_DURATION, not the interval between callbacks. */
    fun frame(durationNanos: Long, nowNanos: Long): Observation? {
        if (durationNanos < 0) return null // Android reports unavailable metrics as -1.
        val duration = durationNanos / 1_000_000.0
        if (startNanos == null) startNanos = nowNanos
        frames++
        if (duration > budgetMillis * 1.5) late++
        maximumDuration = maxOf(maximumDuration, duration)
        val stalled = duration > 80 && lastStallNanos?.let { nowNanos - it > 1_000_000_000L } != false
        if (stalled) lastStallNanos = nowNanos
        val summary = if (nowNanos - startNanos!! >= 5_000_000_000L) {
            Summary(frames, late, maximumDuration).also {
                frames = 0; late = 0; maximumDuration = 0.0; startNanos = nowNanos
            }
        } else null
        return Observation(duration, stalled, summary)
    }
}
