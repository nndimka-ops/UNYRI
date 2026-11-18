package com.messenger.app.ui.chats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.messenger.app.data.local.VideoFrames
import com.messenger.app.data.local.VideoFrameFailures
import com.messenger.app.data.local.videoFrameKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.os.Build
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.messenger.app.data.attachment.ImageProcessor
import com.messenger.app.data.local.ChatImageCache
import java.io.File
import kotlinx.coroutines.ensureActive

/** Static neighbouring previews only. This never starts a download, player or animated drawable. */
@Composable
internal fun rememberMediaViewerPreview(file: File?, mime: String, thumbnailBase64: String? = null, workAllowed: Boolean = true): Bitmap? {
    if (!mime.startsWith("image/") && !mime.startsWith("video/")) return null
    val encoded = thumbnailBase64?.takeIf { it.isNotEmpty() && it.length <= 128 * 1024 }
    if (file == null && encoded == null) return null
    // Reuse the chat photo cache where possible. The shared cache is byte-bounded,
    // deduplicates concurrent decodes and limits IO work to two decode slots.
    val key = remember(file?.path, mime, encoded) {
        if (file == null) "viewer-thumb768:$encoded"
        else if (mime.startsWith("image/")) "photo768:${file.path}" else videoFrameKey(file.path)
    }
    val retry = if (mime.startsWith("video/")) VideoFrameFailures.retryGeneration.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED).value else 0L
    val sourceState = remember { PreviewSourceState<Bitmap>() }
    val preview by produceState(ChatImageCache.peek(key), key, retry, workAllowed) {
        val decoded = sourceState.load(key, workAllowed) {
        ChatImageCache.peek(key) ?: if (file != null && mime.startsWith("video/")) VideoFrames.load(file) else ChatImageCache.load(key) {
            if (file != null) runCatching { ImageProcessor.chatPreview(file, 768) }.getOrNull()
            else encoded?.let { runCatching { embeddedPreview(it) }.getOrNull() }
        }
        }
        // A cancelled page/session cannot publish an obsolete preview.
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (decoded != null) value = decoded
        else if (workAllowed && encoded != null) value = ChatImageCache.load("viewer-thumb768:$encoded") { embeddedPreview(encoded) }
    }
    return preview
}

private fun embeddedPreview(encoded: String): Bitmap? {
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    com.messenger.app.data.attachment.PeerImageDecoder.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
    var sample = 1
    while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) sample *= 2
    return com.messenger.app.data.attachment.PeerImageDecoder.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
