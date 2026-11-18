package com.messenger.app.ui.chats

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MediaSource(val token: Any, val bounds: Rect, val bitmap: WeakReference<Bitmap>, val visible: Boolean,
    val geometry: LiveChatBounds? = null) {
    fun currentBounds(): Rect = if (geometry == null) bounds else geometry.bounds ?: Rect.Zero
}
internal object MediaSources {
    private val sources = MediaSourceRegistry<MediaSource>({ source ->
        val bounds = source.currentBounds()
        source.visible && source.bitmap.get() != null && bounds.width > 0 && bounds.height > 0
    })
    fun put(path: String, source: MediaSource) = sources.put(path, source.token, source)
    fun remove(path: String, token: Any) = sources.remove(path, token)
    fun get(path: String) = sources.get(path)?.let { it.copy(bounds = it.currentBounds(), geometry = null) }

}

@Composable internal fun Modifier.mediaViewerSource(path: String?, bitmap: Bitmap?): Modifier {
    if (path == null || bitmap == null) return this
    val token = remember(path) { Any() }
    val work = LocalTimelineMediaWork.current
    val row = LocalMediaRowKey.current
    // A viewer cover stops decoding, but the attached row remains a valid return target.
    val visible = work == null || row in work.visible
    val bounds = remember(token) { LiveChatBounds() }
    val attached = remember(token) { booleanArrayOf(false) }
    fun publish() { MediaSources.put(path, MediaSource(token, bounds.bounds ?: Rect.Zero, WeakReference(bitmap), visible && attached[0], bounds)) }
    SideEffect { publish() }
    DisposableEffect(token) { onDispose { attached[0] = false; MediaSources.remove(path, token) } }
    return onGloballyPositioned { attached[0] = it.isAttached; bounds.coordinates = it; publish() }
}

/** One finite bitmap flight. The registry owns only weak references, never another cache. */
@Composable internal fun MediaViewerTransition(source: MediaSource?, closing: Boolean, reduce: Boolean,
    opened: () -> Unit, dismissed: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val latestOpened by rememberUpdatedState(opened)
    val latestDismissed by rememberUpdatedState(dismissed)
    LaunchedEffect(closing) {
        if (source == null) { if (closing) latestDismissed() else latestOpened(); return@LaunchedEffect }
        progress.animateTo(if (closing) 0f else 1f, tween(if (reduce || source == null) 180 else 240))
        if (closing) latestDismissed() else latestOpened()
    }
    val bitmap = source?.bitmap?.get()
    if (bitmap == null || reduce) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
        val fit = minOf(w / bitmap.width, h / bitmap.height)
        val targetW = bitmap.width * fit; val targetH = bitmap.height * fit
        val from = source.bounds
        val p = progress.value
        if (p < 1f || closing) {
            val x = from.left + ((w - targetW) / 2 - from.left) * p
            val y = from.top + ((h - targetH) / 2 - from.top) * p
            val width = from.width + (targetW - from.width) * p
            val height = from.height + (targetH - from.height) * p
            Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }
                    .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                    .clip(RoundedCornerShape((12 * (1 - p)).dp)))
        }
    }
}

@Composable internal fun ViewerAmbientGlow(bitmap: Bitmap?) {
    val color by produceState(Color.Black, bitmap) {
        value = withContext(Dispatchers.Default) {
            if (bitmap == null || bitmap.isRecycled) Color.Black else {
                var r = 0L; var g = 0L; var b = 0L
                repeat(8) { y -> repeat(8) { x -> val c = bitmap.getPixel(x * bitmap.width / 8, y * bitmap.height / 8); r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255 } }
                Color((r / 64).toInt(), (g / 64).toInt(), (b / 64).toInt())
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(color.copy(alpha = .3f), Color.Black))))
}
