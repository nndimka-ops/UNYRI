package com.messenger.app.ui.theme

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity

/** Product coordinates match the saved editor: patternScale × 3 is a tile in dp. */
@Immutable
data class CocoPatternSpec(
    val kind: String = CocoSavedPattern.kind,
    val opacity: Float = CocoSavedPattern.opacity,
    val scale: Float = CocoSavedPattern.scale,
    val density: String = CocoSavedPattern.density,
    val stroke: Float = CocoSavedPattern.stroke,
    val color: Color = Color(CocoSavedPattern.colorArgb),
    val offsetX: Float = CocoSavedPattern.offsetX,
    val offsetY: Float = CocoSavedPattern.offsetY,
    val rotation: Float = CocoSavedPattern.rotation,
)

val LocalCocoPatternEnabled = staticCompositionLocalOf { CocoSavedPattern.enabledByDefault }
val LocalCocoPatternSpec = staticCompositionLocalOf { CocoPatternSpec() }

/** One retained source tile shared by screen instances. Never recycle an image
 * that may still be referenced by an outgoing screen's shader. */
internal object CocoPatternRasterCache {
    private data class Key(val kind: String, val color: Int, val stroke: Float, val density: String, val rotation: Float)
    private var key: Key? = null
    private var image: ImageBitmap? = null

    @Synchronized
    fun image(spec: CocoPatternSpec): ImageBitmap {
        val next = Key(spec.kind, spec.color.toArgb(), spec.stroke, spec.density, spec.rotation)
        image?.takeIf { key == next }?.let { return it }
        val bitmap = android.graphics.Bitmap.createBitmap(384, 384, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = next.color
            style = android.graphics.Paint.Style.FILL
        }
        if (spec.kind == "dots" || spec.kind == "coco") {
            // Exact 384 px source / 192 logical coordinates from editor tile().
            // Geometry is rasterized once per design key, never during scrolling.
            canvas.scale(2f, 2f)
            val spacing = when (spec.density) { "dense" -> 32f; "sparse" -> 96f; else -> 64f }
            val radius = 1.1f + spec.stroke * .35f
            paint.strokeWidth = spec.stroke
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.strokeJoin = android.graphics.Paint.Join.ROUND
            val path = android.graphics.Path()
            var index = 0
            var y = spacing / 2f
            while (y < 192f) {
                val stagger = if ((y / spacing).toInt() % 2 == 1) spacing * .12f else 0f
                var x = spacing / 2f
                while (x < 192f) {
                    canvas.save()
                    canvas.translate(x + stagger, y)
                    canvas.rotate(spec.rotation)
                    if (spec.kind == "dots") {
                        paint.style = android.graphics.Paint.Style.FILL
                        canvas.drawCircle(0f, 0f, radius, paint)
                    } else {
                        paint.style = android.graphics.Paint.Style.STROKE
                        path.rewind()
                        when (index++ % 6) {
                            0 -> { // chat: roundRect(-10,-7,20,14,3), with the small tail.
                                path.addRoundRect(-10f, -7f, 10f, 7f, 3f, 3f, android.graphics.Path.Direction.CW)
                                path.moveTo(-5f, 7f); path.lineTo(-5f, 11f); path.lineTo(0f, 7f)
                                canvas.drawPath(path, paint)
                            }
                            1, 5 -> canvas.drawCircle(0f, 0f, 9f, paint)
                            2 -> {
                                path.moveTo(0f, -9f)
                                path.quadTo(2f, -2f, 9f, 0f)
                                path.quadTo(2f, 2f, 0f, 9f)
                                path.quadTo(-2f, 2f, -9f, 0f)
                                path.quadTo(-2f, -2f, 0f, -9f)
                                canvas.drawPath(path, paint)
                            }
                            3 -> {
                                path.moveTo(0f, -9f); path.lineTo(9f, 0f)
                                path.lineTo(0f, 9f); path.lineTo(-9f, 0f); path.close()
                                canvas.drawPath(path, paint)
                            }
                            4 -> {
                                canvas.drawLine(-4f, 0f, 4f, 0f, paint)
                                canvas.drawLine(0f, -4f, 0f, 4f, paint)
                            }
                        }
                    }
                    canvas.restore()
                    x += spacing
                }
                y += spacing
            }
        } else if (spec.kind != "none") {
            // Preserve the optional legacy motif for pre-existing non-editor callers.
            canvas.scale(384f / 120f, 384f / 120f)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = spec.stroke
            val path = android.graphics.Path().apply {
                moveTo(18f, 18f); lineTo(40f, 18f); quadTo(44f, 18f, 44f, 22f)
                lineTo(44f, 34f); lineTo(30f, 34f); lineTo(24f, 39f); lineTo(24f, 34f); lineTo(18f, 34f); close()
                moveTo(86f, 22f); lineTo(89f, 29f); lineTo(96f, 32f); lineTo(89f, 35f)
                lineTo(86f, 42f); lineTo(83f, 35f); lineTo(76f, 32f); lineTo(83f, 29f); close()
                addOval(24f, 82f, 38f, 96f, android.graphics.Path.Direction.CW)
                moveTo(87f, 80f); lineTo(96f, 89f); lineTo(87f, 98f); lineTo(78f, 89f); close()
            }
            canvas.rotate(spec.rotation, 60f, 60f)
            canvas.drawPath(path, paint)
        }
        return bitmap.asImageBitmap().also { image = it; key = next }
    }
}

/** Neither scrolling nor the keyboard's viewport resize enters the raster/shader
 * keys. Font scaling also leaves the physical repeat unchanged. */
@Composable
fun Modifier.cocoConversationBackground(colors: CocoColors): Modifier {
    val enabled = LocalCocoPatternEnabled.current
    val spec = LocalCocoPatternSpec.current
    val pixelDensity = LocalDensity.current.density
    val brush = remember(enabled, spec, pixelDensity) {
        if (!enabled || spec.kind == "none") null else {
            val image = CocoPatternRasterCache.image(spec)
            val scale = spec.scale.coerceAtLeast(1f) * 3f * pixelDensity / 384f
            val shader = ImageShader(image, TileMode.Repeated, TileMode.Repeated)
            shader.setLocalMatrix(android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate(spec.offsetX * pixelDensity, spec.offsetY * pixelDensity)
            })
            ShaderBrush(shader)
        }
    }
    return drawWithCache {
        onDrawBehind {
            drawRect(colors.background)
            brush?.let { drawRect(it, alpha = spec.opacity.coerceIn(0f, 1f)) }
        }
    }
}

/** Root-screen surface only. Cards and media keep their own opaque surfaces. */
@Composable
fun CocoScreenSurface(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = LocalCocoColors.current.background,
    content: @Composable () -> Unit,
) {
    val colors = LocalCocoColors.current
    androidx.compose.material3.Surface(modifier = modifier, color = color) {
        Box(Modifier.fillMaxSize().cocoConversationBackground(colors)) { content() }
    }
}
