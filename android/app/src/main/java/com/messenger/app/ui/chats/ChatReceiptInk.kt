package com.messenger.app.ui.chats

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** One callback per live burst, never a repeating timer. Cancelled/offscreen rows do not count. */
private object ReceiptInkBatch {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val waiting = mutableListOf<CancellableContinuation<Boolean>>()
    private var scheduled = false
    suspend fun request(): Boolean = suspendCancellableCoroutine { continuation ->
        waiting += continuation
        if (!scheduled) {
            scheduled = true
            handler.postDelayed({
                val batch = waiting.filter { it.isActive }
                waiting.clear()
                scheduled = false
                val animate = ReceiptInkPolicy.batchAnimates(batch.size)
                batch.forEach { if (it.isActive) it.resume(animate) }
            }, ReceiptInkPolicy.batchWindowMillis)
        }
    }
}

internal val LocalReceiptStatusFeed = staticCompositionLocalOf<ReceiptStatusFeed?> { null }

private class ReceiptInkState(status: MessageStatus, visibility: ReceiptVisibility) {
    val observation = ReceiptInkObservation(status, visibility, SystemClock::uptimeMillis)
    var settled by mutableStateOf(status)
    var running by mutableStateOf(false)
    val reveal = Animatable(ReceiptInkPolicy.reveal(status))
    val read = Animatable(if (status == MessageStatus.READ) 1f else 0f)
    val clock = Animatable(if (status == MessageStatus.PENDING) 1f else 0f)
}

@Composable
internal fun receiptTextStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(
    fontSize = CapsuleChatStyle.timeSize.sp, lineHeight = (CapsuleChatStyle.timeSize * 1.3f).sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false), textDirection = TextDirection.Ltr)

/** Constant double-tick geometry; only an observed, forward live change mounts the ink layers. */
@Composable
internal fun MessageReceipt(status: MessageStatus, liveID: String? = null, modifier: Modifier = Modifier) {
    val settings = rememberChatMotionSettings()
    val style = receiptTextStyle()
    val measurer = rememberTextMeasurer()
    val metrics = remember(measurer, style) { measurer.measure(ReceiptInkPolicy.doubleTick, style = style, softWrap = false) }
    val slot = with(LocalDensity.current) { metrics.size.width.toDp() }
    val label = stringResource(MessageReceiptPolicy.label(status))
    val feed = LocalReceiptStatusFeed.current
    val visibility = (feed?.visibility?.current ?: ReceiptVisibility(false, settings.visibilityEpoch, 0))
        .let { it.copy(active = it.active && settings.active) }
    val stamp = feed?.stamp(liveID, status)
    val state = remember(liveID) { ReceiptInkState(status, visibility) }
    LaunchedEffect(status, liveID, visibility, settings.calm, stamp) {
        val change = state.observation.observe(status, visibility, settings.calm, stamp)
        val from = change.from
        val live = liveID != null && change.animate
        if (!live || !ReceiptInkBatch.request() || stamp?.isLive(feed?.visibility?.current ?: visibility) != true) {
            state.settled = status
            state.running = false
            state.reveal.snapTo(ReceiptInkPolicy.reveal(status))
            state.read.snapTo(if (status == MessageStatus.READ) 1f else 0f)
            state.clock.snapTo(if (status == MessageStatus.PENDING) 1f else 0f)
            return@LaunchedEffect
        }
        // Animatables survive retargeting: quick sent -> delivered continues the visible fill.
        state.settled = status
        state.running = true
        val target = ReceiptInkPolicy.reveal(status)
        val revealMoves = state.reveal.value != target
        coroutineScope {
            launch { state.clock.animateTo(0f, tween(120, easing = CubicBezierEasing(.42f, 0f, 1f, 1f))) }
            launch {
                if (revealMoves) {
                    if (from == MessageStatus.PENDING) delay((50 * settings.durationScale).toLong())
                    state.reveal.animateTo(target, tween(if (from == MessageStatus.PENDING && target >= 1f) 300 else 220,
                        easing = CubicBezierEasing(.3f, 0f, .2f, 1f)))
                }
            }
            launch {
                val readTarget = if (status == MessageStatus.READ) 1f else 0f
                if (state.read.value != readTarget) {
                    if (revealMoves) delay((120 * settings.durationScale).toLong())
                    state.read.animateTo(readTarget, tween(320, easing = CubicBezierEasing(.42f, 0f, .58f, 1f)))
                }
            }
        }
        state.running = false
    }
    val muted = CapsuleChatStyle.muted
    val ink = CapsuleChatChrome.delivered
    val readInk = CapsuleChatStyle.ticks
    val currentInterval = state.observation.isCurrent(visibility.active, visibility.epoch)
    Box(modifier.width(slot).clearAndSetSemantics { contentDescription = label }) {
        if (state.running && !settings.calm && currentInterval) {
            Text(MessageReceiptPolicy.glyph(MessageStatus.PENDING), style = style, color = muted, modifier = Modifier.graphicsLayer {
                alpha = state.clock.value
                scaleX = .6f + .4f * state.clock.value
                scaleY = scaleX
            })
            ReceiptInkLayer(style, ink) { state.reveal.value }
            ReceiptInkLayer(style, readInk) { state.read.value }
        } else {
            val settled = if (settings.calm || !currentInterval) status else state.settled
            Text(MessageReceiptPolicy.glyph(settled), style = style, softWrap = false, color = when (settled) {
                MessageStatus.READ -> readInk
                MessageStatus.SENT -> CapsuleChatChrome.sent
                MessageStatus.DELIVERED -> ink
                MessageStatus.PENDING -> muted
                else -> LocalCocoColors.current.danger
            })
        }
    }
}

