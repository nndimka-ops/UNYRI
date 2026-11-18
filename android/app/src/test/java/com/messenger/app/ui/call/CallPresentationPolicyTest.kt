package com.messenger.app.ui.call

import com.messenger.app.data.call.AudioRouteKind
import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.*
import kotlin.test.*

class CallPresentationPolicyTest {
    private val call = CallInfo("call-a", "peer", "Peer", CallMediaKind.VIDEO, CallDirection.OUTGOING)
    private val live = CallUiState.Connected(call, 10, videoMode = VideoMode.VIDEO,
        localVideoEnabled = true, localCameraState = LocalCameraState.ON)

    @Test fun `four controls fit as one row or exactly two pairs`() {
        assertEquals(4, CallPresentationPolicy.VIDEO_CONTROL_COUNT)
        for (w in listOf(240f, 320f, 360f, 600f)) assertEquals(4, CallPresentationPolicy.controlsPerRow(w))
        for (w in listOf(120f, 180f, 220f)) assertEquals(2, CallPresentationPolicy.controlsPerRow(w))
    }
    @Test fun `flip exists only for current running multi camera self view`() {
        assertTrue(CallPresentationPolicy.flipVisible(live, true))
        assertTrue(CallPresentationPolicy.canFlip("call-a", live, true))
        assertFalse(CallPresentationPolicy.canFlip("old-call", live, true))
        assertFalse(CallPresentationPolicy.flipVisible(live, false))
        assertFalse(CallPresentationPolicy.flipVisible(live, true, false))
        for (camera in LocalCameraState.entries.filter { it != LocalCameraState.ON }) assertFalse(CallPresentationPolicy.flipVisible(live.copy(localCameraState = camera), true))
        assertFalse(CallPresentationPolicy.flipVisible(live.copy(localVideoEnabled = false), true))
        assertFalse(CallPresentationPolicy.flipVisible(live.copy(videoMode = VideoMode.AUDIO), true))
        assertFalse(CallPresentationPolicy.canFlip("call-a", CallUiState.Ended(call, CallEndReason.HANGUP), true))
        assertTrue(CallPresentationPolicy.flipVisible(CallUiState.Reconnecting(call, live), true))
    }
    @Test fun `stage follows actual pictures and retained remote frame not call kind`() {
        assertTrue(CallPresentationPolicy.anyPicture(live))
        assertFalse(CallPresentationPolicy.anyPicture(live.copy(localVideoEnabled = false)))
        assertFalse(CallPresentationPolicy.anyPicture(live.copy(localCameraState = LocalCameraState.STARTING)))
        assertTrue(CallPresentationPolicy.anyPicture(live.copy(localVideoEnabled = false, remoteVideoState = RemoteVideoState.ON)))
        assertTrue(CallPresentationPolicy.anyPicture(live.copy(localVideoEnabled = false), true))
    }
    @Test fun `hidden tucked background and self view surfaces stop drawing without detaching`() {
        assertEquals(true to true, CallPresentationPolicy.rendererVisibility(true,true,false,false,true))
        assertEquals(false to true, CallPresentationPolicy.rendererVisibility(true,true,true,false,true))
        assertEquals(false to true, CallPresentationPolicy.rendererVisibility(true,true,false,true,true))
        assertEquals(false to true, CallPresentationPolicy.rendererVisibility(true,true,false,false,false))
        assertEquals(false to false, CallPresentationPolicy.rendererVisibility(true,false,true,false,true))
        assertEquals(false to false, CallPresentationPolicy.rendererVisibility(false,true,false,false,true))
        assertEquals(true to false, CallPresentationPolicy.rendererVisibility(true,true,false,false,true,false))
    }
    @Test fun `paused visible PiP keeps peer drawing but stopped or ordinary background does not`() {
        assertEquals(false to true, CallPresentationPolicy.rendererVisibility(false,true,false,true,true,started=true))
        assertEquals(false to false, CallPresentationPolicy.rendererVisibility(false,true,false,true,true,started=false))
        assertEquals(false to false, CallPresentationPolicy.rendererVisibility(false,true,false,false,true,started=true))
        assertEquals(false to false, CallPresentationPolicy.rendererVisibility(false,false,false,true,true,started=true))
    }
    @Test fun `all created video phases use the same controls and live camera ownership`() {
        val states=listOf(CallUiState.OutgoingRinging(call),CallUiState.Connecting(call),live)
        assertEquals(1,states.map { CallPresentationPolicy.videoControls(it) }.distinct().size)
        for(state in states) {
            assertEquals(false,CallPresentationPolicy.cameraTarget(call.callId,state))
            assertNull(CallPresentationPolicy.cameraTarget("replaced",state))
        }
        assertEquals(true,CallPresentationPolicy.cameraTarget(call.callId,CallUiState.OutgoingRinging(call,localVideoEnabled=false)))
        assertNull(CallPresentationPolicy.cameraTarget(call.callId,CallUiState.IncomingRinging(call)))
        assertNull(CallPresentationPolicy.cameraTarget(call.callId,CallUiState.Ended(call,CallEndReason.HANGUP)))
        assertNull(CallPresentationPolicy.videoControls(CallUiState.OutgoingRinging(call.copy(mediaKind=CallMediaKind.AUDIO))))
    }
    @Test fun `owner output highlight rule including headphones and tablet`() {
        assertTrue(CallPresentationPolicy.isVideoCall(CallUiState.OutgoingRinging(call)))
        assertTrue(CallPresentationPolicy.isVideoCall(CallUiState.Connecting(call)))
        assertFalse(CallPresentationPolicy.isVideoCall(CallUiState.OutgoingRinging(call.copy(mediaKind = CallMediaKind.AUDIO))))
        assertFalse(CallPresentationPolicy.isVideoCall(live.copy(videoMode = VideoMode.AUDIO)))
        for (video in listOf(false, true)) for (earpiece in listOf(false, true)) {
            assertFalse(CallPresentationPolicy.outputHighlighted(AudioRouteKind.BLUETOOTH, video, earpiece))
            assertFalse(CallPresentationPolicy.outputHighlighted(AudioRouteKind.WIRED_HEADSET, video, earpiece))
            assertEquals(!video && earpiece, CallPresentationPolicy.outputHighlighted(AudioRouteKind.SPEAKER, video, earpiece))
            assertEquals(video, CallPresentationPolicy.outputHighlighted(AudioRouteKind.EARPIECE, video, earpiece))
            assertEquals(if (video || !earpiece) AudioRouteKind.SPEAKER else AudioRouteKind.EARPIECE,
                CallPresentationPolicy.assumedOutput(video, earpiece))
        }
    }
    @Test fun `only alerts survive hidden chrome and ended sheet is unavailable`() {
        assertFalse(CallPresentationPolicy.capsuleVisible(false, false))
        assertTrue(CallPresentationPolicy.capsuleVisible(false, true))
        assertFalse(CallPresentationPolicy.capsuleVisible(false, true, true))
        assertTrue(CallPresentationPolicy.sheetEnabled(live))
        assertFalse(CallPresentationPolicy.sheetEnabled(CallUiState.Ended(call, CallEndReason.HANGUP)))
        assertFalse(CallPresentationPolicy.sheetEnabled(CallUiState.Failed(call, CallFailureReason.NETWORK)))
    }
    @Test fun `verification cannot be invented from fingerprints or incomplete exchange`() {
        assertFalse(CallPresentationPolicy.verificationCardVisible(false, listOf(35, 39, 50, 43)))
        assertFalse(CallPresentationPolicy.verificationCardVisible(true, null))
        assertFalse(CallPresentationPolicy.verificationCardVisible(true, listOf(1, 2, 3)))
        assertFalse(CallPresentationPolicy.verificationCardVisible(true, listOf(1, 2, 3, 64)))
        assertTrue(CallPresentationPolicy.verificationCardVisible(true, listOf(35, 39, 50, 43)))
    }
}
