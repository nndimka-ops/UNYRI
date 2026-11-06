package com.messenger.app.ui.chats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R

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
    val view = androidx.compose.ui.platform.LocalView.current
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val movement = remember { androidx.compose.animation.core.Animatable(0f) }
    var previous by remember { mutableIntStateOf(count) }
    val label = stringResource(R.string.chat_scroll_latest)
    LaunchedEffect(count) {
        val increased = count > previous
        previous = count
        val animationsEnabled = android.animation.ValueAnimator.areAnimatorsEnabled()
        movement.snapTo(0f)
        if (increased && animationsEnabled) {
            movement.animateTo(2f, androidx.compose.animation.core.tween(130))
            movement.animateTo(0f, androidx.compose.animation.core.tween(130))
        }
    }
    Box(Modifier.size(52.dp)
        .then(com.messenger.app.ui.theme.cocoPressFeedback(source, haptic = false))
        .then(Modifier.semantics { contentDescription = label + if (count > 0) ": $count" else "" })
        .clickableForJump(source) { com.messenger.app.ui.theme.CocoHaptics.tap(view); onClick() },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(42.dp).background(colors.inputSurface, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(23.dp).offset(y = movement.value.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .5f, size.height * .15f)
                    lineTo(size.width * .5f, size.height * .85f)
                    moveTo(size.width * .2f, size.height * .55f)
                    lineTo(size.width * .5f, size.height * .85f)
                    lineTo(size.width * .8f, size.height * .55f)
                }
                drawPath(path, colors.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        if (count > 0) Text(if (count > 99) "99+" else "$count", color = colors.onAccent, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopEnd).background(colors.accent, RoundedCornerShape(8.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

private fun Modifier.clickableForJump(source: androidx.compose.foundation.interaction.MutableInteractionSource, onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(interactionSource = source, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick))
