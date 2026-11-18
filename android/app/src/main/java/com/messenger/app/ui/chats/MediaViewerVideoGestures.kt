package com.messenger.app.ui.chats

import android.content.Context
import android.os.SystemClock
import android.view.*
import androidx.media3.common.Player

/** Gesture recognition is event driven; only a pending hold has a delayed callback. */
internal class ViewerVideoGestureView(context: Context) : View(context) {
    var player: Player? = null
    var toggleChrome: () -> Unit = {}
    var seek: (Int, Boolean) -> Unit = { _, _ -> }
    var holdChanged: (Float?) -> Unit = {}
    var dismiss: () -> Unit = {}
    var dragChanged: (Float, Float) -> Unit = { _, _ -> }
    private var velocity: VelocityTracker? = null
    private var vertical = false
    private var multiTouch = false
    var fillChanged: (Boolean) -> Unit = {}
    var canFill: () -> Boolean = { false }
    private val dp = resources.displayMetrics.density
    private var downX = 0f; private var downY = 0f
    private var holding = false; private var baseSpeed = 1f; private var heldSpeed = 1f
    private var lastSeek = Long.MIN_VALUE / 2
    private var suppressSingle = false
    private var pinchScale = 1f; private var pinchDecided = false
    private val hold = Runnable {
        val p = player
        if (p?.isPlaying == true) {
            holding = true; baseSpeed = p.playbackParameters.speed; heldSpeed = VideoPolicy.holdRate(baseSpeed, 0f)
            p.setPlaybackSpeed(heldSpeed); holdChanged(heldSpeed); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (holding || pinchDecided) return true
            val side = VideoPolicy.zone(event.x / width.coerceAtLeast(1))
            if (side == 0) { suppressSingle = true; toggleChrome() }
            else if (SystemClock.uptimeMillis() - lastSeek < 700) { suppressSingle = true; step(side) }
            return true
        }
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (!suppressSingle && !holding && !pinchDecided) toggleChrome()
            suppressSingle = false; return true
        }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            val side = VideoPolicy.zone(event.x / width.coerceAtLeast(1))
            if (side != 0) step(side)
            suppressSingle = true; return true
        }
    }).apply { setIsLongpressEnabled(false) }
    private fun step(side: Int) { val first = SystemClock.uptimeMillis() - lastSeek >= 700; lastSeek = SystemClock.uptimeMillis(); seek(side, first) }
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { removeCallbacks(hold); pinchScale = 1f; pinchDecided = false; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchScale *= detector.scaleFactor
            if (!pinchDecided && canFill()) VideoPolicy.pinchFill(pinchScale)?.let { pinchDecided = true; fillChanged(it) }
            return true
        }
    })
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { velocity?.recycle(); velocity = VelocityTracker.obtain(); downX = event.x; downY = event.y; suppressSingle = false; pinchDecided = false; vertical = false; multiTouch = false; postDelayed(hold, 350) }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) { removeCallbacks(hold); multiTouch = true; if (vertical) dragChanged(0f, 0f); vertical = false }
        velocity?.addMovement(event)
        pinch.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (!holding && !multiTouch && event.pointerCount == 1 && !pinch.isInProgress) {
                val dx = kotlin.math.abs(event.x - downX); val dy = kotlin.math.abs(event.y - downY)
                if (dx > 8 * dp && dx > dy * 1.5f) { removeCallbacks(hold); return false }
                if (dy > 8 * dp && dy > dx * 1.5f) { vertical = true; removeCallbacks(hold) }
            }

            if (vertical) dragChanged(0f, event.y - downY)
            if (holding) {
                val rate = VideoPolicy.holdRate(baseSpeed, (event.x - downX) / dp)
                if (rate != heldSpeed) { heldSpeed = rate; player?.setPlaybackSpeed(rate); holdChanged(rate); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            } else if (kotlin.math.abs(event.x - downX) > 8 * dp || kotlin.math.abs(event.y - downY) > 8 * dp) removeCallbacks(hold)
        }
        if (!holding && !vertical && !pinch.isInProgress && !pinchDecided) detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(hold)
            velocity?.computeCurrentVelocity(1000)
            if (vertical && event.actionMasked == MotionEvent.ACTION_UP && viewerDismisses((event.y - downY) / dp, (velocity?.yVelocity ?: 0f) / dp, height / dp)) dismiss()
            else if (vertical) dragChanged(0f, 0f)
            velocity?.recycle(); velocity = null
            if (holding) { player?.setPlaybackSpeed(baseSpeed); holdChanged(null); holding = false }
        }
        return true
    }
    override fun onDetachedFromWindow() { velocity?.recycle(); velocity = null; removeCallbacks(hold); if (holding) player?.setPlaybackSpeed(baseSpeed); super.onDetachedFromWindow() }
}
