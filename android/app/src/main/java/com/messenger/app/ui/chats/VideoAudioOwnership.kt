package com.messenger.app.ui.chats

/** Empty tracks before readiness are unknown, not evidence of an audible file. */
internal fun videoAudioAvailability(tracks: androidx.media3.common.Tracks, ready: Boolean): Boolean? =
    if (tracks.isEmpty && !ready) null else tracks.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_AUDIO)

/** Volume is silenced before releasing focus/tracks; acquiring is explicit and never starts playback. */
internal class VideoAudioOwnership(private val volume: (Boolean) -> Unit, private val focus: (Boolean) -> Unit,
    private val tracks: (Boolean) -> Unit = {}) {
    private var audible: Boolean? = null
    fun apply(allowed: Boolean, muted: Boolean, wantsPlay: Boolean, hasAudio: Boolean? = null) {
        // Only positive track discovery may interrupt another audio owner.
        val next = allowed && !muted && wantsPlay && hasAudio == true
        if (audible == next) return
        if (!next) { volume(false); tracks(false); focus(false) }
        else { tracks(true); focus(true); volume(true) }
        audible = next
    }
}
