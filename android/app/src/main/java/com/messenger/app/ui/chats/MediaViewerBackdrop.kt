package com.messenger.app.ui.chats

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** iOS UnyriAmbientColor: one tiny average per poster, capped HSV, finite tint transition. */
@Composable internal fun MediaViewerBackdrop(bitmap: Bitmap?, active: Boolean, reduceMotion: Boolean) {
    val average by produceState(Color.Black, bitmap, active) {
        if (!active) return@produceState
        value = withContext(Dispatchers.Default) {
            if (bitmap == null || bitmap.isRecycled) return@withContext Color.Black
            runCatching {
                val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
                try {
                    val tiny = Bitmap.createScaledBitmap(software, 8, 8, true)
                    try {
                        val pixels = IntArray(64); tiny.getPixels(pixels, 0, 8, 0, 0, 8, 8)
                        val r = pixels.sumOf { (it shr 16 and 255).toLong() }.toInt() / 64
                        val g = pixels.sumOf { (it shr 8 and 255).toLong() }.toInt() / 64
                        val b = pixels.sumOf { (it and 255).toLong() }.toInt() / 64
                        val hsv = FloatArray(3)
                        android.graphics.Color.RGBToHSV(r, g, b, hsv)
                        hsv[1] = hsv[1].coerceAtMost(.75f); hsv[2] = hsv[2].coerceIn(.35f, .8f)
                        Color(android.graphics.Color.HSVToColor(hsv))
                    } finally { if (tiny !== software) tiny.recycle() }
                } finally { if (software !== bitmap) software.recycle() }
            }.getOrDefault(Color.Black)
        }
    }
    val color by animateColorAsState(average, tween(if (reduceMotion || !active) 0 else 450), label = "viewer-tint")
    val radius = with(LocalDensity.current) { 520.dp.toPx() }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(
        0f to color.copy(alpha = .38f), (40f / 520) to color.copy(alpha = .38f),
        (280f / 520) to color.copy(alpha = .10f), 1f to Color.Transparent, radius = radius)))
}
