package com.messenger.app.data.attachment

import android.media.AudioManager
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class VoiceCaptureStartupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `platform phone communication and ringing modes never request focus or prepare capture`() {
        for (mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_RINGTONE, AudioManager.MODE_CALL_SCREENING, AudioManager.MODE_INVALID)) {
            val trace = mutableListOf<String>()
            val startup = VoiceCaptureStartup({ mode }, { trace += "request focus"; true })
            assertFailsWith<VoicePlatformCallActive> {
                startup.start({ trace += "prepare" }, { trace += "record" })
            }
            assertTrue(trace.isEmpty(), "Must not disturb external audio mode $mode")
        }
    }

    @Test fun `call starting during preparation is rejected before the focus request`() {
        var mode = AudioManager.MODE_NORMAL
        val trace = mutableListOf<String>()
        val startup = VoiceCaptureStartup({ mode }, { trace += "focus"; true })
        assertFailsWith<VoicePlatformCallActive> {
            startup.start({ trace += "prepare"; mode = AudioManager.MODE_IN_COMMUNICATION }, { trace += "record" })
        }
        assertEquals(listOf("prepare"), trace)
    }

    @Test fun `normal startup requests focus after preparation and records only after grant`() {
        for (granted in listOf(true, false)) {
            val trace = mutableListOf<String>()
            val startup = VoiceCaptureStartup({ AudioManager.MODE_NORMAL }, { trace += "focus"; granted })
            val operation = { startup.start({ trace += "prepare" }, { trace += "record" }) }
            if (granted) operation() else assertFailsWith<IllegalStateException>(block = operation)
            assertEquals(if (granted) listOf("prepare", "focus", "record") else listOf("prepare", "focus"), trace)
        }
    }

    @Test fun `blocked startup releases its recorder and file without touching call focus`() {
        val file = temporary.newFile()
        var focusRequests = 0
        var released = 0
        val startup = VoiceCaptureStartup({ AudioManager.MODE_IN_COMMUNICATION }, { focusRequests++; true })
        val session = VoiceCaptureSession({ file }, { _, _ -> object : VoiceCapture {
            override fun start() = startup.start({ fail("Must not prepare") }, { fail("Must not record") })
            override fun stop() = Unit
            override fun release() { released++ }
            override fun pause() = Unit
            override fun resume() = startup.requireIdlePlatform()
            override fun level() = 0f
        } }, { 0L }, { null })
        assertFailsWith<VoicePlatformCallActive> { session.start() }
        assertEquals(0, focusRequests)
        assertEquals(1, released)
        assertFalse(file.exists())
        assertFalse(session.hasRecordingSession)
    }

    @Test fun `mode lookup failure fails closed before preparation or focus`() {
        var focusRequests = 0
        val startup = VoiceCaptureStartup({ error("audio service unavailable") }, { focusRequests++; true })
        assertFailsWith<IllegalStateException> { startup.start({ fail("Must not prepare") }, { fail("Must not record") }) }
        assertEquals(0, focusRequests)
    }
}
