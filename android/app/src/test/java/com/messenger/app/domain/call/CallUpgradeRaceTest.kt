package com.messenger.app.domain.call

import com.messenger.app.data.call.CallSignalingEvent
import com.messenger.app.data.call.PeerConnectionState
import com.messenger.app.data.call.WebRtcEvent
import com.messenger.app.domain.model.CallMediaKind
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.*

/** Two controllers exchange real signaling decisions; no camera, network or JNI is used. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallUpgradeRaceTest {
    private class Side(scope: CoroutineScope, now: () -> Long) {
        val api = FakeCallApi()
        val wire = FakeCallSignalingClient()
        val engine = FakeWebRtcEngine()
        val controller = CallController(api, wire, engine, scope, currentUserId = { "me" }).also { it.elapsedRealtimeMs = now }
        var eventsRead = 0
        val deliveries = mutableListOf<String>()
        fun connected(): CallUiState.Connected = when (val s = controller.state.value) {
            is CallUiState.Reconnecting -> assertIs<CallUiState.Connected>(s.since)
            else -> assertIs<CallUiState.Connected>(s)
        }
        fun deliverTo(other: Side): Boolean {
            var delivered = false
            // Preserve WebSocket send order, especially answer then reoffer.
            while (eventsRead < wire.sentEvents.size) {
                delivered = true
                val event = wire.sentEvents[eventsRead++]
                other.wire.emit(event)
                deliveries += "${event.javaClass.simpleName}: ${other.controller.state.value}"
            }
            return delivered
        }
    }

    private fun TestScope.pair(): Pair<Side, Side> {
        val scope = backgroundScope + UnconfinedTestDispatcher(testScheduler)
        val caller = Side(scope) { testScheduler.currentTime }
        val callee = Side(scope) { testScheduler.currentTime }
        caller.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        callee.wire.emit(CallSignalingEvent.Ringing("call-0", "alice", "audio"))
        callee.controller.acceptIncoming()
        caller.wire.emit(CallSignalingEvent.Accepted("call-0"))
        exchange(caller, callee)
        for (side in listOf(caller, callee)) {
            side.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
            assertEquals(VideoMode.AUDIO, side.connected().videoMode)
        }
        return caller to callee
    }

    private fun exchange(a: Side, b: Side) {
        repeat(12) {
            val sentA = a.deliverTo(b)
            val sentB = b.deliverTo(a)
            if (!sentA && !sentB) return
        }
        fail("Negotiation must settle without an offer loop")
    }

    private fun assertLive(vararg sides: Side) = sides.forEach {
        it.connected()
        assertFalse(it.api.calls.any { action -> action in listOf("hangup", "markFailed") })
        assertEquals(0, it.engine.disposeCount)
    }

    @Test fun `crossed upgrade requests converge and preserve both camera intents`() = runTest {
        val (caller, callee) = pair()
        caller.controller.requestVideoUpgrade()
        callee.controller.requestVideoUpgrade()
        exchange(caller, callee)
        for (side in listOf(caller, callee)) {
            assertEquals(VideoMode.VIDEO, side.connected().videoMode)
            assertEquals(VideoUpgradeState.Idle, side.connected().videoUpgrade)
            assertTrue(side.connected().localVideoEnabled, "capture lost: ${side.controller.state.value}; deliveries=${caller.deliveries + callee.deliveries}")
            assertEquals(1, side.engine.cameraCaptureStartCount)
        }
        assertEquals(0, caller.engine.rollbackCount, "caller is impolite")
        assertEquals(1, callee.engine.rollbackCount, "callee yields its crossed offer")
        assertTrue(caller.wire.sentOffers.size + callee.wire.sentOffers.size <= 4,
            "Crossed requests must settle with a bounded number of offers")
        assertLive(caller, callee)
    }

    @Test fun `either side alone upgrades without opening the other camera`() = runTest {
        for (callerUpgrades in listOf(true, false)) {
            val (caller, callee) = pair()
            val sender = if (callerUpgrades) caller else callee
            val receiver = if (callerUpgrades) callee else caller
            sender.controller.requestVideoUpgrade()
            exchange(caller, callee)
            assertEquals(VideoMode.VIDEO, sender.connected().videoMode)
            assertEquals(VideoMode.VIDEO, receiver.connected().videoMode)
            assertTrue(sender.connected().localVideoEnabled)
            assertFalse(receiver.connected().localVideoEnabled)
            assertEquals(0, receiver.engine.cameraCaptureStartCount)
            receiver.controller.setCameraEnabled(true)
            assertEquals(true, receiver.engine.videoEnabled)
            assertLive(caller, callee)
        }
    }

    @Test fun `second camera can start while the first upgrade answer is pending`() = runTest {
        val (caller, callee) = pair()
        val answerGate = CompletableDeferred<Unit>()
        callee.engine.createAnswerGate = answerGate
        caller.controller.requestVideoUpgrade()
        exchange(caller, callee)
        assertEquals(VideoUpgradeState.Upgrading, callee.connected().videoUpgrade)
        callee.controller.setCameraEnabled(true)
        assertEquals(true, callee.engine.videoEnabled)
        assertTrue(callee.connected().localVideoEnabled)
        callee.engine.createAnswerGate = null
        answerGate.complete(Unit)
        exchange(caller, callee)
        assertTrue(callee.connected().localVideoEnabled)
        assertLive(caller, callee)
    }

    @Test fun `upgrade during reconnect queues behind restart and never ends a transient outage`() = runTest {
        val (caller, callee) = pair()
        for (side in listOf(caller, callee)) {
            side.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
            side.controller.requestVideoUpgrade()
        }
        exchange(caller, callee)
        advanceTimeBy(1_500); runCurrent()
        for (side in listOf(caller, callee)) {
            assertIs<CallUiState.Reconnecting>(side.controller.state.value)
            assertEquals(VideoMode.VIDEO, side.connected().videoMode)
            assertTrue(side.connected().localVideoEnabled, "capture lost: ${side.controller.state.value}; deliveries=${caller.deliveries + callee.deliveries}")
        }
        assertLive(caller, callee)
        for (side in listOf(caller, callee)) {
            side.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
            assertEquals(VideoMode.VIDEO, assertIs<CallUiState.Connected>(side.controller.state.value).videoMode)
        }
        advanceTimeBy(31_000); runCurrent()
        assertLive(caller, callee)
    }

    @Test fun `refused glare rollback preserves the call and retries on recovery wake`() = runTest {
        val (caller, callee) = pair()
        callee.controller.requestVideoUpgrade()
        callee.wire.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        callee.engine.rollbackError = IllegalStateException("test rollback refusal")
        callee.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        callee.wire.emit(CallSignalingEvent.Offer("call-0", "v=0\r\nm=audio 9 RTP/AVP 111\r\nm=video 9 RTP/AVP 96\r\n"))
        assertIs<CallUiState.Reconnecting>(callee.controller.state.value)
        assertLive(caller, callee)
        callee.engine.rollbackError = null
        advanceTimeBy(1_000); runCurrent()
        assertTrue(callee.engine.rollbackCount >= 2)
        assertTrue(callee.engine.createOfferCount >= 2, "queued camera intent is offered after rollback succeeds")
        callee.wire.emit(CallSignalingEvent.Answer("call-0", "v=0 video-answer"))
        callee.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertLive(caller, callee)
    }

    @Test fun `failed answer rollback does not hang up healthy audio`() = runTest {
        val (caller, callee) = pair()
        caller.controller.requestVideoUpgrade()
        caller.wire.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        caller.engine.setRemoteDescriptionError = IllegalStateException("test answer refusal")
        caller.engine.rollbackError = IllegalStateException("test rollback refusal")
        caller.wire.emit(CallSignalingEvent.Answer("call-0", "v=0 invalid-answer"))
        assertLive(caller, callee)
        caller.engine.setRemoteDescriptionError = null
        caller.engine.rollbackError = null
        caller.controller.requestVideoUpgrade()
        caller.wire.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        assertTrue(caller.engine.rollbackCount >= 2)
        caller.wire.emit(CallSignalingEvent.Answer("call-0", "v=0 valid-answer"))
        assertEquals(VideoMode.VIDEO, caller.connected().videoMode)
        assertLive(caller, callee)
    }
}
