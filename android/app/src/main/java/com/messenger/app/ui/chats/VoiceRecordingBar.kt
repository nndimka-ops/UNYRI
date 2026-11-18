package com.messenger.app.ui.chats

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.data.attachment.VoiceFormat
import com.messenger.app.data.attachment.VoiceRecorder
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.components.cocoChromeMaterial
import com.messenger.app.ui.components.rememberCocoComposerSurfaceStyle
import kotlinx.coroutines.delay

/** Tenths are observed here, never by the chat timeline. Paused/hidden clocks do no work. */
@Composable
private fun VoiceRecordingClock(recorder: VoiceRecorder, paused: Boolean) {
    val motion = rememberChatMotionSettings()
    var millis by remember(recorder) { mutableStateOf(recorder.recordedDurationMs) }
    LaunchedEffect(recorder, paused, motion.active) {
        millis = recorder.recordedDurationMs
        if (motion.active && !paused) while (true) { delay(100); millis = recorder.recordedDurationMs }
    }
    val seconds = millis / 1000
    val separator = java.text.DecimalFormatSymbols.getInstance(LocalConfiguration.current.locales[0]).decimalSeparator
    Row(Modifier.clearAndSetSemantics { contentDescription = VoiceFormat.durationText(millis) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        val pulse = if (paused) .35f else if (motion.calm) 1f else
            (.6 + .4 * kotlin.math.cos(SystemClock.uptimeMillis() / 1000.0 * 4.5)).toFloat()
        Box(Modifier.size(9.dp).alpha(pulse).background(Color(0xFFFF453B), CircleShape))
        Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}$separator${millis / 100 % 10}",
            color = LocalCocoColors.current.primaryText, fontSize = 15.sp,
            style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.testTag("voice-recording-clock"))
    }
}

@Composable
private fun VoiceRecordingMeter(recorder: VoiceRecorder, modifier: Modifier, count: Int, paused: Boolean,
    onLevel: (Float) -> Unit = {}) {
    val motion = rememberChatMotionSettings()
    val levels = remember(recorder, count) { mutableStateOf(List(count) { 0f }) }
    val sample by rememberUpdatedState(onLevel)
    LaunchedEffect(recorder, count, paused, motion.active) {
        if (motion.active && !paused) while (true) {
            delay(100)
            val level = recorder.level()
            levels.value = levels.value.drop(1) + level
            sample(level)
        }
    }
    val ink = LocalCocoColors.current.accent
    Canvas(modifier) {
        val step = size.width / count
        levels.value.forEachIndexed { i, level ->
            val h = (size.height * kotlin.math.sqrt(level.coerceIn(0f, 1f))).coerceAtLeast(2.dp.toPx())
            drawLine(ink, Offset((i + .5f) * step, (size.height - h) / 2),
                Offset((i + .5f) * step, (size.height + h) / 2), 2.dp.toPx())
        }
    }
}

@Composable
internal fun VoiceControl(icon: ImageVector?, label: String, tag: String, enabled: Boolean = true,
    modifier: Modifier = Modifier, glyphSize: Float = 19f, onClick: () -> Unit) {
    Box(modifier.size(44.dp).testTag(tag).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }.alpha(if (enabled) 1f else .4f), contentAlignment = Alignment.Center) {
        if (icon == null) Box(Modifier.size(15.dp).background(LocalCocoColors.current.primaryText, RoundedCornerShape(2.dp)))
        else Icon(icon, null, tint = LocalCocoColors.current.primaryText, modifier = Modifier.size(glyphSize.dp))
    }
}

@Composable
internal fun VoiceSendButton(enabled: Boolean = true, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val label = stringResource(R.string.voice_send)
    Box(modifier.size(44.dp).testTag(tag).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }.alpha(if (enabled) 1f else .4f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(38.dp).background(CapsuleChatChrome.micColor, CircleShape), contentAlignment = Alignment.Center) {
            Icon(ChatControls.send, null, tint = CapsuleChatChrome.micInk, modifier = Modifier.size(20.dp))
        }
    }
}

