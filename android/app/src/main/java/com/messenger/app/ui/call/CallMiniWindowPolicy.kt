package com.messenger.app.ui.call

import kotlin.math.*

/** Direct port of CallMiniWindowPolicy in CallScreen.swift. Coordinates are dp. */
object CallMiniWindowPolicy {
    data class Point(val x: Float, val y: Float) { operator fun plus(p: Point) = Point(x + p.x, y + p.y) }
    data class Size(val width: Float, val height: Float)
    data class Area(val x: Float, val y: Float, val width: Float, val height: Float) {
        val right get() = x + width
        val bottom get() = y + height
    }
    enum class Corner(val left: Boolean, val top: Boolean) {
        TOP_LEFT(true, true), TOP_RIGHT(false, true), BOTTOM_LEFT(true, false), BOTTOM_RIGHT(false, false);
        companion object { fun at(left: Boolean, top: Boolean) = entries.first { it.left == left && it.top == top } }
    }
    enum class Step(val width: Float) { SMALL(88f), MEDIUM(120f), LARGE(168f) }
    data class Placement(val corner: Corner = Corner.BOTTOM_RIGHT, val step: Step = Step.MEDIUM,
                         val tucked: Boolean = false, val fraction: Float = 1f)
    data class Sample(val point: Point, val timeMs: Long)

    fun area(width: Float, height: Float, keyboard: Float): Area {
        val free = height - max(0f, keyboard)
        var top = 112f
        var bottom = free - 100f
        if (bottom - top < 90f) top = max(12f, bottom - 90f)
        if (bottom - top < 90f) bottom = min(free - 12f, top + 90f)
        return Area(12f, top, max(0f, width - 24f), max(0f, bottom - top))
    }
    fun size(step: Step, area: Area): Size {
        val w = max(64f, minOf(step.width, area.width * .6f, area.height / 1.4f)).roundToInt().toFloat()
        return Size(w, (w * 1.4f).roundToInt().toFloat())
    }
    fun verticalRange(size: Size, area: Area): ClosedFloatingPointRange<Float> {
        val bottom = area.bottom - size.height
        return min(area.y, bottom)..bottom
    }
    fun origin(p: Placement, size: Size, area: Area, width: Float): Point {
        val y = verticalRange(size, area)
        return if (p.tucked) Point(if (p.corner.left) -size.width - 16f else width + 16f,
            y.start + (y.endInclusive - y.start) * p.fraction.coerceIn(0f, 1f))
        else Point(if (p.corner.left) area.x else max(area.x, area.right - size.width),
            if (p.corner.top) y.start else y.endInclusive)
    }
    fun dragged(p: Point, size: Size, area: Area, width: Float): Point = Point(
        p.x.coerceIn(-size.width * .75f, width - size.width * .25f),
        p.y.coerceIn(verticalRange(size, area)))
    fun fraction(centerY: Float, size: Size, area: Area): Float =
        if (area.height <= size.height) 0f else ((centerY - size.height / 2 - area.y) / (area.height - size.height)).coerceIn(0f, 1f)
    fun release(p: Placement, origin: Point, velocity: Point, size: Size, area: Area, width: Float): Placement {
        val center = Point(origin.x + size.width / 2 + velocity.x * .2f, origin.y + size.height / 2 + velocity.y * .2f)
        val tuck = center.x < -size.width / 5 || center.x > width + size.width / 5
        return p.copy(corner = Corner.at(center.x < width / 2, center.y < area.y + area.height / 2),
            tucked = tuck, fraction = fraction(center.y, size, area))
    }
    fun tab(p: Placement, size: Size, area: Area, width: Float): Area {
        val origin = origin(p.copy(tucked = true), size, area, width)
        return Area(if (p.corner.left) -10f else width - 20f, origin.y + size.height / 2 - 32f, 30f, 64f)
    }
    fun tabTarget(tab: Area) = Area(tab.x + tab.width / 2 - 22f, tab.y - 12f, 44f, 88f)
    fun untuck(p: Placement, fraction: Float = p.fraction) = p.copy(tucked = false, corner = Corner.at(p.corner.left, fraction < .5f))
    fun nearest(width: Float) = Step.entries.minBy { abs(it.width - width) }
    fun next(step: Step) = Step.entries[(step.ordinal + 1) % Step.entries.size]
    fun velocity(samples: List<Sample>): Point {
        val last = samples.lastOrNull() ?: return Point(0f, 0f)
        val first = samples.first { last.timeMs - it.timeMs <= 100 }
        val dt = (last.timeMs - first.timeMs) / 1000f
        return if (dt <= .008f) Point(0f, 0f) else Point((last.point.x - first.point.x) / dt, (last.point.y - first.point.y) / dt)
    }
    fun springVelocity(v: Point, from: Point, to: Point): Float {
        val dx = to.x - from.x; val dy = to.y - from.y
        val d = hypot(dx, dy)
        return if (d <= 1) 0f else ((v.x * dx + v.y * dy) / (d * d)).coerceIn(0f, 12f)
    }
    fun drawerExcluded(point: Point, frame: Area, tucked: Boolean): Boolean {
        val extra = if (tucked) 0f else 8f
        return point.x in frame.x - extra..frame.right + extra && point.y in frame.y - extra..frame.bottom + extra
    }
}

/** Owned outside the mini composition; hide is presentation-only and resets on minimize/new call. */
class CallMiniWindowMemory {
    private var callId: String? = null
    private var stored = CallMiniWindowPolicy.Placement()
    var hidden: Boolean = false; private set
    fun placement(id: String, defaultCorner: CallMiniWindowPolicy.Corner = CallMiniWindowPolicy.Corner.BOTTOM_RIGHT): CallMiniWindowPolicy.Placement {
        resetFor(id, defaultCorner); return stored
    }
    fun store(id: String, placement: CallMiniWindowPolicy.Placement) { resetFor(id); stored = placement }
    fun hide(id: String) { resetFor(id); hidden = true }
    // Minimizing before the first video frame must not choose an LTR corner.
    // The host supplies the direction-dependent default when it first appears.
    fun minimized(id: String) { if (callId != id) clear(); hidden = false }
    fun clear() { callId = null; stored = CallMiniWindowPolicy.Placement(); hidden = false }
    private fun resetFor(id: String, corner: CallMiniWindowPolicy.Corner = CallMiniWindowPolicy.Corner.BOTTOM_RIGHT) {
        if (callId != id) { callId = id; stored = CallMiniWindowPolicy.Placement(corner = corner); hidden = false }
    }
}

/** Once two fingers participate, no pan or throw is accepted until all are up. */
class CallMiniGesturePolicy {
    var pinched = false; private set
    fun begin() { pinched = false }
    fun observe(pointerCount: Int) { if (pointerCount > 1) pinched = true }
    fun acceptsDrag() = !pinched
}
