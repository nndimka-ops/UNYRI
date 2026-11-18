package com.messenger.app.data.call

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.atan2

/** Degrees use Display.getRotation's direction, not OrientationEventListener's direction. */
object CallOrientationPolicy {
    fun normalize(degrees: Int) = (degrees % 360 + 360) % 360
    fun physicalDegrees(listenerDegrees: Int) = normalize(360 - listenerDegrees)
    fun physicalFromGravity(x: Float, y: Float, z: Float): Int =
        if (x * x + y * y < .01f || (x * x + y * y) * 4 < z * z) -1
        else normalize(Math.toDegrees(atan2(x, y).toDouble()).roundToInt())
    fun landscape(degrees: Int) = degrees == 90 || degrees == 270

    // stream-webrtc-android:1.3.10 Camera2Session.getFrameOrientation:
    // if (!isCameraFrontFacing) rotation = 360 - rotation;
    // return (cameraOrientation + rotation) % 360;
    fun frameRotation(sensor: Int, front: Boolean, device: Int) =
        normalize(sensor + if (front) device else -device)

    fun tag(screen: Int, physical: Int, stableForMs: Long, preferScreen: Boolean = false): Int = when {
        landscape(screen) -> screen
        stableForMs >= (if (preferScreen) 1_500 else 600) -> physical
        else -> screen
    }

    /** Rotate the already mirrored renderer, preserving its handedness. */
    fun selfViewDelta(screen: Int, tag: Int): Int = normalize(tag - screen).let { if (it > 180) it - 360 else it }
    fun remoteFills(frameWidth: Int, frameHeight: Int, stageWidth: Float, stageHeight: Float): Boolean =
        frameWidth <= 0 || frameHeight <= 0 || (frameWidth > frameHeight) == (stageWidth > stageHeight)

    /** Center crop in the inner renderer's coordinates, before self-view compensation. */
    fun previewCrop(viewWidth: Int, viewHeight: Int, frameAspect: Float): Pair<Float, Float> {
        if (viewWidth <= 0 || viewHeight <= 0 || !frameAspect.isFinite() || frameAspect <= 0) return 1f to 1f
        val viewport = viewWidth.toFloat() / viewHeight
        return if (frameAspect > viewport) frameAspect / viewport to 1f else 1f to viewport / frameAspect
    }
}

/** Stateful Schmitt trigger; timestamps are monotonic milliseconds. Flat/unknown interrupts dwell. */
class CallOrientationHysteresis(initial: Int = 0) {
    var orientation: Int = initial
        private set
    var stableSinceMs: Long = Long.MAX_VALUE
        private set
    private var candidate = -1
    private var candidateSinceMs = 0L

    /** Pausing capture must not count inactive time toward a pending turn. */
    fun interrupt() { candidate = -1 }

    fun update(degrees: Int, zGravity: Float, nowMs: Long): Int {
        if (degrees < 0 || abs(zGravity) > .85f) { candidate = -1; return orientation }
        val angle = CallOrientationPolicy.normalize(degrees)
        val distance = abs((angle - orientation + 540) % 360 - 180)
        if (distance < 60) {
            candidate = -1
            if (stableSinceMs == Long.MAX_VALUE) stableSinceMs = nowMs
            return orientation
        }
        val next = (angle / 90f).roundToInt() * 90 % 360
        if (next != candidate) { candidate = next; candidateSinceMs = nowMs }
        if (nowMs - candidateSinceMs >= 250) {
            orientation = next
            stableSinceMs = nowMs
            candidate = -1
        }
        return orientation
    }
}

data class CallRemoteGeometry(val width: Int = 0, val height: Int = 0, val rotation: Int = 0)

/** Captured atomically for a frame; UI must not combine a new display with an old tag. */
data class CallFrameOrientation(val screen: Int = 0, val tag: Int = 0)

internal fun CallFrameOrientation.selfViewDelta(screenDegrees: Int, previous: Int): Int =
    if (screen == screenDegrees) CallOrientationPolicy.selfViewDelta(screen, tag) else previous
