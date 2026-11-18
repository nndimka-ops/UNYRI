package com.messenger.app.ui.call

import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.*
import kotlin.test.*

class CallPipPolicyTest {
    private val call = CallInfo("call", "peer", "Peer", CallMediaKind.AUDIO, CallDirection.OUTGOING)
    private val video = CallUiState.Connected(call, 1L, videoMode = VideoMode.VIDEO, remoteVideoState = RemoteVideoState.ON)

    @Test fun `negotiated upgrade is eligible even when original call was audio`() {
        assertTrue(CallPipPolicy.canEnter(video, true, false))
        assertFalse(CallPipPolicy.canEnter(video.copy(videoMode = VideoMode.AUDIO), true, false))
    }
    @Test fun `locked and unsupported devices cannot automatically expose video`() {
        assertFalse(CallPipPolicy.canEnter(video, false, false))
        assertFalse(CallPipPolicy.canEnter(video, true, true))
    }
    @Test fun `ICE recovery keeps session and masks last frame`() {
        val reconnecting = CallUiState.Reconnecting(call, video)
        assertFalse(CallPipPolicy.canEnter(reconnecting, true, false))
        assertTrue(CallPipPolicy.canRemain(reconnecting, true, false))
        assertEquals(RemoteVideoState.WAITING, CallPipPolicy.videoState(reconnecting)?.remoteVideoState)
        assertEquals(video.call, CallPipPolicy.videoState(reconnecting)?.call)
    }
    @Test fun `remote camera must have a fresh frame`() {
        assertFalse(CallPipPolicy.canEnter(video.copy(remoteVideoState = RemoteVideoState.OFF), true, false))
        assertFalse(CallPipPolicy.canEnter(video.copy(remoteVideoState = RemoteVideoState.WAITING), true, false))
    }
    @Test fun `open pip survives frame loss camera off and recovery for the same video session`() {
        // Before the fix MainActivity reused canEnter here and finished on the
        // first WAITING/OFF/Reconnecting state, despite a live video session.
        val waiting = video.copy(remoteVideoState = RemoteVideoState.WAITING)
        val cameraOff = video.copy(remoteVideoState = RemoteVideoState.OFF)
        val recovery = CallUiState.Reconnecting(call, waiting)
        val sequence = listOf(video, waiting, cameraOff, recovery, CallUiState.Reconnecting(call, recovery), video)
        sequence.forEach {
            assertTrue(CallPipPolicy.canRemain(it, true, false), "Transient video state must retain its window: $it")
        }
        assertFalse(CallPipPolicy.canEnter(recovery, true, false), "Keeping a safe placeholder must not permit a new auto-entry")
        assertFalse(CallPipPolicy.canRemain(video.copy(videoMode = VideoMode.AUDIO), true, false))
    }
    @Test fun `an open pip still closes for lock or unsupported hardware`() {
        for (state in listOf(video, video.copy(remoteVideoState = RemoteVideoState.OFF), CallUiState.Reconnecting(call, video))) {
            assertFalse(CallPipPolicy.canRemain(state, true, true))
            assertFalse(CallPipPolicy.canRemain(state, false, false))
        }
    }
    @Test fun `ringing terminal and idle states cannot keep pip alive`() {
        listOf(CallUiState.Idle, CallUiState.IncomingRinging(call), CallUiState.OutgoingRinging(call),
            CallUiState.Connecting(call), CallUiState.Ended(call, CallEndReason.HANGUP),
            CallUiState.Failed(call, CallFailureReason.NETWORK)).forEach {
            assertFalse(CallPipPolicy.canEnter(it, true, false))
            assertFalse(CallPipPolicy.canRemain(it, true, false))
        }
    }
}