/** Finite lock choreography; there is no animation clock once the control settles. */
@Composable
private fun lockedControlReveal(delayMs: Long, from: Offset = Offset.Zero, send: Boolean = false): Modifier {
    val motion = rememberChatMotionSettings()
    val progress = remember { Animatable(if (motion.calm) 1f else 0f) }
    val density = LocalDensity.current.density
    LaunchedEffect(motion.active, motion.calm) {
        if (motion.calm) progress.snapTo(1f) else {
            delay(delayMs)
            progress.animateTo(1f, spring(dampingRatio = if (send) .62f else .8f,
                stiffness = if (send) 304.62f else 385.53f))
        }
    }
    return Modifier.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        scaleX = (if (send) .3f else .6f) + (if (send) .7f else .4f) * p
        scaleY = scaleX
        translationX = from.x * density * (1 - p)
        translationY = from.y * density * (1 - p)
    }
}

private class VoiceDragTrail { var offset = Offset.Zero }

@Composable
private fun RecordingLockGlyph(progress: Float) {
    val open = 1 - (progress / .92f).coerceIn(0f, 1f)
    val ink = LocalCocoColors.current.primaryText.copy(alpha = .9f)
    Box(Modifier.size(22.dp).graphicsLayer { scaleX = 1 + progress * .12f; scaleY = scaleX }, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.offset(y = (-8 - 3.5f * open).dp).size(9.dp, 11.dp).graphicsLayer {
            rotationZ = -24 * open; transformOrigin = TransformOrigin(0f, 1f)
        }) {
            val path = Path().apply {
                moveTo(0f, size.height); lineTo(0f, 4.5.dp.toPx())
                cubicTo(0f, 2.dp.toPx(), 2.dp.toPx(), 0f, size.width / 2, 0f)
                cubicTo(7.dp.toPx(), 0f, size.width, 2.dp.toPx(), size.width, 4.5.dp.toPx())
                lineTo(size.width, size.height)
            }
            drawPath(path, ink, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
        }
        Box(Modifier.size(15.dp, 11.5.dp).background(ink, RoundedCornerShape(3.2.dp)))
    }
}

