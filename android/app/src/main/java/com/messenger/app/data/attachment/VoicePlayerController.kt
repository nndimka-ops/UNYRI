package com.messenger.app.data.attachment

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class VoicePlaybackState(
    val playingAttachmentId: String? = null,
    val durationMs: Int = 0,
    val positionMs: Int = 0,
    val pausedAttachmentId: String? = null,
    val speed: Float = 1f,
)

/** Narrow seam so the media gallery ViewModel can be unit-tested (M.2). */
interface VoicePlayback {
    fun togglePlay(attachmentId: String, file: File)
}

/** Exactly one voice message plays at a time app-wide via a single shared
 *  `MediaPlayer` - never one instance per chat bubble (Этап H п.30).
 *  Scrolling the chat list never disturbs an in-progress playback since
 *  this lives above any individual bubble Composable's lifecycle.
 *
 *  Playback position is published centrally at ~10 Hz (Phase 5 §8) so the
 *  active bubble can render its played/unplayed waveform split without any
 *  per-bubble timer; inactive bubbles recompose once on the id change only. */
class VoicePlayerController internal constructor(private val createPlayer: () -> VoicePlayerBackend) : VoicePlayback {
    constructor() : this({ AndroidVoicePlayer() })

    private var cacheLease: AutoCloseable? = null
    private var mediaPlayer: VoicePlayerBackend? = null
    private var preparing = false
    private var interruptedForCall = false
    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state
    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            val player = mediaPlayer ?: return
            if (preparing || _state.value.playingAttachmentId == null) return
            try {
                _state.value = _state.value.copy(positionMs = player.position)
                handler.postDelayed(this, PROGRESS_INTERVAL_MS)
            } catch (_: Exception) { stop() }
        }
    }

    override fun togglePlay(attachmentId: String, file: File) {
        if (interruptedForCall) return
        if (_state.value.playingAttachmentId == attachmentId) {
            // A second tap during preparation cancels it; pause is illegal then.
            if (preparing) { stop(); return }
            try {
                mediaPlayer?.pause()
                handler.removeCallbacks(progressRunnable)
                _state.value = _state.value.copy(playingAttachmentId = null, pausedAttachmentId = attachmentId)
            } catch (_: Exception) { stop() }
            return
        }
        if (_state.value.pausedAttachmentId == attachmentId && mediaPlayer != null) {
            try {
                mediaPlayer?.start()
                _state.value = _state.value.copy(playingAttachmentId = attachmentId, pausedAttachmentId = null)
                handler.post(progressRunnable)
            } catch (_: Exception) { stop() }
            return
        }
        stop()
        val player = try { createPlayer() } catch (_: Exception) { return }
        cacheLease = com.messenger.app.data.local.MediaCacheUse.acquire(listOf(file))
        com.messenger.app.data.local.MediaCacheUse.touchCache(file)
        mediaPlayer = player
        preparing = true
        // Reflect the accepted tap immediately, without polling an unprepared player.
        _state.value = VoicePlaybackState(playingAttachmentId = attachmentId)
        try {
            player.prepare(file,
                onPrepared = {
                    if (mediaPlayer === player && preparing) {
                        try {
                            preparing = false
                            player.start()
                            _state.value = VoicePlaybackState(attachmentId, player.duration, 0)
                            handler.post(progressRunnable)
                        } catch (_: Exception) { stop() }
                    }
                },
                onFinished = { if (mediaPlayer === player) stop() })
        } catch (_: Exception) { if (mediaPlayer === player) stop() }
    }

    fun seek(fraction: Float) {
        if (preparing || !fraction.isFinite()) return
        try { mediaPlayer?.let {
            val position = (it.duration * fraction.coerceIn(0f, 1f)).toInt()
            it.seek(position)
            _state.value = _state.value.copy(positionMs = position)
        } }
        catch (_: Exception) { stop() }
    }
    fun cycleSpeed() {
        if (preparing) return
        val speed = when (_state.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        try {
            mediaPlayer?.let {
                it.setSpeed(speed, _state.value.pausedAttachmentId != null)
                _state.value = _state.value.copy(speed = speed)
            }
        } catch (_: Exception) { stop() }
    }
    fun stop() {
        handler.removeCallbacks(progressRunnable)
        val old = mediaPlayer
        mediaPlayer = null // Invalidate callbacks before releasing, including pending preparation.
        preparing = false
        _state.value = VoicePlaybackState()
        runCatching { old?.release() }
        cacheLease?.close(); cacheLease = null
    }
    /** Calls own audio until their terminal state. Do not auto-resume a voice
     * message after hangup, or let a pending download restart it mid-call. */
    fun setCallActive(active: Boolean) {
        interruptedForCall = active
        if (active) stop()
    }
    fun currentPositionMs(): Int = if (preparing) 0 else runCatching { mediaPlayer?.position ?: 0 }.getOrDefault(0)

    private companion object { const val PROGRESS_INTERVAL_MS = 100L }
}

/** Test seam for asynchronous preparation and late platform callbacks. Main-thread only. */
internal interface VoicePlayerBackend {
    val duration: Int
    val position: Int
    fun prepare(file: File, onPrepared: () -> Unit, onFinished: () -> Unit)
    fun start()
    fun pause()
    fun seek(position: Int)
    fun setSpeed(speed: Float, paused: Boolean)
    fun release()
}

private class AndroidVoicePlayer : VoicePlayerBackend {
    private val player = MediaPlayer()
    override val duration get() = player.duration
    override val position get() = player.currentPosition
    override fun prepare(file: File, onPrepared: () -> Unit, onFinished: () -> Unit) {
        player.setOnPreparedListener { onPrepared() }
        player.setOnCompletionListener { onFinished() }
        player.setOnErrorListener { _, _, _ -> onFinished(); true }
        player.setDataSource(file.absolutePath)
        player.prepareAsync()
    }
    override fun start() = player.start()
    override fun pause() = player.pause()
    override fun seek(position: Int) = player.seekTo(position)
    override fun setSpeed(speed: Float, paused: Boolean) {
        player.playbackParams = player.playbackParams.setSpeed(speed)
        if (paused) player.pause()
    }
    override fun release() {
        player.setOnPreparedListener(null)
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
        player.release()
    }
}
