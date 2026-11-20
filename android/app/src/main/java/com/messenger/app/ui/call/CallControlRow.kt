package com.messenger.app.ui.call

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.messenger.app.ui.theme.CocoSpacing
import kotlinx.coroutines.withContext

// The owner's reduced-motion contract keeps a short fade even when system scale is zero.
// Only these finite insert/placement animations use this context; no idle clock is started.
private object CallControlMotionScale : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** Shared audio/video geometry. Stable keys keep existing controls mounted during insertion. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CallControlRow(
    controls: List<CallControlRowPolicy.Control>,
    size: Dp,
    spacing: Dp,
    modifier: Modifier = Modifier,
    maxPerRow: Int = 4,
    retainTerminalHeight: Boolean = false,
    content: @Composable (CallControlRowPolicy.Control) -> Unit,
) {
    val density = LocalDensity.current
    val motion = CallControlRowPolicy.motion(callReducedMotion())
    BoxWithConstraints(modifier.semantics { testTagsAsResourceId = true }.testTag("call-control-row")) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val sizePx = with(density) { size.roundToPx() }
        val spacingPx = with(density) { spacing.roundToPx() }
        val gapPx = with(density) { CocoSpacing.md.roundToPx() }
        val geometry = CallControlRowPolicy.geometry(controls.size, widthPx, sizePx, spacingPx, gapPx, maxPerRow)
        // Ended video retains its identity stage height, but has no controls or empty action slots.
        val height = if (retainTerminalHeight)
            CallControlRowPolicy.geometry(4, widthPx, sizePx, spacingPx, gapPx, maxPerRow).height
        else geometry.height
        Box(Modifier.fillMaxWidth().height(with(density) { height.toDp() })) {
            controls.forEachIndexed { index, control -> key(control) {
                val target = geometry.positions[index].let { IntOffset(it.x, it.y) }
                val position = remember { Animatable(target, IntOffset.VectorConverter) }
                val appearance = remember { Animatable(0f) }
                val previousControls = remember { arrayOf(controls) }
                LaunchedEffect(target, controls, motion.move) {
                    val recenter = motion.move && previousControls[0] != controls
                    previousControls[0] = controls
                    withContext(CallControlMotionScale) {
                        if (recenter) position.animateTo(target, spring(dampingRatio = .86f, stiffness = 400f))
                        else position.snapTo(target)
                    }
                }
                LaunchedEffect(Unit) {
                    withContext(CallControlMotionScale) { appearance.animateTo(1f, tween(motion.fadeMillis)) }
                }
                Box(Modifier.offset { position.value }.size(size).testTag("call-control-${control.tag}")
                    .graphicsLayer {
                        alpha = appearance.value
                        val scale = motion.initialScale + (1f - motion.initialScale) * appearance.value
                        scaleX = scale
                        scaleY = scale
                    }) { content(control) }
            } }
        }
    }
}
