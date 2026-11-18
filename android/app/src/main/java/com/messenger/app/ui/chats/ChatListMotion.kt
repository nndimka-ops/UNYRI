package com.messenger.app.ui.chats

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** iOS response is the natural period, not the spring's settling duration. */
internal fun <T> chatSpring(response: Float, damping: Float): SpringSpec<T> =
    spring(dampingRatio = damping, stiffness = (2f * Math.PI.toFloat() / response).let { it * it })

/** Consume hidden events as well, so returning to a mounted row cannot replay catch-up. */
internal fun consumeRowMotion(signal: ChatRowMotionSignal?, clock: ChatListMotionClock, active: Boolean): Boolean =
    signal != null && clock.consume(signal.token) && active

@Composable
internal fun ChatArchiveDeparture(leaving: Boolean, calm: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var width by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    LaunchedEffect(leaving, calm) {
        if (calm) progress.snapTo(if (leaving) 1f else 0f)
        else progress.animateTo(if (leaving) 1f else 0f, tween(if (leaving) 260 else 200))
    }
    Box(modifier.onSizeChanged { width = it.width }.graphicsLayer { translationX = direction * progress.value * width }) { content() }
}

@Composable
internal fun ChatListRowMotion(signal: ChatRowMotionSignal?, clock: ChatListMotionClock, calm: Boolean,
    active: Boolean, durationScale: Float = 1f, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scale = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    var sweep by remember { mutableStateOf(false) }
    LaunchedEffect(signal?.token, active, calm) {
        scale.snapTo(1f); glow.snapTo(0f); sweep = false
        if (!consumeRowMotion(signal, clock, active) || signal == null) return@LaunchedEffect
        if (signal.lift && !calm) launch {
            scale.animateTo(1.02f, tween(160, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
            delay((40 * durationScale).toLong())
            scale.animateTo(1f, chatSpring(.36f, .78f))
        }
        if (signal.glow || signal.sweep && calm) launch {
            glow.animateTo(.06f, tween(150))
            glow.animateTo(0f, tween(450))
        }
        if (signal.sweep && !calm) {
            sweep = true
            delay((1250 * durationScale).toLong())
            sweep = false
        }
    }
    Box(modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .drawWithContent { drawContent(); if (glow.value > 0f) drawRoundRect(Color.White.copy(alpha = glow.value), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx())) }) {
        content()
        if (sweep) AndroidView(factory = { SweepView(it) }, modifier = Modifier.matchParentSize().clip(RoundedCornerShape(18.dp)).clearAndSetSemantics {})
    }
}

/** All continuous typing motion uses hardware View properties; Compose never ticks. */
@Composable
internal fun ChatTypingWave(color: Color, calm: Boolean, durationScale: Float = 1f) {
    AndroidView(factory = { TypingMotionView(it, false) }, update = { it.configure(color.toArgb(), calm, durationScale) },
        onRelease = { it.stop() }, modifier = Modifier.width(18.dp).height(12.dp).clearAndSetSemantics {})
}

@Composable
internal fun ChatTypingRing(color: Color, calm: Boolean, durationScale: Float = 1f, modifier: Modifier = Modifier) {
    AndroidView(factory = { TypingMotionView(it, true) }, update = { it.configure(color.toArgb(), calm, durationScale) },
        onRelease = { it.stop() }, modifier = modifier.clearAndSetSemantics {})
}

internal class TypingMotionView(context: Context, private val ring: Boolean) : FrameLayout(context) {
    private val elements = List(if (ring) 1 else 3) { View(context).also(::addView) }
    private var tone = 0
    private var quiet = true
    private var configured = false
    private var running = false
    private var generation = 0
    private var durationScale = 1f
    private var visible = false
    private val pending = ArrayList<Runnable>()
    private val dp = resources.displayMetrics.density
    init { clipChildren = false; clipToPadding = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun configure(color: Int, calm: Boolean, scale: Float) {
        if (configured && color == tone && calm == quiet && scale == durationScale) return
        tone = color; quiet = calm; durationScale = scale; configured = true
        elements.forEach { view -> view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (ring) { setColor(android.graphics.Color.TRANSPARENT); setStroke(max(1, (1.5f * dp).toInt()), color) }
            else setColor(color)
        } }
        stop(); start()
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        elements.forEachIndexed { index, child ->
            if (ring) child.layout(0, 0, width, height)
            else {
                val size = (4 * dp).toInt(); val x = ((width - 18 * dp) / 2 + index * 7 * dp).toInt()
                val y = (height - size) / 2; child.layout(x, y, x + size, y + size)
            }
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visible = isVisible
        if (isVisible) start() else stop()
    }
    fun stop() {
        generation++; running = false
        pending.forEach { removeCallbacks(it) }; pending.clear()
        elements.forEachIndexed { index, view ->
            view.animate().cancel(); view.translationY = 0f; view.scaleX = 1f; view.scaleY = 1f
            view.alpha = if (ring) .22f else floatArrayOf(.45f, .7f, 1f)[index]
        }
    }
    private fun schedule(after: Long, action: () -> Unit) {
        val task = object : Runnable { override fun run() { pending.remove(this); action() } }
        pending += task
        postDelayed(task, after)
    }
    private fun start() {
        if (!configured || !isAttachedToWindow || !visible || running) return
        running = true
        val ticket = generation
        elements.forEachIndexed { index, view ->
            view.translationY = 0f; view.scaleX = 1f; view.scaleY = 1f
            if (quiet || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
                view.alpha = if (ring) .22f else floatArrayOf(.45f, .7f, 1f)[index]; return@forEachIndexed
            }
            if (ring) {
                view.alpha = .10f; view.scaleX = .97f; view.scaleY = .97f
                breathe(view, ticket, true)
            } else { view.alpha = .35f; schedule((index * 150L * durationScale).toLong()) { if (valid(ticket)) wave(view, ticket) } }
        }
    }
    private fun valid(ticket: Int) = ticket == generation && isAttachedToWindow && visible && !quiet && android.animation.ValueAnimator.areAnimatorsEnabled()
    private fun wave(view: View, ticket: Int) {
        if (!valid(ticket)) return
        view.animate().translationY(-2.5f * dp).alpha(1f).setDuration(300).setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (valid(ticket)) view.animate().translationY(0f).alpha(.35f).setDuration(300)
                    .withEndAction { schedule((600 * durationScale).toLong()) { if (valid(ticket)) wave(view, ticket) } }.start()
            }.start()
    }
    private fun breathe(view: View, ticket: Int, up: Boolean) {
        if (!valid(ticket)) return
        val scale = if (up) 1.03f else .97f
        view.animate().scaleX(scale).scaleY(scale).alpha(if (up) .30f else .10f).setDuration(1800)
            .setInterpolator(AccelerateDecelerateInterpolator()).withEndAction { breathe(view, ticket, !up) }.start()
    }
}

private class SweepView(context: Context) : FrameLayout(context) {
    private val band = View(context).also { child ->
        child.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x00ffffff, 0x17ffffff, 0x00ffffff))
        addView(child)
    }
    private var played = false
    init { clipChildren = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val bandWidth = (width * .44f).toInt()
        band.layout(0, 0, bandWidth, height)
        if (!played && width > 0) {
            played = true; band.translationX = -bandWidth.toFloat()
            band.animate().translationX(width.toFloat()).setStartDelay(220).setDuration(900)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
    }
    override fun onDetachedFromWindow() { band.animate().cancel(); super.onDetachedFromWindow() }
}

