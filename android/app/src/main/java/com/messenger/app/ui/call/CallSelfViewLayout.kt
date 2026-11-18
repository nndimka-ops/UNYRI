package com.messenger.app.ui.call

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.LocalCocoColors
import kotlin.math.roundToInt

/** Production self-view geometry/input, with a slot for the mounted native renderer.
 *  The caller applies system insets once; sizing and dragging share this safe area. */
@Composable
internal fun CallSelfViewLayout(
    owner: String,
    visible: Boolean,
    chrome: Boolean,
    measuredTrayHeightPx: Int,
    modifier: Modifier = Modifier,
    picture: @Composable () -> Unit,
    controls: @Composable BoxScope.() -> Unit,
    rotation: Int = 0,
) {
    val colors = LocalCocoColors.current
    BoxWithConstraints(modifier, contentAlignment = AbsoluteAlignment.TopLeft) {
        val density = LocalDensity.current
        val width = maxWidth.value
        val height = maxHeight.value
        val tray = measuredTrayHeightPx / density.density
        var scale by remember(owner) { mutableFloatStateOf(1f) }
        val quarterTurn = rotation % 180 != 0
        val swapped = width > height
        val size = CallLayoutPolicy.selfViewSize(width, height, tray, scale, swapped)
        var position by remember(owner) { mutableStateOf(Offset(
            width - size.first - CallLayoutPolicy.PREVIEW_MARGIN,
            CallLayoutPolicy.PREVIEW_HEADER_CLEARANCE)) }
        var placementViewport by remember(owner) { mutableStateOf(Offset(width, height)) }
        var moved by remember(owner) { mutableStateOf(false) }
        fun origin(at: Offset, dimensions: Pair<Float, Float> = size): Offset? =
            CallLayoutPolicy.selfViewOrigin(at.x, at.y, width, height,
                dimensions.first, dimensions.second, tray, chrome)?.let { Offset(it.first, it.second) }
        val resized = placementViewport.x != width || placementViewport.y != height
        val adaptedPosition = if (!moved)
            Offset(width - size.first - CallLayoutPolicy.PREVIEW_MARGIN, CallLayoutPolicy.PREVIEW_HEADER_CLEARANCE)
        else if (!resized) position
        else Offset(position.x * width / placementViewport.x.coerceAtLeast(1f),
            position.y * height / placementViewport.y.coerceAtLeast(1f))
        val fittedOrigin = origin(adaptedPosition)
        val shown = visible && fittedOrigin != null
        LaunchedEffect(owner, visible, width, height, size, tray, chrome) {
            // A mini/PiP host's temporary dimensions must not move the self-view.
            if (visible) fittedOrigin?.let { position = it; placementViewport = Offset(width, height) }
        }
        Box(Modifier.absoluteOffset {
            fittedOrigin?.takeIf { visible }?.let {
                IntOffset((it.x * density.density).roundToInt(), (it.y * density.density).roundToInt())
            } ?: IntOffset(-20000, 0)
        }.size(size.first.dp, size.second.dp).testTag("call-self-view")
            .clip(CocoShapes.radiusLocalVideo)
            .background(Color.Black)
            .border(1.dp, colors.outlineSecondary, CocoShapes.radiusLocalVideo)
            .then(if (shown) Modifier else Modifier.clearAndSetSemantics {})) {
            // Crop in the swapped inner bounds, then rotate; the outer tile alone clips corners.
            Box(Modifier.align(androidx.compose.ui.Alignment.Center)
                .requiredSize(if (quarterTurn) size.second.dp else size.first.dp,
                    if (quarterTurn) size.first.dp else size.second.dp)
                .graphicsLayer { rotationZ = rotation.toFloat() }) { picture() }
            // Above native video, below its accessories: the renderer never eats input.
            Box(Modifier.fillMaxSize().pointerInput(owner, width, height, tray, chrome, rotation) {
                detectTransformGestures { _, pan, zoom, _ ->
                    moved = true
                    val next = CallLayoutPolicy.selfViewSize(width, height, tray, scale * zoom, swapped)
                    val base = if (width > height) CallLayoutPolicy.landscape(width, height).previewWidth else
                        CallLayoutPolicy.localPreviewSize(width - 2 * CallLayoutPolicy.PREVIEW_MARGIN).first
                    scale = (if (swapped) next.second else next.first) / base
                    origin(position + pan / density.density, next)?.let { position = it }
                }
            })
            controls()
        }
    }
}