/** Two opposite RenderNode translations slide the clip while keeping the glyph stationary.
 * Its fixed feather mask is drawn once; no text layout or mask drawing runs per frame. */
@Composable
private fun ReceiptInkLayer(style: TextStyle, color: Color, reveal: () -> Float) {
    val measurer = rememberTextMeasurer()
    val pixels = remember(measurer, style) { measurer.measure(ReceiptInkPolicy.doubleTick, style = style, softWrap = false).size.width.toFloat() }
    val slot = with(LocalDensity.current) { pixels.toDp() }
    val maskWidth = with(LocalDensity.current) { (pixels + 1f).toDp() }
    fun shift(): Float = reveal().coerceIn(0f, 1f).let { if (it > 0f) pixels * (1f - it) else pixels + 1f }
    Box(Modifier.width(maskWidth).graphicsLayer {
        translationX = -shift()
        clip = true
    }.drawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint())
            drawContent()
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent),
                startX = size.width - 1.5f * density, endX = size.width), blendMode = BlendMode.DstIn)
            canvas.restore()
        }
    }) {
        Text(ReceiptInkPolicy.doubleTick, style = style, softWrap = false, color = color, modifier = Modifier.width(slot).graphicsLayer {
            translationX = shift()
        })
    }
}

/** The transparent double-tick run keeps inline text wrapping unchanged across all statuses. */
@Composable
internal fun InlineMessageReceiptText(text: AnnotatedString, receiptOffset: Int?, messageID: String,
    status: MessageStatus, style: TextStyle, color: Color) {
    val measurer = rememberTextMeasurer()
    val receiptStyle = receiptTextStyle()
    val metrics = remember(measurer, receiptStyle) { measurer.measure(ReceiptInkPolicy.doubleTick, style = receiptStyle, softWrap = false) }
    val accessibleText = remember(text, receiptOffset) { MessageReceiptPolicy.inlineAccessibleText(text, receiptOffset) }
    var position by remember(messageID, text) { mutableStateOf<Offset?>(null) }
    Box {
        Text(text, style = style, color = color,
            modifier = Modifier.semantics { this.text = accessibleText }, onTextLayout = { layout ->
            if (receiptOffset != null && receiptOffset >= 0 && receiptOffset < layout.layoutInput.text.length) {
                val glyph = layout.getBoundingBox(receiptOffset)
                val line = layout.getLineForOffset(receiptOffset)
                val next = Offset(glyph.left, layout.getLineBaseline(line) - metrics.firstBaseline)
                if (next != position) position = next
            }
        })
        if (receiptOffset != null) position?.let { at ->
            MessageReceipt(status, messageID, Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) })
        }
    }
}
