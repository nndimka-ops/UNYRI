package com.messenger.app.ui.chats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.ui.components.cocoChromeMaterial
import com.messenger.app.ui.components.rememberCocoComposerSurfaceStyle

/** Overlay only: the timeline never reserves or animates space for this control. */
@Composable
internal fun NewMessagesPill(count: Int, visible: Boolean, onClick: () -> Unit) {
    val motion = rememberChatMotionSettings()
    val duration = if (motion.calm) 0 else 180
    val easing = com.messenger.app.ui.theme.CocoEaseOut
    androidx.compose.animation.AnimatedVisibility(visible = visible,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(duration, easing = easing)) +
            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(duration, easing = easing)) { it },
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(duration, easing = easing)) +
            androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(duration, easing = easing)) { it }) {
        ChatNavigationButton(count = count, onClick = onClick)
    }
}

@Composable
internal fun ChatNavigationButton(back: Boolean = false, count: Int = 0, onClick: () -> Unit) {
    if (!back) { JumpToLatestButton(count, onClick); return }
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val label = stringResource(R.string.back)
    androidx.compose.material3.IconButton(onClick = { com.messenger.app.ui.theme.CocoHaptics.tap(hapticView); onClick() },
        modifier = Modifier.size(44.dp).semantics { contentDescription = label }) {
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * .7f, size.height * .1f)
                lineTo(size.width * .3f, size.height * .5f)
                lineTo(size.width * .7f, size.height * .9f)
            }
            drawPath(path, Color(0xFFF2F3F7), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Composable
private fun JumpToLatestButton(count: Int, onClick: () -> Unit) {
    val colors = com.messenger.app.ui.theme.LocalCocoColors.current
    val monochrome = com.messenger.app.ui.theme.LocalAppColorStyle.current == com.messenger.app.ui.theme.AppColorStyle.MONOCHROME
    val view = androidx.compose.ui.platform.LocalView.current
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val motion = rememberChatMotionSettings()
    val surface = rememberCocoComposerSurfaceStyle(CapsuleChatChrome.field, CapsuleChatStyle.background)
        .let { if (it.backdropEnabled) it.copy(border = BorderStroke(.5.dp, Color.White.copy(alpha = .12f))) else it }
    val movement = remember { androidx.compose.animation.core.Animatable(0f) }
    var previous by remember { mutableIntStateOf(count) }
    val label = stringResource(R.string.chat_scroll_latest)
    LaunchedEffect(count, motion.calm) {
        val increased = count > previous
        previous = count
        movement.snapTo(0f)
        if (increased && !motion.calm) {
            movement.animateTo(2f, androidx.compose.animation.core.tween(130, easing = com.messenger.app.ui.theme.CocoEaseOut))
            movement.animateTo(0f, androidx.compose.animation.core.tween(130,
                easing = androidx.compose.animation.core.CubicBezierEasing(.42f, 0f, 1f, 1f)))
        }
    }
    Box(Modifier.size(52.dp, 56.dp)
        .then(com.messenger.app.ui.theme.cocoQuietPressFeedback(source, haptic = false))
        .then(Modifier.semantics { contentDescription = label + if (count > 0) ": $count" else "" })
        .clickableForJump(source) { com.messenger.app.ui.theme.CocoHaptics.tap(view); onClick() },
        contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.size(44.dp).cocoChromeMaterial(androidx.compose.foundation.shape.CircleShape, surface), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(19.dp)) {
                val nudge = (movement.value + 1f).dp.toPx()
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .14f, size.height * .33f + nudge)
                    lineTo(size.width * .5f, size.height * .69f + nudge)
                    lineTo(size.width * .86f, size.height * .33f + nudge)
                }
                drawPath(path, colors.primaryText, style = androidx.compose.ui.graphics.drawscope.Stroke(2.3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        if (count > 0) Box(Modifier.align(Alignment.TopCenter).heightIn(min = 22.dp).widthIn(min = 22.dp)
            .background(if (monochrome) Color.White else ChatControls.blue, RoundedCornerShape(11.dp))
            .padding(horizontal = 6.dp).clearAndSetSemantics {},
            contentAlignment = Alignment.Center) {
            Text(if (count > 99) "99+" else "$count", color = if (monochrome) Color.Black else Color.White, fontSize = 12.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
        }
    }
}

private fun Modifier.clickableForJump(source: androidx.compose.foundation.interaction.MutableInteractionSource, onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(interactionSource = source, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick))
