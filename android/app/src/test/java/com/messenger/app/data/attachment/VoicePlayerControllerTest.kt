package com.messenger.app.data.attachment

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VoicePlayerControllerTest {
    @Test fun callInterruptsPreparationAndBlocksLatePlaybackRequests() {
        val player = Fake(); var creations = 0
        val controller = VoicePlayerController { creations++; player }
        controller.togglePlay("a", File("a"))
        controller.setCallActive(true)
        player.ready()
        controller.togglePlay("b", File("b"))
        assertEquals(1, creations)
        assertEquals(0, player.starts)
        assertEquals(1, player.releases)
        assertEquals(VoicePlaybackState(), controller.state.value)
    }
    @Test fun callDiscardsPausedPlaybackAndHangupDoesNotAutoResume() {
        val old = Fake(); val next = Fake(); val players = ArrayDeque(listOf(old, next))
        val controller = VoicePlayerController { players.removeFirst() }
        controller.togglePlay("a", File("a")); old.ready()
        controller.togglePlay("a", File("a"))
        controller.setCallActive(true)
        controller.togglePlay("a", File("a"))
        controller.setCallActive(false)
        assertEquals(1, old.starts)
        assertEquals(1, old.releases)
        assertEquals(VoicePlaybackState(), controller.state.value)
        controller.togglePlay("b", File("b")); next.ready()
        assertEquals(1, next.starts)
        assertEquals("b", controller.state.value.playingAttachmentId)
    }
    @Test fun ringingStopsPlayingVoiceAndRepeatedCallUpdatesAreIdempotent() {
        val player = Fake(); val controller = VoicePlayerController { player }
        controller.togglePlay("a", File("a")); player.ready()
        controller.setCallActive(true)
        controller.setCallActive(true)
        player.finish()
        assertEquals(1, player.starts)
        assertEquals(1, player.releases)
        assertEquals(VoicePlaybackState(), controller.state.value)
    }
    @Test fun speedCyclesOnPauseWithoutResumingOrReplacingPlayback() {
        val player = Fake(); val controller = VoicePlayerController { player }
        controller.togglePlay("a", File("a")); player.ready()
        controller.togglePlay("a", File("a"))
        for (expected in listOf(1.5f, 2f, 1f)) {
            controller.cycleSpeed()
            assertEquals(expected, controller.state.value.speed)
            assertEquals("a", controller.state.value.pausedAttachmentId)
            assertEquals(1, player.starts)
            assertEquals(0, player.releases)
        }
    }
    @Test fun pausedSeekUpdatesVisibleProgressWithoutStartingPlayback() {
        val player = Fake(); val controller = VoicePlayerController { player }
        controller.togglePlay("a", File("a")); player.ready()
        controller.togglePlay("a", File("a"))
        controller.seek(.75f)
        assertEquals(900, controller.state.value.positionMs)
        assertEquals("a", controller.state.value.pausedAttachmentId)
        assertEquals(1, player.starts)
        controller.seek(Float.NaN)
        assertEquals(1, player.seeks)
        controller.seek(2f)
        assertEquals(1200, controller.state.value.positionMs)
    }
    private class Fake : VoicePlayerBackend {
        override val duration = 1200
        override val position = 300
        lateinit var ready: () -> Unit
        lateinit var finish: () -> Unit
        var starts = 0
        var pauses = 0
        var releases = 0
        var seeks = 0
        var speeds = 0
        override fun prepare(file: File, onPrepared: () -> Unit, onFinished: () -> Unit) { ready = onPrepared; finish = onFinished }
        override fun start() { starts++ }
        override fun pause() { pauses++ }
        override fun seek(position: Int) { seeks++ }
        override fun setSpeed(speed: Float, paused: Boolean) { speeds++ }
        override fun release() { releases++ }
    }
    @Test fun doesNotStartOrSeekBeforePreparation() {
        val player = Fake(); val controller = VoicePlayerController { player }
        controller.togglePlay("a", File("a"))
        controller.seek(.5f); controller.cycleSpeed()
        assertEquals(0, player.starts); assertEquals(0, player.seeks); assertEquals(0, player.speeds)
        assertEquals(0, controller.currentPositionMs())
        player.ready()
        assertEquals(1, player.starts); assertEquals(1200, controller.state.value.durationMs)
    }
    @Test fun secondTapCancelsPreparationWithoutPausing() {
        val player = Fake(); val controller = VoicePlayerController { player }
        controller.togglePlay("a", File("a")); controller.togglePlay("a", File("a"))
        player.ready()
        assertEquals(0, player.starts); assertEquals(0, player.pauses); assertEquals(1, player.releases)
        assertNull(controller.state.value.playingAttachmentId)
    }
    @Test fun lateCallbacksCannotStartOrStopReplacement() {
        val old = Fake(); val next = Fake(); val players = ArrayDeque(listOf(old, next))
        val controller = VoicePlayerController { players.removeFirst() }
        controller.togglePlay("a", File("a")); controller.togglePlay("b", File("b"))
        old.ready(); old.finish(); next.ready(); old.finish()
        assertEquals(0, old.starts); assertEquals(1, old.releases)
        assertEquals(1, next.starts); assertEquals("b", controller.state.value.playingAttachmentId)
        assertEquals(0, next.releases)
    }
    @Test fun logoutStopInvalidatesPendingPreparation() {
        val player = Fake(); val controller = VoicePlayerController { player }
        controller.togglePlay("a", File("a")); controller.stop(); player.ready(); player.finish()
        assertEquals(0, player.starts); assertEquals(1, player.releases)
        assertEquals(VoicePlaybackState(), controller.state.value)
    }
    @Test fun preparedPlaybackCanPauseAndResumeWithoutRecreatingPlayer() {
        val player = Fake(); var creations = 0
        val controller = VoicePlayerController { creations++; player }
        controller.togglePlay("a", File("a")); player.ready(); controller.togglePlay("a", File("a"))
        assertEquals("a", controller.state.value.pausedAttachmentId)
        controller.togglePlay("a", File("a"))
        assertEquals(1, creations); assertEquals(1, player.pauses); assertEquals(2, player.starts)
        player.finish()
        assertEquals(VoicePlaybackState(), controller.state.value); assertEquals(1, player.releases)
    }
    @Test fun preparationFailureReleasesAndAllowsRetry() {
        val player = Fake(); val retry = Fake(); val players = ArrayDeque(listOf(player, retry))
        val controller = VoicePlayerController { players.removeFirst() }
        controller.togglePlay("a", File("a")); player.finish()
        assertEquals(1, player.releases); assertEquals(VoicePlaybackState(), controller.state.value)
        player.ready(); assertEquals(0, player.starts)
        controller.togglePlay("a", File("a")); retry.ready()
        assertEquals(1, retry.starts); assertEquals("a", controller.state.value.playingAttachmentId)
    }
}
