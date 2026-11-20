package com.messenger.app.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/** Watches the original message stream, including events consumed by its long press.
 * The menu is in the same window, so installing it never steals this pointer stream. */
@Stable internal class ReactionPressTracker {
    var origin = Offset.Zero
    var position = Offset.Zero
    var pressed = false
    var releasingContinuous = false
        private set
    private var serial = 0L
    private var listeningSerial: Long? = null
    private var listener: ((Offset, Boolean) -> Unit)? = null
    fun event(at: Offset, down: Boolean, wasDown: Boolean) {
        if (down && !wasDown) serial++
        position = at; pressed = down
        if (listeningSerial == serial) {
            releasingContinuous = !down
            try { listener?.invoke(at, !down) }
            finally { releasingContinuous = false }
        }
        if (!down) detach()
    }
    fun attach(block: (Offset, Boolean) -> Unit) {
        listener = block
        listeningSerial = serial.takeIf { pressed }
        if (pressed) block(position, false)
    }
    fun detach() { listener = null; listeningSerial = null }
    fun cancel() { pressed = false; detach() }
}

internal val LocalReactionPressTracker = staticCompositionLocalOf<ReactionPressTracker?> { null }

internal fun Modifier.reactionPressStream(tracker: ReactionPressTracker) =
    onGloballyPositioned { tracker.origin = it.boundsInWindow().topLeft }.pointerInput(tracker) {
        try {
            awaitPointerEventScope {
                var original: androidx.compose.ui.input.pointer.PointerId? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (original == null) original = event.changes.firstOrNull { it.pressed && !it.previousPressed }?.id
                    event.changes.firstOrNull { it.id == original }?.let { change ->
                        tracker.event(tracker.origin + change.position, change.pressed, change.previousPressed)
                        if (!change.pressed) original = null
                    }
                }
            }
        } finally { tracker.cancel() }
    }

@Stable internal class ReactionChipTarget {
    var bounds = Rect.Zero
    var landing by mutableLongStateOf(0L)
}
internal data class ReactionFlight(val token: Long, val owner: String, val emoji: String,
    val source: Offset, val fallback: Offset)

/** Geometry is plain storage; each chip observes only its own one-shot arrival token. */
@Stable internal class ReactionFlightState {
    private val chips = mutableMapOf<Pair<String, String>, ReactionChipTarget>()
    var flight by mutableStateOf<ReactionFlight?>(null)
        private set
    private var serial = 0L
    fun target(owner: String, emoji: String) = chips.getOrPut(owner to emoji) { ReactionChipTarget() }
    fun forget(owner: String, emoji: String, target: ReactionChipTarget) {
        if (chips[owner to emoji] === target) chips.remove(owner to emoji)
    }
    fun launch(owner: String, emoji: String, source: Offset, fallback: Offset) {
        flight = ReactionFlight(++serial, owner, emoji, source, fallback)
    }
    fun destination(item: ReactionFlight): Offset = chips[item.owner to item.emoji]?.bounds
        ?.takeIf { it.width > 0 && it.height > 0 }?.center ?: item.fallback
    fun land(item: ReactionFlight) {
        target(item.owner, item.emoji).landing = item.token
        if (flight?.token == item.token) flight = null
    }
}
internal val LocalReactionFlight = staticCompositionLocalOf<ReactionFlightState?> { null }

@Composable internal fun Modifier.reactionChipLanding(owner: String, emoji: String): Modifier {
    val registry = LocalReactionFlight.current ?: return testTag("reaction_chip_$emoji")
    val target = remember(registry, owner, emoji) { registry.target(owner, emoji) }
    val progress = remember { Animatable(1f) }
    val motion = LocalChatMotionSettings.current
    DisposableEffect(registry, owner, emoji, target) {
        onDispose { registry.forget(owner, emoji, target) }
    }
    LaunchedEffect(target.landing) {
        if (target.landing > 0) {
            progress.snapTo(0f)
            if (motion?.reduceMotion == true) kotlinx.coroutines.withContext(ReactionFadeDurationScale) {
                progress.animateTo(1f, tween(150))
            } else progress.animateTo(1f, tween(150))
        }
    }
    return this.testTag("reaction_chip_$emoji").onGloballyPositioned { target.bounds = it.boundsInWindow() }
        .drawBehind {
            val p = progress.value
            if (p < 1f) drawRoundRect(Color.White.copy(alpha = .30f * (1f - p)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
        }
}

/** Composes once per send; progress is read only in the layer, never by timeline rows. */
@Composable internal fun ReactionFlyToChip(registry: ReactionFlightState, origin: Offset) {
    val item = registry.flight ?: return
    val motion = rememberChatMotionSettings()
    val progress = remember(item.token) { Animatable(0f) }
    val half = with(LocalDensity.current) { 18.dp.toPx() }
    val emojiSize = (30 / LocalDensity.current.fontScale).sp
    LaunchedEffect(item.token) {
        if (!motion.reduceMotion) progress.animateTo(1f, tween(320,
            easing = androidx.compose.animation.core.CubicBezierEasing(.2f, .7f, .2f, 1f)))
        registry.land(item)
    }
    if (!motion.reduceMotion) Box(Modifier.fillMaxSize()) {
        Text(item.emoji, fontSize = emojiSize, modifier = Modifier.size(36.dp).graphicsLayer {
            val p = progress.value
            val destination = registry.destination(item)
            translationX = item.source.x + (destination.x - item.source.x) * p - origin.x - half
            translationY = item.source.y + (destination.y - item.source.y) * p - origin.y - half - 28.dp.toPx() * sin(Math.PI * p).toFloat()
            scaleX = 1f - .35f * p; scaleY = scaleX
            alpha = if (p > .85f) (1f - p) / .15f else 1f
        })
    }
}

/** A finite critically damped spring: settles exactly at 320 ms, without overshooting safe edges. */
internal val ReactionExpansionEasing = androidx.compose.animation.core.Easing { fraction ->
    fun response(t: Float): Float = 1f - (1f + 10f * t) * kotlin.math.exp(-10f * t)
    response(fraction) / response(1f)
}

/** Remove animations keeps only the specified, bounded opacity feedback. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal val ReactionFadeDurationScale = object : androidx.compose.ui.MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** Window metrics stay independent of adjustResize's shortened composition bounds. */
internal fun reactionWindowSize(view: android.view.View): androidx.compose.ui.unit.IntSize {
    val manager = view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        val bounds = manager.currentWindowMetrics.bounds
        return androidx.compose.ui.unit.IntSize(bounds.width(), bounds.height())
    }
    @Suppress("DEPRECATION")
    val metrics = android.util.DisplayMetrics().also { manager.defaultDisplay.getRealMetrics(it) }
    return androidx.compose.ui.unit.IntSize(metrics.widthPixels, metrics.heightPixels)
}

/** A tone cell lives in a popup window; convert its screen position back to the chat's window. */
internal fun reactionBoundsInHostWindow(coordinates: androidx.compose.ui.layout.LayoutCoordinates,
    host: android.view.View): Rect {
    val screen = coordinates.localToScreen(Offset.Zero)
    val onScreen = IntArray(2).also(host::getLocationOnScreen)
    val inWindow = IntArray(2).also(host::getLocationInWindow)
    val left = screen.x - onScreen[0] + inWindow[0]
    val top = screen.y - onScreen[1] + inWindow[1]
    return Rect(left, top, left + coordinates.size.width, top + coordinates.size.height)
}
