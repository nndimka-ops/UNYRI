package com.messenger.app.ui.call

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.log10

object CallVoiceHaloPolicy {
    fun target(rms: Double?): Float {
        if (rms == null || !rms.isFinite() || rms <= 0) return 0f
        return ((20 * log10(rms) + 55) / 40).coerceIn(0.0, 1.0).toFloat()
    }
}

/** A cold receiver-only meter, collected only by the visible identity. Silence emits no updates. */
@Composable
internal fun CallVoiceHalo(diameter: Dp) {
    val snapshot = LocalCallChrome.current
    val active = callForeground() && snapshot?.state is com.messenger.app.domain.call.CallUiState.Connected
    val callId = snapshot?.state?.let(CallPresentationPolicy::connected)?.call?.callId
    val reduced = callReducedMotion()
    val flow = remember(snapshot?.voiceLevels, reduced, callId) { snapshot?.voiceLevels?.map {
        if (!reduced) it else when { it < .15f -> 0f; it < .45f -> .33f; it < .75f -> .66f; else -> 1f }
    }?.distinctUntilChanged() }
    val target by (if (active) flow?.collectAsState(initial = 0f) else null) ?: remember { mutableFloatStateOf(0f) }
    val level = remember { Animatable(0f) }
    LaunchedEffect(target, active) {
        if (!active) level.snapTo(0f)
        else level.animateTo(target, tween(if (reduced) 0 else if (target > level.value) 140 else 300))
    }
    Canvas(Modifier.size(diameter).clearAndSetSemantics {}) {
        val value = level.value
        if (value >= .01f) repeat(3) { index ->
            drawCircle(Color.White.copy(alpha = value * (.14f - index * .03f)),
                radius = size.minDimension / 2 + (6 + index * 7).dp.toPx() * value,
                style = Stroke((3 - index * .5f).dp.toPx()))
        }
    }
}
