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
    val initialPhase = remember { phase }
    val motion = rememberChatMotionSettings()
    LaunchedEffect(phase, motion.active, motion.calm) {
        if (!motion.active || motion.calm || phase == initialPhase) {
            opacity.snapTo(1f)
            return@LaunchedEffect
        }
        opacity.snapTo(.9f)
        opacity.animateTo(1f, tween(180))
    }
    graphicsLayer {
        alpha = opacity.value
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    }
}
