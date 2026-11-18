package com.messenger.app.ui.chats

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

internal object VideoPolicy {
    val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
    fun restore(saved: Long, duration: Long) = if (duration <= 0 || saved < 3000 || saved >= duration - 1000) 0L else saved
    fun loops(duration: Long, hasAudio: Boolean) = !hasAudio && duration in 1..30_000
    fun speed(saved: Float, duration: Long) = if (duration >= 30_000 && saved in speeds) saved else 1f
    fun scrubFactor(distanceDp: Float): Float = when { !distanceDp.isFinite() -> 1f; abs(distanceDp) < 50 -> 1f; abs(distanceDp) < 100 -> .5f; abs(distanceDp) < 150 -> .25f; else -> .125f }
    fun scrub(start: Long, dx: Float, width: Float, duration: Long, factor: Float): Long =
        if (!dx.isFinite() || !width.isFinite() || width <= 0 || !factor.isFinite()) start.coerceIn(0, duration.coerceAtLeast(0))
        else (start + dx / width * duration * factor).toLong().coerceIn(0, duration.coerceAtLeast(0))
    fun tapSeeks(from: Long, to: Long, thumbDistanceDp: Float) = abs(thumbDistanceDp) > 11 && abs(to - from) >= 34
    fun total(duration: Long) = (duration.coerceAtLeast(0) / 1000.0).roundToLong() * 1000
    fun remaining(position: Long, duration: Long) = (total(duration) - position.coerceAtLeast(0) / 1000 * 1000).coerceAtLeast(0)
    fun fps(widthPx: Float, duration: Long) = if (!widthPx.isFinite() || duration <= 0) 4 else ceil(widthPx * 1000 / duration).toInt().coerceIn(4, 60)
    fun zone(fraction: Float) = if (!fraction.isFinite()) 0 else if (fraction < .3f) -1 else if (fraction > .7f) 1 else 0
    fun holdRate(base: Float, dxDp: Float, fast: Boolean = true): Float {
        val top = if (fast) 3f else 2f
        val start = if (base < 2) 2f else top
        return if (dxDp <= -40 && base < 1.5) 1.5f else if (dxDp >= 40) top else start
    }
    fun offersFill(video: Float, screen: Float) = video.isFinite() && screen.isFinite() && video > 0 && screen > 0 && abs(video - screen) / screen > .12f
    fun pinchFill(scale: Float): Boolean? = if (!scale.isFinite()) null else if (scale > 1.08f) true else if (scale < .92f) false else null
}

/** One seek command in flight, latest desired target replaces its predecessor. */
internal class VideoSeekChaser {
    private var inFlight = false
    private var newest: Long? = null
    fun offer(target: Long): Long? { if (inFlight) { newest = target; return null }; inFlight = true; return target }
    fun completed(): Long? { val next = newest; newest = null; inFlight = next != null; return next }
    fun reset() { inFlight = false; newest = null }
}

internal object VideoPlaybackMemory {
    var muted = false
    var speed = 1f
    var remaining = false
    private val positions = LinkedHashMap<String, Long>()
    fun position(key: String) = positions[key] ?: 0L
    fun save(key: String, position: Long) { positions.remove(key); positions[key] = position; while (positions.size > 128) positions.remove(positions.keys.first()) }
}
