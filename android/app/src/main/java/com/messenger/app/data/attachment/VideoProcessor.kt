package com.messenger.app.data.attachment

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
internal object VideoProcessor {
    suspend fun convert(context: Context, source: Uri, output: File, high: Boolean) = withContext(Dispatchers.Main.immediate) {
        var transformer: Transformer? = null
        var completed = false
        try {
            withTimeout(10 * 60 * 1000L) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(source)).setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(if (high) 1080 else 720)))).build()
                    transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) { if (continuation.isActive) continuation.resume(Unit) }
                            override fun onError(composition: Composition, result: ExportResult, error: ExportException) { if (continuation.isActive) continuation.resumeWithException(error) }
                        }).build()
                    val composition = Composition.Builder(EditedMediaItemSequence(item)).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
                    transformer?.start(composition, output.path)
                }
            }
            completed = true
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { transformer?.cancel(); if (!completed) output.delete() }
        }
    }
}
