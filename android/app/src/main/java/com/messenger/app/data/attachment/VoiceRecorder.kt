package com.messenger.app.data.attachment

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executor

/** AAC-LC/mono/16 kHz/32 kbps M4A. Session ownership and finalization are shared by every stop path. */
class VoiceRecorder(context: Context) {
    private val app = context.applicationContext
    private val session = VoiceCaptureSession(
        output = { File(app.cacheDir, "voice_${java.util.UUID.randomUUID()}.m4a") },
        create = { file, interrupted -> AndroidVoiceCapture(app, file, interrupted) },
        now = SystemClock::elapsedRealtime,
        audioDuration = ::readableAudioDuration,
    )
    var onInterrupted: () -> Unit
        get() = session.onInterrupted
        set(value) { session.onInterrupted = value }
    val hasRecordingSession: Boolean get() = session.hasRecordingSession
    val wasInterrupted: Boolean get() = session.wasInterrupted
    val isPaused: Boolean get() = session.isPaused
    val recordedDurationMs: Long get() = session.recordedDurationMs
    fun start(): File = session.start()
    fun stop(): Pair<File, Long>? = session.stop()
    fun cancel() = session.cancel()
    fun togglePause() = session.togglePause()
    fun level(): Float = session.level()
}

/** Check for an actual audio sample even if MediaRecorder.stop() failed after an error. No decoding. */
private fun readableAudioDuration(file: File): Long? {
    if (!file.isFile || file.length() == 0L) return null
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        if (extractor.sampleTime < 0 || !format.containsKey(MediaFormat.KEY_DURATION)) null
        else (format.getLong(MediaFormat.KEY_DURATION) / 1000).takeIf { it > 0 }
    } catch (_: Exception) { null } finally { extractor.release() }
}

private class AndroidVoiceCapture(context: Context, private val file: File,
    private val interrupted: () -> Unit) : VoiceCapture {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
    @Suppress("DEPRECATION")
    private val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
    private var released = false
    private var started = false
    private var paused = false
    private var observesCapture = false
    private var ownsFocus = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) signalInterruption()
        }, handler).build()
    private val configurations = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT >= 29 && !released) {
                val current = runCatching { recorder.activeRecordingConfiguration }.getOrNull()
                if ((started && voiceCaptureRequiresReview(paused, current?.isClientSilenced == true, current == null)) || (current != null && configs.any {
                    it.clientAudioSessionId == current.clientAudioSessionId && it.isClientSilenced
                })) signalInterruption()
            }
        }
    }
    private val startup = VoiceCaptureStartup(mode = { audio.mode }, requestFocus = {
        ownsFocus = true
        audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    })
    private fun signalInterruption() {
        if (released) return
        if (Looper.myLooper() == handler.looper) interrupted()
        else handler.post { if (!released) interrupted() }
    }

    override fun start() {
        startup.start(prepare = {
            recorder.setOnErrorListener { _, _, _ -> signalInterruption() }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(16_000)
            recorder.setAudioEncodingBitRate(32_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            // Per-recorder callbacks cannot accidentally react to another app-owned recording.
            if (Build.VERSION.SDK_INT >= 29) {
                recorder.registerAudioRecordingCallback(Executor { handler.post(it) }, configurations)
                observesCapture = true
            }
        }, record = {
            recorder.start()
            started = true
        })
    }
    override fun silenced(): Boolean = Build.VERSION.SDK_INT >= 29 && recorder.activeRecordingConfiguration?.isClientSilenced == true
    // A disappearing capture is also unsafe to auto-send while its callback is queued.
    override fun requiresReview(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val current = recorder.activeRecordingConfiguration
        return voiceCaptureRequiresReview(paused, current?.isClientSilenced == true, current == null)
    }
    override fun stop() { started = false; recorder.stop() }
    override fun pause() { recorder.pause(); paused = true }
    override fun resume() { startup.requireIdlePlatform(); recorder.resume(); paused = false }
    override fun level(): Float = (recorder.maxAmplitude / 32767f).coerceIn(0f, 1f)
    override fun release() {
        if (released) return
        released = true
        try {
            runCatching { recorder.setOnErrorListener(null) }
            if (Build.VERSION.SDK_INT >= 29 && observesCapture) runCatching { recorder.unregisterAudioRecordingCallback(configurations) }
        } finally {
            try { recorder.release() }
            finally { if (ownsFocus) { ownsFocus = false; audio.abandonAudioFocusRequest(focus) } }
        }
    }
}
