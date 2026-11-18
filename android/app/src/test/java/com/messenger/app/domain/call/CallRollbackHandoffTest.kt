package com.messenger.app.domain.call

import com.messenger.app.data.call.*
import com.messenger.app.domain.model.CallMediaKind
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import livekit.org.webrtc.*

private fun handoffSdp(session: String, version: Int) =
    "v=0\r\no=- $session $version IN IP4 0.0.0.0\r\n" +
        (if (session == "remote") "a=x-unyri-perfect-negotiation\r\n" else "") + "m=audio 9 RTP/AVP 111\r\n"

/** Strict SDP state machine: remote offers require explicit rollback in HAVE_LOCAL_OFFER. */
private class HandoffPeer : PeerConnection(NativePeerConnectionFactory { 0L }) {
    var state = SignalingState.STABLE
    var holdLocalOffer = false
    lateinit var pendingLocal: SdpObserver
    var rollbacks = 0
    var rejectedRemoteOffers = 0
    var acceptedRemoteOffers = 0
    val trace = mutableListOf<String>()
    private var version = 0
    override fun signalingState() = state
    override fun getTransceivers(): List<RtpTransceiver> = emptyList()
    override fun getSenders(): List<RtpSender> = emptyList()
    override fun getReceivers(): List<RtpReceiver> = emptyList()
    override fun createOffer(observer: SdpObserver, constraints: MediaConstraints) {
        observer.onCreateSuccess(SessionDescription(SessionDescription.Type.OFFER, handoffSdp("local", ++version)))
    }
    override fun createAnswer(observer: SdpObserver, constraints: MediaConstraints) {
        check(state == SignalingState.HAVE_REMOTE_OFFER)
        observer.onCreateSuccess(SessionDescription(SessionDescription.Type.ANSWER, handoffSdp("local", ++version)))
    }
    override fun setLocalDescription(observer: SdpObserver, description: SessionDescription) {
        when (description.type) {
            SessionDescription.Type.OFFER -> {
                check(state == SignalingState.STABLE)
                state = SignalingState.HAVE_LOCAL_OFFER
                trace += "local-offer"
                if (holdLocalOffer) { pendingLocal = observer; return }
            }
            SessionDescription.Type.ROLLBACK -> {
                check(state == SignalingState.HAVE_LOCAL_OFFER)
                rollbacks++
                trace += "rollback"
                state = SignalingState.STABLE
            }
            SessionDescription.Type.ANSWER -> {
                check(state == SignalingState.HAVE_REMOTE_OFFER)
                trace += "local-answer"
                state = SignalingState.STABLE
            }
            else -> error("unexpected local description")
        }
        observer.onSetSuccess()
    }
    override fun setRemoteDescription(observer: SdpObserver, description: SessionDescription) {
        if (description.type == SessionDescription.Type.OFFER) {
            if (state == SignalingState.HAVE_LOCAL_OFFER) {
                rejectedRemoteOffers++
                observer.onSetFailure("explicit rollback required")
                return
            }
            check(state == SignalingState.STABLE)
            acceptedRemoteOffers++
            trace += "remote-offer"
            state = SignalingState.HAVE_REMOTE_OFFER
        } else {
            check(state == SignalingState.HAVE_LOCAL_OFFER)
            state = SignalingState.STABLE
        }
        observer.onSetSuccess()
    }
}

/** Use compiled production SDP methods and mutexes, skipping only Android media setup. */
private fun handoffEngine(peer: HandoffPeer): WebRtcEngineImpl {
    val type = WebRtcEngineImpl::class.java
    val allocatorClass = Class.forName("sun.misc.Unsafe")
    val allocator = allocatorClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val engine = allocatorClass.getMethod("allocateInstance", Class::class.java).invoke(allocator, type) as WebRtcEngineImpl
    fun set(name: String, value: Any) { type.getDeclaredField(name).apply { isAccessible = true }.set(engine, value) }
    set("peerConnection", peer)
    set("sdpMutex", Mutex())
    set("mediaProfile", CallMediaLadder.wifi)
    set("diagnostics", CallMediaDiagnostics(0))
    return engine
}

