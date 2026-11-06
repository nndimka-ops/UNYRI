package com.messenger.app.ui.chats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
internal fun rememberMediaViewerPreview(file: File?, mime: String, thumbnailBase64: String? = null): Bitmap? {
    if (!mime.startsWith("image/") && !mime.startsWith("video/")) return null
    val encoded = thumbnailBase64?.takeIf { it.isNotEmpty() && it.length <= 128 * 1024 }
    if (file == null && encoded == null) return null
    // Reuse the chat photo cache where possible. The shared cache is byte-bounded,
    // deduplicates concurrent decodes and limits IO work to two decode slots.
    val key = remember(file?.path, mime, encoded) {
        if (file == null) "viewer-thumb768:$encoded"
        else if (mime.startsWith("image/")) "photo768:${file.path}" else "viewer-video768:${file.path}"
    }
    val preview by produceState(ChatImageCache.peek(key), key) {
        val decoded = ChatImageCache.load(key) {
            val local = file?.let { runCatching {
                if (mime.startsWith("image/")) ImageProcessor.chatPreview(it, 768) else scaledVideoPoster(it)
            }.getOrNull() }
            local ?: encoded?.let { runCatching { embeddedPreview(it) }.getOrNull() }
        }
        // A cancelled page/session cannot publish an obsolete preview.
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        value = decoded
    }
    return preview
}

private fun embeddedPreview(encoded: String): Bitmap? {
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
    var sample = 1
    while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun scaledVideoPoster(file: File): Bitmap? {
    // API 26 can only decode an unrestricted video frame. Keep its existing
    // placeholder rather than allocating a full 4K frame for a passive page.
    if (Build.VERSION.SDK_INT < 27) return null
    val reader = MediaMetadataRetriever()
    return try {
        reader.setDataSource(file.path)
        val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        reader.getScaledFrameAtTime(minOf(500L, duration / 2) * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 768, 768)
    } finally { runCatching { reader.release() } }
}
