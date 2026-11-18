package com.messenger.app.data.attachment

import com.messenger.app.ui.chats.VoiceTakeDestination
import com.messenger.app.ui.chats.voiceTakeDestination
import java.io.File
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class VoiceCaptureSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Capture(val file: File, val interrupted: () -> Unit) : VoiceCapture {
        var stops = 0
        var releases = 0
        var isSilenced = false
        var configurationMissing = false
        var paused = false
        var stopError = false
        var levelError = false
        var duringStart: (() -> Unit)? = null
        override fun start() { file.writeText("usable encoded audio"); duringStart?.invoke() }
        override fun stop() { stops++; if (stopError) error("recorder server failed") }
        override fun release() { releases++ }
        override fun pause() { paused = true }
        override fun resume() { paused = false }
        override fun level(): Float { if (levelError) error("capture lost"); return .5f }
        override fun silenced() = isSilenced
        override fun requiresReview() = voiceCaptureRequiresReview(paused, isSilenced, configurationMissing)
        fun silence() { isSilenced = true; interrupted() }
        fun focusLoss() = interrupted()
        fun recorderError() { stopError = true; interrupted() }
    }

    private inner class Fixture(private val configure: (Capture) -> Unit = {}) {
        var time = 100L
        var encodedDuration: Long? = 1500L
        val captures = mutableListOf<Capture>()
        val session = VoiceCaptureSession(
            output = { temporary.newFile() },
            create = { file, signal -> Capture(file, signal).also { captures += it; configure(it) } },
            now = { time },
            audioDuration = { encodedDuration },
        )
        val capture: Capture get() = captures.last()
    }

    @Test fun `silence callback finalizes and preserves once even without a lifecycle stop`() {
        val f = Fixture()
        val drafts = mutableListOf<Pair<File, Long>>()
        var uiRecording = true
        var notifications = 0
        f.session.onInterrupted = {
            notifications++
            uiRecording = false
            f.session.stop()?.let(drafts::add) // The same stop-to-draft drain used by the UI.
        }
        f.session.start()
        f.time += 1700
        f.capture.silence()
        f.time += 20000
        f.capture.focusLoss()
        f.capture.recorderError()
        assertFalse(uiRecording)
        assertFalse(f.session.hasRecordingSession)
        assertTrue(f.session.wasInterrupted)
        assertEquals(1, notifications)
        assertEquals(1, f.capture.stops)
        assertEquals(1, f.capture.releases)
        assertEquals(1500L, drafts.single().second)
        assertTrue(drafts.single().first.exists())
        assertEquals(0L, f.session.recordedDurationMs)
        assertNull(f.session.stop()) // A later release, background event or disposal cannot consume twice.
    }

    @Test fun `focus loss freezes the clock and does not restart on a later platform event`() {
        val f = Fixture()
        f.session.start()
        f.time += 1600
        f.capture.focusLoss()
        val frozen = f.session.recordedDurationMs
        f.time += 50000
        f.capture.focusLoss()
        assertEquals(frozen, f.session.recordedDurationMs)
        assertFalse(f.session.hasRecordingSession)
        assertEquals(1, f.capture.releases)
        assertNotNull(f.session.stop())
    }

    @Test fun `release before the silence callback still goes to review rather than automatic send`() {
        val f = Fixture()
        f.session.start()
        f.capture.isSilenced = true // System state changes before its queued callback is dispatched.
        val result = assertNotNull(f.session.stop())
        assertEquals(VoiceTakeDestination.Review,
            voiceTakeDestination(true && !f.session.wasInterrupted, result.second, false))
        assertTrue(result.first.exists())
        assertEquals(1, f.capture.stops)
        f.capture.silence()
        assertNull(f.session.stop())
    }

    @Test fun `recorder error salvages readable audio even when stop throws`() {
        val f = Fixture()
        f.session.start()
        f.capture.recorderError()
        val result = assertNotNull(f.session.stop())
        assertTrue(f.session.wasInterrupted)
        assertEquals(1500L, result.second)
        assertEquals("usable encoded audio", result.first.readText())
        assertEquals(1, f.capture.releases)
    }

    @Test fun `capture disappearing before its callback cannot auto-send the remaining audio`() {
        val f = Fixture()
        f.session.start()
        f.capture.configurationMissing = true
        assertNotNull(f.session.stop())
        assertTrue(f.session.wasInterrupted)
        assertFalse(f.session.hasRecordingSession)
    }

    @Test fun `unreadable error take is discarded but still stops UI timer and capture ownership`() {
        val f = Fixture()
        var notifications = 0
        f.session.onInterrupted = { notifications++ }
        val file = f.session.start()
        f.encodedDuration = null
        f.capture.recorderError()
        assertEquals(1, notifications)
        assertFalse(f.session.hasRecordingSession)
        assertEquals(0L, f.session.recordedDurationMs)
        assertNull(f.session.stop())
        assertFalse(file.exists())
        assertEquals(1, f.capture.releases)
    }

    @Test fun `an error during user stop cannot turn salvaged audio into an automatic send`() {
        val f = Fixture()
        f.session.start()
        f.capture.stopError = true
        val result = assertNotNull(f.session.stop())
        assertTrue(f.session.wasInterrupted)
        assertEquals(VoiceTakeDestination.Review,
            voiceTakeDestination(!f.session.wasInterrupted, result.second, false))
    }

    @Test fun `late callbacks from a cancelled or completed capture do not interrupt the next take`() {
        val f = Fixture()
        val discarded = f.session.start()
        val old = f.capture
        f.session.cancel()
        assertFalse(discarded.exists())
        f.session.start()
        old.silence()
        old.recorderError()
        assertTrue(f.session.hasRecordingSession)
        assertFalse(f.session.wasInterrupted)
        assertEquals(0, f.capture.stops)
        assertNotNull(f.session.stop())
        assertEquals(1, old.releases)
    }

    @Test fun `pause freezes elapsed time and resume excludes the paused interval`() {
        val f = Fixture()
        f.session.start()
        f.time += 600
        f.session.togglePause()
        assertTrue(f.session.isPaused)
        f.time += 10000
        assertEquals(600L, f.session.recordedDurationMs)
        f.session.togglePause()
        f.time += 400
        assertEquals(1000L, f.session.recordedDurationMs)
        assertFalse(f.session.isPaused)
        f.session.cancel()
    }

    @Test fun `meter read failures use the same interruption and preservation path`() {
        val f = Fixture()
        var notified = 0
        f.session.onInterrupted = { notified++ }
        f.session.start()
        f.capture.levelError = true
        assertEquals(0f, f.session.level())
        assertEquals(1, notified)
        assertTrue(f.session.wasInterrupted)
        assertNotNull(f.session.stop())
    }

    @Test fun `interruption during startup cannot reactivate the recording session or timer`() {
        val f = Fixture { capture -> capture.duringStart = capture::focusLoss }
        val drafts = mutableListOf<Pair<File, Long>>()
        f.session.onInterrupted = { f.session.stop()?.let(drafts::add) }
        f.session.start()
        f.time += 10000
        assertFalse(f.session.hasRecordingSession) // The UI checks this before entering recording state.
        assertEquals(0L, f.session.recordedDurationMs)
        assertEquals(1, drafts.size)
        assertEquals(1, f.capture.releases)
        assertNull(f.session.stop())
    }

    @Test fun `a deliberately paused take can still send when its active configuration disappears`() {
        val f = Fixture()
        f.session.start()
        f.time += 1500
        f.session.togglePause()
        f.capture.configurationMissing = true
        val result = assertNotNull(f.session.stop())
        assertFalse(f.session.wasInterrupted)
        assertEquals(VoiceTakeDestination.Send,
            voiceTakeDestination(!f.session.wasInterrupted, result.second, false))
    }
}
