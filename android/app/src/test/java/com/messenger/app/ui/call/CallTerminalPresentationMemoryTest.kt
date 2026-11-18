package com.messenger.app.ui.call

import com.messenger.app.data.call.AudioRouteKind
import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.*
import kotlin.test.*

class CallTerminalPresentationMemoryTest {
    private val call = CallInfo("call-a", "peer", "Peer", CallMediaKind.AUDIO, CallDirection.OUTGOING)
    private val audio = CallUiState.Connected(call, 1_000)
    private val video = audio.copy(videoMode = VideoMode.VIDEO, localVideoEnabled = false,
        isMuted = true, audioRoute = AudioRouteKind.BLUETOOTH)

    @Test fun `audio call upgraded to video keeps negotiated controls through end`() {
        val memory = CallTerminalPresentationMemory()
        memory.observe(audio)
        val negotiated = memory.observe(video)
        val ended = assertNotNull(memory.observe(CallUiState.Ended(call, CallEndReason.HANGUP)))
        assertEquals(negotiated, ended)
        assertTrue(ended.videoLayout)
        assertEquals(CallPresentationPolicy.VideoControls(call.callId, false, true, AudioRouteKind.BLUETOOTH), ended.videoControls)
    }

    @Test fun `negotiated downgrade overrides original video intent before end`() {
        val originalVideo = call.copy(mediaKind = CallMediaKind.VIDEO)
        val memory = CallTerminalPresentationMemory()
        memory.observe(CallUiState.OutgoingRinging(originalVideo))
        memory.observe(audio.copy(call = originalVideo, videoMode = VideoMode.AUDIO))
        val ended = assertNotNull(memory.observe(CallUiState.Ended(originalVideo, CallEndReason.HANGUP)))
        assertFalse(ended.videoLayout)
        assertNull(ended.videoControls)
    }

    @Test fun `reconnect and camera off preserve negotiated video layout and route`() {
        val memory = CallTerminalPresentationMemory()
        memory.observe(CallUiState.Reconnecting(call, CallUiState.Reconnecting(call, video)))
        val ended = assertNotNull(memory.observe(CallUiState.Ended(call, CallEndReason.FAILED)))
        assertTrue(ended.videoLayout)
        assertFalse(ended.localVideoEnabled)
        assertTrue(ended.isMuted)
        assertEquals(AudioRouteKind.BLUETOOTH, ended.audioRoute)
    }

    @Test fun `video canceled before connection retains the connecting controls`() {
        val originalVideo = call.copy(mediaKind = CallMediaKind.VIDEO)
        val memory = CallTerminalPresentationMemory()
        memory.observe(CallUiState.OutgoingRinging(originalVideo))
        val connecting = memory.observe(CallUiState.Connecting(originalVideo, isMuted = true,
            audioRoute = AudioRouteKind.WIRED_HEADSET, localVideoEnabled = false))
        assertEquals(connecting, memory.observe(CallUiState.Ended(originalVideo, CallEndReason.CANCELLED)))
        assertTrue(assertNotNull(memory.snapshot).videoLayout)
    }

    @Test fun `replacement call cannot inherit the previous negotiated video layout`() {
        val replacement = call.copy(callId = "call-b")
        val memory = CallTerminalPresentationMemory()
        memory.observe(video)
        memory.observe(CallUiState.OutgoingRinging(replacement))
        val ended = assertNotNull(memory.observe(CallUiState.Ended(replacement, CallEndReason.REJECTED)))
        assertEquals(replacement.callId, ended.callId)
        assertFalse(ended.videoLayout)
        assertFalse(ended.isMuted)
        assertEquals(AudioRouteKind.EARPIECE, ended.audioRoute)
    }

    @Test fun `orphan terminal event cannot borrow another call controls`() {
        val memory = CallTerminalPresentationMemory()
        memory.observe(video.copy(call = call.copy(callId = "other")))
        val ended = assertNotNull(memory.observe(CallUiState.Ended(call, CallEndReason.HANGUP)))
        assertEquals(call.callId, ended.callId)
        assertFalse(ended.videoLayout)
    }

    @Test fun `idle and failed without a call discard retained controls`() {
        val memory = CallTerminalPresentationMemory()
        memory.observe(video)
        assertNull(memory.observe(CallUiState.Idle))
        assertFalse(assertNotNull(memory.observe(CallUiState.Ended(call, CallEndReason.HANGUP))).videoLayout)
        memory.observe(video)
        assertNull(memory.observe(CallUiState.Failed(null, CallFailureReason.DEVICE_RESTRICTED)))
        assertNull(memory.snapshot)
    }

    @Test fun `restored terminal controls survive recreation but stay scoped to their call`() {
        val original = CallTerminalPresentationMemory()
        original.observe(video)
        val restored = CallTerminalPresentationMemory(original.snapshot)
        assertEquals(original.snapshot, restored.observe(CallUiState.Ended(call, CallEndReason.HANGUP)))
        assertFalse(assertNotNull(restored.observe(CallUiState.Ended(call.copy(callId = "new"), CallEndReason.HANGUP))).videoLayout)
        val coldVideo = CallTerminalPresentationMemory().observe(CallUiState.Ended(call.copy(mediaKind = CallMediaKind.VIDEO), CallEndReason.HANGUP))
        assertTrue(assertNotNull(coldVideo).videoLayout)
    }

    @Test fun `reconnect cannot copy nested controls belonging to a different call`() {
        val memory = CallTerminalPresentationMemory()
        memory.observe(audio)
        val invalidReconnect = CallUiState.Reconnecting(call, video.copy(call = call.copy(callId = "old")))
        assertEquals(memory.snapshot, memory.observe(invalidReconnect))
        assertFalse(assertNotNull(memory.snapshot).videoLayout)
    }
}
