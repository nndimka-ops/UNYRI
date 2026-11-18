package com.messenger.app.ui.chats

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.messenger.app.ui.theme.GlassBackdrop
import kotlin.math.roundToInt

/** A passive, cache-owned poster supplies material even for native ImageView/SurfaceView.
 * Never read pixels from a live decoder, or keep a second full-resolution image. */
@Composable internal fun MediaViewerMaterial(bitmap: Bitmap?, frame: Rect?, backdrop: GlassBackdrop) {
    if (!backdrop.blurEnabled) return
    val image = remember(bitmap) { bitmap?.takeUnless { it.isRecycled }?.asImageBitmap() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { backdrop.origin = it.localToScreen(Offset.Zero) }
        .drawWithContent {
            backdrop.layer.record {
                drawRect(Color.Black)
                if (image != null && frame != null && frame.width > 0 && frame.height > 0) drawImage(image,
                    dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                    dstSize = IntSize(frame.width.roundToInt().coerceAtLeast(1), frame.height.roundToInt().coerceAtLeast(1)))
            }
            backdrop.recorded()
            // This layer is sampled by glass controls only; the native media stays visible.
        })
}
