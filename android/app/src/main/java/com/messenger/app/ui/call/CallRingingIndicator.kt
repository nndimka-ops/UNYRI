package com.messenger.app.ui.call

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.domain.call.CallRingIndicator.Phase
import com.messenger.app.ui.theme.LocalCocoColors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Frame state is read only by drawing; cached native objects never rebuild per frame. */
@Composable
internal fun CallRingingIndicator(
    phase: Phase,
    connectedAtMs: Long? = null,
    answeredAtMs: Long? = null,
    ringbackStartedAtMs: Long? = null,
    visible: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var staticOnly by remember { mutableStateOf(power.isPowerSaveMode || !ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(lifecycle, context) {
        fun refresh() {
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            staticOnly = power.isPowerSaveMode || !ValueAnimator.areAnimatorsEnabled()
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ -> refresh() }
        val powerObserver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        val motionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        lifecycle.addObserver(lifecycleObserver)
        androidx.core.content.ContextCompat.registerReceiver(context, powerObserver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver)
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            context.unregisterReceiver(powerObserver)
            context.contentResolver.unregisterContentObserver(motionObserver)
        }
    }
    val phaseAt = remember(phase) { SystemClock.elapsedRealtime() }
    val frameTime = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val looping = phase == Phase.SENDING || phase == Phase.RINGING
    LaunchedEffect(phase, connectedAtMs, answeredAtMs, visible, foreground, staticOnly) {
        frameTime.longValue = SystemClock.elapsedRealtime()
        if (!visible || !foreground || staticOnly) return@LaunchedEffect
        val until = if (phase == Phase.ANSWERED) maxOf((answeredAtMs ?: phaseAt) + 350L, (connectedAtMs ?: 0L) + 1_200L) else phaseAt
        while (looping || SystemClock.elapsedRealtime() < until) {
            withFrameNanos { frameTime.longValue = SystemClock.elapsedRealtime() }
        }
    }
    val drawing = remember(LocalDensity.current.density) { CallRingDrawingCache() }
    val colors = LocalCocoColors.current
    val accent = 0xFFF1F1F1.toInt()
    val background = colors.callBackground.toArgb()
    val danger = colors.danger.toArgb()
    val warning = colors.warning.toArgb()
    Box(Modifier.size(165.dp, 40.dp).clearAndSetSemantics { }.drawWithCache {
        // Geometry is expressed in the reference 165 x 40 plane. Size/density
        // changes rebuild this cache; frames only change primitive positions.
        val scale = min(size.width / 165f, size.height / 40f)
        onDrawBehind {
            if (visible) drawing.draw(drawContext.canvas.nativeCanvas, scale, phase,
                frameTime.longValue, phaseAt, answeredAtMs, connectedAtMs, ringbackStartedAtMs,
                staticOnly || !foreground, accent, background, danger, warning)
        }
    })
}

/** Paths, paints, rectangles and the static 6/5 dash effect are allocated once. */
private class CallRingDrawingCache {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.75f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(outline).apply { strokeWidth = 3f }
    private val dashedLine = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f) }
    private val wave = Paint(outline)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val phone = Path().apply {
        addRoundRect(RectF(-7.75f, -14f, 7.75f, 14f), 3f, 3f, Path.Direction.CW)
        moveTo(-2.5f, 10.5f); lineTo(2.5f, 10.5f)
        moveTo(-2f, -10.5f); lineTo(2f, -10.5f)
    }
    private val wavePath = Path().apply {
        val bounds = RectF(-17.5f, -17.5f, 17.5f, 17.5f)
        arcTo(bounds, -60f, 120f, true)
        // Independent contours prevent the left wave from gaining a chord.
        arcTo(bounds, 120f, 120f, true)
    }
    private val lockShackle = Path().apply {
        moveTo(-7f, 0f); lineTo(-7f, -6f)
        cubicTo(-7f, -17f, 7f, -17f, 7f, -6f); lineTo(7f, 0f)
    }
    private val lockBody = RectF(-12f, -2f, 12f, 15f)
    private val wifiSlash = Path().apply {
        arcTo(RectF(-5.5f, -5f, 5.5f, 6f), 225f, 90f, true)
        arcTo(RectF(-3f, -1f, 3f, 5f), 225f, 90f, true)
        moveTo(-5f, -5f); lineTo(5f, 5f)
    }
    private val handset = Path().apply {
        moveTo(-5.5f, 0f); quadTo(0f, -5f, 5.5f, 0f)
        lineTo(4f, 3f); lineTo(1.7f, 2f); lineTo(1.7f, .2f)
        quadTo(0f, -.5f, -1.7f, .2f); lineTo(-1.7f, 2f); lineTo(-4f, 3f); close()
    }
    private val triangle = Path().apply {
        moveTo(0f, -5.5f); lineTo(5.5f, 4.5f); lineTo(-5.5f, 4.5f); close()
    }
    private val exclamation = Path().apply { moveTo(0f, -2.5f); lineTo(0f, .8f) }

    fun draw(canvas: Canvas, scale: Float, phase: Phase, now: Long, phaseAt: Long,
             answeredAt: Long?, connectedAt: Long?, ringbackAt: Long?, staticOnly: Boolean,
             accent: Int, background: Int, danger: Int, warning: Int) {
        if (phase == Phase.CANCELLED || scale <= 0f) return
        val save = canvas.save()
        canvas.scale(scale, scale)
        val elapsed = if (staticOnly) 0L else (now - (ringbackAt ?: phaseAt)).coerceAtLeast(0L)
        val cycleMs = elapsed % 3_000L
        if (phase == Phase.ANSWERED) {
            val merge = if (staticOnly) 1f else ((now - (answeredAt ?: phaseAt)) / 350f).coerceIn(0f, 1f)
            val fade = if (connectedAt == null) 1f else if (staticOnly) 0f else
                (1f - ((now - connectedAt - 1_000L).coerceAtLeast(0L) / 200f)).coerceIn(0f, 1f)
            if (merge < 1f) {
                drawPhone(canvas, 14f + 68.5f * merge, (1f - merge) * fade, 0f)
                drawPhone(canvas, 151f - 68.5f * merge, (1f - merge) * fade, 0f)
            }
            val lockSave = canvas.save()
            canvas.translate(82.5f, 20f)
            val lockScale = .6f + .4f * merge
            canvas.scale(lockScale, lockScale)
            outline.color = PRIMARY; outline.alpha = alpha(merge * fade)
            canvas.drawPath(lockShackle, outline)
            if (connectedAt == null) canvas.drawRoundRect(lockBody, 3f, 3f, outline)
            else { fill.color = PRIMARY; fill.alpha = alpha(merge * fade); canvas.drawRoundRect(lockBody, 3f, 3f, fill) }
            canvas.restoreToCount(lockSave)
            canvas.restoreToCount(save)
            return
        }
        when (phase) {
            Phase.SENDING -> {
                // Moving numeric dots: no mutable DashPathEffect or float array.
                fill.color = accent; fill.alpha = 255
                val clip = canvas.save()
                canvas.clipRect(38f, 18f, 127f, 22f)
                val offset = if (staticOnly) 0f else (elapsed % 350L) / 350f * 8.85f
                var x = 38f - 8.85f + offset
                while (x <= 128.5f) { canvas.drawCircle(x, 20f, 1.5f, fill); x += 8.85f }
                canvas.restoreToCount(clip)
            }
            Phase.RINGING -> { line.color = accent; line.alpha = 255; canvas.drawLine(38f, 20f, 127f, 20f, line) }
            Phase.NOT_ONLINE, Phase.NO_ANSWER -> {
                dashedLine.color = accent; dashedLine.alpha = 255
                canvas.drawLine(38f, 20f, 127f, 20f, dashedLine)
            }
            else -> Unit
        }
        drawPhone(canvas, 14f, 1f, 0f)
        val pale = phase == Phase.SENDING || phase == Phase.NOT_ONLINE || phase == Phase.NO_ANSWER
        val rotation = if (phase == Phase.RINGING && !staticOnly && cycleMs < 450L)
            (sin(cycleMs / 90.0 * PI) * 4.584).toFloat() else 0f
        drawPhone(canvas, 151f, if (pale) .4f else 1f, rotation)
        if (phase == Phase.RINGING) {
            wave.color = accent
            if (staticOnly) drawWave(canvas, 1f, .65f)
            else {
                var index = 0
                while (index < 2) {
                    val progress = (cycleMs - index * 250L) / 900f
                    if (progress >= 0f && progress <= 1f) drawWave(canvas, .8f + .5f * progress, .8f * (1f - progress))
                    index++
                }
            }
        }
        if (phase == Phase.NOT_ONLINE || phase == Phase.BUSY || phase == Phase.DECLINED || phase == Phase.FAILED) {
            val badgeSave = canvas.save()
            canvas.translate(155f, 29f) // 11 dp glyph in an 18 dp box
            fill.color = background; fill.alpha = 255; canvas.drawCircle(0f, 0f, 9f, fill)
            when (phase) {
                Phase.NOT_ONLINE -> {
                    outline.color = PRIMARY; outline.alpha = 255; canvas.drawPath(wifiSlash, outline)
                    fill.color = PRIMARY; fill.alpha = 255; canvas.drawCircle(0f, 4f, .8f, fill)
                }
                Phase.FAILED -> {
                    fill.color = warning; fill.alpha = 255; canvas.drawPath(triangle, fill)
                    outline.color = background; outline.alpha = 255; canvas.drawPath(exclamation, outline)
                    fill.color = background; canvas.drawCircle(0f, 2.7f, .7f, fill)
                }
                else -> { fill.color = danger; fill.alpha = 255; canvas.drawPath(handset, fill) }
            }
            canvas.restoreToCount(badgeSave)
        }
        canvas.restoreToCount(save)
    }

    private fun drawPhone(canvas: Canvas, x: Float, opacity: Float, rotation: Float) {
        val save = canvas.save()
        canvas.translate(x, 20f)
        if (rotation != 0f) canvas.rotate(rotation)
        outline.color = PRIMARY; outline.alpha = alpha(opacity)
        canvas.drawPath(phone, outline)
        canvas.restoreToCount(save)
    }
    private fun drawWave(canvas: Canvas, scale: Float, opacity: Float) {
        val save = canvas.save()
        canvas.translate(151f, 20f); canvas.scale(scale, scale)
        wave.alpha = alpha(opacity); canvas.drawPath(wavePath, wave)
        canvas.restoreToCount(save)
    }
    private fun alpha(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).toInt()
    companion object { private const val PRIMARY = 0xFFF1F1F1.toInt() }
}
