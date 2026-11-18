package com.messenger.app.ui.chats

import kotlin.test.*

class VoicePermissionStateTest {
    @Test fun `first request has no rationale but still launches a prompt`() {
        var remembered = false
        val state = VoicePermissionState(false) { remembered = it }
        assertEquals(VoicePermissionState.Action.Prompt, state.request(false, false))
        assertFalse(remembered) // An unavailable capture lease must not count as a permission request.
        assertEquals(VoicePermissionState.Action.Prompt, state.request(false, false))
        state.launched()
        assertTrue(remembered)
        state.result(false, true)
        assertEquals(VoicePermissionState.Feedback.Denied, state.feedback)
        state.dismiss()
        assertNull(state.feedback)
        assertEquals(VoicePermissionState.Action.Prompt, state.request(false, true))
    }

    @Test fun `permanent denial explains settings and never silently reopens the unavailable prompt`() {
        var requests = 0
        val state = VoicePermissionState(false) { if (it) requests++ }
        state.request(false, false)
        state.launched()
        state.result(false, false)
        assertEquals(VoicePermissionState.Feedback.Settings, state.feedback)
        state.dismiss()
        assertEquals(VoicePermissionState.Action.ExplainSettings, state.request(false, false))
        assertEquals(VoicePermissionState.Feedback.Settings, state.feedback)
        assertEquals(1, requests)
    }

    @Test fun `history survives recreation and a settings grant overrides denial history`() {
        val changes = mutableListOf<Boolean>()
        val restored = VoicePermissionState(true) { changes += it }
        assertEquals(VoicePermissionState.Action.ExplainSettings, restored.request(false, false))
        assertEquals(VoicePermissionState.Action.Granted, restored.request(true, false))
        assertNull(restored.feedback)
        restored.result(true, false)
        assertNull(restored.feedback)
        assertEquals(listOf(false), changes)
    }

    @Test fun `temporary denial survives a cancelled hold and feedback can always be dismissed`() {
        val state = VoicePermissionState(true) {}
        // Result feedback is independent of whether the original hold/capture lease still exists.
        state.result(false, true)
        assertEquals(VoicePermissionState.Feedback.Denied, state.feedback)
        state.dismiss()
        assertNull(state.feedback)
        assertEquals(VoicePermissionState.Action.Prompt, state.request(false, true))
    }

    @Test fun `a previous grant followed by one-time expiry or settings revocation can prompt again`() {
        var persisted = false
        val state = VoicePermissionState(false) { persisted = it }
        state.launched()
        state.result(true, false)
        assertFalse(persisted)
        val restored = VoicePermissionState(persisted) { persisted = it }
        assertEquals(VoicePermissionState.Action.Prompt, restored.request(false, false))
        assertNull(restored.feedback)
    }
}
