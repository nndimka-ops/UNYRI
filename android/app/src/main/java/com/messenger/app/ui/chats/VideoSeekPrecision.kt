package com.messenger.app.ui.chats

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

/** Approximation belongs to a live drag, never to a player or a later discrete seek. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun videoSeekParameters(exact: Boolean, activeScrub: Boolean, duration: Long): SeekParameters =
    if (exact || !activeScrub) SeekParameters.EXACT
    else if (duration > 600_000) SeekParameters.CLOSEST_SYNC else SeekParameters(100_000, 100_000)

@androidx.annotation.OptIn(UnstableApi::class)
internal fun Player.restoreExactVideoSeek() { (this as? ExoPlayer)?.setSeekParameters(SeekParameters.EXACT) }

/** Media3 queues parameter changes and the seek in order. Restore the default immediately
 * after issuing the command, including no-op/throwing commands, rather than waiting for a frame.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal inline fun <T> Player.withVideoSeekParameters(parameters: SeekParameters, command: () -> T): T {
    try {
        (this as? ExoPlayer)?.setSeekParameters(parameters)
        return command()
    } finally { restoreExactVideoSeek() }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun Player.seekVideoExact(position: Long) {
    withVideoSeekParameters(SeekParameters.EXACT) { seekTo(position) }
}
