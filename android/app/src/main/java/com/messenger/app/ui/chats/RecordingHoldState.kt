package com.messenger.app.ui.chats

import kotlin.math.abs
import kotlin.math.hypot

/** Current iOS 18+ hold policy. Media-neutral so video capture can reuse the gesture. */
internal class RecordingHoldState(width: Float) {
    val cancelDistance = (width * .3f).coerceIn(80f, 120f)
    val lockDistance = 90f
    enum class Outcome { Holding, Send, Cancel, Lock }
    private enum class Axis { Horizontal, Vertical }
    private var axis: Axis? = null
    var outcome = Outcome.Holding
        private set
    var x = 0f
        private set
    var y = 0f
        private set

    fun move(dx: Float, dy: Float): Outcome {
        if (outcome != Outcome.Holding) return outcome
        val ax = abs(dx); val ay = abs(dy)
        if (hypot(ax, ay) > 12f) {
            if (ax > ay * 1.3f) axis = Axis.Horizontal
            else if (ay > ax * 1.3f) axis = Axis.Vertical
        }
        // Axis steers only the illustration. Cancel wins if both thresholds cross.
        outcome = when {
            dx < -cancelDistance -> Outcome.Cancel
            dy < -lockDistance -> Outcome.Lock
            else -> Outcome.Holding
        }
        x = if (axis == Axis.Horizontal) dx.coerceIn(-cancelDistance, 0f) else 0f
        y = if (axis == Axis.Vertical) dy.coerceIn(-lockDistance, 0f) else 0f
        return outcome
    }

    fun end(interrupted: Boolean = false): Outcome {
        if (outcome == Outcome.Holding) outcome = if (interrupted) Outcome.Lock else Outcome.Send
        return outcome
    }
}

/** Invalidates pending asynchronous starts on release/cancel/background. */
internal class VoiceTakeGate {
    private var generation = 0L
    private var active = false
    fun begin(): Long { active = true; return ++generation }
    fun current(ticket: Long): Boolean = active && ticket == generation
    fun finish(): Boolean {
        if (!active) return false
        active = false
        return true
    }
}

internal enum class VoiceTakeDestination { Discard, Review, Send }
internal fun voiceTakeDestination(send: Boolean, durationMs: Long, resumed: Boolean): VoiceTakeDestination = when {
    send && durationMs < 1000 && !resumed -> VoiceTakeDestination.Discard
    send -> VoiceTakeDestination.Send
    else -> VoiceTakeDestination.Review
}
