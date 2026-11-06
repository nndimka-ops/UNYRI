package com.messenger.app.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/** Short phase feedback, with stable geometry and no overlapping old controls. */
internal fun Modifier.voicePhaseReveal(phase: Any): Modifier = composed {
    val opacity = remember { Animatable(1f) }
    LaunchedEffect(phase) {
        opacity.snapTo(.9f)
        opacity.animateTo(1f, tween(140))
    }
    graphicsLayer { alpha = opacity.value }
}
