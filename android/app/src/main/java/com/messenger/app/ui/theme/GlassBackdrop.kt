package com.messenger.app.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

class GlassBackdrop(val layer: GraphicsLayer, val blurEnabled: Boolean = false) {
    var origin by mutableStateOf(Offset.Zero)
}
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }
val LocalGlassBottomInset = compositionLocalOf { 0.dp }

@Composable
fun rememberGlassBackdrop(blurEnabled: Boolean = false): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer, blurEnabled) { GlassBackdrop(layer, blurEnabled) }
}

fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier =
    if (!backdrop.blurEnabled) this else onGloballyPositioned { backdrop.origin = it.localToScreen(Offset.Zero) }.drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/** Samples only the separately recorded content, never the controls themselves. */
fun Modifier.glassPanel(shape: Shape, backdrop: GlassBackdrop? = null): Modifier = composed {
    val source = backdrop ?: LocalGlassBackdrop.current
    val colors = LocalCocoColors.current
    if (source?.blurEnabled != true) {
        return@composed this.clip(shape).background(colors.deepPanel)
    }
    val frost = rememberGraphicsLayer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val blur = remember(density) {
        with(density) { BlurEffect(18.dp.toPx(), 18.dp.toPx(), TileMode.Clamp) }
    }
    SideEffect { frost.renderEffect = blur }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val supported = Build.VERSION.SDK_INT >= 31 && source != null
    val tint = colors.glassPrimary.copy(alpha = if (supported) 0.42f else 0.90f)
    this.clip(shape).onGloballyPositioned { origin = it.localToScreen(Offset.Zero) }.drawWithContent {
        if (supported && source != null) {
            val delta = source.origin - origin
            frost.record {
                translate(delta.x, delta.y) { drawLayer(source.layer) }
            }
            drawLayer(frost)
        }
        drawRect(tint)
        drawContent()
    }
}
