package com.messenger.app.ui.chats

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One finite, transparent-window flight. Closing starts at the actual dragged/zoomed image. */
@Composable internal fun rememberViewerFlight(closing: Boolean, reduce: Boolean, dismissed: () -> Unit): State<Float> {
    val progress = remember { Animatable(0f) }
    val latestDismissed by rememberUpdatedState(dismissed)
    LaunchedEffect(closing) {
        progress.animateTo(if (closing) 0f else 1f, tween(if (reduce) 120 else 220))
        if (closing) latestDismissed()
    }
    return progress.asState()
}

@Composable internal fun MediaViewerFlight(bitmap: Bitmap?, source: Rect?, currentRect: Rect?,
    closing: Boolean, progress: Float, reduce: Boolean) {
    if (bitmap == null || bitmap.isRecycled || source == null || reduce || progress == 1f && !closing) return
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val fit = viewerFitRect(w, h, bitmap.width.toFloat(), bitmap.height.toFloat()) ?: return@BoxWithConstraints
        val frame = lerp(source, if (closing) currentRect ?: fit else fit, progress)
        // Window rectangles are physical coordinates. A zoomed tile may be larger
        // than this viewport: constrain the viewport, never the captured image.
        Layout(content = {
            Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier.clip(RoundedCornerShape((18 * (1 - progress)).dp)).testTag("media-flight-image"))
        }, modifier = Modifier.fillMaxSize()) { children, viewport ->
            val tile = children.single().measure(Constraints.fixed(
                frame.width.roundToInt().coerceAtLeast(1), frame.height.roundToInt().coerceAtLeast(1)))
            layout(viewport.maxWidth, viewport.maxHeight) {
                tile.place(frame.left.roundToInt(), frame.top.roundToInt())
            }
        }
    }
}
