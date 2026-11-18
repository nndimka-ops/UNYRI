package com.messenger.app.data.local

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

internal fun videoFrameKey(path: String, edge: Int = 768, note: Boolean = false) =
    "video-frame:$edge:$note:$path"

internal object VideoFrames {
    suspend fun load(file: File, edge: Int = 768, note: Boolean = false, durationMs: Long? = null): Bitmap? {
        val key = videoFrameKey(file.path, edge, note)
        val generation = ChatImageCache.epoch()
        var immediateRetry = true
        while (true) {
            if (VideoFrameFailures.policy.holds(key, VideoFrameFailures.now())) return ChatImageCache.peek(key)
            val attempt = VideoFrameFailures.policy.attempt()
            var kind = if (note) FrameFailure.POSTER else FrameFailure.UNKNOWN
            var exists = false
            val job = currentCoroutineContext()
            val result = ChatImageCache.load(key) {
                exists = file.isFile
                if (!exists) return@load null
                if (Build.VERSION.SDK_INT == 26) return@load try {
                    Api26VideoFrame.read(file, edge, if (note) minOf(500L, (durationMs ?: 1000L).coerceAtLeast(0) / 2) * 1000 else 0L) { job.ensureActive() }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.path)
                    // Ordinary covers need neither duration nor an audio-track probe.
                    val time = if (note) minOf(500L, (durationMs ?: 1000L).coerceAtLeast(0) / 2) * 1000 else 0L
                    var width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: edge
                    var height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: edge
                    val fit = minOf(1.0, edge.toDouble() / maxOf(width, height).coerceAtLeast(1))
                    width = (width * fit).toInt().coerceAtLeast(1); height = (height * fit).toInt().coerceAtLeast(1)
                    reader.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
                } catch (error: Exception) {
                    kind = when {
                        note -> FrameFailure.POSTER
                        error is MediaCodec.CodecException && (error.isTransient || error.isRecoverable) -> FrameFailure.INTERRUPTED
                        error is UnsupportedOperationException -> FrameFailure.UNREADABLE
                        else -> FrameFailure.UNKNOWN
                    }
                    null
                } finally { runCatching { reader.release() } }
            }
            currentCoroutineContext().ensureActive()
            if (result != null) return result
            val retry = VideoFrameFailures.policy.failed(key, kind, exists, attempt, generation, ChatImageCache.epoch(), VideoFrameFailures.now())
            if (retry != FrameRetry.NOW || !immediateRetry) return null
            immediateRetry = false
        }
    }
}