private class HandoffEngine(private val native: WebRtcEngineImpl, support: FakeWebRtcEngine) : WebRtcEngine by support {
    var requestedRollbacks = 0
    override suspend fun createOffer() = native.createOffer()
    override suspend fun createOffer(isCurrent: () -> Boolean) = native.createOffer(isCurrent)
    override suspend fun createAnswer() = native.createAnswer()
    override suspend fun createAnswer(isCurrent: () -> Boolean) = native.createAnswer(isCurrent)
    override suspend fun setRemoteDescription(type: String, sdp: String) = native.setRemoteDescription(type, sdp)
    override suspend fun setRemoteDescription(type: String, sdp: String, isCurrent: () -> Boolean) =
        native.setRemoteDescription(type, sdp, isCurrent)
    override suspend fun rollbackLocalDescription() { requestedRollbacks++; native.rollbackLocalDescription() }
    override suspend fun rollbackLocalDescription(isCurrent: () -> Boolean) {
        requestedRollbacks++
        native.rollbackLocalDescription(isCurrent)
    }
}

class CallRollbackHandoffTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `expired local application hands rollback to the newer remote offer before applying it`() = runTest {
        for (outgoing in listOf(false, true)) {
            val api = FakeCallApi()
            val wire = FakeCallSignalingClient()
            val support = FakeWebRtcEngine()
            val peer = HandoffPeer()
            val engine = HandoffEngine(handoffEngine(peer), support)
            val controller = CallController(api, wire, engine,
                backgroundScope + UnconfinedTestDispatcher(testScheduler), currentUserId = { "me" })
                .also { it.elapsedRealtimeMs = { testScheduler.currentTime } }
            val id = if (outgoing) "call-0" else "incoming"
            if (outgoing) {
                controller.startCall("peer", "Peer", CallMediaKind.AUDIO)
                wire.emit(CallSignalingEvent.Accepted(id))
                wire.emit(CallSignalingEvent.Answer(id, handoffSdp("remote", 1)))
            } else {
                wire.emit(CallSignalingEvent.Ringing(id, "peer", "audio"))
                controller.acceptIncoming()
                wire.emit(CallSignalingEvent.Offer(id, handoffSdp("remote", 1)))
            }
            support.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
            val connected = assertIs<CallUiState.Connected>(controller.state.value)
            val offersSent = wire.sentOffers.size
            val answersSent = wire.sentAnswers.size
            val acceptedOffers = peer.acceptedRemoteOffers
            peer.trace.clear()
            peer.holdLocalOffer = true
            support.emit(WebRtcEvent.NetworkInterfaceChanged)
            assertEquals(PeerConnection.SignalingState.HAVE_LOCAL_OFFER, peer.state)
            advanceTimeBy(30_000); runCurrent()
            assertEquals(1, engine.requestedRollbacks, "expiry queued abandonment behind native setLocalDescription")
            assertEquals(0, peer.rollbacks)
            wire.emit(CallSignalingEvent.Offer(id, handoffSdp("remote", 2)))
            assertEquals(2, engine.requestedRollbacks, "the new remote owner takes over the rollback obligation")
            assertEquals(acceptedOffers, peer.acceptedRemoteOffers, "no remote application while local callback is pending")
            peer.holdLocalOffer = false
            peer.pendingLocal.onSetSuccess()
            runCurrent()
            assertEquals(1, peer.rollbacks, "stale rollback skips; the current owner rolls back once")
            assertEquals(0, peer.rejectedRemoteOffers)
            assertEquals(listOf("local-offer", "rollback", "remote-offer", "local-answer"), peer.trace)
            assertEquals(PeerConnection.SignalingState.STABLE, peer.state)
            assertEquals(offersSent, wire.sentOffers.size, "never transmit the abandoned local offer")
            assertEquals(answersSent + 1, wire.sentAnswers.size)
            assertEquals(connected.connectedAtElapsedRealtimeMs,
                assertIs<CallUiState.Connected>(controller.state.value).connectedAtElapsedRealtimeMs)
            // Cleanup must also clear the obligation so a later legitimate round can start.
            support.emit(WebRtcEvent.NetworkInterfaceChanged)
            assertEquals(offersSent + 1, wire.sentOffers.size)
            wire.emit(CallSignalingEvent.Answer(id, handoffSdp("remote", 3)))
            assertEquals(PeerConnection.SignalingState.STABLE, peer.state)
            controller.terminateForLogout()
        }
    }
}
