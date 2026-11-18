package com.messenger.app.ui.chats
import kotlin.test.*
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoAudioOwnershipTest {
    @Test fun rememberedMuteAndPausedPlaybackNeverClaimFocus() {
        val calls = mutableListOf<String>(); val audio = VideoAudioOwnership({ calls += "volume:$it" }, { calls += "focus:$it" }, { calls += "tracks:$it" })
        audio.apply(true, true, true, hasAudio = true); assertEquals(listOf("volume:false", "tracks:false", "focus:false"), calls)
        calls.clear(); audio.apply(true, false, false, hasAudio = true); assertTrue(calls.isEmpty())
        audio.apply(true, false, true, hasAudio = true); assertEquals(listOf("tracks:true", "focus:true", "volume:true"), calls)
    }
    @Test fun mutingSilencesAndDisablesTracksBeforeReleasingFocusAndUnmuteDoesNotPlay() {
        val calls = mutableListOf<String>(); val audio = VideoAudioOwnership({ calls += "volume:$it" }, { calls += "focus:$it" }, { calls += "tracks:$it" })
        audio.apply(true, false, true, hasAudio = true); calls.clear(); audio.apply(true, true, true, hasAudio = true)
        assertEquals(listOf("volume:false", "tracks:false", "focus:false"), calls)
        calls.clear(); audio.apply(true, false, false, hasAudio = true); assertTrue(calls.isEmpty())
    }
    @Test fun callsSilentClipsAndRepeatedUpdatesCannotRetainOrChurnOwnership() {
        val focus = mutableListOf<Boolean>(); val audio = VideoAudioOwnership({}, focus::add)
        audio.apply(true, false, true, hasAudio = true); audio.apply(false, false, true, hasAudio = true)
        repeat(10) { audio.apply(false, false, true, hasAudio = true) }; assertEquals(listOf(true, false), focus)
        audio.apply(true, false, true, hasAudio = false); assertEquals(listOf(true, false), focus)
    }

    private fun tracks(mime: String, support: Int = C.FORMAT_HANDLED) = Tracks(listOf(Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(mime).build()), false, intArrayOf(support), booleanArrayOf(false))))
    @Test fun loadingToSilentVideoNeverClaimsFocusOrStopsVoicePlayback() {
        val calls = mutableListOf<String>(); var stoppedVoice = 0
        val audio = VideoAudioOwnership({ calls += "volume:$it" }, { calls += "focus:$it"; if (it) stoppedVoice++ }, { calls += "tracks:$it" })
        // Same unknown default used before VideoPage has any track information.
        repeat(3) { audio.apply(true, false, true) }
        assertNull(videoAudioAvailability(Tracks.EMPTY, ready = false))
        audio.apply(true, false, true, videoAudioAvailability(tracks("video/avc"), ready = false))
        audio.apply(true, false, true, videoAudioAvailability(tracks("video/avc"), ready = true))
        assertEquals(listOf("volume:false", "tracks:false", "focus:false"), calls)
        assertEquals(0, stoppedVoice)
    }
    @Test fun supportedAudioDiscoveryAcquiresOnceEvenWhenAudioTracksWereInitiallyDisabled() {
        val focus = mutableListOf<Boolean>(); val enabled = mutableListOf<Boolean>()
        val audio = VideoAudioOwnership({}, focus::add, enabled::add)
        audio.apply(true, false, true)
        val discovered = videoAudioAvailability(tracks("audio/mp4a-latm"), ready = false)
        assertEquals(true, discovered) // support, not selection: initial mute disabled selection
        repeat(3) { audio.apply(true, false, true, discovered) }
        assertEquals(listOf(false, true), focus); assertEquals(listOf(false, true), enabled)
    }
    @Test fun emptyReadyAndUnsupportedAudioRemainInaudible() {
        val focus = mutableListOf<Boolean>(); val audio = VideoAudioOwnership({}, focus::add)
        assertEquals(false, videoAudioAvailability(Tracks.EMPTY, ready = true))
        val unsupported = videoAudioAvailability(tracks("audio/mp4a-latm", C.FORMAT_UNSUPPORTED_SUBTYPE), ready = true)
        assertEquals(false, unsupported)
        audio.apply(true, false, true, videoAudioAvailability(Tracks.EMPTY, ready = false))
        audio.apply(true, false, true, videoAudioAvailability(Tracks.EMPTY, ready = true))
        audio.apply(true, false, true, unsupported)
        assertEquals(listOf(false), focus)
    }
}