/** Content only: the mounted composer's single material remains underneath. */
@Composable
internal fun RecordingBanner(paused: Boolean, onPause: () -> Unit, onCancel: () -> Unit,
    onReview: () -> Unit, onSend: () -> Unit, locked: Boolean = true, recorder: VoiceRecorder, drag: Offset) {
    val colors = LocalCocoColors.current
    val motion = rememberChatMotionSettings()
    val trail = remember { VoiceDragTrail() }
    SideEffect { if (!locked && drag != Offset.Zero) trail.offset = drag }
    val lockedBySlide = locked && -trail.offset.y >= 54
    val morph = remember { Animatable(if (locked) 1f else 0f) }
    LaunchedEffect(locked, motion.calm) {
        if (!locked || motion.calm || !lockedBySlide) morph.snapTo(if (locked) 1f else 0f)
        else morph.animateTo(1f, tween(520, easing = LinearEasing))
    }
    val morphProgress = morph.value
    var level by remember { mutableStateOf(0f) }
    val smoothLevel by animateFloatAsState(if (paused || locked || motion.calm) 0f else level,
        tween(if (motion.calm) 0 else 120), label = "recording-level")
    val lockMaterial = rememberCocoComposerSurfaceStyle(CapsuleChatChrome.field, CapsuleChatStyle.background).copy(border = null)
    BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)
        .pointerInput(Unit) { detectTapGestures {} }
        .testTag(if (locked) "voice-recording-locked" else "voice-recording-holding")) {
        val illustratedDrag = if (locked) trail.offset else drag
        val cancelProgress = (-illustratedDrag.x / ((maxWidth.value + 24) * .3f).coerceIn(80f, 120f)).coerceIn(0f, 1f)
        val lockProgress = (-illustratedDrag.y / 90f).coerceIn(0f, 1f)
        Row(Modifier.fillMaxSize().padding(start = if (locked) 0.dp else 14.dp, end = if (locked) 0.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (locked) VoiceControl(CocoIcons.Delete, stringResource(R.string.voice_cancel_recording), "voice-recording-cancel",
                modifier = lockedControlReveal(if (lockedBySlide) 100 else 0), onClick = onCancel)
            Box(Modifier.offset(x = if (locked) 0.dp else (drag.x * .25f).dp).alpha(if (locked) 1f else 1 - cancelProgress * .7f)) {
                VoiceRecordingClock(recorder, paused)
            }
            VoiceRecordingMeter(recorder,
                if (locked) Modifier.weight(1f).height(22.dp) else Modifier.width(58.dp).height(20.dp)
                    .offset(x = (drag.x * .25f).dp).alpha(1 - cancelProgress), if (locked) 26 else 14, paused,
                onLevel = { if (!locked) level = it })
            if (locked) {
                VoiceControl(if (paused) CocoIcons.Mic else CocoIcons.Pause, stringResource(R.string.voice_pause_resume), "voice-recording-pause",
                    modifier = lockedControlReveal(if (lockedBySlide) 280 else 0), onClick = onPause)
                VoiceControl(null, stringResource(R.string.voice_review), "voice-recording-stop",
                    modifier = lockedControlReveal(if (lockedBySlide) 260 else 0, if (lockProgress > .6f) Offset(50f, -91f) else Offset.Zero), onClick = onReview)
                VoiceSendButton(tag = "voice-recording-send", modifier = lockedControlReveal(if (lockedBySlide) 120 else 0, send = true), onClick = onSend)
            } else {
                Spacer(Modifier.weight(1f))
                Row(Modifier.offset(x = (drag.x * .6f).dp).alpha(1 - cancelProgress * .9f).clearAndSetSemantics {},
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Canvas(Modifier.size(8.dp, 13.dp)) {
                        val p = Path().apply { moveTo(size.width, 0f); lineTo(1.dp.toPx(), size.height / 2); lineTo(size.width, size.height) }
                        drawPath(p, colors.secondaryText, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
                    }
                    Text(stringResource(R.string.voice_slide_cancel), fontSize = 15.sp, maxLines = 1, color = colors.secondaryText)
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(56.dp))
            }
        }
        if (!locked || morphProgress < 1f) {
            // Overlays never change bar bounds or the gesture's root coordinates.
            val micMerge = if (locked) (morphProgress * 520 / 170).coerceIn(0f, 1f) else 0f
            Box(Modifier.align(Alignment.CenterEnd).offset(x = (12 + illustratedDrag.x).dp, y = illustratedDrag.y.dp)
                .size(76.dp).graphicsLayer {
                    val stretch = .56f * lockProgress * (1 - lockProgress)
                    scaleX = (1 - stretch * .5f) * (1 - cancelProgress * .2f - lockProgress * .16f)
                    scaleY = (1 + stretch) * (1 - cancelProgress * .2f - lockProgress * .16f)
                    scaleX *= 1 - .8f * micMerge; scaleY *= 1 - .8f * micMerge
                    alpha = 1 - micMerge
                }.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                val breath = smoothLevel
                if (!motion.calm) Box(Modifier.size(76.dp).graphicsLayer {
                    scaleX = 1.16f + breath * .62f; scaleY = scaleX
                    alpha = ((.1f + breath * .9f) * (1 - lockProgress)).coerceIn(0f, 1f)
                }.border(1.2.dp, Color.White.copy(alpha = .35f), CircleShape))
                Box(Modifier.size(76.dp).graphicsLayer { scaleX = 1.12f + breath * .5f; scaleY = scaleX }
                    .background(Color.White.copy(alpha = .10f), CircleShape))
                Box(Modifier.size(76.dp).graphicsLayer { scaleX = 1 + breath * .28f; scaleY = scaleX }
                    .background(Color.White.copy(alpha = .16f), CircleShape))
                Box(Modifier.size(68.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (cancelProgress > .98f) CocoIcons.Delete else CocoIcons.Mic, null, tint = Color.Black, modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = (-122 + 30 * lockProgress).dp)
                .graphicsLayer {
                    val click = ((morphProgress - .19f) / .31f).coerceIn(0f, 1f)
                    val move = ((morphProgress - .5f) / .5f).coerceIn(0f, 1f)
                    val eased = move * move * (3 - 2 * move)
                    scaleX = 1 + .16f * kotlin.math.sin(click * kotlin.math.PI).toFloat() - .3f * eased
                    scaleY = scaleX
                    translationX = -50.dp.toPx() * eased
                    val centerY = -122 + 30 * lockProgress + (46 + 16 * (1 - lockProgress)) / 2
                    translationY = (22 - centerY).dp.toPx() * eased
                    alpha = 1 - ((move - .4f) / .6f).coerceIn(0f, 1f)
                }
                .size(44.dp, (46 + 16 * (1 - lockProgress)).dp)
                .cocoChromeMaterial(CircleShape, lockMaterial).border(.5.dp, Color.White.copy(alpha = .14f + .12f * lockProgress), CircleShape)
                .alpha(1 - cancelProgress).padding(top = 12.dp).clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                RecordingLockGlyph(lockProgress)
                Text("⌃", color = colors.primaryText, fontSize = 12.sp, modifier = Modifier.alpha(1 - lockProgress))
            }
        }
    }
}
