package com.messenger.app.domain.call

import com.messenger.app.data.call.*
import com.messenger.app.domain.model.CallMediaKind
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CallPreMediaCameraTest {
    private class Fixture(scope: CoroutineScope) {
        val api=FakeCallApi()
        val signaling=FakeCallSignalingClient()
        val engine=FakeWebRtcEngine().apply { localVideoRunning=true }
        val controller=CallController(api,signaling,engine,scope).also { it.elapsedRealtimeMs={1000L} }
        fun start(kind: CallMediaKind=CallMediaKind.VIDEO) = controller.startCall("peer","Peer",kind)
        fun accept() = signaling.emit(CallSignalingEvent.Accepted("call-0"))
        fun connect() = engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
    }
    @Test fun `ringing off intent survives setup and connection without capture`() = runTest {
        val f=Fixture(backgroundScope+UnconfinedTestDispatcher(testScheduler))
        f.engine.currentRoute=AudioRoute(AudioRouteKind.SPEAKER,"Speaker")
        f.start();runCurrent()
        assertTrue(assertIs<CallUiState.OutgoingRinging>(f.controller.state.value).localVideoEnabled)
        f.controller.setCameraEnabled(false)
        assertEquals(0,f.engine.initializeCount)
        assertEquals(0,f.engine.cameraCaptureStartCount)
        f.accept();runCurrent()
        assertEquals(listOf(false),f.engine.initializedCameraIntents)
        assertFalse(assertIs<CallUiState.Connecting>(f.controller.state.value).localVideoEnabled)
        assertEquals(AudioRouteKind.EARPIECE,f.engine.currentRoute.kind)
        f.connect();runCurrent()
        val connected=assertIs<CallUiState.Connected>(f.controller.state.value)
        assertEquals(VideoMode.VIDEO,connected.videoMode)
        assertFalse(connected.localVideoEnabled);assertEquals(LocalCameraState.OFF,connected.localCameraState)
        assertTrue(f.signaling.sentVideoStates.contains("call-0" to false))
    }
    @Test fun `ICE wait reads latest camera intent then connecting toggle works`() = runTest {
        val f=Fixture(backgroundScope+UnconfinedTestDispatcher(testScheduler))
        f.api.iceDelayMs=1000
        f.start();f.accept();runCurrent()
        assertIs<CallUiState.Connecting>(f.controller.state.value)
        f.controller.setCameraEnabled(false)
        advanceTimeBy(1001);runCurrent()
        assertEquals(listOf(false),f.engine.initializedCameraIntents)
        f.controller.setCameraEnabled(true)
        assertTrue(assertIs<CallUiState.Connecting>(f.controller.state.value).localVideoEnabled)
        assertEquals(true,f.engine.videoEnabled)
        f.connect();runCurrent()
        assertTrue(assertIs<CallUiState.Connected>(f.controller.state.value).localVideoEnabled)
    }
    @Test fun `connecting off ignores stale local frame and connection cannot turn camera back on`() = runTest {
        val f=Fixture(backgroundScope+UnconfinedTestDispatcher(testScheduler))
        f.start();f.accept();runCurrent()
        f.controller.setCameraEnabled(false)
        f.engine.emit(WebRtcEvent.LocalFirstFrame)
        f.controller.toggleMute()
        f.connect();runCurrent()
        val connected=assertIs<CallUiState.Connected>(f.controller.state.value)
        assertTrue(connected.isMuted);assertFalse(connected.localVideoEnabled)
        assertEquals(LocalCameraState.OFF,connected.localCameraState)
    }
    @Test fun `camera start failure leaves connecting camera off and audio alive`() = runTest {
        val f=Fixture(backgroundScope+UnconfinedTestDispatcher(testScheduler))
        f.engine.localVideoRunning=false;f.engine.startLocalVideoResult=false
        f.start();f.accept();runCurrent()
        f.controller.setCameraEnabled(true)
        assertFalse(assertIs<CallUiState.Connecting>(f.controller.state.value).localVideoEnabled)
        f.connect();runCurrent()
        val connected=assertIs<CallUiState.Connected>(f.controller.state.value)
        assertFalse(connected.localVideoEnabled);assertEquals(LocalCameraState.FAILED,connected.localCameraState)
    }
    @Test fun `audio call cannot gain camera via pre-media control`() = runTest {
        val f=Fixture(backgroundScope+UnconfinedTestDispatcher(testScheduler))
        f.start(CallMediaKind.AUDIO);runCurrent();f.controller.setCameraEnabled(true)
        assertFalse(assertIs<CallUiState.OutgoingRinging>(f.controller.state.value).localVideoEnabled)
        assertNull(f.engine.videoEnabled);assertEquals(0,f.engine.startLocalVideoCount)
    }
    @Test fun `old camera start completion cannot enable a replacement call`() = runTest {
        val f=Fixture(backgroundScope+UnconfinedTestDispatcher(testScheduler))
        f.engine.localVideoRunning=false
        f.start();f.accept();runCurrent()
        f.engine.beforeVideoStartReturns={
            f.controller.hangup();f.controller.acknowledgeEnded();f.start(CallMediaKind.AUDIO)
        }
        f.controller.setCameraEnabled(true);runCurrent()
        val next=assertIs<CallUiState.OutgoingRinging>(f.controller.state.value)
        assertEquals("call-1",next.call.callId);assertFalse(next.localVideoEnabled)
        assertNull(f.engine.videoEnabled)
        assertFalse(f.signaling.sentVideoStates.any { it.first=="call-1" })
    }
    @Test fun `ringing mute and selected audio output survive native media initialization`() = runTest {
        for (kind in CallMediaKind.entries) {
            val f=Fixture(backgroundScope+UnconfinedTestDispatcher(testScheduler))
            f.engine.resetRouteOnInitialize=true
            f.start(kind);runCurrent()
            f.controller.toggleMute()
            f.controller.setAudioRoute(AudioRoute(AudioRouteKind.SPEAKER,"Speaker"))
            val ringing=assertIs<CallUiState.OutgoingRinging>(f.controller.state.value)
            assertTrue(ringing.isMuted);assertEquals(AudioRouteKind.SPEAKER,ringing.audioRoute)
            f.accept();runCurrent()
            assertEquals(true,f.engine.muted);assertEquals(AudioRouteKind.SPEAKER,f.engine.currentRoute.kind)
            f.connect();runCurrent()
            val connected=assertIs<CallUiState.Connected>(f.controller.state.value)
            assertTrue(connected.isMuted);assertEquals(AudioRouteKind.SPEAKER,connected.audioRoute)
            f.controller.hangup();runCurrent()
        }
    }
}
