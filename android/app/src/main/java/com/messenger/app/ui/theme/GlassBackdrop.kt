package com.messenger.app.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.components.rememberCocoChromePolicy
import kotlin.math.ceil

class GlassBackdrop(val layer: GraphicsLayer, val blurEnabled: Boolean = false) {
    var origin by mutableStateOf(Offset.Zero)
    internal var contentVersion by mutableLongStateOf(0)
        private set
    private var recordings = 0L
    // Never read snapshot state in the source draw: that would invalidate itself.
    internal fun recorded() { contentVersion = ++recordings }
}
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }
val LocalGlassBottomInset = compositionLocalOf { 0.dp }

@Composable
fun rememberGlassBackdrop(blurEnabled: Boolean = false): GlassBackdrop {
    val enabled = blurEnabled && rememberCocoChromePolicy().samplesBackdrop(Build.VERSION.SDK_INT >= 31)
    val layer = rememberGraphicsLayer()
    return remember(layer, enabled) { GlassBackdrop(layer, enabled) }
}

/** Captures are leaves of the sampling graph. Nested panels use opaque material,
 * excluding direct/indirect RenderNode cycles, including cached child layers. */
internal val LocalGlassSourceContent = staticCompositionLocalOf { false }
internal fun glassSamplingAllowed(sourceEnabled: Boolean, insideSource: Boolean): Boolean =
    sourceEnabled && !insideSource

@Composable
fun GlassBackdropSource(backdrop: GlassBackdrop, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val nested = LocalGlassSourceContent.current
    CompositionLocalProvider(LocalGlassSourceContent provides (nested || backdrop.blurEnabled)) {
        Box((if (nested) Modifier else Modifier.glassSource(backdrop)).then(modifier), content = content)
    }
}

private fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier =
    if (!backdrop.blurEnabled) this else onGloballyPositioned { backdrop.origin = it.localToScreen(Offset.Zero) }.drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        backdrop.recorded()
        drawLayer(backdrop.layer)
    }

/** One tiny immutable tile shared by every panel; no random work during drawing. */
private val frostGrain: Brush by lazy {
    var seed = 0x51F15E
    val pixels = IntArray(64 * 64) {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        // Two percent maximum opacity. The transparent pixels avoid a flat grey veil.
        when (seed and 3) { 0 -> 0x05FFFFFF; 1 -> 0x05000000; else -> 0 }
    }
    val bitmap = android.graphics.Bitmap.createBitmap(pixels, 64, 64, android.graphics.Bitmap.Config.ARGB_8888)
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/** Shared optical material. Bright backdrops retain detail without washing out dark-theme ink. */
internal fun frostTint(base: Color): Color = if (base.luminance() < .5f)
    Color.White.copy(alpha = .045f).compositeOver(Color.Black.copy(alpha = .82f))
else Color.White.copy(alpha = .74f)

/** Only the panel plus its blur margin is rasterized. Its source excludes controls.
 * API 31/32 explicitly follow source invalidations via contentVersion, without a frame clock.
 * Foreground-only changes (e.g. cursor blink) reuse the previously recorded frost. */
internal fun Modifier.frostedGlass(shape: Shape, source: GlassBackdrop?, tint: Color, opaqueFill: Color,
    enabled: Boolean = true): Modifier = composed {
    val sample = enabled && Build.VERSION.SDK_INT >= 31 &&
        glassSamplingAllowed(source?.blurEnabled == true, LocalGlassSourceContent.current)
    if (!sample) return@composed this.clip(shape).background(opaqueFill.copy(alpha = 1f))
    val frost = rememberGraphicsLayer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val blur = remember(density) { with(density) { BlurEffect(18.dp.toPx(), 18.dp.toPx(), TileMode.Clamp) } }
    SideEffect { frost.renderEffect = blur; frost.clip = true }
    var origin by remember { mutableStateOf(Offset.Zero) }
    this.clip(shape).onGloballyPositioned { origin = it.localToScreen(Offset.Zero) }.drawWithCache {
        val margin = ceil(20.dp.toPx()).toInt()
        val captureSize = IntSize(ceil(size.width).toInt() + margin * 2, ceil(size.height).toInt() + margin * 2)
        val outline = shape.createOutline(size, layoutDirection, this)
        val edge = Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .04f)))
        val lip = Brush.verticalGradient(listOf(Color.White.copy(alpha = .035f), Color.Transparent), endY = 16.dp.toPx())
        var recordedVersion = -1L
        var recordedOffset = Offset.Unspecified
        onDrawWithContent {
            val backdrop = requireNotNull(source)
            val delta = backdrop.origin - origin
            if (recordedVersion != backdrop.contentVersion || recordedOffset != delta) {
                frost.record(size = captureSize) {
                    translate(delta.x + margin, delta.y + margin) { drawLayer(backdrop.layer) }
                }
                recordedVersion = backdrop.contentVersion
                recordedOffset = delta
            }
            drawRect(opaqueFill)
            translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(frost) }
            drawRect(tint)
            drawRect(lip)
            drawRect(frostGrain)
            drawOutline(outline, edge, style = Stroke(.5.dp.toPx()))
            drawContent()
        }
    }
}

/** Generic panels and the chat chrome use the same frost and the same solid gates. */
fun Modifier.glassPanel(shape: Shape, backdrop: GlassBackdrop? = null): Modifier = composed {
    val colors = LocalCocoColors.current
    val policy = rememberCocoChromePolicy()
    this.frostedGlass(shape, backdrop ?: LocalGlassBackdrop.current, frostTint(colors.glassPrimary), colors.deepPanel,
        enabled = policy.samplesBackdrop(Build.VERSION.SDK_INT >= 31))
}
