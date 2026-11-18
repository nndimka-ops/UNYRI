package com.messenger.app.ui.chats

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.*
import com.messenger.app.data.local.ChatImageCache
import com.messenger.app.data.local.VideoFrames
import com.messenger.app.data.local.VideoFrameFailures
import com.messenger.app.data.local.videoFrameKey
import com.messenger.app.data.attachment.ImageProcessor
import com.messenger.app.domain.model.Attachment
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal data class ConversationMediaPreview(val soft: Bitmap?, val sharp: Bitmap?)

/** Keys never prefer the inline thumbnail over a downloaded local file. */
@Composable internal fun rememberConversationMediaPreview(attachment: Attachment, edge: Int = 768): ConversationMediaPreview {
    val allowed by rememberUpdatedState(rememberMediaPreviewWorkAllowed())
    val video = attachment.mimeType.startsWith("video/")
    val retry = if (video) VideoFrameFailures.retryGeneration.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED).value else 0L
    val source = attachment.localPath
    val sharpKey = source?.let { if (video) videoFrameKey(it, edge, attachment.videoNote) else "photo$edge:$it" }
    val softKey = attachment.thumbnailBase64?.let { "soft40:" + attachment.attachmentId + ":" + it.hashCode() }
    val soft = key(softKey) {
        val bitmap by produceState(softKey?.let(ChatImageCache::peek), softKey) {
            if (value == null && softKey != null) {
                snapshotFlow { allowed }.first { it }
                value = ChatImageCache.load(softKey) { softMediaPreview(attachment.thumbnailBase64) }
            }
        }
        bitmap
    }
    val sharp = key(sharpKey) {
        val bitmap by produceState(sharpKey?.let(ChatImageCache::peek), sharpKey, retry) {
            if (value == null && source != null && sharpKey != null) {
                snapshotFlow { allowed }.first { it }
                // After admission, finish this bounded static decode even if an IME
                // inset or overlay changes admission. Disposal/source changes still
                // cancel it; playback/download admission remains unchanged.
                // Scrolling through a note never starts a decoder.
                if (attachment.videoNote) delay(120)
                value = if (video) VideoFrames.load(File(source), edge, attachment.videoNote, attachment.durationMs)
                    else ChatImageCache.load(sharpKey) { ImageProcessor.chatPreview(File(source), edge) }
            }
        }
        bitmap
    }
    return ConversationMediaPreview(soft, sharp)
}

/** 40 px DCT preview + one separable 7x7 tent blur, always called on the decode pool. */
internal fun softMediaPreview(encoded: String?): Bitmap? {
    if (encoded.isNullOrEmpty() || encoded.length > 128 * 1024) return null
    return runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        com.messenger.app.data.attachment.PeerImageDecoder.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 40) sample *= 2
        val decoded = com.messenger.app.data.attachment.PeerImageDecoder.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val w = decoded.width; val h = decoded.height
        val src = IntArray(w * h); val temp = IntArray(w * h); val dst = IntArray(w * h)
        decoded.getPixels(src, 0, w, 0, 0, w, h)
        fun pass(input: IntArray, output: IntArray, horizontal: Boolean) {
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (d in -3..3) {
                    val weight = 4 - kotlin.math.abs(d)
                    val color = input[(if (horizontal) y else (y + d).coerceIn(0, h - 1)) * w +
                        (if (horizontal) (x + d).coerceIn(0, w - 1) else x)]
                    r += (color shr 16 and 255) * weight; g += (color shr 8 and 255) * weight; b += (color and 255) * weight
                }
                output[y * w + x] = (255 shl 24) or ((r / 16) shl 16) or ((g / 16) shl 8) or (b / 16)
            }
        }
        pass(src, temp, true); pass(temp, dst, false)
        decoded.recycle()
        Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