internal enum class ChatPresenceState { Unknown, Offline, Online }

@Composable
internal fun ChatPresenceDot(state: ChatPresenceState, calm: Boolean, color: Color, cutout: Color, durationScale: Float = 1f, modifier: Modifier = Modifier) {
    var previous by remember { mutableStateOf(state) }
    val alpha = remember { Animatable(if (state == ChatPresenceState.Online) 1f else 0f) }
    val scale = remember { Animatable(1f) }
    var ripple by remember { mutableStateOf(false) }
    LaunchedEffect(state, calm) {
        val old = previous; previous = state
        ripple = false
        when {
            old == ChatPresenceState.Offline && state == ChatPresenceState.Online -> {
                if (calm) { scale.snapTo(1f); alpha.animateTo(1f, tween(200)) }
                else {
                    scale.snapTo(.3f); alpha.snapTo(1f); ripple = true
                    launch { scale.animateTo(1f, chatSpring(.34f, .6f)) }
                    delay((750 * durationScale).toLong()); ripple = false
                }
            }
            old == ChatPresenceState.Online && state == ChatPresenceState.Offline -> alpha.animateTo(0f, tween(200))
            else -> { scale.snapTo(1f); alpha.snapTo(if (state == ChatPresenceState.Online) 1f else 0f) }
        }
    }
    Box(modifier.size(10.dp), contentAlignment = Alignment.Center) {
        if (ripple) AndroidView(factory = { PresenceRippleView(it, color.toArgb()) },
            modifier = Modifier.requiredSize(28.dp).clearAndSetSemantics {})
        Box(Modifier.size(10.dp).graphicsLayer { this.alpha = alpha.value; scaleX = scale.value; scaleY = scale.value }
            .background(color, CircleShape).border(2.dp, cutout, CircleShape))
    }
}

