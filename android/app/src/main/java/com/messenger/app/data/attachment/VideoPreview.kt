package com.messenger.app.data.attachment

import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File

/** Only a small JPEG goes into the encrypted message; never a full video frame. */
internal data class VideoPreview(val image: ImageInfoDto? = null, val durationMs: Long? = null, val thumbnail: String? = null) {
    companion object {
        fun read(file: File): VideoPreview {
            val reader = MediaMetadataRetriever()
            return try {
                reader.setDataSource(file.path)
                val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it >= 0 }
                var width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                if (rotation == 90 || rotation == 270) { val swap = width; width = height; height = swap }
                val dimensions = if (width > 0 && height > 0) ImageInfoDto(width, height) else null
                val thumbnail = runCatching {
                    // API 26 cannot request a bounded decoded frame. Keep the icon
                    // fallback there rather than allocating an arbitrary 4K frame.
                    val frame = if (Build.VERSION.SDK_INT >= 27) reader.getScaledFrameAtTime(
                        minOf(500L, (duration ?: 0L) / 2) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512) else null
                    frame?.let { bitmap ->
                        try {
                            ImageProcessor.createThumbnailBase64(bitmap).takeIf { it.isNotEmpty() }
                        } finally { bitmap.recycle() }
                    }
                }.getOrNull()
                VideoPreview(dimensions, duration, thumbnail)
            } catch (_: Exception) { VideoPreview() }
            finally { runCatching { reader.release() } }
        }
    }
}
