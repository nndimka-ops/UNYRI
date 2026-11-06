package com.messenger.app.data.attachment

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * AAC-LC in an MP4/M4A container via the platform `MediaRecorder` - zero
 * extra dependencies/APK size (Этап H п.27: no Opus/native codec library
 * pulled in just for voice messages). Mono, 16kHz, 32kbps is a
 * speech-appropriate profile (Этап H п.28): a minute of speech lands
 * around 200-250KB, nowhere near "tens of MB".
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0
    var isPaused: Boolean = false; private set
    private var pausedAtMs: Long = 0
    private var pausedDurationMs: Long = 0
    fun level(): Float = if (isPaused) 0f else runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f)
    fun togglePause() {
        val current = recorder ?: return
        if (isPaused) { current.resume(); pausedDurationMs += SystemClock.elapsedRealtime() - pausedAtMs }
        else { current.pause(); pausedAtMs = SystemClock.elapsedRealtime() }
        isPaused = !isPaused
    }

    val recordedDurationMs: Long
        get() = if (recorder == null) 0 else ((if (isPaused) pausedAtMs else SystemClock.elapsedRealtime()) - startedAtMs - pausedDurationMs).coerceAtLeast(0)

    fun start(): File {
        check(recorder == null) { "Recording already active" }
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val newRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try { newRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(32_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        } } catch (error: Exception) {
            newRecorder.release()
            file.delete()
            throw error
        }
        recorder = newRecorder
        outputFile = file
        startedAtMs = SystemClock.elapsedRealtime()
        isPaused = false; pausedDurationMs = 0
        return file
    }

    /** Returns the recorded file + duration, or `null` if recording never
     *  produced a usable result (Этап H п.31: a permission revoked/app
     *  backgrounded/interrupted-mid-recording case must never auto-send a
     *  corrupt file - `MediaRecorder.stop()` itself throws for a too-short
     *  recording, which we treat identically to "nothing to send"). */
    fun stop(): Pair<File, Long>? {
        val current = recorder ?: return null
        val file = outputFile
        val duration = recordedDurationMs
        recorder = null
        outputFile = null
        return try {
            current.stop()
            current.release()
            file?.let { it to duration }
        } catch (e: RuntimeException) {
            current.release()
            file?.delete()
            null
        }
    }

    fun cancel() {
        val current = recorder
        recorder = null
        try {
            current?.stop()
        } catch (e: RuntimeException) {
            // Expected for a too-short/interrupted recording - discarding anyway.
        }
        current?.release()
        outputFile?.delete()
        outputFile = null
    }
}