private class PresenceRippleView(context: Context, color: Int) : FrameLayout(context) {
    private val circle = View(context).also { child ->
        child.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(android.graphics.Color.TRANSPARENT)
            setStroke(max(1, (1.2f * resources.displayMetrics.density).toInt()), color)
        }; addView(child)
    }
    private var played = false
    init { clipChildren = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        circle.layout(0, 0, width, height)
        if (!played && width > 0) {
            played = true; circle.scaleX = 1f / 2.8f; circle.scaleY = 1f / 2.8f; circle.alpha = .6f
            circle.animate().scaleX(1f).scaleY(1f).alpha(0f).setStartDelay(50).setDuration(700)
                .setInterpolator(PathInterpolator(.2f, .6f, .3f, 1f)).start()
        }
    }
    override fun onDetachedFromWindow() { circle.animate().cancel(); super.onDetachedFromWindow() }
}

/** A permanent badge slot keeps the time above it at a fixed position. */
@Composable
internal fun ChatUnreadOdometer(count: Int, animate: Boolean, calm: Boolean, color: Color, ink: Color) {
    val text = if (count > 99) "99+" else count.toString()
    val description = stringResource(com.messenger.app.R.string.chat_list_unread_count, count)
    Box(Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).clearAndSetSemantics {
        if (count > 0) contentDescription = description
    }, contentAlignment = Alignment.Center) {
        if (count > 0) Row(Modifier.background(color, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            text.forEachIndexed { index, digit ->
                key(text.length - 1 - index) {
                    AnimatedContent(targetState = digit, transitionSpec = {
                        if (!animate || calm) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        else (slideInVertically(chatSpring(.34f, .72f)) { it } + fadeIn(tween(180))) togetherWith
                            (slideOutVertically(chatSpring(.34f, .72f)) { -it } + fadeOut(tween(180)))
                    }, label = "unread digit") { value ->
                        Text(value.toString(), color = ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

internal data class ChatSwipeAction(val kind: String, val title: String, val icon: ImageVector, val tint: Color,
    val result: ImageVector = icon)

@Composable
internal fun ChatSwipeRow(actions: List<ChatSwipeAction>, isOpen: Boolean, enabled: Boolean, calm: Boolean,
    touchExploration: Boolean, durationScale: Float = 1f, onBegin: () -> Unit, onClose: () -> Unit, onCommit: (String) -> Unit,
    content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current.density
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    val width = ChatSwipePolicy.openWidth(actions.size)
    var raw by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var velocity by remember { mutableFloatStateOf(0f) }
    var morphed by remember { mutableStateOf<String?>(null) }
    var pulsing by remember { mutableStateOf<String?>(null) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    val latestOpen by rememberUpdatedState(isOpen)
    val begin by rememberUpdatedState(onBegin)
    val close by rememberUpdatedState(onClose)
    val commitAction by rememberUpdatedState(onCommit)
    fun snap(target: Float, committing: Boolean = false) {
        snapJob?.cancel()
        snapJob = scope.launch {
            val value = Animatable(raw)
            value.animateTo(target, if (calm) tween(180, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
                else if (committing) chatSpring(.38f, .86f)
                else if (target > 0) chatSpring(.32f, .74f) else chatSpring(.3f, .9f)) { raw = this.value }
        }
    }
    fun pulse(kind: String) {
        if (!calm) scope.launch { pulsing = kind; delay((140 * durationScale).toLong()); if (pulsing == kind) pulsing = null }
    }
    fun commit(kind: String) {
        if (morphed != null) return
        morphed = kind
        if (!armed) pulse(kind)
        scope.launch {
            delay((300 * durationScale).toLong())
            armed = false; snap(0f, true); close(); commitAction(kind)
            delay((450 * durationScale).toLong()); morphed = null
        }
    }
    LaunchedEffect(isOpen, enabled, touchExploration) {
        if ((!isOpen && !dragging && morphed == null) || !enabled || touchExploration) {
            armed = false; snap(0f)
            if ((!enabled || touchExploration) && isOpen) close()
        }
    }
    val distance = ChatSwipePolicy.displayed(raw, width, armed)
    val progress = (distance / width).coerceIn(0f, 1f)
    val gesture = Modifier.pointerInput(enabled, touchExploration, direction, width) {
        if (!enabled || touchExploration) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (morphed != null) return@awaitEachGesture
            val tracker = VelocityTracker(); tracker.addPosition(down.uptimeMillis, down.position)
            var dx = 0f; var dy = 0f; var started = false
            val base = if (latestOpen || raw > 0f) width else 0f
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) { released = true; break }
                    if (change.isConsumed) break
                    val delta = change.positionChange(); dx += delta.x; dy += delta.y
                    if (!started) {
                        if (max(abs(dx), abs(dy)) < viewConfiguration.touchSlop) continue
                        if (abs(dx) <= abs(dy) * 1.35f || base == 0f && dx * direction <= 0f) break
                        snapJob?.cancel(); started = true; dragging = true; begin()
                    }
                    change.consume()
                    raw = base + dx * direction / density
                    velocity = tracker.calculateVelocity().x * direction / density
                    val next = ChatSwipePolicy.armed(raw, width, armed)
                    if (next != armed) {
                        armed = next
                        if (next) { CocoHaptics.select(view); actions.firstOrNull()?.let { pulse(it.kind) } }
                    }
                }
            } finally {
                if (started) {
                    dragging = false
                    val outcome = if (!released) ChatSwipePolicy.Release.Close else
                        ChatSwipePolicy.release(raw, tracker.calculateVelocity().x * direction / density, width, armed)
                    velocity = 0f
                    when (outcome) {
                        ChatSwipePolicy.Release.Commit -> actions.firstOrNull()?.let { commit(it.kind) }
                        ChatSwipePolicy.Release.Open -> { armed = false; snap(width); begin() }
                        ChatSwipePolicy.Release.Close -> { armed = false; snap(0f); close() }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().clipToBounds().then(gesture)) {
        if (distance > .5f) Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(distance.dp).fillMaxHeight().graphicsLayer {
                if (dragging && !calm) {
                    scaleX = 1f + min(.2f, abs(velocity) / 2500f)
                    scaleY = 1f - min(.08f, abs(velocity) / 6000f)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (direction < 0) 1f else 0f, .5f)
                }
            }.clearAndSetSemantics {}) {
                actions.forEachIndexed { index, action ->
                    val widened = armed && index == 0
                    val hidden = armed && index != 0
                    val diameter = 44f * max(.35f, progress)
                    val capsuleWidth = if (widened) max(diameter, distance - 12f) else diameter
                    val boxWidth = max(capsuleWidth, 64f)
                    val center = if (widened) distance / 2 else distance * (index + .5f) / actions.size
                    val shift = -(center - boxWidth / 2)
                    val pulse by androidx.compose.animation.core.animateFloatAsState(
                        if (pulsing == action.kind) 1.18f else 1f,
                        if (calm) tween(180) else chatSpring(.28f, .66f), label = "swipe pulse")
                    Column(Modifier.align(Alignment.CenterEnd).width(boxWidth.dp).offset(x = shift.dp)
                        .graphicsLayer { alpha = if (hidden) 0f else 1f; scaleX = if (hidden) .6f else 1f; scaleY = scaleX }
                        .clickable(enabled = !dragging && morphed == null && !hidden) { commit(action.kind) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val colors = LocalCocoColors.current
                        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(capsuleWidth.dp, diameter.dp)
                                .background(if (calm) colors.elevated else colors.elevated.copy(alpha = .84f), CircleShape)
                                .border(.5.dp, Color.White.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                                // Icon is above its own capsule; no shared blur can cover it.
                                androidx.compose.animation.Crossfade(targetState = widened || morphed == action.kind,
                                    animationSpec = if (calm) tween(180) else chatSpring(.28f, .66f), label = "swipe ink") { filled ->
                                    Icon(if (filled) action.result else action.icon, null,
                                        tint = action.tint, modifier = Modifier.size(19.dp).graphicsLayer {
                                            scaleX = max(.35f, progress) * pulse; scaleY = scaleX
                                        })
                                }
                            }
                        }
                        Text(action.title, color = colors.secondaryText, fontSize = 11.sp, maxLines = 1,
                            modifier = Modifier.graphicsLayer { alpha = ((progress - .6f) / .4f).coerceIn(0f, 1f) })
                    }
                }
            }
        }
        Box(Modifier.graphicsLayer { translationX = direction * distance * density }) { content() }
    }
}

private val swipeArtwork = HashMap<String, ImageVector>()
internal fun chatSwipeIcon(kind: String, alternate: Boolean, filled: Boolean): ImageVector =
    swipeArtwork.getOrPut("$kind|$alternate|$filled") {
        ImageVector.Builder("$kind-$alternate-$filled", 24.dp, 24.dp, 24f, 24f).apply {
            val ink = SolidColor(Color.Black)
            val fill = if (filled) ink else null
            val stroke = if (filled) null else ink
            when (kind) {
                "pin" -> {
                    path(fill = fill, stroke = stroke, strokeLineWidth = 1.8f) {
                        moveTo(7f, 3f); lineTo(17f, 3f); lineTo(16f, 11f); lineTo(20f, 15f)
                        lineTo(20f, 17f); lineTo(4f, 17f); lineTo(4f, 15f); lineTo(8f, 11f); close()
                    }
                    path(fill = null, stroke = ink, strokeLineWidth = 1.8f) { moveTo(12f, 17f); lineTo(12f, 23f) }
                }
                "mute" -> path(fill = fill, stroke = stroke, strokeLineWidth = 1.8f) {
                    moveTo(12f, 3f); curveTo(8f, 3f, 5f, 6f, 5f, 10f); lineTo(5f, 15f)
                    lineTo(3f, 18f); lineTo(21f, 18f); lineTo(19f, 15f); lineTo(19f, 10f)
                    curveTo(19f, 6f, 16f, 3f, 12f, 3f); close()
                    moveTo(9f, 20f); curveTo(10f, 23f, 14f, 23f, 15f, 20f)
                }
                else -> if (alternate) {
                    path(fill = fill, stroke = stroke, strokeLineWidth = 1.8f) {
                        moveTo(3f, 12f); lineTo(7f, 12f); lineTo(9f, 16f); lineTo(15f, 16f)
                        lineTo(17f, 12f); lineTo(21f, 12f); lineTo(21f, 21f); lineTo(3f, 21f); close()
                    }
                    path(fill = null, stroke = ink, strokeLineWidth = if (filled) 2.5f else 1.8f) {
                        moveTo(12f, 13f); lineTo(12f, 2f); moveTo(8f, 6f); lineTo(12f, 2f); lineTo(16f, 6f)
                    }
                } else {
                    path(fill = fill, stroke = stroke, strokeLineWidth = 1.8f,
                        pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd) {
                        moveTo(3f, 3f); lineTo(21f, 3f); lineTo(21f, 8f); lineTo(3f, 8f); close()
                        moveTo(4f, 10f); lineTo(20f, 10f); lineTo(20f, 22f); lineTo(4f, 22f); close()
                        moveTo(9f, 12f); lineTo(15f, 12f); lineTo(15f, 14f); lineTo(9f, 14f); close()
                    }
                }
            }
            if (alternate && kind != "archive") path(fill = null, stroke = ink, strokeLineWidth = 1.8f) {
                moveTo(2f, 2f); lineTo(22f, 22f)
            }
        }.build()
    }
