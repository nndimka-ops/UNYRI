package com.messenger.app.ui.chats

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi

internal const val VIDEO_SEEK_TIMEOUT_MS = 2_000L
internal fun interface VideoSeekTimeout {
    /** Application-thread, one-shot callback; the returned function removes it. */
    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit
}

/**
 * A rendered frame or native BUFFERING -> READY completes a seek. Media3 masks a seek as
 * BUFFERING synchronously, then restores READY after processing an adjusted no-op without
 * resetting the renderer. Its synchronous SEEK discontinuity is not an acknowledgement.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class RenderedVideoSeekBackend(private val player: Player, private val timeout: VideoSeekTimeout, private val isScrubbing: () -> Boolean = { false },
    private val duration: () -> Long) : VideoSeekBackend {
    private var listener: Player.Listener? = null
    private var cancelTimeout: (() -> Unit)? = null
    override fun seek(request: VideoSeekRequest, completed: (VideoSeekResult) -> Unit) {
        cancel()
        try {
            player.withVideoSeekParameters(videoSeekParameters(request.exact, isScrubbing(), duration())) {
                if (request.position == player.currentPosition && player.playbackState == Player.STATE_READY) {
                    completed(VideoSeekResult.LANDED); return
                }
                val observer = object : Player.Listener {
                    private var buffering = player.playbackState == Player.STATE_BUFFERING
                    private fun finish(result: VideoSeekResult) {
                        if (listener !== this) return
                        cancel()
                        completed(result)
                    }
                    override fun onRenderedFirstFrame() {
                        // A previously queued frame can arrive while the new seek is still masked.
                        if (player.playbackState == Player.STATE_READY) finish(VideoSeekResult.LANDED)
                    }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT && player.playbackState == Player.STATE_READY) {
                            finish(VideoSeekResult.LANDED)
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) { finish(VideoSeekResult.FAILED) }
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> buffering = true
                            Player.STATE_READY -> if (buffering && player.playbackState == Player.STATE_READY) finish(VideoSeekResult.LANDED)
                            Player.STATE_IDLE, Player.STATE_ENDED -> finish(VideoSeekResult.FAILED)
                        }
                    }
                    fun expired() {
                        val result = when {
                            player.playerError != null || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED -> VideoSeekResult.FAILED
                            player.playbackState == Player.STATE_READY -> VideoSeekResult.LANDED
                            else -> VideoSeekResult.TIMED_OUT
                        }
                        finish(result)
                    }
                }
                listener = observer
                player.addListener(observer)
                cancelTimeout = timeout.schedule(VIDEO_SEEK_TIMEOUT_MS, observer::expired)
                player.seekTo(request.position)
            }
        } catch (failure: RuntimeException) { cancel(); completed(VideoSeekResult.FAILED) }
    }
    override fun cancel() {
        listener?.let(player::removeListener); listener = null
        cancelTimeout?.invoke(); cancelTimeout = null
        player.restoreExactVideoSeek()
    }
}
