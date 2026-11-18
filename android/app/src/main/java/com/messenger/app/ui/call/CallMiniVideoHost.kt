package com.messenger.app.ui.call

import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.zIndex
import com.messenger.app.R
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.call.CallMiniWindowPolicy as P
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The same content/AndroidView remains mounted fullscreen, mini, tucked and x-hidden. */
@Composable
internal fun CallMiniVideoHost(state: CallUiState.Connected, expanded: Boolean, hidden: Boolean,
    memory: CallMiniWindowMemory, onHide: () -> Unit, onReturn: () -> Unit, onToggleMute: () -> Unit,
    content: @Composable (mini: Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        val density = LocalDensity.current
        val containerWidth = maxWidth.value
        val keyboard = WindowInsets.ime.getBottom(density) / density.density
        val area = P.area(maxWidth.value, maxHeight.value, keyboard)
        val direction = LocalLayoutDirection.current
        var placement by remember(state.call.callId) { mutableStateOf(memory.placement(state.call.callId,
            if (direction == LayoutDirection.Rtl) P.Corner.BOTTOM_LEFT else P.Corner.BOTTOM_RIGHT)) }
        var pinch by remember { mutableFloatStateOf(1f) }
        var dragging by remember { mutableStateOf(false) }
        var resizing by remember { mutableStateOf(false) }
        var drag by remember { mutableStateOf<P.Point?>(null) }
        var releaseVelocity by remember { mutableStateOf(P.Point(0f, 0f)) }
        val baseSize = P.size(placement.step, area)
        val miniSize = P.Size(baseSize.width * pinch, baseSize.height * pinch)
        val rest = P.origin(placement, miniSize, area, maxWidth.value)
        val x = remember { Animatable(rest.x) }
        val y = remember { Animatable(rest.y) }
        val pinchReturnX = remember { Animatable(0f) }
        val pinchReturnY = remember { Animatable(0f) }
        val reduced = !android.animation.ValueAnimator.areAnimatorsEnabled()
        val powerSaver = androidx.compose.ui.platform.LocalContext.current.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
        val opacity = remember { Animatable(1f) }
        val presence = remember { Animatable(1f) }
        var pixelsVisible by remember { mutableStateOf(!hidden) }
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val mini = !expanded
        val morph = rememberCallMorph()
        fun reopen() {
            val p = drag ?: P.Point(x.value, y.value)
            val frame = android.graphics.Rect((p.x*density.density).roundToInt(), (p.y*density.density).roundToInt(),
                ((p.x+miniSize.width)*density.density).roundToInt(), ((p.y+miniSize.height)*density.density).roundToInt())
            if (morph != null) morph.expand(from = frame, present = onReturn) else onReturn()
        }
        val drop = callDropShown(LocalCallChrome.current?.state is CallUiState.Reconnecting)
        val returnLabel = stringResource(R.string.call_mini_return, state.call.otherDisplayName)
        val hideLabel = stringResource(R.string.call_mini_hide)
        val showLabel = stringResource(R.string.call_mini_show)
        val hint = stringResource(R.string.call_mini_hint)
        val muteLabel = stringResource(if (state.isMuted) R.string.call_unmute else R.string.call_mute)
        fun store(next: P.Placement) {
            if (next.step != placement.step || next.tucked != placement.tucked) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            placement = next
            memory.store(state.call.callId, next)
        }
        LaunchedEffect(hidden, expanded) {
            if (hidden && !expanded) { presence.animateTo(0f, tween(220)); pixelsVisible = false }
            else { pixelsVisible = true; if (!expanded) presence.snapTo(0f); presence.animateTo(1f, tween(220)) }
        }
        LaunchedEffect(rest, dragging, resizing, expanded, hidden) {
            if (!dragging) {
                if (expanded || resizing || hidden) { x.snapTo(rest.x); y.snapTo(rest.y) }
                else if (reduced) {
                    opacity.animateTo(0f, tween(120)); x.snapTo(rest.x); y.snapTo(rest.y); opacity.animateTo(1f, tween(160))
                }
                else {
                    launch { x.animateTo(rest.x, if (powerSaver) tween(250) else spring(dampingRatio = .84f, stiffness = 240f), initialVelocity = releaseVelocity.x) }
                    launch { y.animateTo(rest.y, if (powerSaver) tween(250) else spring(dampingRatio = .84f, stiffness = 240f), initialVelocity = releaseVelocity.y) }
                }
            }
        }
        val visible = !mini || (!hidden && !placement.tucked)
        // Size changes are event-driven; the renderer is never recreated or polled.
        Box(Modifier.absoluteOffset {
            if (expanded) IntOffset.Zero
            else if (!pixelsVisible) IntOffset(-20000, 0)
            else {
                val p = drag ?: if (resizing) P.Point(rest.x + pinchReturnX.value, rest.y + pinchReturnY.value) else P.Point(x.value, y.value)
                IntOffset((p.x * density.density).roundToInt(), (p.y * density.density).roundToInt())
            }
        }.size(if (expanded) maxWidth else miniSize.width.dp, if (expanded) maxHeight else miniSize.height.dp)
            .graphicsLayer { alpha = if (expanded) 1f else opacity.value * presence.value
                scaleX = if (expanded || reduced) 1f else .92f + .08f * presence.value; scaleY = scaleX }
            .then(if (!visible) Modifier.clearAndSetSemantics {} else Modifier)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(if (expanded) 0.dp else 18.dp))
                .then(if (mini) Modifier.clearAndSetSemantics {} else Modifier)) {
                CompositionLocalProvider(LocalCallRendererVisible provides visible) { content(mini) }
            }
            if (mini && !hidden && !placement.tucked) {
                // Above the native video: gesture target extends 8dp around the frame.
                Box(Modifier.layout { measurable, constraints ->
                    val extra = 8.dp.roundToPx()
                    val target = measurable.measure(Constraints.fixed(constraints.maxWidth + extra * 2, constraints.maxHeight + extra * 2))
                    layout(constraints.maxWidth, constraints.maxHeight) { target.place(-extra, -extra) }
                }.fillMaxSize().testTag("call-mini-window")
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = returnLabel
                        onClick(label = hint) { reopen(); true }
                        customActions = listOf(CustomAccessibilityAction(hideLabel) { onHide(); true },
                            CustomAccessibilityAction(muteLabel) { onToggleMute(); true })
                    }
                    .pointerInput(state.call.callId, placement, baseSize) {
                        detectTapGestures(onTap = { reopen() }, onDoubleTap = { store(placement.copy(step = P.next(placement.step))) })
                    }
                    .pointerInput(state.call.callId, area, baseSize) {
                        try {
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            val policy = CallMiniGesturePolicy().apply { begin() }
                            val start = P.Point(x.value, y.value)
                            val samples = mutableListOf(P.Sample(start, first.uptimeMillis))
                            var position = start
                            var traveled = P.Point(0f, 0f)
                            var moved = false
                            var sawPinch = false
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                policy.observe(maxOf(pressed, event.changes.count { it.previousPressed }))
                                if (policy.pinched) {
                                    if (!sawPinch) {
                                        sawPinch = true
                                        val corner = P.origin(placement, baseSize, area, containerWidth)
                                        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                            pinchReturnX.snapTo(if (reduced) 0f else position.x - corner.x)
                                            pinchReturnY.snapTo(if (reduced) 0f else position.y - corner.y)
                                            launch { pinchReturnX.animateTo(0f, if (powerSaver) tween(250) else spring(dampingRatio = .85f, stiffness = 500f)) }
                                            launch { pinchReturnY.animateTo(0f, if (powerSaver) tween(250) else spring(dampingRatio = .85f, stiffness = 500f)) }
                                        }
                                    }
                                    resizing = true
                                    dragging = false
                                    // Keep the scale when one finger lifts; reset only after ALL lift.
                                    if (pressed > 1) pinch = (pinch * event.calculateZoom()).coerceIn(.6f, 1.8f)
                                    drag = null
                                    moved = true
                                    event.changes.forEach { it.consume() }
                                } else if (policy.acceptsDrag()) {
                                    val pan = event.calculatePan()
                                    if (pan.getDistance() > 0) {
                                        traveled = traveled + P.Point(pan.x / density.density, pan.y / density.density)
                                        val next = start + traveled
                                        if (moved || kotlin.math.hypot(next.x - start.x, next.y - start.y) * density.density > viewConfiguration.touchSlop) {
                                            moved = true; dragging = true
                                            position = P.dragged(next, miniSize, area, containerWidth)
                                            drag = position
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                    val time = event.changes.first().uptimeMillis
                                    samples.add(P.Sample(position, time))
                                    samples.removeAll { time - it.timeMs > 150 }
                                }
                            } while (event.changes.any { it.pressed })
                            if (policy.pinched) store(placement.copy(step = P.nearest(baseSize.width * pinch)))
                            else if (moved) {
                                val velocity = P.velocity(samples)
                                val next = P.release(placement, position, velocity, miniSize, area, containerWidth)
                                val target = P.origin(next, miniSize, area, containerWidth)
                                val springVelocity = P.springVelocity(velocity, position, target)
                                releaseVelocity = P.Point((target.x - position.x) * springVelocity, (target.y - position.y) * springVelocity)
                                scope.launch { x.snapTo(position.x); y.snapTo(position.y) }
                                store(next)
                            }
                            pinch = 1f; drag = null; dragging = false; resizing = false
                        }
                        } finally { pinch = 1f; drag = null; dragging = false; resizing = false }
                    })
                val inner = if (placement.corner.left) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft
                Box(Modifier.align(inner).absoluteOffset(if (placement.corner.left) 6.dp else (-6).dp, (-6).dp).size(44.dp).zIndex(2f), contentAlignment = Alignment.Center) {
                    Surface(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onHide() },
                        modifier = Modifier.size(44.dp).testTag("call-mini-hide"), color = Color.Transparent, shape = CircleShape) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(Modifier.size(24.dp), shape = CircleShape, color = CallGlass, border = BorderStroke(1.dp, CallEdge)) {
                                Icon(CocoIcons.Close, hideLabel, Modifier.padding(5.dp), tint = CallWhite)
                            }
                        }
                    }
                }
                if (state.isMuted) Box(Modifier.align(if (placement.corner.left) AbsoluteAlignment.BottomRight else AbsoluteAlignment.BottomLeft)
                    .clearAndSetSemantics {}
                    .padding(7.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
                    Icon(CocoIcons.MicOff, muteLabel, Modifier.size(13.dp), tint = CallWhite)
                }
                AnimatedVisibility(drop, modifier = Modifier.clearAndSetSemantics {}, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.call_reconnecting), color = CallWhite, fontSize = 11.sp, modifier = Modifier.padding(5.dp))
                }
                }
            }
        }
        if (mini && !hidden && placement.tucked) {
            val tab = P.tab(placement, baseSize, area, maxWidth.value)
            val target = P.tabTarget(tab)
            Box(Modifier.absoluteOffset { IntOffset((target.x * density.density).roundToInt(), (target.y * density.density).roundToInt()) }
                .size(44.dp, 88.dp).testTag("call-mini-tab").clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = showLabel
                    onClick(label = returnLabel) { store(P.untuck(placement)); true }
                    customActions = listOf(CustomAccessibilityAction(returnLabel) { onReturn(); true })
                }.pointerInput(placement) { detectTapGestures { store(P.untuck(placement)) } }
                .pointerInput(placement, area) {
                    var endY = tab.y + 32f
                    detectDragGestures(onDragStart = { endY = tab.y + 32f },
                        onDragEnd = { store(P.untuck(placement, P.fraction(endY, baseSize, area))) },
                        onDragCancel = { store(P.untuck(placement)) },
                        onDrag = { change, _ -> change.consume(); endY = target.y + change.position.y / density.density })
                },
                contentAlignment = Alignment.Center) {
                Surface(Modifier.size(30.dp, 64.dp), shape = RoundedCornerShape(50), color = CallGlass, border = BorderStroke(1.dp, CallEdge)) {
                    Box(contentAlignment = Alignment.Center) { Text(if (placement.corner.left) "›" else "‹", color = CallWhite, fontSize = 24.sp) }
                }
            }
        }
    }
}
