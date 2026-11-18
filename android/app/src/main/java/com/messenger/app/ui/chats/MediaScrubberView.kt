package com.messenger.app.ui.chats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.messenger.app.R
import kotlin.math.abs

/** Touch area and track are both inside this 44dp view. No idle callback. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class MediaScrubberView(context: Context) : View(context) {
    var canResume: () -> Boolean = { true }
    var player: Player? = null
        set(value) {
            if (field === value) return
            seeks?.cancel(); dragging = false; resume = false; field = value
            seeks = value?.let { p -> VideoSeekSession(RenderedVideoSeekBackend(p, VideoSeekTimeout { delayMillis, action ->
                val callback = Runnable(action)
                postDelayed(callback, delayMillis)
                val cancel: () -> Unit = { removeCallbacks(callback); Unit }
                cancel
            }, isScrubbing = { dragging }) { duration }) { if (canResume()) p.play() } }
        }
    private var seeks: VideoSeekSession? = null
    var onScrubbing: (Boolean) -> Unit = {}
    var onPosition: (Long) -> Unit = {}
    var onFine: (Float) -> Unit = {}
    var duration = 0L
    var position = 0L
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downX = 0f; private var downY = 0f; private var downAt = 0L
    private var baseX = 0f; private var base = 0L; private var factor = 1f
    private var dragging = false; private var resume = false
    private var edge = -1
    private var bubble: android.widget.PopupWindow? = null
    private var bubbleText: android.widget.TextView? = null
    private fun showBubble(fingerY: Float) {
        if (bubble == null) {
            val text = android.widget.TextView(context).apply { setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt()); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xdd202020.toInt()); cornerRadius = 14 * dp } }
            bubbleText = text
            bubble = android.widget.PopupWindow(text, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false).apply { isTouchable = false; isClippingEnabled = true }
        }
        val text = bubbleText ?: return
        text.text = videoTime(position) + if (factor < 1f) "\n" + context.getString(R.string.media_fine_scrubbing, when (factor) { .5f -> "½"; .25f -> "¼"; else -> "⅛" }) else ""
        text.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val location = IntArray(2); getLocationInWindow(location)
        val status = rootWindowInsets?.systemWindowInsetTop ?: 0
        val bottom = location[1] + height / 2 + minOf(-12 * dp, fingerY - height / 2 - 12 * dp)
        val y = maxOf(status + 8 * dp, bottom - text.measuredHeight).toInt()
        val x = (location[0] + x(position) - text.measuredWidth / 2).toInt().coerceAtLeast((8 * dp).toInt())
        val popup = bubble ?: return
        if (popup.isShowing) popup.update(x, y, text.measuredWidth, text.measuredHeight) else popup.showAtLocation(this, Gravity.TOP or Gravity.LEFT, x, y)
    }

    private val accessibility get() = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    init { isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; minimumHeight = (44 * dp).toInt() }
    private val track get() = (width - 16 * dp).coerceAtLeast(1f)
    private fun x(value: Long) = 8 * dp + track * (if (duration > 0) value.toFloat() / duration else 0f).coerceIn(0f, 1f)
    private fun target(x: Float) = ((x - 8 * dp) / track * duration).toLong().coerceIn(0, duration.coerceAtLeast(0))
    override fun onDraw(canvas: Canvas) {
        val y = height / 2f; val end = x(position)
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = 3 * dp
        paint.color = 0x55ffffff; canvas.drawLine(8 * dp, y, width - 8 * dp, y, paint)
        paint.color = Color.WHITE; canvas.drawLine(8 * dp, y, end, y, paint)
        canvas.drawCircle(end, y, (if (dragging) 10 else 5) * dp, paint)
    }
    private fun seek(target: Long) { seeks?.offer(VideoSeekRequest(target, exact = false)) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || duration <= 0 || accessibility?.isTouchExplorationEnabled == true) {
            seeks?.cancel(); bubble?.dismiss()
            if (dragging) onScrubbing(false)
            dragging = false; resume = false
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; downAt = event.eventTime; baseX = downX; base = position; factor = 1f; dragging = false; edge = -1; parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(event.x - downX) > 4 * dp || abs(event.y - downY) > 4 * dp)) {
                    dragging = true; resume = player?.playWhenReady == true; player?.pause(); onScrubbing(true)
                }
                if (dragging) {
                    val nextFactor = VideoPolicy.scrubFactor((event.y - downY) / dp)
                    if (factor != nextFactor) { base = position; baseX = event.x; factor = nextFactor }
                    position = VideoPolicy.scrub(base, event.x - baseX, track, duration, factor)
                    onPosition(position); onFine(factor); seek(position); showBubble(event.y); invalidate()
                    val nextEdge = if (position == 0L) 0 else if (position == duration) 1 else -1
                    if (nextEdge != -1 && nextEdge != edge) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    edge = nextEdge
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                bubble?.dismiss()
                val wasDragging = dragging
                dragging = false
                player?.restoreExactVideoSeek()
                if (wasDragging) {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) position = base
                    seeks?.offer(VideoSeekRequest(position, exact = true, resume = resume))
                } else if (event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - downAt < 250 &&
                    VideoPolicy.tapSeeks(position, target(event.x), (event.x - x(position)) / dp)) {
                    position = target(event.x); seeks?.offer(VideoSeekRequest(position, exact = true))
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                dragging = false; onScrubbing(false); onFine(1f); onPosition(position); parent?.requestDisallowInterceptTouchEvent(false); invalidate(); return true
            }
        }
        return true
    }
    override fun onDetachedFromWindow() { dragging = false; resume = false; seeks?.cancel(); bubble?.dismiss(); super.onDetachedFromWindow() }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.contentDescription = context.getString(R.string.media_video_position, videoTime(position), videoTime(VideoPolicy.total(duration)))
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, duration.toFloat(), position.toFloat())
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.isClickable = false
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val next = when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> return true
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> videoSeekTarget(position, 10_000, duration)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> videoSeekTarget(position, -10_000, duration)
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> (arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.toLong() ?: position).coerceIn(0, duration.coerceAtLeast(0))
            else -> return super.performAccessibilityAction(action, arguments)
        }
        position = next; seeks?.offer(VideoSeekRequest(next, exact = true)); onPosition(next); invalidate(); sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED); return true
    }
}
