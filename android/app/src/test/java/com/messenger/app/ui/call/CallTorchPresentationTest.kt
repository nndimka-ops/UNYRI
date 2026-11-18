package com.messenger.app.ui.call

import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.*
import kotlin.test.*

class CallTorchPresentationTest {
    private val back = CallUiState.Connected(
        CallInfo("call", "peer", "Peer", CallMediaKind.VIDEO, CallDirection.OUTGOING), 0,
        videoMode = VideoMode.VIDEO, localVideoEnabled = true,
        localCameraState = LocalCameraState.ON, localCameraIsFrontFacing = false)

    @Test fun torchRequiresLiveRearCaptureCapabilityAndChrome() {
        assertTrue(CallPresentationPolicy.torchVisible(back, true))
        assertFalse(CallPresentationPolicy.torchVisible(back, false))
        assertFalse(CallPresentationPolicy.torchVisible(back, true, false))
        assertFalse(CallPresentationPolicy.torchVisible(back.copy(localCameraIsFrontFacing = true), true))
        assertFalse(CallPresentationPolicy.torchVisible(back.copy(localVideoEnabled = false), true))
        for (camera in listOf(LocalCameraState.OFF, LocalCameraState.STARTING, LocalCameraState.FAILED))
            assertFalse(CallPresentationPolicy.torchVisible(back.copy(localCameraState = camera), true))
        assertFalse(CallPresentationPolicy.torchVisible(back.copy(videoMode = VideoMode.AUDIO), true))
    }

    @Test fun releaseCannotTargetReplacementOrEndedCall() {
        assertTrue(CallPresentationPolicy.canTorch("call", back, true))
        assertFalse(CallPresentationPolicy.canTorch("old", back, true))
        assertFalse(CallPresentationPolicy.canTorch("call", CallUiState.Ended(back.call, CallEndReason.HANGUP), true))
        assertFalse(CallPresentationPolicy.canTorch("call", back.copy(localCameraIsFrontFacing = true), true))
    }
}
