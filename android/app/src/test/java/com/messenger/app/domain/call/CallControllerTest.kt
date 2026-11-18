package com.messenger.app.domain.call

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.CallDto
import com.messenger.app.data.api.IceServerDto
import com.messenger.app.data.call.AudioRoute
import com.messenger.app.data.call.AudioRouteKind
import com.messenger.app.data.call.CallApi
import com.messenger.app.data.call.CallSignalingClient
import com.messenger.app.data.call.CallSignalingEvent
import com.messenger.app.data.call.CallLocalSdp
import com.messenger.app.data.call.PeerConnectionState
import com.messenger.app.data.call.WebRtcEngine
import com.messenger.app.data.call.WebRtcCallStats
import com.messenger.app.data.call.WebRtcEvent
import com.messenger.app.domain.model.CallMediaKind
import com.messenger.app.domain.model.CallEndReason
import com.messenger.app.domain.repository.SocketConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.NativePeerConnectionFactory
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.MediaConstraints

internal class FakeCallApi : CallApi {
    val calls = mutableListOf<String>()
    var acceptShouldFail = false
    var createDelayMs = 0L
    var createCallGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var createFailure: Exception? = null
    var acceptDelayMs = 0L
    var iceDelayMs = 0L
    var endDelayMs = 0L
    var activeCallResult: CallDto? = null
    var getCallResult: CallDto? = null
    var getCallHandler: (suspend (String) -> CallDto)? = null
    var getCallShouldFail = false
    var getCallFailure: Exception? = null
    var getActiveCallCount = 0
    var getCallCount = 0
    var getCallDelayMs = 0L
    var getCallGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var ringingFailure: Exception? = null
    var ringingCount = 0
    var createExpected: Boolean? = null
    private var seq = 0
    private val createdMediaKinds = mutableMapOf<String, String>()

    private fun dto(id: String, state: String, mediaKind: String = "audio", endReason: String? = null) = CallDto(
        id = id,
        callerUserId = "me",
        calleeUserId = "other",
        mediaKind = mediaKind,
        state = state,
        endReason = endReason,
        createdAt = "2026-01-01T00:00:00Z",
    )

    override suspend fun createCall(calleeUserId: String, mediaKind: String): CallDto {
        calls += "createCall"
        val gate = createCallGate
        val failure = createFailure
        gate?.await()
        delay(createDelayMs)
        failure?.let { throw it }
        val id = "call-${seq++}"
        createdMediaKinds[id] = mediaKind
        return dto(id, "ringing", mediaKind).copy(peerRingingExpected = createExpected)
    }

    override suspend fun getActiveCall(): CallDto? {
        calls += "getActiveCall"
        getActiveCallCount++
        return activeCallResult
    }

    override suspend fun getCall(callId: String): CallDto {
        calls += "getCall"
        getCallCount++
        getCallHandler?.let { return it(callId) }
        getCallGate?.await()
        getCallFailure?.let { throw it }
        if (getCallShouldFail) throw AuthApiException.Network("down")
        if (getCallDelayMs > 0) delay(getCallDelayMs)
        return getCallResult ?: dto(callId, "ringing", createdMediaKinds[callId] ?: "audio")
    }

    override suspend fun ringing(callId: String) {
        ringingCount++
        ringingFailure?.let { throw it }
    }

    override suspend fun accept(callId: String): CallDto {
        calls += "accept"
        delay(acceptDelayMs)
        if (acceptShouldFail) throw AuthApiException.Api("conflict", "Call is no longer ringing.")
        return dto(callId, "accepted", createdMediaKinds[callId] ?: "audio")
    }

    override suspend fun reject(callId: String): CallDto {
        calls += "reject"
        return dto(callId, "ended")
    }

    override suspend fun cancel(callId: String): CallDto {
        calls += "cancel"
        return dto(callId, "ended")
    }

    override suspend fun hangup(callId: String): CallDto {
        calls += "hangup"
        delay(endDelayMs)
        return dto(callId, "ended")
    }

    override suspend fun markConnected(callId: String): CallDto {
        calls += "markConnected"
        return dto(callId, "connected")
    }

    override suspend fun markFailed(callId: String): CallDto {
        calls += "markFailed"
        return dto(callId, "ended")
    }

    override suspend fun iceServers(): List<IceServerDto> {
        calls += "iceServers"
        delay(iceDelayMs)
        return emptyList()
    }
}

internal class FakeCallSignalingClient : CallSignalingClient {
    private val flow = MutableSharedFlow<CallSignalingEvent>(extraBufferCapacity = 16)
    private val connFlow = MutableSharedFlow<SocketConnectionState>(extraBufferCapacity = 16)
    override val events = flow
    override val connectionState = connFlow
    val sentOffers = mutableListOf<Pair<String, String>>()
    val sentAnswers = mutableListOf<Pair<String, String>>()
    val sentIce = mutableListOf<String>()
    val sentEvents = mutableListOf<CallSignalingEvent>()

    fun emit(event: CallSignalingEvent) {
        check(flow.tryEmit(event)) { "buffer full" }
    }

    fun emitConnectionState(state: SocketConnectionState) {
        check(connFlow.tryEmit(state)) { "buffer full" }
    }

    override fun sendOffer(callId: String, sdp: String): Boolean {
        sentOffers += callId to sdp
        sentEvents += CallSignalingEvent.Offer(callId, sdp)
        return true
    }

    override fun sendAnswer(callId: String, sdp: String): Boolean {
        sentAnswers += callId to sdp
        sentEvents += CallSignalingEvent.Answer(callId, sdp)
        return true
    }

    override fun sendIce(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?): Boolean {
        sentIce += candidate
        return true
    }

    val sentVideoUpgradeRequests = mutableListOf<String>()
    val sentVideoUpgradeAccepts = mutableListOf<String>()
    val sentVideoUpgradeRejects = mutableListOf<String>()
    val sentVideoUpgradeCancels = mutableListOf<String>()
    val sentVideoStates = mutableListOf<Pair<String, Boolean>>()

    override fun sendVideoUpgradeRequest(callId: String): Boolean { sentVideoUpgradeRequests += callId; sentEvents += CallSignalingEvent.VideoUpgradeRequest(callId); return true }
    override fun sendVideoUpgradeAccept(callId: String): Boolean { sentVideoUpgradeAccepts += callId; sentEvents += CallSignalingEvent.VideoUpgradeAccept(callId); return true }
    override fun sendVideoUpgradeReject(callId: String): Boolean { sentVideoUpgradeRejects += callId; return true }
    override fun sendVideoUpgradeCancel(callId: String): Boolean { sentVideoUpgradeCancels += callId; return true }
    override fun sendVideoState(callId: String, enabled: Boolean): Boolean { sentVideoStates += callId to enabled; return true }
    val sentKeepAlives = mutableListOf<String>()
    override fun sendKeepAlive(callId: String): Boolean { sentKeepAlives += callId; return true }
}

private class RecordingSdpPeer(private val sdp: String, private val applied: MutableList<SessionDescription>) :
    PeerConnection(NativePeerConnectionFactory { 0L }) {
    override fun createOffer(observer: SdpObserver, constraints: MediaConstraints) =
        observer.onCreateSuccess(SessionDescription(SessionDescription.Type.OFFER, sdp))
    override fun createAnswer(observer: SdpObserver, constraints: MediaConstraints) =
        observer.onCreateSuccess(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    override fun setLocalDescription(observer: SdpObserver, description: SessionDescription) {
        applied += description
        observer.onSetSuccess()
    }
}

internal class FakeWebRtcEngine : WebRtcEngine {
    override val torchState = kotlinx.coroutines.flow.MutableStateFlow(com.messenger.app.data.call.CallTorchState())
    var torchToggleCommands = 0
    val torchSetCommands = mutableListOf<Boolean>()
    override fun toggleTorch() { torchToggleCommands++ }
    override fun setTorch(on: Boolean) { torchSetCommands += on }
    var frontFacing = true
    var beforeVideoStartReturns: (() -> Unit)? = null
    override fun localCameraIsFrontFacing() = frontFacing
    override fun isCurrentCameraGeneration(generation: Long) = generation == 1L
    var frameResets = 0
    override fun expectRemoteVideoFrame() { frameResets++ }
    private val flow = MutableSharedFlow<WebRtcEvent>(extraBufferCapacity = 16)
    override val events = flow
    var initializeCount = 0
    val initializedCameraIntents = mutableListOf<Boolean>()
    var prepareAudioCount = 0
    var preparingAudio: (() -> Unit)? = null
    var prepareAudioFailure: Exception? = null
    var audioPrepared = false
    override fun prepareCallAudio() {
        prepareAudioCount++
        preparingAudio?.invoke()
        audioPrepared = true
        prepareAudioFailure?.let { throw it }
    }
    var disposeCount = 0
    var createOfferCount = 0
    var createOfferGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var uniqueOffers = false
    var offerSdp: String? = null
    var answerSdp: String? = null
    var statsGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val remoteDescriptions = mutableListOf<Pair<String, String>>()
    val nativeLocalDescriptions = mutableListOf<SessionDescription>()
    var remoteDescriptionGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val remoteApplyMutex = Mutex()
    var rollbackCount = 0
    var rollbackError: Exception? = null
    var videoNegotiationAllowed = false
    val offerVideoIntents = mutableListOf<Boolean>()
    val answerVideoIntents = mutableListOf<Boolean>()
    override fun setVideoNegotiationEnabled(enabled: Boolean) { videoNegotiationAllowed = enabled }
    var createAnswerCount = 0
    var createAnswerGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var setRemoteDescriptionCount = 0
    val addedIceCandidates = mutableListOf<String>()
    var muted: Boolean? = null
    var speakerOn: Boolean? = null
    var videoEnabled: Boolean? = null
    var switchCameraCount = 0
    var restartIceCount = 0
    var localVideoRunning = false
    val attachedLocalRenderers = mutableListOf<Any?>()
    val attachedRemoteRenderers = mutableListOf<Any?>()
    val detachedLocalRenderers = mutableListOf<Any?>()
    val detachedRemoteRenderers = mutableListOf<Any?>()
    var detachLocalCount = 0
    var detachRemoteCount = 0

    fun emit(event: WebRtcEvent) {
        if (event is WebRtcEvent.LocalCameraFacingChanged && isCurrentCameraGeneration(event.cameraGeneration)) frontFacing = event.isFrontFacing
        if (event is WebRtcEvent.AudioRouteChanged) {
            currentRoute = event.route
            if (routes.none { it.kind == event.route.kind }) routes = routes + event.route
        }
        check(flow.tryEmit(event)) { "buffer full" }
    }

    override fun initialize(iceServers: List<IceServerDto>, mediaKind: CallMediaKind) { initializeCount++; videoNegotiationAllowed = mediaKind == CallMediaKind.VIDEO }
    override fun initialize(iceServers: List<IceServerDto>, mediaKind: CallMediaKind, captureVideo: Boolean) {
        initializedCameraIntents += captureVideo
        initialize(iceServers, mediaKind)
        if (mediaKind == CallMediaKind.VIDEO && !captureVideo) setVideoEnabled(false)
    }
    override suspend fun createOffer(): String {
        val number = ++createOfferCount
        offerVideoIntents += videoNegotiationAllowed
        createOfferGate?.await()
        return offerSdp?.let { CallLocalSdp.createAndApply(RecordingSdpPeer(it, nativeLocalDescriptions),
            SessionDescription.Type.OFFER, false, { true }) } ?: if (uniqueOffers) "v=0 offer-$number" else "v=0 offer"
    }
    override suspend fun rollbackLocalDescription() { remoteApplyMutex.withLock { rollbackCount++ } }
    override suspend fun rollbackLocalDescription(isCurrent: () -> Boolean) {
        remoteApplyMutex.withLock {
            if (isCurrent()) { rollbackCount++; rollbackError?.let { throw it } }
        }
    }
    override suspend fun createAnswer(): String {
        createAnswerCount++; answerVideoIntents += videoNegotiationAllowed
        createAnswerGate?.await()
        return answerSdp?.let { CallLocalSdp.createAndApply(RecordingSdpPeer(it, nativeLocalDescriptions),
            SessionDescription.Type.ANSWER, false, { true }) } ?: "v=0 answer"
    }
    override suspend fun setRemoteDescription(type: String, sdp: String): Unit = remoteApplyMutex.withLock {
        setRemoteDescriptionCount++
        setRemoteDescriptionError?.let { throw it }
        remoteDescriptionGate?.await()
        remoteDescriptions += type to sdp
    }
    override fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        addedIceCandidates += candidate
    }
    override fun restartIce() { restartIceCount++ }
    override fun setMuted(muted: Boolean) { this.muted = muted }
    override fun setSpeakerOn(on: Boolean) {
        this.speakerOn = on
        val kind = if (on) AudioRouteKind.SPEAKER else com.messenger.app.data.call.CallAudioPolicy.initialRoute(routes.map { it.kind }, currentRoute.kind) ?: return
        currentRoute = AudioRoute(kind, "Test output")
    }
    override fun setVideoEnabled(enabled: Boolean) { this.videoEnabled = enabled }
    var startLocalVideoResult = true
    var startLocalVideoCount = 0
    var cameraCaptureStartCount = 0
    var stopLocalVideoCount = 0
    var multipleCameras = true
    var setRemoteDescriptionError: Exception? = null
    override fun startLocalVideo(capture: Boolean): Boolean {
        startLocalVideoCount++
        if (capture) cameraCaptureStartCount++
        localVideoRunning = startLocalVideoResult
        if (startLocalVideoResult) beforeVideoStartReturns?.invoke()
        return startLocalVideoResult
    }
    override fun stopLocalVideo() { stopLocalVideoCount++; localVideoRunning = false }
    override fun hasMultipleCameras(): Boolean = multipleCameras
    override fun switchCamera() { switchCameraCount++ }
    var routes: List<AudioRoute> = listOf(
        AudioRoute(AudioRouteKind.EARPIECE, "Phone"),
        AudioRoute(AudioRouteKind.SPEAKER, "Speaker"),
    )
    override fun availableAudioRoutes(): List<AudioRoute> = routes
    override fun setAudioRoute(route: AudioRoute) { this.currentRoute = route }
    override fun currentAudioRoute(): AudioRoute = currentRoute
    override fun attachLocalRenderer(renderer: Any?) { attachedLocalRenderers += renderer }
    override fun attachRemoteRenderer(renderer: Any?) { attachedRemoteRenderers += renderer }
    override fun detachLocalRenderer(renderer: Any?) { detachLocalCount++; detachedLocalRenderers += renderer }
    override fun detachRemoteRenderer(renderer: Any?) { detachRemoteCount++; detachedRemoteRenderers += renderer }
    override fun reinitRenderers() {}
    override fun localVideoRunning(): Boolean = localVideoRunning
    override fun dispose() { disposeCount++; audioPrepared = false }
    override suspend fun getStats(): WebRtcCallStats? { statsGate?.await(); return WebRtcCallStats() }
    var currentRoute: AudioRoute = AudioRoute(AudioRouteKind.EARPIECE, "Phone")
}

private class Harness(gracePeriodMs: Long = 15_000L, videoUpgradeTimeoutMs: Long = 25_000L, sessionOwner: () -> String? = { "test" }, resolver: suspend (String) -> String = { it }, externalCallBusy: () -> Boolean = { false }, currentUserId: () -> String? = { "me" }, nonCallCaptureBusy: () -> Boolean = { false }, drainNonCallCapture: suspend () -> Boolean = { true }, onCaptureStillBusy: () -> Unit = {}, scopeOverride: CoroutineScope? = null, isLocallyBlocked: (String) -> Boolean = { false }) {
    val startBlocked = mutableListOf<CallStartBlockedReason>()
    val callApi = FakeCallApi()
    val signaling = FakeCallSignalingClient()
    val engine = FakeWebRtcEngine()
    val scope = scopeOverride ?: CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    val controller = CallController(
        isLocallyBlocked = isLocallyBlocked,
        callApi = callApi,
        signalingClient = signaling,
        webRtcEngine = engine,
        scope = scope,
        sessionOwner = sessionOwner,
        currentUserId = currentUserId,
        externalCallBusy = externalCallBusy,
        nonCallCaptureBusy = nonCallCaptureBusy,
        drainNonCallCapture = drainNonCallCapture,
        onCaptureStillBusy = onCaptureStillBusy,
        onStartBlocked = { startBlocked += it },
        displayNameResolver = resolver,
        decorationDispatcher = Dispatchers.Unconfined,
        reconnectGracePeriodMs = gracePeriodMs,
        videoUpgradeTimeoutMs = videoUpgradeTimeoutMs,
    ).also { it.elapsedRealtimeMs = { 1000L } }

    /** Drives the controller into a Connected AUDIO call. */
    fun connectAudio(): CallUiState.Connected {
        controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        signaling.emit(CallSignalingEvent.Accepted("call-0"))
        signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 answer"))
        engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        return assertIs<CallUiState.Connected>(controller.state.value)
    }
}

class CallControllerTest {
    @Test fun blockedCallerCannotRingThroughSocketOrPush(): Unit = runBlocking {
        var blocked = true
        val h = Harness(isLocallyBlocked = { it == "blocked" && blocked })
        h.signaling.emit(CallSignalingEvent.Ringing("blocked-call", "blocked", "audio"))
        assertIs<CallUiState.Idle>(h.controller.state.value)
        h.callApi.getCallResult = CallDto("blocked-call", "blocked", "me", "audio", "ringing", createdAt = "2026-01-01T00:00:00Z")
        h.controller.showIncomingCallFromPush("blocked-call", "audio")
        assertIs<CallUiState.Idle>(h.controller.state.value)
        blocked = false
        h.signaling.emit(CallSignalingEvent.Ringing("allowed-call", "blocked", "audio"))
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        h.controller.terminateForLogout()
        h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun remoteSdp(version: Int, video: Boolean = false, session: String = "remote"): String =
        "v=0\r\no=- $session $version IN IP4 0.0.0.0\r\na=x-unyri-perfect-negotiation\r\nm=audio 9 RTP/AVP 111\r\n" +
            if (video) "m=video 9 RTP/AVP 96\r\na=sendrecv\r\n" else ""

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `answer accepted before expiry stays ordered when native apply completes after abandonment`() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        advanceTimeBy(29_999); runCurrent()
        val nativeApply = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.engine.remoteDescriptionGate = nativeApply
        val answer = remoteSdp(1, video = true)
        h.signaling.emit(CallSignalingEvent.Answer("call-0", answer))
        assertEquals(2, h.engine.setRemoteDescriptionCount)
        advanceTimeBy(1); runCurrent()
        assertEquals(0, h.engine.rollbackCount, "rollback waits for the native apply, as in the engine mutex")
        h.engine.remoteDescriptionGate = null
        nativeApply.complete(Unit); runCurrent()
        assertTrue(h.engine.remoteDescriptions.any { it == ("answer" to answer) }, "native application succeeded across expiry")
        assertEquals(1, h.engine.rollbackCount)
        assertEquals(VideoUpgradeState.Idle, assertIs<CallUiState.Connected>(h.controller.state.value).videoUpgrade)
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        val applied = h.engine.setRemoteDescriptionCount
        h.signaling.emit(CallSignalingEvent.Answer("call-0", answer))
        assertEquals(applied, h.engine.setRemoteDescriptionCount, "duplicate cannot complete the new offer")
        assertEquals(VideoUpgradeState.Upgrading, assertIs<CallUiState.Connected>(h.controller.state.value).videoUpgrade)
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(2, video = true)))
        assertEquals(VideoMode.VIDEO, assertIs<CallUiState.Connected>(h.controller.state.value).videoMode)
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `abandoned call rollback loses ownership before replacement call starts`() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        val applying = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.engine.remoteDescriptionGate = applying
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(1, video = true)))
        advanceTimeBy(30_000); runCurrent()
        assertEquals(0, h.engine.rollbackCount, "rollback is queued behind native SDP")
        h.controller.terminateForLogout()
        h.controller.startCall("replacement", "Replacement", CallMediaKind.AUDIO)
        assertEquals("call-1", assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).call.callId)
        h.engine.remoteDescriptionGate = null
        applying.complete(Unit); runCurrent()
        assertEquals(0, h.engine.rollbackCount, "controller passes its negotiation ticket into the native lock")
        assertEquals("call-1", assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).call.callId)
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `glare restores requested capture before reoffer but respects newer camera off or downgrade`() = runTest {
        for (newIntent in listOf("keep", "off", "audio")) {
            val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
            h.signaling.emit(CallSignalingEvent.Ringing("incoming", "alice", "audio"))
            h.controller.acceptIncoming()
            h.signaling.emit(CallSignalingEvent.Offer("incoming", remoteSdp(1)))
            h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
            h.controller.requestVideoUpgrade()
            h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("incoming"))
            assertEquals(1, h.engine.cameraCaptureStartCount)
            val answerGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            h.engine.createAnswerGate = answerGate
            h.signaling.emit(CallSignalingEvent.Offer("incoming", remoteSdp(2)))
            assertFalse(h.engine.localVideoRunning)
            assertFalse(assertIs<CallUiState.Connected>(h.controller.state.value).localVideoEnabled)
            when (newIntent) {
                "off" -> h.controller.setCameraEnabled(false)
                "audio" -> h.controller.switchToAudio()
            }
            h.engine.createAnswerGate = null
            answerGate.complete(Unit); runCurrent()
            val capture = newIntent == "keep"
            assertEquals(if (capture) 2 else 1, h.engine.cameraCaptureStartCount)
            assertEquals(capture, h.engine.localVideoRunning, "restore capture only if the user still requests it")
            assertEquals(newIntent != "audio", h.engine.offerVideoIntents.last())
            assertEquals(capture, assertIs<CallUiState.Connected>(h.controller.state.value).localVideoEnabled)
            h.signaling.emit(CallSignalingEvent.Answer("incoming", remoteSdp(3, video = newIntent != "audio")))
            val connected = assertIs<CallUiState.Connected>(h.controller.state.value)
            assertEquals(if (newIntent == "audio") VideoMode.AUDIO else VideoMode.VIDEO, connected.videoMode)
            assertEquals(capture, connected.localVideoEnabled)
            if (capture) {
                h.engine.emit(WebRtcEvent.LocalFirstFrame)
                assertEquals(LocalCameraState.ON, assertIs<CallUiState.Connected>(h.controller.state.value).localCameraState)
            }
            h.controller.terminateForLogout()
        }
    }

    @Test fun `old answers cannot complete newer media or restart rounds and old offers cannot replace video`() {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        h.signaling.emit(CallSignalingEvent.Accepted("call-0"))
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(1)))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        val applied = h.engine.remoteDescriptions.size
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(1)))
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(0)))
        assertEquals(applied, h.engine.remoteDescriptions.size)
        assertEquals(VideoUpgradeState.Upgrading, assertIs<CallUiState.Connected>(h.controller.state.value).videoUpgrade)
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(2, video = true)))
        assertEquals(VideoMode.VIDEO, assertIs<CallUiState.Connected>(h.controller.state.value).videoMode)
        h.engine.emit(WebRtcEvent.NetworkInterfaceChanged)
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(2, video = true)))
        assertEquals(applied + 1, h.engine.remoteDescriptions.size)
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(3, video = true)))
        assertEquals(applied + 2, h.engine.remoteDescriptions.size)
        h.controller.switchToAudio()
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(2, video = true)))
        assertEquals(VideoUpgradeState.Upgrading, assertIs<CallUiState.Connected>(h.controller.state.value).videoUpgrade)
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(4)))
        h.signaling.emit(CallSignalingEvent.Offer("call-0", remoteSdp(3, video = true)))
        assertEquals(applied + 3, h.engine.remoteDescriptions.size)
        assertEquals(VideoMode.AUDIO, assertIs<CallUiState.Connected>(h.controller.state.value).videoMode)
        h.signaling.emit(CallSignalingEvent.Offer("call-0", remoteSdp(5, video = true)))
        val answered = h.signaling.sentAnswers.size
        h.signaling.emit(CallSignalingEvent.Offer("call-0", remoteSdp(5, video = true)))
        assertEquals(answered + 1, h.signaling.sentAnswers.size, "same-version offer resends its lost answer")
        h.controller.terminateForLogout()
    }

    @Test fun `video preserving glare keeps the requested camera ON without waiting for another first frame`() {
        val h = Harness()
        h.signaling.emit(CallSignalingEvent.Ringing("incoming", "alice", "audio"))
        h.controller.acceptIncoming()
        h.signaling.emit(CallSignalingEvent.Offer("incoming", remoteSdp(1)))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("incoming"))
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(LocalCameraState.ON, assertIs<CallUiState.Connected>(h.controller.state.value).localCameraState)
        h.signaling.emit(CallSignalingEvent.Offer("incoming", remoteSdp(2, video = true)))
        assertEquals(1, h.engine.cameraCaptureStartCount)
        assertEquals(LocalCameraState.ON, assertIs<CallUiState.Connected>(h.controller.state.value).localCameraState)
        h.signaling.emit(CallSignalingEvent.Answer("incoming", remoteSdp(3, video = true)))
        assertTrue(assertIs<CallUiState.Connected>(h.controller.state.value).localVideoEnabled)
        assertEquals(LocalCameraState.ON, assertIs<CallUiState.Connected>(h.controller.state.value).localCameraState)
        h.controller.terminateForLogout()
    }

    @Test fun `video preserving remote restart keeps a visible picture and fresh video arms first frame`() {
        val h = Harness()
        prepareVideo(h)
        h.engine.emit(WebRtcEvent.RemoteVideoEnabled(true))
        h.engine.emit(WebRtcEvent.RemoteFirstFrame)
        val before = assertIs<CallUiState.Connected>(h.controller.state.value)
        val arms = h.engine.frameResets
        h.signaling.emit(CallSignalingEvent.Offer("call-0", remoteSdp(1, video = true)))
        val preserved = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(RemoteVideoState.ON, preserved.remoteVideoState)
        assertEquals(before.connectedAtElapsedRealtimeMs, preserved.connectedAtElapsedRealtimeMs)
        assertEquals(arms, h.engine.frameResets)
        h.signaling.emit(CallSignalingEvent.Offer("call-0", remoteSdp(2)))
        assertEquals(RemoteVideoState.OFF, assertIs<CallUiState.Connected>(h.controller.state.value).remoteVideoState)
        h.signaling.emit(CallSignalingEvent.VideoUpgradeRequest("call-0"))
        h.controller.acceptVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.Offer("call-0", remoteSdp(3, video = true)))
        assertEquals(RemoteVideoState.WAITING, assertIs<CallUiState.Connected>(h.controller.state.value).remoteVideoState)
        assertTrue(h.engine.frameResets > arms)
        h.engine.emit(WebRtcEvent.RemoteFirstFrame)
        assertEquals(RemoteVideoState.ON, assertIs<CallUiState.Connected>(h.controller.state.value).remoteVideoState)
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `healthy media offers retry the transmitted bytes and fail boundedly without relying on stats`() = runTest {
        for (upgrade in listOf(true, false)) {
            val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
            h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
            h.engine.statsGate = kotlinx.coroutines.CompletableDeferred()
            h.engine.uniqueOffers = true
            if (upgrade) {
                h.connectAudio()
                h.controller.requestVideoUpgrade()
                h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
            } else { prepareVideo(h); h.controller.switchToAudio() }
            val original = h.signaling.sentOffers.last()
            val produced = h.engine.createOfferCount
            advanceTimeBy(5_000); runCurrent()
            assertEquals(original, h.signaling.sentOffers.last())
            assertEquals(2, h.signaling.sentOffers.count { it == original })
            assertEquals(produced, h.engine.createOfferCount)
            advanceTimeBy(25_000); runCurrent()
            val state = assertIs<CallUiState.Connected>(h.controller.state.value)
            assertEquals(VideoUpgradeState.Idle, state.videoUpgrade)
            assertEquals(CallNotice.VIDEO_FAILED, state.notice)
            assertEquals(1, h.engine.rollbackCount)
            assertEquals(0, h.engine.restartIceCount)
            val sent = h.signaling.sentOffers.size
            advanceTimeBy(10_000); runCurrent()
            assertEquals(sent, h.signaling.sentOffers.size)
            h.controller.requestVideoUpgrade()
            assertEquals(VideoUpgradeState.Requesting, assertIs<CallUiState.Connected>(h.controller.state.value).videoUpgrade)
            h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
            h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(1, video = true)))
            assertEquals(VideoMode.VIDEO, assertIs<CallUiState.Connected>(h.controller.state.value).videoMode)
            h.controller.switchToAudio()
            h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(2)))
            h.signaling.emit(CallSignalingEvent.VideoUpgradeRequest("call-0"))
            assertEquals(VideoUpgradeState.Upgrading, assertIs<CallUiState.Connected>(h.controller.state.value).videoUpgrade)
            assertTrue(h.signaling.sentVideoUpgradeAccepts.isNotEmpty(), "incoming upgrade is accepted after timeout cleanup")
            h.signaling.emit(CallSignalingEvent.Offer("call-0", remoteSdp(3, video = true)))
            assertEquals(VideoMode.VIDEO, assertIs<CallUiState.Connected>(h.controller.state.value).videoMode)
            h.controller.terminateForLogout()
        }
    }

    @Test fun `callee glare reoffers abandoned downgrade after answering video restart`() {
        val h = Harness()
        h.signaling.emit(CallSignalingEvent.Ringing("incoming", "alice", "video"))
        h.callApi.getCallResult = CallDto("incoming", "alice", "me", "video", "accepted", createdAt = "now")
        h.controller.acceptIncoming()
        h.signaling.emit(CallSignalingEvent.Offer("incoming", remoteSdp(1, video = true)))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        h.controller.switchToAudio()
        assertEquals(listOf(false), h.engine.offerVideoIntents)
        h.signaling.emit(CallSignalingEvent.Offer("incoming", remoteSdp(2, video = true)))
        assertEquals(1, h.engine.rollbackCount)
        assertEquals(listOf(false, false), h.engine.offerVideoIntents, "reoffer the rolled back audio intent")
        assertEquals(true, h.engine.answerVideoIntents.last(), "first answer the peer's video-preserving restart")
        h.signaling.emit(CallSignalingEvent.Answer("incoming", remoteSdp(3)))
        assertEquals(VideoMode.AUDIO, assertIs<CallUiState.Connected>(h.controller.state.value).videoMode)
        h.controller.terminateForLogout()
    }

    @Test fun `callee glare keeps a newer queued downgrade over the abandoned upgrade`() {
        val h = Harness()
        h.signaling.emit(CallSignalingEvent.Ringing("incoming", "alice", "audio"))
        h.controller.acceptIncoming()
        h.signaling.emit(CallSignalingEvent.Offer("incoming", remoteSdp(1)))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("incoming"))
        h.controller.switchToAudio()
        h.signaling.emit(CallSignalingEvent.Offer("incoming", remoteSdp(2, video = true)))
        assertEquals(listOf(true, false), h.engine.offerVideoIntents)
        h.signaling.emit(CallSignalingEvent.Answer("incoming", remoteSdp(3)))
        assertEquals(VideoMode.AUDIO, assertIs<CallUiState.Connected>(h.controller.state.value).videoMode)
        h.controller.terminateForLogout()
    }

    @Test fun `every transmitted offer and answer advertises capability once before media`() {
        val h = Harness()
        val native = "v=0\r\no=- local 1 IN IP4 0.0.0.0\r\nm=audio 9 RTP/AVP 111\r\n"
        h.engine.offerSdp = native
        h.engine.answerSdp = native
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(1, video = true)))
        h.controller.switchToAudio()
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(2)))
        h.engine.emit(WebRtcEvent.NetworkInterfaceChanged)
        h.signaling.emit(CallSignalingEvent.Answer("call-0", remoteSdp(3)))
        h.signaling.emit(CallSignalingEvent.Offer("call-0", remoteSdp(4)))
        h.controller.terminateForLogout()
        h.signaling.emit(CallSignalingEvent.Ringing("incoming", "alice", "audio"))
        h.controller.acceptIncoming()
        h.signaling.emit(CallSignalingEvent.Offer("incoming", remoteSdp(1)))
        assertEquals(4, h.signaling.sentOffers.size) // initial, upgrade, downgrade, restart
        assertEquals(2, h.signaling.sentAnswers.size) // established and initial
        val marker = "a=x-unyri-perfect-negotiation"
        for ((_, sent) in h.signaling.sentOffers + h.signaling.sentAnswers) {
            assertEquals(1, sent.lineSequence().count { it == marker })
            assertTrue(sent.indexOf(marker) < sent.indexOf("m="))
            assertEquals(native, sent.replace("$marker\r\n", ""))
        }
        assertEquals(6, h.engine.nativeLocalDescriptions.size)
        for (applied in h.engine.nativeLocalDescriptions) {
            assertEquals(native, applied.description, "observe the actual setLocalDescription argument")
            assertFalse(applied.description.contains(marker))
        }
        assertEquals(4, h.engine.nativeLocalDescriptions.count { it.type == SessionDescription.Type.OFFER })
        assertEquals(2, h.engine.nativeLocalDescriptions.count { it.type == SessionDescription.Type.ANSWER })
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `incoming socket and push adoption clear outgoing creation and retained failure presentation`() = runTest {
        for (push in listOf(false, true)) for (pending in listOf(false, true)) {
            val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            if (pending) h.callApi.createCallGate = gate
            else h.callApi.createFailure = AuthApiException.Network("offline")
            h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
            assertNotNull(h.controller.ringIndicator.value)
            if (push) {
                h.callApi.getCallResult = ringingCallDto("incoming")
                h.controller.showIncomingCallFromPush("incoming", "audio")?.join()
            } else h.signaling.emit(CallSignalingEvent.Ringing("incoming", "alice", "audio"))
            assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
            assertNull(h.controller.ringIndicator.value)
            assertNull(h.controller.ringbackStartedAtElapsedRealtimeMs)
            assertNull(h.controller.startingOutgoing.value)
            if (pending) { gate.complete(Unit); runCurrent(); assertTrue("cancel" in h.callApi.calls) }
            assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
            h.controller.acceptIncoming()
            h.signaling.emit(CallSignalingEvent.Offer("incoming", remoteSdp(1)))
            h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
            assertIs<CallUiState.Connected>(h.controller.state.value)
            assertNull(h.controller.ringIndicator.value)
            h.controller.terminateForLogout()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `late outgoing create displaced by incoming cannot clear a newer outgoing presentation`() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val old = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.createCallGate = old
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        h.signaling.emit(CallSignalingEvent.Ringing("incoming", "alice", "audio"))
        h.signaling.emit(CallSignalingEvent.Ended("incoming", "cancelled"))
        h.controller.acknowledgeEnded()
        val newer = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.createCallGate = newer
        h.controller.startCall("charlie", "Charlie", CallMediaKind.AUDIO)
        old.complete(Unit); runCurrent()
        assertTrue("cancel" in h.callApi.calls)
        assertEquals("charlie", h.controller.startingOutgoing.value?.otherUserId)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        newer.complete(Unit); runCurrent()
        assertEquals("charlie", assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).call.otherUserId)
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `handover resends an outstanding upgrade or downgrade before any restart`() = runTest {
        for (upgrade in listOf(true, false)) {
            val h = Harness(gracePeriodMs = 30_000, scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
            h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
            h.engine.uniqueOffers = true
            if (upgrade) {
                h.connectAudio()
                h.controller.requestVideoUpgrade()
                h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
            } else {
                prepareVideo(h)
                h.controller.switchToAudio()
            }
            val pending = h.signaling.sentOffers.last()
            val produced = h.engine.createOfferCount
            h.engine.emit(WebRtcEvent.NetworkInterfaceChanged)
            h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
            advanceTimeBy(5_000); runCurrent()
            assertEquals(produced, h.engine.createOfferCount, "do not replace a pending media offer")
            assertEquals(0, h.engine.restartIceCount)
            assertEquals(pending, h.signaling.sentOffers.last(), "retransmit the original SDP verbatim")
            assertTrue(h.signaling.sentOffers.count { it == pending } >= 2)
            h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 media-answer"))
            advanceTimeBy(1_000); runCurrent()
            assertEquals(1, h.engine.restartIceCount, "restart only after the media offer resolves")
            assertEquals(upgrade, h.engine.offerVideoIntents.last())
            assertIs<CallUiState.Reconnecting>(h.controller.state.value)
            h.controller.terminateForLogout()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `glare invalidates a suspended offer producer without disturbing the next offer`() = runTest {
        for (mediaOffer in listOf(false, true)) {
            val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
            h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
            h.engine.uniqueOffers = true
            h.signaling.emit(CallSignalingEvent.Ringing("incoming", "alice", "audio"))
            h.controller.acceptIncoming()
            h.signaling.emit(CallSignalingEvent.Offer("incoming", "v=0\r\na=x-unyri-perfect-negotiation\r\nm=audio 9 RTP/AVP 111\r\n"))
            h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
            val abandoned = kotlinx.coroutines.CompletableDeferred<Unit>()
            h.engine.createOfferGate = abandoned
            if (mediaOffer) {
                h.controller.requestVideoUpgrade()
                h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("incoming"))
            } else h.engine.emit(WebRtcEvent.NetworkInterfaceChanged)
            assertEquals(1, h.engine.createOfferCount)
            val replacement = kotlinx.coroutines.CompletableDeferred<Unit>()
            if (mediaOffer) h.engine.createOfferGate = replacement
            h.signaling.emit(CallSignalingEvent.Offer("incoming", "v=0\r\nm=audio 9 RTP/AVP 111\r\n"))
            assertEquals(1, h.engine.rollbackCount)
            assertEquals(2, h.signaling.sentAnswers.size)
            assertTrue(h.signaling.sentOffers.isEmpty())
            // Start a newer offer before the abandoned native callback returns.
            if (mediaOffer) {
                assertEquals(2, h.engine.createOfferCount, "glare automatically reoffers the abandoned media intent")
                replacement.complete(Unit)
            } else {
                h.engine.createOfferGate = null
                h.engine.emit(WebRtcEvent.NetworkInterfaceChanged)
            }
            assertEquals(listOf("incoming" to "v=0 offer-2\na=x-unyri-perfect-negotiation\n"), h.signaling.sentOffers)
            abandoned.complete(Unit); runCurrent()
            assertEquals(1, h.signaling.sentOffers.size, "the rolled-back producer must never send")
            val applied = h.engine.setRemoteDescriptionCount
            h.signaling.emit(CallSignalingEvent.Answer("incoming", "v=0 current-answer"))
            assertEquals(applied + 1, h.engine.setRemoteDescriptionCount, "stale completion must not clear the new offer")
            assertIs<CallUiState.Connected>(h.controller.state.value)
            h.controller.terminateForLogout()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `media upgrade waits for a pending restart to resolve`() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.connectAudio()
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0\r\na=x-unyri-perfect-negotiation\r\n"))
        h.engine.emit(WebRtcEvent.DeadPairDetected(false, 0, 0, 3_000))
        assertEquals(2, h.engine.createOfferCount)
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        assertEquals(2, h.engine.createOfferCount)
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 restart-answer"))
        assertEquals(3, h.engine.createOfferCount)
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 upgrade-answer"))
        assertEquals(VideoMode.VIDEO, assertIs<CallUiState.Connected>(h.controller.state.value).videoMode)
        h.controller.terminateForLogout()
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `cancel during create absorbs a late create failure`() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.createCallGate = gate
        h.callApi.createFailure = AuthApiException.Network("late failure")
        h.controller.startCall("other", "Peer", CallMediaKind.AUDIO)
        assertNotNull(h.controller.startingOutgoing.value)
        h.controller.cancelStartingOutgoing()
        assertNull(h.controller.startingOutgoing.value)
        gate.complete(Unit); runCurrent()
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertFalse(h.controller.ringIndicator.value?.ringbackAllowed == true)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `cancel during create cancels a late created call without publishing ringing`() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.createCallGate = gate
        h.controller.startCall("other", "Peer", CallMediaKind.AUDIO)
        h.controller.cancelStartingOutgoing()
        gate.complete(Unit); runCurrent()
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertTrue("cancel" in h.callApi.calls)
        assertFalse(h.controller.ringIndicator.value?.ringbackAllowed == true)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `callee confirms once only after validated incoming state and presented post`() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.incomingNotificationPosted("incoming", true)
        assertEquals(0, h.callApi.ringingCount)
        h.callApi.getCallResult = ringingCallDto("incoming")
        h.controller.showIncomingCallFromPush("incoming", "audio")?.join()
        h.controller.incomingNotificationPosted("incoming", false)
        h.controller.incomingNotificationPosted("foreign", true)
        assertEquals(0, h.callApi.ringingCount)
        h.controller.incomingNotificationPosted("incoming", true)
        h.controller.incomingNotificationPosted("incoming", true)
        assertEquals(1, h.callApi.ringingCount)
        h.controller.acceptIncoming()
        h.controller.incomingNotificationPosted("incoming", true)
        assertEquals(1, h.callApi.ringingCount)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `cold silent callee confirms its posted ringing surface`() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.callApi.getCallResult = ringingCallDto("incoming")
        h.controller.showIncomingCallFromPush("incoming", "audio")?.join()
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        assertFalse(CallRingConfirmationPolicy.wouldAlert(true, true, 4, false, false, true))
        h.controller.incomingNotificationPosted("incoming", CallRingConfirmationPolicy.presented(true, true, 4, true, true))
        assertEquals(1, h.callApi.ringingCount)
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `callee transport or server failure gets exactly one retry after one second`() = runTest {
        for (failure in listOf(AuthApiException.Network("offline"), AuthApiException.Api("server", "server", 500),
            AuthApiException.Api("server", "server", 503), AuthApiException.Api("server", "server", 599))) {
            val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
            h.callApi.getCallResult = ringingCallDto("incoming")
            h.controller.showIncomingCallFromPush("incoming", "audio")?.join()
            h.callApi.ringingFailure = failure
            h.controller.incomingNotificationPosted("incoming", true)
            assertEquals(1, h.callApi.ringingCount)
            advanceTimeBy(999); runCurrent()
            assertEquals(1, h.callApi.ringingCount)
            advanceTimeBy(1); runCurrent()
            assertEquals(2, h.callApi.ringingCount)
            advanceTimeBy(10_000); runCurrent()
            h.controller.incomingNotificationPosted("incoming", true)
            assertEquals(2, h.callApi.ringingCount)
            h.controller.terminateForLogout()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `callee final HTTP errors never retry and duplicate posts cannot restart them`() = runTest {
        for (status in listOf(400, 401, 403, 404, 409, 429)) {
            val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
            h.callApi.getCallResult = ringingCallDto("incoming")
            h.controller.showIncomingCallFromPush("incoming", "audio")?.join()
            h.callApi.ringingFailure = AuthApiException.Api("final", "final", status)
            h.controller.incomingNotificationPosted("incoming", true)
            advanceTimeBy(2_000); runCurrent()
            h.controller.incomingNotificationPosted("incoming", true)
            assertEquals(1, h.callApi.ringingCount)
            h.controller.terminateForLogout()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `callee retry is cancelled on accept reject and logout`() = runTest {
        for (action in listOf("accept", "reject", "logout")) {
            val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
            h.callApi.getCallResult = ringingCallDto("incoming")
            h.controller.showIncomingCallFromPush("incoming", "audio")?.join()
            h.callApi.ringingFailure = AuthApiException.Network("offline")
            h.controller.incomingNotificationPosted("incoming", true)
            when (action) { "accept" -> h.controller.acceptIncoming(); "reject" -> h.controller.rejectIncoming(); else -> h.controller.terminateForLogout() }
            advanceTimeBy(2_000); runCurrent()
            assertEquals(1, h.callApi.ringingCount, action)
            h.controller.terminateForLogout()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `outgoing failed GET stays sending and only postdeadline successful GET shows not online`() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.callApi.createExpected = true
        h.callApi.getCallFailure = AuthApiException.Network("offline")
        h.controller.startCall("other", "Peer", CallMediaKind.AUDIO)
        assertEquals(CallRingIndicator.Phase.SENDING, h.controller.ringIndicator.value?.phase)
        assertFalse(h.controller.ringIndicator.value!!.ringbackAllowed)
        advanceTimeBy(6_000); runCurrent()
        assertEquals(CallRingIndicator.Phase.SENDING, h.controller.ringIndicator.value?.phase)
        h.callApi.getCallFailure = null
        h.callApi.getCallResult = CallDto("call-0", "me", "other", "audio", "ringing", createdAt = "now", peerRingingExpected = true, peerRinging = false)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(CallRingIndicator.Phase.NOT_ONLINE, h.controller.ringIndicator.value?.phase)
        h.signaling.emit(CallSignalingEvent.PeerRinging("foreign"))
        assertEquals(CallRingIndicator.Phase.NOT_ONLINE, h.controller.ringIndicator.value?.phase)
        h.signaling.emit(CallSignalingEvent.PeerRinging("call-0"))
        assertEquals(CallRingIndicator.Phase.RINGING, h.controller.ringIndicator.value?.phase)
        assertTrue(h.controller.ringIndicator.value!!.ringbackAllowed)
        val reads = h.callApi.getCallCount
        advanceTimeBy(10_000); runCurrent()
        assertEquals(reads, h.callApi.getCallCount)
        h.controller.terminateForLogout()
    }

    private fun confirmedRow(peer: Boolean = true) = CallDto("call-0", "me", "other", "audio",
        "ringing", createdAt = "now", peerRingingExpected = true, peerRinging = peer)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun qaColdConfirmationAtTwoPointFiveSecondsSurvivesStaleRows() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.callApi.createExpected = true
        h.callApi.getCallResult = confirmedRow(false)
        h.signaling.emitConnectionState(SocketConnectionState.CONNECTED)
        h.controller.startCall("other", "Peer", CallMediaKind.AUDIO)
        advanceTimeBy(1_909); runCurrent() // QA: incoming FCM; caller still awaits confirmation.
        assertEquals(CallRingIndicator.Phase.SENDING, h.controller.ringIndicator.value?.phase)
        advanceTimeBy(591); runCurrent() // Server disclosure floor.
        h.signaling.emit(CallSignalingEvent.PeerRinging("call-0"))
        assertEquals(CallRingIndicator.Phase.RINGING, h.controller.ringIndicator.value?.phase)
        assertEquals(2_500L, h.controller.ringbackStartedAtElapsedRealtimeMs)
        h.signaling.emitConnectionState(SocketConnectionState.DISCONNECTED)
        h.signaling.emitConnectionState(SocketConnectionState.CONNECTED) // Stale false row.
        advanceTimeBy(10_000); runCurrent()
        assertEquals(CallRingIndicator.Phase.RINGING, h.controller.ringIndicator.value?.phase)
        assertTrue(h.controller.ringIndicator.value!!.ringbackAllowed)
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun qaForegroundGetConfirmationAt2528MsAppliesBeforeDeadline() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.callApi.createExpected = true
        h.callApi.getCallResult = confirmedRow(false)
        h.signaling.emitConnectionState(SocketConnectionState.CONNECTED)
        h.controller.startCall("other", "Peer", CallMediaKind.AUDIO)
        advanceTimeBy(2_528); runCurrent()
        h.callApi.getCallResult = confirmedRow()
        h.controller.refreshOutgoingRinging()
        assertEquals(2, h.callApi.getCallCount)
        assertEquals(CallRingIndicator.Phase.RINGING, h.controller.ringIndicator.value?.phase)
        assertEquals(2_528L, h.controller.ringbackStartedAtElapsedRealtimeMs)
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun slowEarlyNegativeGetAndPendingDeadlineGetKeepSending() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.callApi.createExpected = true
        h.callApi.getCallHandler = { delay(4_500); confirmedRow(false) }
        h.signaling.emitConnectionState(SocketConnectionState.CONNECTED)
        h.controller.startCall("other", "Peer", CallMediaKind.AUDIO)
        advanceTimeBy(2_528); runCurrent()
        h.signaling.emitConnectionState(SocketConnectionState.DISCONNECTED)
        h.signaling.emitConnectionState(SocketConnectionState.CONNECTED)
        advanceTimeBy(3_472); runCurrent()
        assertEquals(CallRingIndicator.Phase.SENDING, h.controller.ringIndicator.value?.phase)
        advanceTimeBy(4_499); runCurrent()
        assertEquals(CallRingIndicator.Phase.SENDING, h.controller.ringIndicator.value?.phase)
        advanceTimeBy(1); runCurrent()
        assertEquals(CallRingIndicator.Phase.NOT_ONLINE, h.controller.ringIndicator.value?.phase)
        h.signaling.emit(CallSignalingEvent.PeerRinging("call-0"))
        assertEquals(CallRingIndicator.Phase.RINGING, h.controller.ringIndicator.value?.phase)
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun refreshSupersedesStalledGetAndRejectsItsStaleCompletion() = runTest {
        val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.callApi.createExpected = true
        val old = kotlinx.coroutines.CompletableDeferred<Unit>()
        var requests = 0
        h.callApi.getCallHandler = {
            if (++requests == 1) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { old.await() }
                confirmedRow(false)
            } else confirmedRow()
        }
        h.signaling.emitConnectionState(SocketConnectionState.CONNECTED)
        h.controller.startCall("other", "Peer", CallMediaKind.AUDIO)
        advanceTimeBy(2_528); runCurrent()
        h.signaling.emitConnectionState(SocketConnectionState.DISCONNECTED)
        h.signaling.emitConnectionState(SocketConnectionState.CONNECTED)
        assertEquals(CallRingIndicator.Phase.RINGING, h.controller.ringIndicator.value?.phase)
        old.complete(Unit); runCurrent()
        advanceTimeBy(4_000); runCurrent()
        assertEquals(CallRingIndicator.Phase.RINGING, h.controller.ringIndicator.value?.phase)
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun earlyConfirmationIsReplayedOnlyForTheCreatedCall() = runTest {
        for (id in listOf("foreign", "call-0")) {
            val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
            h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
            h.callApi.createExpected = true
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            h.callApi.createCallGate = gate
            h.callApi.getCallResult = confirmedRow(false)
            h.controller.startCall("other", "Peer", CallMediaKind.AUDIO)
            advanceTimeBy(2_500); runCurrent()
            h.signaling.emit(CallSignalingEvent.PeerRinging(id))
            gate.complete(Unit); runCurrent()
            assertEquals(if (id == "call-0") CallRingIndicator.Phase.RINGING else CallRingIndicator.Phase.SENDING,
                h.controller.ringIndicator.value?.phase)
            h.controller.terminateForLogout()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun confirmedAndLegacyCallsRecoverLostTerminalAtBackstop() = runTest {
        for (expected in listOf(true, false)) {
            val h = Harness(scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
            h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
            h.callApi.createExpected = expected
            h.callApi.getCallResult = confirmedRow(expected)
            h.controller.startCall("other", "Peer", CallMediaKind.AUDIO)
            h.callApi.getCallResult = confirmedRow(false).copy(state = "ended", endReason = "missed")
            advanceTimeBy(45_999); runCurrent()
            assertIs<CallUiState.OutgoingRinging>(h.controller.state.value)
            advanceTimeBy(1); runCurrent()
            assertEquals(CallEndReason.MISSED, assertIs<CallUiState.Ended>(h.controller.state.value).reason)
            val reads = h.callApi.getCallCount
            advanceTimeBy(20_000); runCurrent()
            assertEquals(reads, h.callApi.getCallCount)
            h.controller.terminateForLogout()
        }
    }

    @Test fun effectiveVideoDirectionKeepsOneWayVideoAndStopsOnlyFullAudio() {
        val cases = listOf(
            Triple("media_inactive_nonzero", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=inactive\r\n", false),
            Triple("session_inactive_inherited", "v=0\r\na=inactive\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=sendrecv\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n", false),
            Triple("zero_port_overrides_sendrecv", "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\nm=video 0 UDP/TLS/RTP/SAVPF 96\na=sendrecv\n", false),
            Triple("media_sendrecv_overrides_session_inactive", "v=0\r\na=inactive\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=sendrecv\r\n", true),
            Triple("media_recvonly_overrides_session_inactive", "v=0\r\na=inactive\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n", true),
            Triple("session_sendonly_inherited", "v=0\r\na=sendonly\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n", true),
            Triple("session_recvonly_inherited", "v=0\r\na=recvonly\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n", true),
            Triple("default_sendrecv", "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\nm=video 9 UDP/TLS/RTP/SAVPF 96\n", true),
            Triple("audio_inactive_does_not_leak", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=inactive\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n", true),
            Triple("mixed_video_any_active_preserves_video", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=inactive\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n", true),
            Triple("all_video_rejected_or_inactive", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 0 UDP/TLS/RTP/SAVPF 96\r\na=sendrecv\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=inactive\r\n", false),
            Triple("no_media_compatibility_preserves_current", "v=0 compatibility-offer", true)
        )
        for ((name, sdp, video) in cases) for (reconnect in listOf(false, true)) {
            val h = Harness()
            try {
                prepareVideo(h)
                h.engine.emit(WebRtcEvent.LocalFirstFrame)
                val before = assertIs<CallUiState.Connected>(h.controller.state.value)
                if (reconnect) h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
                h.signaling.emit(CallSignalingEvent.Offer("call-0", sdp))
                val current = h.controller.state.value
                val after = if (current is CallUiState.Reconnecting) assertIs<CallUiState.Connected>(current.since)
                    else assertIs<CallUiState.Connected>(current)
                assertEquals(if (video) VideoMode.VIDEO else VideoMode.AUDIO, after.videoMode, name)
                assertEquals(video, after.localVideoEnabled, name)
                assertEquals(video, h.engine.localVideoRunning, name)
                assertEquals(if (video) 0 else 1, h.engine.stopLocalVideoCount, name)
                assertEquals(video, h.engine.answerVideoIntents.last(), "$name: intent must precede native answer")
                assertEquals(if (video) AudioRouteKind.SPEAKER else AudioRouteKind.EARPIECE, h.engine.currentRoute.kind, name)
                assertEquals(before.call.callId, after.call.callId, name)
                assertEquals(before.connectedAtElapsedRealtimeMs, after.connectedAtElapsedRealtimeMs, name)
                assertEquals(0, h.engine.disposeCount, name)
                if (!video) {
                    h.engine.emit(WebRtcEvent.LocalFirstFrame)
                    assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind, "$name: late frame")
                }
            } finally { h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
    }

    @Test fun explicitAudioIntentSurvivesIceRestartAndRestoresForLaterUpgrade() {
        val h = Harness()
        try {
            prepareVideo(h)
            val before = assertIs<CallUiState.Connected>(h.controller.state.value)
            h.controller.setCameraEnabled(false)
            assertTrue(h.engine.videoNegotiationAllowed, "Camera toggle retains video negotiation")
            h.controller.setCameraEnabled(true)
            h.controller.switchToAudio()
            assertFalse(h.engine.offerVideoIntents.last(), "Explicit full-audio intent precedes offer")
            h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 audio-answer"))
            h.controller.requestVideoUpgrade()
            h.controller.cancelVideoUpgrade()
            h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
            assertFalse(h.engine.offerVideoIntents.last(), "ICE restart cannot restore video")
            h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 audio-restart-answer"))
            h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
            h.controller.requestVideoUpgrade()
            h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
            assertTrue(h.engine.offerVideoIntents.last(), "Later explicit upgrade restores video intent")
            assertEquals(before.call.callId, assertIs<CallUiState.Connected>(h.controller.state.value).call.callId)
            assertEquals(before.connectedAtElapsedRealtimeMs, assertIs<CallUiState.Connected>(h.controller.state.value).connectedAtElapsedRealtimeMs)
        } finally { h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }


    @Test fun cameraRestartKeepsFrontFacingInsideReconnectingSnapshotAndIgnoresOldGeneration() {
        val h = Harness()
        try {
            prepareVideo(h)
            h.engine.emit(WebRtcEvent.LocalCameraFacingChanged(1L, false))
            h.engine.stopLocalVideo()
            h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
            h.engine.beforeVideoStartReturns = { h.engine.emit(WebRtcEvent.LocalCameraFacingChanged(1L, true)) }
            h.controller.setCameraEnabled(true)
            val saved = assertIs<CallUiState.Connected>(assertIs<CallUiState.Reconnecting>(h.controller.state.value).since)
            assertTrue(saved.localCameraIsFrontFacing)
            h.engine.emit(WebRtcEvent.LocalCameraFacingChanged(0L, false))
            assertTrue(assertIs<CallUiState.Connected>(assertIs<CallUiState.Reconnecting>(h.controller.state.value).since).localCameraIsFrontFacing)
        } finally { h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    @Test fun freshCameraFacingQueryPreservesRearCaptureInsteadOfForcingEveryPreviewToMirror() {
        val h = Harness()
        try {
            h.connectAudio()
            h.engine.beforeVideoStartReturns = { h.engine.frontFacing = false }
            h.controller.requestVideoUpgrade()
            assertFalse(assertIs<CallUiState.Connected>(h.controller.state.value).localCameraIsFrontFacing)
        } finally { h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    @Test fun acceptingPeerUpgradeReadsFrontTrackFacingWithoutOpeningOurCamera() {
        val h = Harness()
        try {
            h.connectAudio()
            h.engine.emit(WebRtcEvent.LocalCameraFacingChanged(1L, false))
            h.engine.beforeVideoStartReturns = { h.engine.frontFacing = true }
            h.signaling.emit(CallSignalingEvent.VideoUpgradeRequest("call-0"))
            val upgraded = assertIs<CallUiState.Connected>(h.controller.state.value)
            assertEquals(VideoUpgradeState.Upgrading, upgraded.videoUpgrade)
            assertEquals(0, h.engine.cameraCaptureStartCount)
            assertFalse(upgraded.localVideoEnabled)
            assertTrue(upgraded.localCameraIsFrontFacing)
        } finally { h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    @Test fun rearAudioDowngradeThenFrontUpgradePreservesSynchronousFacing() {
        val h = Harness()
        try {
            prepareVideo(h)
            h.engine.emit(WebRtcEvent.LocalCameraFacingChanged(1L, false))
            h.controller.switchToAudio()
            h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 downgrade-answer"))
            val audio = assertIs<CallUiState.Connected>(h.controller.state.value)
            assertEquals(VideoMode.AUDIO, audio.videoMode)
            assertFalse(audio.localCameraIsFrontFacing)
            h.engine.beforeVideoStartReturns = {
                h.engine.emit(WebRtcEvent.LocalCameraFacingChanged(1L, true))
                assertTrue(assertIs<CallUiState.Connected>(h.controller.state.value).localCameraIsFrontFacing,
                    "Real controller must consume the facing event before startLocalVideo returns")
            }
            h.controller.requestVideoUpgrade()
            assertTrue(h.engine.localCameraIsFrontFacing())
            val upgraded = assertIs<CallUiState.Connected>(h.controller.state.value)
            assertEquals(VideoUpgradeState.Requesting, upgraded.videoUpgrade)
            assertTrue(upgraded.localCameraIsFrontFacing, "A stale rear snapshot overwrote the new front-facing state")
        } finally { h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    @Test fun serverBusyIsPresentedAsBusyWithoutPreparingMedia() {
        val h = Harness()
        h.callApi.createFailure = AuthApiException.Api("conflict", "busy", 409)
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        assertEquals(CallFailureReason.BUSY, assertIs<CallUiState.Failed>(h.controller.state.value).reason)
        assertFalse(h.engine.audioPrepared)
    }
    @Test fun unrelatedConflictIsNotMisreportedAsBusy() {
        val h = Harness()
        h.callApi.createFailure = AuthApiException.Api("conflict", "Communication is unavailable for this chat.", 409)
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        assertEquals(CallFailureReason.GENERIC, assertIs<CallUiState.Failed>(h.controller.state.value).reason)
    }
    @Test fun callingFromARoomExplainsTheBlockWithoutCreatingAPrivateCall() {
        val h = Harness(externalCallBusy = { true })
        h.controller.startCall("bob", "Bob", CallMediaKind.VIDEO)
        assertEquals(listOf(CallStartBlockedReason.ROOM), h.startBlocked)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertFalse(h.callApi.calls.contains("createCall"))
        assertFalse(h.engine.audioPrepared)
    }
    @Test fun secondCallExplainsTheBlockAndLeavesConnectedMediaUntouched() {
        val h = Harness()
        val connected = h.connectAudio()
        val disposed = h.engine.disposeCount
        h.controller.startCall("alice", "Alice", CallMediaKind.VIDEO)
        assertEquals(listOf(CallStartBlockedReason.CALL), h.startBlocked)
        assertEquals(connected, h.controller.state.value)
        assertEquals(disposed, h.engine.disposeCount)
        assertEquals(1, h.callApi.calls.count { it == "createCall" })
    }
    private suspend fun assertUnreadableIncomingRemainsUnknown(error: Exception) {
        val escaped = mutableListOf<Throwable>()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob() +
            kotlinx.coroutines.CoroutineExceptionHandler { _, failure -> escaped += failure })
        val h = Harness(scopeOverride = scope)
        var reconciled = 0
        val dismissed = mutableListOf<String>()
        h.controller.onIncomingPushReconciled = { reconciled++ }
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.callApi.getCallFailure = error
        try {
            val job = assertNotNull(h.controller.showIncomingCallFromPush("unreadable", "audio"))
            job.join()
            assertFalse(job.isCancelled, "Read/decoding failure must not escape the wake coroutine")
            assertTrue(escaped.isEmpty())
            assertIs<CallUiState.Idle>(h.controller.state.value)
            assertTrue(dismissed.isEmpty(), "An unreadable response does not prove the call ended")
            assertEquals(1, reconciled)
            assertEquals(0, h.engine.initializeCount)
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun `incoming wake tolerates truncated HTTP body without crashing or dismissing`(): Unit = runBlocking {
        assertUnreadableIncomingRemainsUnknown(java.io.IOException("synthetic truncated body"))
    }

    @Test fun `incoming wake tolerates invalid JSON without crashing or dismissing`(): Unit = runBlocking {
        assertUnreadableIncomingRemainsUnknown(kotlinx.serialization.SerializationException("synthetic invalid JSON"))
    }

    @Test fun `incoming wake cancellation stays cancellation without reconciliation callback`(): Unit = runBlocking {
        val h = Harness()
        var reconciled = 0
        h.controller.onIncomingPushReconciled = { reconciled++ }
        h.callApi.getCallFailure = kotlinx.coroutines.CancellationException("synthetic cancelled wake")
        try {
            val job = assertNotNull(h.controller.showIncomingCallFromPush("cancelled-wake", "audio"))
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(0, reconciled)
            assertIs<CallUiState.Idle>(h.controller.state.value)
        } finally { h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun `non-call capture blocks outgoing and conference admission but not incoming presentation`(): Unit = runBlocking {
        var captureBusy = true
        val h = Harness(nonCallCaptureBusy = { captureBusy })
        h.controller.startCall("bob", "Bob", CallMediaKind.VIDEO)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertTrue(h.callApi.calls.isEmpty())
        assertFalse(h.controller.canBeginConference())
        h.callApi.getCallResult = ringingCallDto("incoming")
        h.controller.showIncomingCallFromPush("incoming", "audio")?.join()
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        assertEquals(0, h.engine.initializeCount)
        captureBusy = false
    }

    @Test fun `answer keeps ringing while external camera owns capture then accepts after result`(): Unit = runBlocking {
        var drained = false
        var notice = 0
        val h = Harness(nonCallCaptureBusy = { !drained }, drainNonCallCapture = { drained }, onCaptureStillBusy = { notice++ })
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.callApi.getCallResult = ringingCallDto("incoming")
        h.controller.showIncomingCallFromPush("incoming", "audio")?.join()
        h.controller.acceptIncoming()
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        assertFalse(h.callApi.calls.contains("accept"))
        assertEquals(0, h.engine.initializeCount)
        assertEquals(1, notice)
        assertTrue(dismissed.isEmpty())
        drained = true
        h.controller.acceptIncoming()
        assertTrue(h.callApi.calls.contains("accept"))
        assertIs<CallUiState.Connecting>(h.controller.state.value)
        assertEquals(1, h.engine.initializeCount)
    }

    @Test fun `incoming answer awaits actual capture drain before API accept or media`(): Unit = runBlocking {
        val drain = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val h = Harness(drainNonCallCapture = { drain.await() })
        h.callApi.getCallResult = ringingCallDto("incoming")
        h.controller.showIncomingCallFromPush("incoming", "audio")?.join()
        h.controller.acceptIncoming()
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        assertFalse(h.callApi.calls.contains("accept"))
        assertEquals(0, h.engine.initializeCount)
        drain.complete(true)
        assertIs<CallUiState.Connecting>(h.controller.state.value)
        assertEquals(1, h.engine.initializeCount)
    }

    @Test fun `logout during capture drain cannot accept old call or initialize another owner media`(): Unit = runBlocking {
        var owner: String? = "old"
        val drain = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val h = Harness(sessionOwner = { owner }, drainNonCallCapture = { drain.await() })
        h.callApi.getCallResult = ringingCallDto("incoming")
        h.controller.showIncomingCallFromPush("incoming", "audio")?.join()
        h.controller.acceptIncoming()
        h.controller.terminateForLogout(); owner = "new"
        drain.complete(true)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertFalse(h.callApi.calls.contains("accept"))
        assertEquals(0, h.engine.initializeCount)
    }

    @Test fun `terminal incoming call during drain never accepts a stale answer`(): Unit = runBlocking {
        val drain = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val h = Harness(drainNonCallCapture = { drain.await() })
        h.callApi.getCallResult = ringingCallDto("incoming")
        h.controller.showIncomingCallFromPush("incoming", "audio")?.join()
        h.controller.acceptIncoming()
        h.controller.rejectIncoming()?.join()
        drain.complete(true)
        assertIs<CallUiState.Ended>(h.controller.state.value)
        assertFalse(h.callApi.calls.contains("accept"))
        assertEquals(0, h.engine.initializeCount)
    }

    @Test fun `self foreign and mismatched hints never interrupt owned capture but valid incoming does`(): Unit = runBlocking {
        val owner = com.messenger.app.capture.CaptureOwner("me", "https://coco.example", "device")
        val captureSession = com.messenger.app.capture.CaptureSession(owner, 1)
        lateinit var h: Harness
        val captures = com.messenger.app.capture.NonCallCaptureCoordinator({ captureSession }, { !h.controller.canBeginNonCallCapture() })
        h = Harness(nonCallCaptureBusy = { captures.busy })
        var stops = 0
        lateinit var lease: com.messenger.app.capture.NonCallCaptureCoordinator.Lease
        lease = assertNotNull(captures.acquire(com.messenger.app.capture.NonCallCaptureKind.VOICE, captureSession) { stops++; captures.release(lease) })
        val observer = h.scope.launch { h.controller.state.collect { if (!h.controller.canBeginNonCallCapture()) captures.interruptAll() } }
        for ((hint, dto) in listOf("own-hint" to CallDto("own-hint", "me", "bob", "audio", "ringing", createdAt = "now"),
            "foreign-hint" to CallDto("foreign-hint", "bob", "someone-else", "audio", "ringing", createdAt = "now"),
            "mismatch-hint" to CallDto("different", "bob", "me", "audio", "ringing", createdAt = "now"))) {
            h.callApi.getCallResult = dto
            h.controller.showIncomingCallFromPush(hint, "audio")?.join()
            assertEquals(0, stops)
        }
        h.signaling.emit(CallSignalingEvent.Ringing("self", "me", "audio"))
        assertEquals(0, stops)
        h.callApi.getCallResult = ringingCallDto("valid")
        h.controller.showIncomingCallFromPush("valid", "audio")?.join()
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        assertEquals(1, stops)
        observer.cancel()
    }

    @Test fun `terminal push reconciles outgoing missed call when socket terminal frame is lost`(): Unit = runBlocking {
        val h = Harness()
        h.controller.startCall("offline-peer", "Peer", CallMediaKind.AUDIO)
        h.callApi.getCallResult = CallDto("call-0", "me", "offline-peer", "audio", "ended", endReason = "missed", createdAt = "now")
        h.controller.dismissIncomingCallFromPush("call-0")
        assertEquals(com.messenger.app.domain.model.CallEndReason.MISSED, assertIs<CallUiState.Ended>(h.controller.state.value).reason)
        assertEquals(2, h.callApi.getCallCount, "post-create recovery plus terminal reconciliation")
    }

    @Test fun `stale or unavailable terminal hint cannot stop a live accepted call`(): Unit = runBlocking {
        val h = Harness()
        h.connectAudio()
        h.callApi.getCallResult = CallDto("call-0", "me", "other", "audio", "connected", createdAt = "now")
        h.controller.dismissIncomingCallFromPush("call-0")
        assertIs<CallUiState.Connected>(h.controller.state.value)
        h.callApi.getCallShouldFail = true
        h.controller.dismissIncomingCallFromPush("call-0")
        assertIs<CallUiState.Connected>(h.controller.state.value)
        h.callApi.getCallShouldFail = false
        h.callApi.getCallResult = h.callApi.getCallResult!!.copy(state = "ended", endReason = "hangup")
        h.controller.dismissIncomingCallFromPush("call-0")
        assertIs<CallUiState.Ended>(h.controller.state.value)
    }

    @Test fun `duplicate terminal hints share one lookup and old owner cannot end a successor`(): Unit = runBlocking {
        var owner: String? = "old-account"
        val h = Harness(sessionOwner = { owner })
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.getCallGate = gate
        h.callApi.getCallResult = CallDto("call-0", "me", "bob", "audio", "ended", endReason = "missed", createdAt = "now")
        h.controller.dismissIncomingCallFromPush("call-0")
        h.controller.dismissIncomingCallFromPush("call-0")
        assertEquals(2, h.callApi.getCallCount, "post-create recovery plus one shared terminal lookup")
        h.controller.terminateForLogout(); owner = "new-account"
        h.controller.startCall("carol", "Carol", CallMediaKind.AUDIO)
        gate.complete(Unit)
        assertEquals("call-1", assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).call.callId)
    }

    @Test fun `outgoing call wake cannot ring as a call from myself`() = runBlocking {
        var nameLookups = 0
        val h = Harness(resolver = { nameLookups++; "My name" })
        h.callApi.getCallResult = CallDto("own-call", "me", "offline-peer", "audio", "ringing", createdAt = "now")
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.controller.showIncomingCallFromPush("own-call", "audio")?.join()
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertEquals(listOf("own-call"), dismissed)
        assertEquals(0, nameLookups)
        assertEquals(0, h.engine.initializeCount)
        assertEquals(0, h.engine.prepareAudioCount)
    }

    @Test fun `foreign recipient and mismatched call lookup never become incoming`() = runBlocking {
        for (call in listOf(
            CallDto("hint", "alice", "another-account", "audio", "ringing", createdAt = "now"),
            CallDto("different-id", "alice", "me", "audio", "ringing", createdAt = "now"),
            CallDto("hint", "me", "me", "audio", "ringing", createdAt = "now"),
        )) {
            val h = Harness()
            h.callApi.getCallResult = call
            val dismissed = mutableListOf<String>()
            h.controller.onDismissIncomingPush = { dismissed += it }
            h.controller.showIncomingCallFromPush("hint", "audio")?.join()
            assertIs<CallUiState.Idle>(h.controller.state.value)
            assertEquals(listOf("hint"), dismissed)
            assertEquals(0, h.engine.initializeCount)
        }
    }

    @Test fun `self ringing socket cannot win a pending outgoing creation`() = runBlocking {
        val h = Harness()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.createCallGate = gate
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        h.signaling.emit(CallSignalingEvent.Ringing("call-0", "me", "audio"))
        assertIs<CallUiState.Idle>(h.controller.state.value)
        gate.complete(Unit)
        assertIs<CallUiState.OutgoingRinging>(h.controller.state.value)
        assertFalse(h.callApi.calls.contains("cancel"))
    }

    @Test fun `unknown account waits without presenting or erasing a possibly valid incoming hint`() = runBlocking {
        val h = Harness(currentUserId = { null })
        h.callApi.getCallResult = CallDto("hint", "alice", "me", "audio", "ringing", createdAt = "now")
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.signaling.emit(CallSignalingEvent.Ringing("hint", "alice", "audio"))
        h.controller.showIncomingCallFromPush("hint", "audio")?.join()
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertTrue(dismissed.isEmpty())
    }

    @Test fun `persisted outgoing notification owner is removed without incoming presentation`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto("old-owner", "me", "bob", "audio", "ringing", createdAt = "now")
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.controller.reconcileIncomingNotificationOwner("old-owner")
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertEquals(listOf("old-owner"), dismissed)
    }

    @Test fun `conference admission blocks outgoing push and socket private calls without media`() {
        var conferenceBusy = true
        val h = Harness(externalCallBusy = { conferenceBusy })
        h.controller.startCall("peer", "Peer", CallMediaKind.VIDEO)
        assertNull(h.controller.showIncomingCallFromPush("incoming", "video"))
        h.signaling.emit(CallSignalingEvent.Ringing("incoming", "peer", "video"))
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertEquals(0, h.engine.initializeCount)
        assertTrue(h.callApi.calls.isEmpty())
        conferenceBusy = false
        assertTrue(h.controller.canBeginConference())
        h.controller.startCall("peer", "Peer", CallMediaKind.AUDIO)
        assertFalse(h.controller.canBeginConference())
    }

    @Test fun `private call creation in flight reserves conference admission`() = runBlocking {
        val h = Harness()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.createCallGate = gate
        h.controller.startCall("peer", "Peer", CallMediaKind.AUDIO)
        assertFalse(h.controller.canBeginConference())
        h.controller.terminateForLogout()
        assertTrue(h.controller.canBeginConference())
        gate.complete(Unit)
        Unit
    }
    @Test fun `foreground service refusal releases media and shows actionable failure once`() {
        val h = Harness()
        h.connectAudio()
        val owner = assertNotNull(h.controller.foregroundServiceTicket())
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        assertTrue(h.controller.foregroundServiceUnavailable(owner))
        val failed = assertIs<CallUiState.Failed>(h.controller.state.value)
        assertEquals(CallFailureReason.DEVICE_RESTRICTED, failed.reason)
        assertEquals("call-0", failed.call?.callId)
        assertEquals(1, h.engine.disposeCount)
        assertEquals(1, h.callApi.calls.count { it == "markFailed" })
        assertEquals(listOf("call-0"), dismissed)
        assertFalse(h.controller.foregroundServiceUnavailable(owner))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertEquals(failed, h.controller.state.value)
        assertEquals(1, h.engine.disposeCount)
    }

    @Test fun `old service callback cannot release another calls media`() {
        val h = Harness()
        h.connectAudio()
        val old = assertNotNull(h.controller.foregroundServiceTicket())
        h.controller.hangup()
        h.controller.acknowledgeEnded()
        h.controller.startCall("peer", "Peer", CallMediaKind.AUDIO)
        h.signaling.emit(CallSignalingEvent.Accepted("call-1"))
        val before = h.controller.state.value
        val disposals = h.engine.disposeCount
        assertFalse(h.controller.foregroundServiceUnavailable(old))
        assertEquals(before, h.controller.state.value)
        assertEquals(disposals, h.engine.disposeCount)
        assertFalse(h.callApi.calls.contains("markFailed"))
    }

    @Test fun `foreground failure cleanup cannot use credentials after logout during dismissal`() {
        var owner = "account-a"
        val h = Harness(sessionOwner = { owner })
        h.connectAudio()
        val ticket = assertNotNull(h.controller.foregroundServiceTicket())
        h.controller.onDismissIncomingPush = { owner = "account-b"; h.controller.terminateForLogout() }
        assertTrue(h.controller.foregroundServiceUnavailable(ticket))
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertFalse(h.callApi.calls.contains("markFailed"))
    }

    @Test fun `service refusal while delayed media configuration cannot initialize after failure`() = runBlocking {
        val h = Harness()
        h.callApi.iceDelayMs = 60
        val observer = h.scope.launch {
            h.controller.state.collect { state ->
                if (state is CallUiState.Connecting) h.controller.foregroundServiceTicket()?.let(h.controller::foregroundServiceUnavailable)
            }
        }
        try {
            h.controller.startCall("peer", "Peer", CallMediaKind.AUDIO)
            h.signaling.emit(CallSignalingEvent.Accepted("call-0"))
            delay(100)
            assertEquals(CallFailureReason.DEVICE_RESTRICTED, assertIs<CallUiState.Failed>(h.controller.state.value).reason)
            assertEquals(0, h.engine.initializeCount)
            assertEquals(0, h.engine.createOfferCount)
            assertEquals(1, h.engine.disposeCount)
        } finally { observer.cancel() }
    }

    @Test fun rapidTorchTapsForwardToggleCommandsDespiteDelayedEngineState() {
        val h = Harness()
        try {
            h.engine.frontFacing = false
            prepareVideo(h)
            h.engine.emit(WebRtcEvent.LocalFirstFrame)
            h.engine.torchState.value = com.messenger.app.data.call.CallTorchState(available = true, on = false)
            h.controller.toggleTorch()
            h.controller.toggleTorch()
            assertEquals(2, h.engine.torchToggleCommands)
            assertTrue(h.engine.torchSetCommands.isEmpty(), "Main must not derive absolute ON commands")
            assertFalse(h.engine.torchState.value.on, "Capture-thread result is deliberately delayed")
            h.engine.torchState.value = com.messenger.app.data.call.CallTorchState()
            h.controller.toggleTorch()
            assertEquals(2, h.engine.torchToggleCommands)
        } finally { h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    private fun prepareVideo(h: Harness, id: String = "call-0", connect: Boolean = true) {
        h.engine.localVideoRunning = true
        h.callApi.getCallResult = CallDto(id, "me", "bob", "video", "accepted", createdAt = "2026-01-01T00:00:00Z")
        h.controller.startCall("bob", "Bob", CallMediaKind.VIDEO)
        h.signaling.emit(CallSignalingEvent.Accepted(id))
        h.signaling.emit(CallSignalingEvent.Answer(id, "v=0 answer"))
        if (connect) h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
    }

    @Test fun `only our first camera frame enables speaker and off ignores late frames`() {
        val h = Harness()
        prepareVideo(h)
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
        h.engine.emit(WebRtcEvent.RemoteVideoEnabled(true))
        h.engine.emit(WebRtcEvent.RemoteFirstFrame)
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
        assertTrue(assertIs<CallUiState.Connected>(h.controller.state.value).isSpeakerOn)
        h.controller.setCameraEnabled(false)
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
    }

    @Test fun `a local frame before connection preserves its automatic route and camera evidence`() {
        val h = Harness()
        prepareVideo(h, connect = false)
        assertIs<CallUiState.Connecting>(h.controller.state.value)
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        val connected = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(LocalCameraState.ON, connected.localCameraState)
        assertTrue(connected.isSpeakerOn)
    }

    @Test fun `manual receiver survives own camera frame renderer resume and reconnect`() {
        val h = Harness()
        prepareVideo(h)
        h.controller.setAudioRoute(AudioRoute(AudioRouteKind.EARPIECE, "Phone"))
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        h.controller.reinitRenderers()
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
        assertFalse(assertIs<CallUiState.Connected>(h.controller.state.value).isSpeakerOn)
    }

    @Test fun `manual speaker survives camera off and camera failure`() {
        val h = Harness()
        prepareVideo(h)
        h.controller.setAudioRoute(AudioRoute(AudioRouteKind.SPEAKER, "Speaker"))
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        h.controller.setCameraEnabled(false)
        assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
        h.controller.setCameraEnabled(true)
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        h.engine.emit(WebRtcEvent.LocalCameraFailed(1L))
        assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
    }

    @Test fun `camera failure releases only automatic speaker`() {
        val h = Harness()
        prepareVideo(h)
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
        h.engine.emit(WebRtcEvent.LocalCameraFailed(1L))
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
    }

    @Test fun `automatic video speaker yields to connected headset and resumes after unplug`() {
        val h = Harness()
        prepareVideo(h)
        val headset = AudioRoute(AudioRouteKind.BLUETOOTH, "Test headset")
        h.engine.emit(WebRtcEvent.AudioRouteChanged(headset))
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.BLUETOOTH, h.engine.currentRoute.kind)
        h.engine.routes = h.engine.routes.filterNot { it.kind == AudioRouteKind.BLUETOOTH }
        h.engine.emit(WebRtcEvent.AudioRouteChanged(AudioRoute(AudioRouteKind.EARPIECE, "Phone")))
        assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
        h.controller.setCameraEnabled(false)
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
    }

    @Test fun `upgrade rejection or cancellation restores automatic private route`() {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
        h.signaling.emit(CallSignalingEvent.VideoUpgradeReject("call-0"))
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
        h.controller.requestVideoUpgrade()
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
        h.controller.cancelVideoUpgrade()
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
    }

    @Test fun `upgrade timeout releases automatic speaker without ending audio`(): Unit = runBlocking {
        val h = Harness(videoUpgradeTimeoutMs = 30)
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
        kotlinx.coroutines.withTimeout(1_000) {
            while ((h.controller.state.value as? CallUiState.Connected)?.videoUpgrade != VideoUpgradeState.Idle) delay(5)
        }
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
        assertIs<CallUiState.Connected>(h.controller.state.value)
    }

    @Test fun `failed camera and receive-only peer upgrade never enable automatic speaker`() {
        val h = Harness()
        h.connectAudio()
        h.engine.startLocalVideoResult = false
        h.controller.requestVideoUpgrade()
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
        val receiver = Harness()
        receiver.connectAudio()
        receiver.signaling.emit(CallSignalingEvent.VideoUpgradeRequest("call-0"))
        assertFalse(assertIs<CallUiState.Connected>(receiver.controller.state.value).localVideoEnabled)
        receiver.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.EARPIECE, receiver.engine.currentRoute.kind)
    }

    @Test fun `new call does not inherit previous manual receiver override for video`() {
        val h = Harness()
        h.connectAudio()
        h.controller.setAudioRoute(AudioRoute(AudioRouteKind.EARPIECE, "Phone"))
        h.controller.hangup(); h.controller.acknowledgeEnded()
        prepareVideo(h, id = "call-1")
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
    }

    @Test fun `failed video upgrade negotiation restores private output and stops local camera`() {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        h.engine.setRemoteDescriptionError = IllegalStateException("test SDP failure")
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "bad answer"))
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
        assertFalse(assertIs<CallUiState.Connected>(h.controller.state.value).localVideoEnabled)
        assertFalse(h.engine.localVideoRunning)
    }

    @Test fun `outgoing ringback state is not visible before audio preparation completes`() {
        val h = Harness()
        val duringPreparation = mutableListOf<CallUiState>()
        h.engine.preparingAudio = {
            duringPreparation += h.controller.state.value
            h.engine.currentRoute = AudioRoute(AudioRouteKind.BLUETOOTH, "Test headset")
        }
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        assertEquals(listOf<CallUiState>(CallUiState.Idle), duringPreparation,
            "An observer must not start a tone while communication mode/output is still being prepared")
        val ringing = assertIs<CallUiState.OutgoingRinging>(h.controller.state.value)
        assertEquals(AudioRouteKind.BLUETOOTH, ringing.audioRoute)
        assertTrue(h.engine.audioPrepared)
    }

    @Test fun `outgoing audio preparation failure releases partial audio and permits next call`() {
        val h = Harness()
        h.engine.prepareAudioFailure = SecurityException("routing unavailable")
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        assertFalse(h.engine.audioPrepared, "A partially acquired route/focus must be released on failure")
        assertEquals(1, h.engine.disposeCount)
        assertTrue(h.controller.state.value is CallUiState.Ended || h.controller.state.value is CallUiState.Failed)
        assertTrue(h.callApi.calls.contains("markFailed"))
        h.engine.prepareAudioFailure = null
        h.controller.acknowledgeEnded()
        h.controller.startCall("carol", "Carol", CallMediaKind.AUDIO)
        assertEquals("carol", assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).call.otherUserId)
        assertTrue(h.engine.audioPrepared)
    }

    @Test fun `terminal push suppresses a late websocket ringing frame for the same call`() {
        val h = Harness()
        h.controller.dismissIncomingCallFromPush("old")
        h.signaling.emit(CallSignalingEvent.Ringing("old", "alice", "audio"))
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertEquals(0, h.engine.prepareAudioCount)
        h.signaling.emit(CallSignalingEvent.Ringing("new", "carol", "video"))
        assertEquals("new", assertIs<CallUiState.IncomingRinging>(h.controller.state.value).call.callId)
    }

    @Test fun `terminal push then delayed websocket ringing cannot resurrect a dismissed call`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto("old", "alice", "me", "audio", "ringing", createdAt = "2026-01-01T00:00:00Z")
        h.controller.showIncomingCallFromPush("old", "audio")!!.join()
        h.controller.dismissIncomingCallFromPush("old")
        assertIs<CallUiState.Idle>(h.controller.state.value)
        h.signaling.emit(CallSignalingEvent.Ringing("old", "alice", "audio"))
        assertIs<CallUiState.Idle>(h.controller.state.value)
        h.signaling.emit(CallSignalingEvent.Ringing("next", "bob", "audio"))
        assertEquals("next", assertIs<CallUiState.IncomingRinging>(h.controller.state.value).call.callId)
    }

    @Test fun `logout invalidates suspended outgoing create before account switch`(): Unit = runBlocking {
        var owner: String? = "serverA|A"
        val h = Harness(sessionOwner = { owner })
        val old = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.createCallGate = old
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        assertEquals(1, h.callApi.calls.count { it == "createCall" })
        h.controller.terminateForLogout(); owner = "serverA|B"
        old.complete(Unit)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertEquals(0, h.engine.prepareAudioCount)
        assertEquals(0, h.engine.initializeCount)
        // Never send an old-account cancel with the new account's token.
        assertFalse(h.callApi.calls.any { it in listOf("cancel", "hangup") })
    }

    @Test fun `owner change alone invalidates suspended outgoing create`(): Unit = runBlocking {
        var owner: String? = "serverA|A"
        val h = Harness(sessionOwner = { owner })
        val old = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.createCallGate = old
        h.controller.startCall("bob", "Bob", CallMediaKind.VIDEO)
        owner = "serverB|A"
        old.complete(Unit)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertEquals(0, h.engine.prepareAudioCount)
        h.callApi.createCallGate = null
        h.controller.startCall("carol", "Carol", CallMediaKind.AUDIO)
        assertEquals("carol", assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).call.otherUserId)
    }

    @Test fun `old outgoing completion cannot unlock newer account creation`(): Unit = runBlocking {
        var owner: String? = "serverA|A"
        val h = Harness(sessionOwner = { owner })
        val old = kotlinx.coroutines.CompletableDeferred<Unit>()
        val current = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.createCallGate = old
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        h.controller.terminateForLogout(); owner = "serverA|B"
        h.callApi.createCallGate = current
        h.controller.startCall("carol", "Carol", CallMediaKind.AUDIO)
        old.complete(Unit)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        h.controller.startCall("duplicate", "Duplicate", CallMediaKind.AUDIO)
        assertEquals(2, h.callApi.calls.count { it == "createCall" })
        assertEquals(0, h.engine.prepareAudioCount)
        current.complete(Unit)
        assertEquals("carol", assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).call.otherUserId)
        assertEquals(1, h.engine.prepareAudioCount)
    }

    @Test fun `old outgoing failure cannot fail or unlock newer account creation`(): Unit = runBlocking {
        var owner: String? = "serverA|A"
        val h = Harness(sessionOwner = { owner })
        val old = kotlinx.coroutines.CompletableDeferred<Unit>()
        val current = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.createCallGate = old
        h.callApi.createFailure = AuthApiException.Network("old request failed")
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        h.controller.terminateForLogout(); owner = "serverA|B"
        h.callApi.createCallGate = current
        h.callApi.createFailure = null
        h.controller.startCall("carol", "Carol", CallMediaKind.AUDIO)
        old.complete(Unit)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        h.controller.startCall("duplicate", "Duplicate", CallMediaKind.AUDIO)
        assertEquals(2, h.callApi.calls.count { it == "createCall" })
        current.complete(Unit)
        assertEquals("carol", assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).call.otherUserId)
        assertEquals(1, h.engine.prepareAudioCount)
    }

    @Test fun `late notification probe cannot dismiss an accepted call`(): Unit = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto("probe", "alice", "me", "audio", "ringing", createdAt = "2026-01-01T00:00:00Z")
        h.controller.showIncomingCallFromPush("probe", "audio")!!.join()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.getCallGate = gate
        val probe = h.scope.launch { h.controller.reconcileRingingNotification("probe") }
        h.controller.acceptIncoming()
        val accepted = h.controller.state.value
        assertFalse(accepted is CallUiState.IncomingRinging)
        h.callApi.getCallResult = h.callApi.getCallResult!!.copy(state = "ended")
        gate.complete(Unit)
        probe.join()
        assertEquals(accepted, h.controller.state.value)
    }

    @Test fun `notification probe clears stale ringing and cannot resurrect it`(): Unit = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto("probe", "alice", "me", "audio", "ringing", createdAt = "2026-01-01T00:00:00Z")
        h.controller.showIncomingCallFromPush("probe", "audio")!!.join()
        h.callApi.getCallResult = h.callApi.getCallResult!!.copy(state = "ended")
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.controller.reconcileRingingNotification("probe")
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertEquals(listOf("probe"), dismissed)
        assertEquals(null, h.controller.showIncomingCallFromPush("probe", "audio"))
    }

    @Test fun `notification probe preserves ringing on network failure and ignores foreign owner`(): Unit = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto("probe", "alice", "me", "video", "ringing", createdAt = "2026-01-01T00:00:00Z")
        h.controller.showIncomingCallFromPush("probe", "video")!!.join()
        h.callApi.getCallShouldFail = true
        h.controller.reconcileRingingNotification("probe")
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        val count = h.callApi.getCallCount
        h.controller.reconcileRingingNotification("old")
        assertEquals(count, h.callApi.getCallCount)
        h.callApi.getCallShouldFail = false
        h.controller.reconcileRingingNotification("probe")
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
    }

    @Test fun `logout invalidates suspended incoming GET before account switch`(): Unit = runBlocking {
        var owner: String? = "serverA|A"
        val h = Harness(sessionOwner = { owner }, currentUserId = { owner?.substringAfterLast('|') })
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.getCallGate = gate
        h.callApi.getCallResult = CallDto("old", "alice", "A", "audio", "ringing", createdAt = "2026-01-01T00:00:00Z")
        val job = h.controller.showIncomingCallFromPush("old", "audio")!!
        assertEquals(1, h.callApi.getCallCount)
        h.controller.terminateForLogout(); owner = "serverA|B"
        gate.complete(Unit); job.join()
        assertIs<CallUiState.Idle>(h.controller.state.value)
    }
    @Test fun `owner change during display name lookup cannot ring old account`(): Unit = runBlocking {
        var owner: String? = "serverA|A"
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val h = Harness(sessionOwner = { owner }, currentUserId = { owner?.substringAfterLast('|') }, resolver = { started.complete(Unit); gate.await(); "Alice" })
        h.callApi.getCallResult = CallDto("old", "alice", "A", "video", "ringing", createdAt = "2026-01-01T00:00:00Z")
        val job = h.controller.showIncomingCallFromPush("old", "video")!!
        kotlinx.coroutines.withTimeout(1000) { started.await() }; owner = "serverB|B"; gate.complete(Unit); job.join()
        assertIs<CallUiState.Idle>(h.controller.state.value)
    }

    @Test
    fun `camera can turn off during reconnect without restoring connected UI`() {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "video answer"))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        h.controller.setCameraEnabled(false)
        val reconnecting = assertIs<CallUiState.Reconnecting>(h.controller.state.value)
        assertFalse(assertIs<CallUiState.Connected>(reconnecting.since).localVideoEnabled)
        assertEquals(false, h.engine.videoEnabled)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `old peer restart waits three seconds and answered round backs off four seconds`() = runTest {
        val h = Harness(gracePeriodMs = 30_000L, scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.connectAudio()
        val offers = h.engine.createOfferCount
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        advanceTimeBy(2_999); runCurrent()
        assertEquals(offers, h.engine.createOfferCount, "legacy peer gets the three-second reconnect window")
        advanceTimeBy(1); runCurrent()
        assertEquals(offers + 1, h.engine.createOfferCount)
        val descriptions = h.engine.setRemoteDescriptionCount
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 restart-answer"))
        assertEquals(descriptions + 1, h.engine.setRemoteDescriptionCount)
        assertIs<CallUiState.Reconnecting>(h.controller.state.value)
        advanceTimeBy(3_999); runCurrent()
        assertEquals(1, h.engine.restartIceCount, "answer starts the ordinary four-second backoff")
        advanceTimeBy(1); runCurrent()
        assertEquals(2, h.engine.restartIceCount)
        h.controller.terminateForLogout()
    }

    @Test
    fun `audio ICE offer does not unexpectedly upgrade video`() {
        val h = Harness()
        h.connectAudio()
        h.signaling.emit(CallSignalingEvent.Offer("call-0", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
        assertEquals(VideoMode.AUDIO, assertIs<CallUiState.Connected>(h.controller.state.value).videoMode)
    }

    @Test
    fun `double tap start creates one call`(): Unit = runBlocking {
        val h = Harness()
        h.callApi.createDelayMs = 40
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        delay(100)
        assertEquals(1, h.callApi.calls.count { it == "createCall" })
        assertIs<CallUiState.OutgoingRinging>(h.controller.state.value)
    }

    @Test
    fun `hangup immediately releases media and old response cannot end next call`() = runBlocking {
        val h = Harness()
        h.connectAudio()
        h.callApi.endDelayMs = 50
        h.controller.hangup()
        assertIs<CallUiState.Ended>(h.controller.state.value)
        assertEquals(1, h.engine.disposeCount)
        h.controller.acknowledgeEnded()
        h.controller.startCall("alice", "Alice", CallMediaKind.AUDIO)
        delay(100)
        assertEquals("call-1", assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).call.callId)
    }

    @Test
    fun `late ICE configuration cannot restart ended media`() = runBlocking {
        val h = Harness()
        h.callApi.iceDelayMs = 50
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        h.signaling.emit(CallSignalingEvent.Accepted("call-0"))
        h.controller.hangup()
        delay(100)
        assertIs<CallUiState.Ended>(h.controller.state.value)
        assertEquals(0, h.engine.initializeCount)
        assertEquals(0, h.engine.createOfferCount)
    }

    @Test
    fun `failed initial SDP does not kill signaling collector`() {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        h.signaling.emit(CallSignalingEvent.Accepted("call-0"))
        h.engine.setRemoteDescriptionError = IllegalStateException("bad SDP")
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "invalid"))
        assertIs<CallUiState.Ended>(h.controller.state.value)
        h.controller.acknowledgeEnded()
        h.engine.setRemoteDescriptionError = null
        h.controller.startCall("alice", "Alice", CallMediaKind.AUDIO)
        h.signaling.emit(CallSignalingEvent.Accepted("call-1"))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertIs<CallUiState.Connected>(h.controller.state.value)
    }

    @Test
    fun `camera off during setup survives late track and connection`() {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.VIDEO)
        h.signaling.emit(CallSignalingEvent.Accepted("call-0"))
        h.signaling.emit(CallSignalingEvent.VideoState("call-0", false))
        h.engine.emit(WebRtcEvent.RemoteVideoEnabled(true))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertFalse(assertIs<CallUiState.Connected>(h.controller.state.value).remoteVideoEnabled)
    }

    @Test
    fun `camera errors and facing are scoped to current capturer`() {
        val h = Harness()
        h.connectAudio()
        h.engine.emit(WebRtcEvent.LocalCameraFacingChanged(1L, false))
        assertFalse(assertIs<CallUiState.Connected>(h.controller.state.value).localCameraIsFrontFacing)
        h.engine.emit(WebRtcEvent.LocalCameraFailed(0L))
        assertEquals(LocalCameraState.OFF, assertIs<CallUiState.Connected>(h.controller.state.value).localCameraState)
        h.engine.emit(WebRtcEvent.LocalCameraFailed(1L))
        assertEquals(LocalCameraState.FAILED, assertIs<CallUiState.Connected>(h.controller.state.value).localCameraState)
        assertTrue(h.signaling.sentVideoStates.contains("call-0" to false))
    }

    @Test
    fun `ringing controls survive answering and reconnecting`() {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        h.controller.toggleMute()
        h.controller.setAudioRoute(AudioRoute(AudioRouteKind.SPEAKER, "Speaker"))
        val ringing = assertIs<CallUiState.OutgoingRinging>(h.controller.state.value)
        assertTrue(ringing.isMuted)
        assertEquals(AudioRouteKind.SPEAKER, ringing.audioRoute)
        assertEquals(0, h.engine.initializeCount)
        h.signaling.emit(CallSignalingEvent.Accepted("call-0"))
        assertTrue(assertIs<CallUiState.Connecting>(h.controller.state.value).isMuted)
        assertEquals(true, h.engine.muted)
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        val connected = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertTrue(connected.isMuted)
        assertTrue(connected.isSpeakerOn)
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        h.controller.toggleMute()
        h.controller.setAudioRoute(AudioRoute(AudioRouteKind.EARPIECE, "Phone"))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        val restored = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertFalse(restored.isMuted)
        assertEquals(AudioRouteKind.EARPIECE, restored.audioRoute)
        h.controller.hangup()
        h.controller.acknowledgeEnded()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        assertFalse(assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).isMuted)
    }

    @Test
    fun `microphone can be changed while connecting`() {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        h.signaling.emit(CallSignalingEvent.Accepted("call-0"))
        h.controller.toggleMute()
        assertEquals(true, h.engine.muted)
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertTrue(assertIs<CallUiState.Connected>(h.controller.state.value).isMuted)
    }

    @Test
    fun `reconnect requires a new received frame even with no renderer attached`() {
        val h = Harness()
        h.connectAudio()
        h.signaling.emit(CallSignalingEvent.VideoState("call-0", true))
        h.engine.emit(WebRtcEvent.RemoteFirstFrame)
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        val resets = h.engine.frameResets
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertEquals(resets + 1, h.engine.frameResets)
        assertEquals(RemoteVideoState.WAITING, assertIs<CallUiState.Connected>(h.controller.state.value).remoteVideoState)
        h.engine.emit(WebRtcEvent.RemoteFirstFrame)
        assertEquals(RemoteVideoState.ON, assertIs<CallUiState.Connected>(h.controller.state.value).remoteVideoState)
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertEquals(resets + 1, h.engine.frameResets)
    }

    @Test
    fun `camera restart rearms frame evidence and late frame cannot undo camera off`() {
        val h = Harness()
        h.connectAudio()
        h.signaling.emit(CallSignalingEvent.VideoState("call-0", true))
        h.engine.emit(WebRtcEvent.RemoteFirstFrame)
        assertEquals(RemoteVideoState.ON, assertIs<CallUiState.Connected>(h.controller.state.value).remoteVideoState)
        h.signaling.emit(CallSignalingEvent.VideoState("call-0", false))
        h.engine.emit(WebRtcEvent.RemoteFirstFrame)
        assertEquals(RemoteVideoState.OFF, assertIs<CallUiState.Connected>(h.controller.state.value).remoteVideoState)
        h.signaling.emit(CallSignalingEvent.VideoState("call-0", true))
        assertEquals(2, h.engine.frameResets)
        h.engine.emit(WebRtcEvent.RemoteFirstFrame)
        assertEquals(RemoteVideoState.ON, assertIs<CallUiState.Connected>(h.controller.state.value).remoteVideoState)
    }

    @Test
    fun `new push call replaces a previous ended screen`() = runBlocking {
        val h = Harness()
        h.connectAudio()
        h.controller.hangup()
        assertIs<CallUiState.Ended>(h.controller.state.value)
        h.callApi.getCallResult = CallDto("new", "alice", "me", "audio", "ringing", createdAt = "2026-01-01T00:00:00Z")
        h.controller.showIncomingCallFromPush("new", "audio")?.join()
        assertEquals("new", assertIs<CallUiState.IncomingRinging>(h.controller.state.value).call.callId)
    }

    @Test
    fun `terminal push invalidates a suspended incoming lookup`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto("race", "alice", "me", "audio", "ringing", createdAt = "2026-01-01T00:00:00Z")
        h.callApi.getCallDelayMs = 40
        val job = h.controller.showIncomingCallFromPush("race", "audio")
        h.controller.dismissIncomingCallFromPush("race")
        job?.join()
        assertIs<CallUiState.Idle>(h.controller.state.value)
        h.controller.showIncomingCallFromPush("race", "audio")?.join()
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertEquals(1, h.callApi.getCallCount)
    }

    @Test
    fun `socket ringing during push GET keeps the live incoming notification`() = runBlocking {
        val h = Harness()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.getCallGate = gate
        h.callApi.getCallResult = CallDto("race", "alice", "me", "audio", "ringing", createdAt = "2026-01-01T00:00:00Z")
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        val lookup = h.controller.showIncomingCallFromPush("race", "audio")
        h.signaling.emit(CallSignalingEvent.Ringing("race", "alice", "audio"))
        assertEquals("race", assertIs<CallUiState.IncomingRinging>(h.controller.state.value).call.callId)
        gate.complete(Unit)
        lookup?.join()
        assertEquals(emptyList(), dismissed, "same live call must not be reported terminal")
        assertEquals("race", assertIs<CallUiState.IncomingRinging>(h.controller.state.value).call.callId)
        h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }

    @Test
    fun `socket ringing during push name lookup keeps the live incoming notification`() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val h = Harness(resolver = { entered.complete(Unit); gate.await(); "Alice" })
        h.callApi.getCallResult = CallDto("race", "alice", "me", "audio", "ringing", createdAt = "2026-01-01T00:00:00Z")
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        val lookup = h.controller.showIncomingCallFromPush("race", "audio")
        entered.await()
        h.signaling.emit(CallSignalingEvent.Ringing("race", "alice", "audio"))
        gate.complete(Unit)
        lookup?.join()
        assertEquals(emptyList(), dismissed, "name lookup must not erase the socket notification")
        assertEquals("race", assertIs<CallUiState.IncomingRinging>(h.controller.state.value).call.callId)
        h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }

    @Test
    fun `ended authoritative lookup clears socket ringing before notification refresh`() = runBlocking {
        val h = Harness()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.callApi.getCallGate = gate
        h.callApi.getCallResult = CallDto("race", "alice", "me", "audio", "ended", createdAt = "2026-01-01T00:00:00Z")
        val refreshed = mutableListOf<CallUiState>()
        h.controller.onIncomingPushReconciled = { refreshed += h.controller.state.value }
        val lookup = h.controller.showIncomingCallFromPush("race", "audio")
        h.signaling.emit(CallSignalingEvent.Ringing("race", "alice", "audio"))
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        gate.complete(Unit)
        lookup?.join()
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertTrue(refreshed.none { it is CallUiState.IncomingRinging }, "refresh must not resurrect an ended notification")
        h.signaling.emit(CallSignalingEvent.Ringing("race", "alice", "audio"))
        assertIs<CallUiState.Idle>(h.controller.state.value)
        h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }

    @Test
    fun `authoritative ended push lookup prevents a late socket ring`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto("late", "alice", "me", "audio", "ended", createdAt = "2026-01-01T00:00:00Z")
        h.controller.showIncomingCallFromPush("late", "audio")?.join()
        h.signaling.emit(CallSignalingEvent.Ringing("late", "alice", "audio"))
        assertIs<CallUiState.Idle>(h.controller.state.value)
        h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }

    @Test
    fun `suspended push does not overwrite a newer outgoing call`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto("old", "alice", "me", "audio", "ringing", createdAt = "2026-01-01T00:00:00Z")
        h.callApi.getCallDelayMs = 40
        val job = h.controller.showIncomingCallFromPush("old", "audio")
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        job?.join()
        assertEquals("call-0", assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).call.callId)
    }

    @Test
    fun `authoritative media kind overrides stale push hint`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto("audio", "alice", "me", "audio", "ringing", createdAt = "2026-01-01T00:00:00Z")
        h.controller.showIncomingCallFromPush("audio", "video")?.join()
        assertEquals(CallMediaKind.AUDIO, assertIs<CallUiState.IncomingRinging>(h.controller.state.value).call.mediaKind)
    }


    @Test
    fun `start call transitions Idle to OutgoingRinging`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val state = assertIs<CallUiState.OutgoingRinging>(h.controller.state.value)
        assertEquals("bob", state.call.otherUserId)
        assertTrue(h.callApi.calls.contains("createCall"))
    }

    @Test
    fun `incoming ringing event transitions Idle to IncomingRinging`() {
        val h = Harness()
        h.signaling.emit(CallSignalingEvent.Ringing("call-1", "alice", "audio"))
        val state = assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        assertEquals("alice", state.call.otherUserId)
    }

    @Test
    fun `fcm wake for a ringing call shows IncomingRinging`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto(
            id = "call-push", callerUserId = "alice", calleeUserId = "me", mediaKind = "video",
            state = "ringing", createdAt = "2026-01-01T00:00:00Z",
        )
        h.controller.showIncomingCallFromPush("call-push", "video")
        val state = assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        assertEquals("call-push", state.call.callId)
        assertEquals(CallMediaKind.VIDEO, state.call.mediaKind)
        assertEquals("alice", state.call.otherUserId)
    }

    @Test
    fun `fcm wake for a terminal call never resurrects`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto(
            id = "call-push", callerUserId = "alice", calleeUserId = "me", mediaKind = "audio",
            state = "ended", endReason = "cancelled", createdAt = "2026-01-01T00:00:00Z",
        )
        h.controller.showIncomingCallFromPush("call-push", "audio")
        assertIs<CallUiState.Idle>(h.controller.state.value)
        Unit
    }

    @Test
    fun `fcm call_ended dismisses the ringing state`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto(
            id = "call-push", callerUserId = "alice", calleeUserId = "me", mediaKind = "audio",
            state = "ringing", createdAt = "2026-01-01T00:00:00Z",
        )
        h.controller.showIncomingCallFromPush("call-push", "audio")
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        h.controller.dismissIncomingCallFromPush("call-push")
        assertIs<CallUiState.Idle>(h.controller.state.value)
        Unit
    }

    @Test
    fun `fcm call_ended for a different call is a no-op`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto(
            id = "call-push", callerUserId = "alice", calleeUserId = "me", mediaKind = "audio",
            state = "ringing", createdAt = "2026-01-01T00:00:00Z",
        )
        h.controller.showIncomingCallFromPush("call-push", "audio")
        h.controller.dismissIncomingCallFromPush("some-other-call")
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        Unit
    }

    @Test
    fun `P0_1 terminal push while Idle signals incoming notification dismissal`() = runBlocking {
        // Process-dead path: the controller was just (re)started with no
        // IncomingRinging state, but a call_ended/cancelled push arrives.
        // It must signal the terminal call_id so the manager can apply the
        // owner-scoped cancel (never touches a newer call's slot).
        val h = Harness()
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        assertIs<CallUiState.Idle>(h.controller.state.value)
        h.controller.dismissIncomingCallFromPush("stale-call")
        assertEquals(listOf("stale-call"), dismissed)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        Unit
    }

    @Test
    fun `P0_1 duplicate terminal push is idempotent`() = runBlocking {
        val h = Harness()
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.controller.dismissIncomingCallFromPush("stale-call")
        h.controller.dismissIncomingCallFromPush("stale-call")
        assertEquals(listOf("stale-call", "stale-call"), dismissed) // each push re-arms; cancel is idempotent at the manager
        assertIs<CallUiState.Idle>(h.controller.state.value)
        Unit
    }

    @Test
    fun `P0_1 terminal push for the ringing call also signals owner-scoped dismissal`() = runBlocking {
        // Process-alive path: the push matches the current ringing call. It
        // must reset state AND signal the owner-scoped slot cleanup (needed
        // when the notification actually belongs to a previous process).
        val h = Harness()
        h.callApi.getCallResult = CallDto(
            id = "call-1", callerUserId = "alice", calleeUserId = "me", mediaKind = "audio",
            state = "ringing", createdAt = "2026-01-01T00:00:00Z",
        )
        h.controller.showIncomingCallFromPush("call-1", "audio")
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.controller.dismissIncomingCallFromPush("call-1")
        assertEquals(listOf("call-1"), dismissed)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        Unit
    }

    @Test
    fun `P0_1 terminal push while ringing a different call does not touch it`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto(
            id = "call-1", callerUserId = "alice", calleeUserId = "me", mediaKind = "audio",
            state = "ringing", createdAt = "2026-01-01T00:00:00Z",
        )
        h.controller.showIncomingCallFromPush("call-1", "audio")
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        // A stale call_ended for a different (already-terminal) call must not
        // cancel the still-ringing call-1 notification.
        h.controller.dismissIncomingCallFromPush("some-other-call")
        assertEquals(emptyList(), dismissed)
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        Unit
    }

    @Test
    fun `P0_1 reconcile owner while still ringing restores IncomingRinging without dismissal`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto(
            id = "owner-call", callerUserId = "alice", calleeUserId = "me", mediaKind = "audio",
            state = "ringing", createdAt = "2026-01-01T00:00:00Z",
        )
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.controller.reconcileIncomingNotificationOwner("owner-call")
        val state = assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        assertEquals("owner-call", state.call.callId)
        assertEquals(emptyList(), dismissed)
        Unit
    }

    @Test
    fun `P0_1 reconcile owner when terminal signals dismissal with that call_id`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto(
            id = "owner-call", callerUserId = "alice", calleeUserId = "me", mediaKind = "audio",
            state = "ended", endReason = "cancelled", createdAt = "2026-01-01T00:00:00Z",
        )
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.controller.reconcileIncomingNotificationOwner("owner-call")
        assertEquals(listOf("owner-call"), dismissed)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        Unit
    }

    @Test
    fun `P0_1 reconcile owner on auth failure leaves state Idle without dismissal`() = runBlocking {
        val h = Harness()
        h.callApi.getCallShouldFail = true
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.controller.reconcileIncomingNotificationOwner("owner-call")
        assertEquals(emptyList(), dismissed) // network failure must never erase a valid notification
        assertIs<CallUiState.Idle>(h.controller.state.value)
        Unit
    }

    @Test
    fun `P0_1 reconcile owner on timeout leaves state Idle without dismissal`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto(
            id = "owner-call", callerUserId = "alice", calleeUserId = "me", mediaKind = "audio",
            state = "ringing", createdAt = "2026-01-01T00:00:00Z",
        )
        h.callApi.getCallDelayMs = 10_000L // hangs past RECONCILE_TIMEOUT_MS
        val dismissed = mutableListOf<String>()
        h.controller.onDismissIncomingPush = { dismissed += it }
        h.controller.reconcileIncomingNotificationOwner("owner-call")
        assertEquals(emptyList(), dismissed)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        Unit
    }

    @Test
    fun `fcm wake reconcile timeout leaves state Idle (stale push never hangs ringing)`() = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = CallDto(
            id = "call-push", callerUserId = "alice", calleeUserId = "me", mediaKind = "audio",
            state = "ringing", createdAt = "2026-01-01T00:00:00Z",
        )
        // The authoritative lookup hangs past the strict reconcile timeout:
        // the push must NOT hang, and the state must stay Idle (never a
        // half-presented ringing that could resurrect a stale call).
        h.callApi.getCallDelayMs = 5_000L
        h.controller.showIncomingCallFromPush("call-push", "audio")
        assertIs<CallUiState.Idle>(h.controller.state.value)
        Unit
    }

    @Test
    fun `accept transitions to Connecting and initializes the engine`() = runBlocking {
        val h = Harness()
        h.signaling.emit(CallSignalingEvent.Ringing("call-1", "alice", "audio"))
        h.controller.acceptIncoming()
        assertIs<CallUiState.Connecting>(h.controller.state.value)
        assertTrue(h.callApi.calls.contains("accept"))
        assertEquals(1, h.engine.initializeCount)
    }

    @Test
    fun `caller sends offer once callee accepts`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        assertIs<CallUiState.Connecting>(h.controller.state.value)
        assertEquals(1, h.engine.createOfferCount)
        assertEquals(1, h.signaling.sentOffers.size)
        assertEquals(callId, h.signaling.sentOffers.first().first)
    }

    @Test
    fun `callee applies remote offer and sends an answer`() = runBlocking {
        val h = Harness()
        h.signaling.emit(CallSignalingEvent.Ringing("call-1", "alice", "audio"))
        h.controller.acceptIncoming()
        h.signaling.emit(CallSignalingEvent.Offer("call-1", "v=0 real offer"))
        assertEquals(1, h.engine.setRemoteDescriptionCount)
        assertEquals(1, h.engine.createAnswerCount)
        assertEquals(1, h.signaling.sentAnswers.size)
    }

    @Test
    fun `offer arriving before accept completes is buffered then applied`() = runBlocking {
        val h = Harness()
        h.signaling.emit(CallSignalingEvent.Ringing("call-1", "alice", "audio"))
        // Offer broadcast to all callee devices BEFORE this device accepted.
        h.signaling.emit(CallSignalingEvent.Offer("call-1", "v=0 early offer"))
        assertEquals(0, h.engine.setRemoteDescriptionCount, "must not apply before accept() completes")
        h.controller.acceptIncoming()
        assertEquals(1, h.engine.setRemoteDescriptionCount)
        assertEquals(1, h.signaling.sentAnswers.size)
    }

    @Test
    fun `pending remote ICE keeps the first 64 unique candidates across SDP application`() {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = assertIs<CallUiState.OutgoingRinging>(h.controller.state.value).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        val candidates = (0..64).map { "candidate:$it 1 udp 100 8.8.8.8 ${5000 + it} typ host" }
        for (candidate in candidates) {
            repeat(2) { h.signaling.emit(CallSignalingEvent.Ice(callId, candidate, "0", 0)) }
        }
        assertTrue(h.engine.addedIceCandidates.isEmpty())
        h.signaling.emit(CallSignalingEvent.Answer(callId, "v=0 answer"))
        assertEquals(candidates.take(64), h.engine.addedIceCandidates)
        h.controller.terminateForLogout()
        h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `remote ICE preserves the 2048 candidate and 64 MID character boundaries`() {
        val h = Harness()
        h.connectAudio()
        val prefix = "candidate:bounded 1 udp 100 8.8.8.8 5000 typ host padding "
        val limit = prefix + "x".repeat(2048 - prefix.length)
        h.signaling.emit(CallSignalingEvent.Ice("call-0", limit + "x", "0", 0))
        h.signaling.emit(CallSignalingEvent.Ice("call-0", limit, "m".repeat(65), 0))
        h.signaling.emit(CallSignalingEvent.Ice("call-0", limit, "m".repeat(64), 17))
        h.signaling.emit(CallSignalingEvent.Ice("call-0", "candidate:unsafe 1 udp 100 127.0.0.1 5000 typ host", "0", 0))
        assertTrue(h.engine.addedIceCandidates.isEmpty())
        h.signaling.emit(CallSignalingEvent.Ice("call-0", limit, "m".repeat(64), 16))
        assertEquals(listOf(limit), h.engine.addedIceCandidates)
        h.controller.terminateForLogout()
        h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `ice candidates arriving before remote description are queued then flushed in order`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        // ICE arrives before the answer (remote description not set yet).
        h.signaling.emit(CallSignalingEvent.Ice(callId, "candidate:1 1 udp 100 8.8.8.8 5001 typ host", "0", 0))
        h.signaling.emit(CallSignalingEvent.Ice(callId, "candidate:2 1 udp 100 8.8.4.4 5002 typ host", "0", 0))
        assertTrue(h.engine.addedIceCandidates.isEmpty(), "must be queued, not forwarded yet")
        h.signaling.emit(CallSignalingEvent.Answer(callId, "v=0 answer"))
        assertEquals(listOf("candidate:1 1 udp 100 8.8.8.8 5001 typ host", "candidate:2 1 udp 100 8.8.4.4 5002 typ host"), h.engine.addedIceCandidates)
    }

    @Test
    fun `duplicate ice candidate is forwarded only once`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.signaling.emit(CallSignalingEvent.Answer(callId, "v=0 answer"))
        h.signaling.emit(CallSignalingEvent.Ice(callId, "candidate:x 1 udp 100 8.8.8.8 5003 typ host", "0", 0))
        h.signaling.emit(CallSignalingEvent.Ice(callId, "candidate:x 1 udp 100 8.8.8.8 5003 typ host", "0", 0))
        assertEquals(listOf("candidate:x 1 udp 100 8.8.8.8 5003 typ host"), h.engine.addedIceCandidates)
    }

    @Test
    fun `late duplicate answer is ignored`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.signaling.emit(CallSignalingEvent.Answer(callId, "first"))
        h.signaling.emit(CallSignalingEvent.Answer(callId, "second"))
        assertEquals(1, h.engine.setRemoteDescriptionCount)
    }

    @Test
    fun `reject transitions IncomingRinging to Ended with rejected reason`() = runBlocking {
        val h = Harness()
        h.signaling.emit(CallSignalingEvent.Ringing("call-1", "alice", "audio"))
        h.controller.rejectIncoming()
        val ended = assertIs<CallUiState.Ended>(h.controller.state.value)
        assertEquals(com.messenger.app.domain.model.CallEndReason.REJECTED, ended.reason)
        assertTrue(h.callApi.calls.contains("reject"))
    }

    @Test
    fun `cancel transitions OutgoingRinging to Ended with cancelled reason`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        h.controller.cancelOutgoing()
        val ended = assertIs<CallUiState.Ended>(h.controller.state.value)
        assertEquals(com.messenger.app.domain.model.CallEndReason.CANCELLED, ended.reason)
    }

    @Test
    fun `hangup ends a connected call and disposes the engine`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertIs<CallUiState.Connected>(h.controller.state.value)
        h.controller.hangup()
        val ended = assertIs<CallUiState.Ended>(h.controller.state.value)
        assertEquals(com.messenger.app.domain.model.CallEndReason.HANGUP, ended.reason)
        assertEquals(1, h.engine.disposeCount)
    }

    @Test
    fun `answered elsewhere silently dismisses the incoming call`() {
        val h = Harness()
        h.signaling.emit(CallSignalingEvent.Ringing("call-1", "alice", "audio"))
        h.signaling.emit(CallSignalingEvent.AnsweredElsewhere("call-1"))
        assertEquals(CallUiState.Idle, h.controller.state.value)
    }

    @Test
    fun `losing the accept race resets to Idle without an error state`() = runBlocking {
        val h = Harness()
        h.callApi.acceptShouldFail = true
        h.signaling.emit(CallSignalingEvent.Ringing("call-1", "alice", "audio"))
        h.controller.acceptIncoming()
        assertEquals(CallUiState.Idle, h.controller.state.value)
    }

    @Test
    fun `terminal call cannot be resurrected by a late signaling event`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.controller.cancelOutgoing()
        assertIs<CallUiState.Ended>(h.controller.state.value)
        // A late offer for the already-ended call must not revive it.
        h.signaling.emit(CallSignalingEvent.Offer(callId, "too late"))
        assertIs<CallUiState.Ended>(h.controller.state.value)
        assertEquals(0, h.engine.setRemoteDescriptionCount)
    }

    @Test
    fun `mute speaker and video toggles delegate to the engine`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.VIDEO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))

        h.controller.toggleMute()
        assertEquals(true, h.engine.muted)
        h.controller.toggleSpeaker()
        assertEquals(true, h.engine.speakerOn)
        h.controller.switchCamera()
        assertEquals(1, h.engine.switchCameraCount)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `reconnect before a legacy peers restart deadline cancels recovery`() = runTest {
        val h = Harness(gracePeriodMs = 30_000L, scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.connectAudio()
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        assertIs<CallUiState.Reconnecting>(h.controller.state.value)
        advanceTimeBy(2_000); runCurrent()
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertIs<CallUiState.Connected>(h.controller.state.value)
        advanceTimeBy(31_000); runCurrent()
        assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(0, h.engine.restartIceCount)
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `unanswered recovery resends the same offer and fails at the thirty second outage budget`() = runTest {
        val h = Harness(gracePeriodMs = 30_000L, scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.connectAudio()
        val initialOffers = h.signaling.sentOffers.size
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, h.engine.restartIceCount)
        val restartOffer = h.signaling.sentOffers.last()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(initialOffers + 2, h.signaling.sentOffers.size)
        assertEquals(restartOffer, h.signaling.sentOffers.last(), "resend preserves the original SDP text")
        assertEquals(1, h.engine.restartIceCount, "unanswered offers never create a second native restart")
        advanceTimeBy(21_999); runCurrent()
        assertIs<CallUiState.Reconnecting>(h.controller.state.value)
        assertEquals(1, h.engine.restartIceCount)
        advanceTimeBy(1); runCurrent()
        val ended = assertIs<CallUiState.Ended>(h.controller.state.value)
        assertEquals(com.messenger.app.domain.model.CallEndReason.FAILED, ended.reason)
        assertTrue(h.callApi.calls.contains("markFailed"))
        assertEquals(1, h.engine.disposeCount)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `media reconnect after restart cancels resends and later timeout`() = runTest {
        val h = Harness(gracePeriodMs = 30_000L, scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.connectAudio()
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        assertIs<CallUiState.Reconnecting>(h.controller.state.value)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, h.engine.restartIceCount)
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertIs<CallUiState.Connected>(h.controller.state.value)
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 recovery-answer"))
        val sent = h.signaling.sentOffers.size
        advanceTimeBy(31_000); runCurrent()
        assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(1, h.engine.restartIceCount)
        assertEquals(sent, h.signaling.sentOffers.size)
        h.controller.terminateForLogout()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `dead pair restarts only the caller toward a glare capable peer`() = runTest {
        for (advertised in listOf(false, true)) {
            val h = Harness(gracePeriodMs = 30_000L, scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
            h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
            h.connectAudio()
            if (advertised) h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0\r\na=x-unyri-perfect-negotiation\r\n"))
            h.engine.emit(WebRtcEvent.DeadPairDetected(sendsRefused = false, packets = 0, refused = 0, watchedMs = 3_000))
            assertEquals(if (advertised) 1 else 0, h.engine.restartIceCount)
            assertIs<CallUiState.Connected>(h.controller.state.value, "dead-pair nudge keeps media up")
            advanceTimeBy(31_000); runCurrent()
            assertIs<CallUiState.Connected>(h.controller.state.value, "dead pair alone can never exhaust the call")
            h.controller.terminateForLogout()
        }
        val callee = Harness(gracePeriodMs = 30_000L, scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        callee.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        callee.signaling.emit(CallSignalingEvent.Ringing("incoming", "bob", "audio"))
        callee.controller.acceptIncoming()
        callee.signaling.emit(CallSignalingEvent.Offer("incoming", "v=0\r\na=x-unyri-perfect-negotiation\r\n"))
        callee.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertIs<CallUiState.Connected>(callee.controller.state.value)
        callee.engine.emit(WebRtcEvent.DeadPairDetected(sendsRefused = true, packets = 30, refused = 10, watchedMs = 3_000))
        advanceTimeBy(31_000); runCurrent()
        assertEquals(0, callee.engine.restartIceCount)
        assertTrue(callee.signaling.sentOffers.isEmpty(), "callee observes a dead pair without offering")
        assertIs<CallUiState.Connected>(callee.controller.state.value)
        callee.controller.terminateForLogout()
    }

    @Test
    fun `terminal ended state never triggers ice restart`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        h.controller.hangup()
        assertIs<CallUiState.Ended>(h.controller.state.value)

        // A late DISCONNECTED after the call already ended must be ignored.
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        assertIs<CallUiState.Ended>(h.controller.state.value)
        assertEquals(0, h.engine.restartIceCount)
    }

    @Test
    fun `sequential calls leave no stale state for the next call`() = runBlocking {
        val h = Harness()
        repeat(3) { i ->
            h.controller.startCall("bob$i", "Bob $i", CallMediaKind.AUDIO)
            assertIs<CallUiState.OutgoingRinging>(h.controller.state.value)
            h.controller.cancelOutgoing()
            assertIs<CallUiState.Ended>(h.controller.state.value)
            h.controller.acknowledgeEnded()
            assertEquals(CallUiState.Idle, h.controller.state.value)
        }
        assertEquals(3, h.engine.disposeCount)
        assertFalse(h.controller.state.value is CallUiState.OutgoingRinging)
    }

    @Test
    fun `new incoming call overrides an unacknowledged Ended state`() {
        val h = Harness()
        // First call rings and is missed (Ended), user has NOT acknowledged yet.
        h.signaling.emit(CallSignalingEvent.Ringing("call-1", "alice", "audio"))
        h.signaling.emit(CallSignalingEvent.Ended("call-1", "missed"))
        assertIs<CallUiState.Ended>(h.controller.state.value)

        // A new incoming call must not be silently dropped.
        h.signaling.emit(CallSignalingEvent.Ringing("call-2", "alice", "audio"))
        val ringing = assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        assertEquals("call-2", ringing.call.callId)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `new incoming call overrides an unacknowledged Failed-ended state`() = runTest {
        val h = Harness(gracePeriodMs = 30_000L, scopeOverride = backgroundScope + UnconfinedTestDispatcher(testScheduler))
        h.controller.elapsedRealtimeMs = { testScheduler.currentTime }
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.FAILED))
        assertIs<CallUiState.Reconnecting>(h.controller.state.value)
        advanceTimeBy(30_000); runCurrent()
        // ICE failure first recovers; only the exhausted outage is terminal.
        val ended = assertIs<CallUiState.Ended>(h.controller.state.value)
        assertEquals(com.messenger.app.domain.model.CallEndReason.FAILED, ended.reason)

        h.signaling.emit(CallSignalingEvent.Ringing("call-2", "alice", "audio"))
        val ringing = assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        assertEquals("call-2", ringing.call.callId)
    }

    @Test
    fun `video call propagates local and remote video track state`() = runBlocking {
        val h = Harness()
        h.engine.localVideoRunning = true
        h.controller.startCall("bob", "Bob", CallMediaKind.VIDEO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))

        val connected = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertTrue(connected.localVideoEnabled, "local camera starts on for a video call")
        assertFalse(connected.remoteVideoEnabled, "remote video unknown until the track arrives")

        h.engine.emit(WebRtcEvent.RemoteVideoEnabled(true))
        assertTrue((h.controller.state.value as CallUiState.Connected).remoteVideoEnabled)
    }

    @Test
    fun `remote video track arriving during Connecting is not lost`() = runBlocking {
        val h = Harness()
        h.engine.localVideoRunning = true
        h.controller.startCall("bob", "Bob", CallMediaKind.VIDEO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        assertIs<CallUiState.Connecting>(h.controller.state.value)
        // Video onTrack fires at setRemoteDescription time, BEFORE ICE connects.
        h.engine.emit(WebRtcEvent.RemoteVideoEnabled(true))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))

        val connected = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertTrue(connected.remoteVideoEnabled, "buffered remote-video event must survive the Connecting->Connected transition")
    }

    @Test
    fun `video toggle delegates to the engine and updates local video state`() = runBlocking {
        val h = Harness()
        h.engine.localVideoRunning = true
        h.controller.startCall("bob", "Bob", CallMediaKind.VIDEO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))

        h.controller.toggleVideo()
        assertEquals(false, h.engine.videoEnabled)
        assertFalse((h.controller.state.value as CallUiState.Connected).localVideoEnabled)

        h.controller.toggleVideo()
        assertEquals(true, h.engine.videoEnabled)
        assertTrue((h.controller.state.value as CallUiState.Connected).localVideoEnabled)
    }

    @Test
    fun `camera switch is delegated to the engine`() = runBlocking {
        val h = Harness()
        h.engine.localVideoRunning = true
        h.controller.startCall("bob", "Bob", CallMediaKind.VIDEO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))

        h.controller.switchCamera()
        h.controller.switchCamera()
        assertEquals(2, h.engine.switchCameraCount)
    }

    @Test
    fun `renderer attach and detach delegate to the engine`() {
        val h = Harness()
        val renderer = Any()
        h.controller.attachLocalRenderer(renderer)
        h.controller.attachRemoteRenderer(renderer)
        assertEquals(listOf<Any?>(renderer), h.engine.attachedLocalRenderers)
        assertEquals(listOf<Any?>(renderer), h.engine.attachedRemoteRenderers)

        h.controller.detachLocalRenderer(renderer)
        h.controller.detachRemoteRenderer(renderer)
        assertEquals(1, h.engine.detachLocalCount)
        assertEquals(1, h.engine.detachRemoteCount)
    }

    @Test
    fun `video call with unavailable local camera degrades to audio-only state`() = runBlocking {
        val h = Harness()
        h.engine.localVideoRunning = false // CAMERA denied / no camera
        h.controller.startCall("bob", "Bob", CallMediaKind.VIDEO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))

        val connected = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertFalse(connected.localVideoEnabled, "must not claim live video when the camera never started")
    }

    @Test
    fun `ws reconnect after remote hangup during outage ends the call locally`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertIs<CallUiState.Connected>(h.controller.state.value)

        // Server no longer has the call active (remote hung up while the WS was down).
        h.callApi.activeCallResult = null
        h.callApi.getCallResult = CallDto(
            id = callId, callerUserId = "me", calleeUserId = "other", mediaKind = "audio",
            state = "ended", endReason = "hangup", createdAt = "2026-01-01T00:00:00Z",
        )
        h.signaling.emitConnectionState(SocketConnectionState.DISCONNECTED)
        h.signaling.emitConnectionState(SocketConnectionState.CONNECTED)

        val ended = assertIs<CallUiState.Ended>(h.controller.state.value)
        assertEquals(com.messenger.app.domain.model.CallEndReason.HANGUP, ended.reason)
        assertEquals(1, h.callApi.getActiveCallCount, "must reconcile via GET /calls/active")
        assertEquals(3, h.callApi.getCallCount, "post-create recovery, setup, then the real end reason")
    }

    @Test
    fun `ws reconnect while the call is still active keeps Connected`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertIs<CallUiState.Connected>(h.controller.state.value)

        h.callApi.activeCallResult = CallDto(
            id = callId, callerUserId = "me", calleeUserId = "other", mediaKind = "audio",
            state = "connected", createdAt = "2026-01-01T00:00:00Z",
        )
        h.signaling.emitConnectionState(SocketConnectionState.DISCONNECTED)
        h.signaling.emitConnectionState(SocketConnectionState.CONNECTED)

        assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(1, h.callApi.getActiveCallCount, "reconciliation still fetches the active call")
        assertEquals(2, h.callApi.getCallCount, "post-create recovery and setup only; the active reconnect needs no extra getCall")
    }

    @Test
    fun `initial socket connect does not trigger call reconciliation`() = runBlocking {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        val callId = (h.controller.state.value as CallUiState.OutgoingRinging).call.callId
        h.signaling.emit(CallSignalingEvent.Accepted(callId))
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))

        // First connection-state emission is the initial connect, not a
        // reconnect - it must not fire a spurious reconciliation.
        h.signaling.emitConnectionState(SocketConnectionState.CONNECTED)

        assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(0, h.callApi.getActiveCallCount, "initial connect must not reconcile")
        Unit
    }

    @Test
    fun `ws reconnect while idle does not fetch call state`() = runBlocking {
        val h = Harness()
        h.signaling.emitConnectionState(SocketConnectionState.DISCONNECTED)
        h.signaling.emitConnectionState(SocketConnectionState.CONNECTED)
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertEquals(0, h.callApi.getActiveCallCount, "idle must not fetch call state")
        Unit
    }

    // ------------------------------------------------------------------
    // N.1A audio -> video upgrade
    // ------------------------------------------------------------------

    @Test
    fun `request video upgrade starts capture and sends request`() {
        val h = Harness()
        val connected = h.connectAudio()
        h.controller.requestVideoUpgrade()
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoUpgradeState.Requesting, state.videoUpgrade)
        assertTrue(state.localVideoEnabled)
        assertEquals(1, h.engine.startLocalVideoCount)
        assertTrue(h.signaling.sentVideoUpgradeRequests.contains("call-0"))
        // same call id, duration start preserved
        assertEquals(connected.call.callId, state.call.callId)
        assertEquals(connected.connectedAtElapsedRealtimeMs, state.connectedAtElapsedRealtimeMs)
    }

    @Test
    fun `duplicate upgrade request is ignored`() {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.controller.requestVideoUpgrade()
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoUpgradeState.Requesting, state.videoUpgrade)
        assertEquals(1, h.engine.startLocalVideoCount)
        assertEquals(1, h.signaling.sentVideoUpgradeRequests.size)
    }

    @Test
    fun `capture failure keeps audio and shows notice`() {
        val h = Harness()
        h.connectAudio()
        h.engine.startLocalVideoResult = false
        h.controller.requestVideoUpgrade()
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoUpgradeState.Idle, state.videoUpgrade)
        assertEquals(CallNotice.CAMERA_FAILED, state.notice)
        assertEquals(LocalCameraState.FAILED, state.localCameraState)
        assertEquals(VideoMode.AUDIO, state.videoMode)
        assertTrue(h.signaling.sentVideoUpgradeRequests.isEmpty())
    }

    @Test
    fun `recipient accepts request and requester re-offers`() {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoUpgradeState.Upgrading, state.videoUpgrade)
        assertTrue(h.signaling.sentOffers.size >= 2, "renegotiation offer must be sent")
    }

    @Test
    fun `renegotiation answer flips to video without resetting duration`() = runBlocking {
        val h = Harness()
        val connected = h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 renegotiation-answer"))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoMode.VIDEO, state.videoMode)
        assertEquals(VideoUpgradeState.Idle, state.videoUpgrade)
        assertEquals(connected.connectedAtElapsedRealtimeMs, state.connectedAtElapsedRealtimeMs, "duration must not reset")
        assertEquals(connected.call.callId, state.call.callId, "same call id")
    }

    @Test
    fun `reject stops local video and keeps audio`() {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeReject("call-0"))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoUpgradeState.Idle, state.videoUpgrade)
        assertEquals(CallNotice.VIDEO_REQUEST_DECLINED, state.notice)
        assertEquals(VideoMode.AUDIO, state.videoMode)
        assertFalse(state.localVideoEnabled)
        assertEquals(1, h.engine.stopLocalVideoCount)
    }

    @Test
    fun `requester cancel stops capture and sends cancel`() {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.controller.cancelVideoUpgrade()
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoUpgradeState.Idle, state.videoUpgrade)
        assertTrue(h.signaling.sentVideoUpgradeCancels.contains("call-0"))
        assertEquals(1, h.engine.stopLocalVideoCount)
    }

    @Test
    fun `incoming request is auto accepted without a second dialog`() {
        val h = Harness()
        h.connectAudio()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeRequest("call-0"))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoUpgradeState.Upgrading, state.videoUpgrade)
        assertTrue(h.signaling.sentVideoUpgradeAccepts.contains("call-0"))
    }

    @Test
    fun `accept creates disabled local video track so camera can arm later`() {
        val h = Harness()
        h.connectAudio()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeRequest("call-0"))
        h.controller.acceptVideoUpgrade()
        // The track must exist (negotiated in the peer's re-offer) but be
        // disabled — enabling the camera later must only flip setEnabled,
        // never require a second renegotiation (black-screen regression).
        assertEquals(1, h.engine.startLocalVideoCount)
        assertEquals(false, h.engine.videoEnabled)
        assertEquals(0, h.engine.cameraCaptureStartCount, "Receiving peer video must not open the local camera")
    }

    @Test
    fun `incoming request does not expose a reject action`() {
        val h = Harness()
        h.connectAudio()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeRequest("call-0"))
        h.controller.rejectVideoUpgrade()
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoUpgradeState.Upgrading, state.videoUpgrade)
        assertFalse(h.signaling.sentVideoUpgradeRejects.contains("call-0"))
    }

    @Test
    fun `renegotiation offer received while connected answers and flips to video`() = runBlocking {
        val h = Harness()
        h.connectAudio()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeRequest("call-0"))
        h.controller.acceptVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.Offer("call-0", "v=0 renegotiation-offer"))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoMode.VIDEO, state.videoMode)
        assertEquals(VideoUpgradeState.Idle, state.videoUpgrade)
        assertTrue(h.signaling.sentAnswers.size >= 1)
        assertTrue(state.remoteVideoEnabled, "recipient must see the requester's video after an audio->video upgrade")
    }

    @Test
    fun `renegotiation failure keeps audio and shows notice`() = runBlocking {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        h.engine.setRemoteDescriptionError = IllegalStateException("sdp failure")
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 renegotiation-answer"))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoUpgradeState.Idle, state.videoUpgrade)
        assertEquals(CallNotice.VIDEO_FAILED, state.notice)
        assertEquals(VideoMode.AUDIO, state.videoMode)
    }

    @Test
    fun `upgrade request times out and audio continues`() = runBlocking {
        val h = Harness(videoUpgradeTimeoutMs = 50L)
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        delay(300L)
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoUpgradeState.Idle, state.videoUpgrade)
        assertEquals(CallNotice.VIDEO_REQUEST_TIMEOUT, state.notice)
        assertEquals(VideoMode.AUDIO, state.videoMode)
        assertEquals(1, h.engine.stopLocalVideoCount)
    }

    @Test
    fun `switch to audio stops capture and renegotiates`() = runBlocking {
        val h = Harness()
        val connected = h.connectAudio()
        // Force into a video call first.
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 renegotiation-answer"))
        assertIs<CallUiState.Connected>(h.controller.state.value).let { assertEquals(VideoMode.VIDEO, it.videoMode) }

        h.controller.switchToAudio()
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 downgrade-answer"))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoMode.AUDIO, state.videoMode, "downgrade answer must not flip back to video")
        assertEquals(VideoUpgradeState.Idle, state.videoUpgrade)
        assertFalse(state.localVideoEnabled)
        assertEquals(1, h.engine.stopLocalVideoCount)
        assertEquals(connected.call.callId, state.call.callId, "same call id preserved")
    }

    @Test
    fun `remote full audio downgrade stops own camera and automatic speaker`() {
        for (videoMedia in listOf("", "m=video 0 UDP/TLS/RTP/SAVPF 96\r\na=inactive\r\n")) {
            val h = Harness()
            prepareVideo(h)
            h.engine.emit(WebRtcEvent.LocalFirstFrame)
            val before = assertIs<CallUiState.Connected>(h.controller.state.value)
            assertTrue(before.localVideoEnabled)
            assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
            val answers = h.engine.createAnswerCount

            h.signaling.emit(CallSignalingEvent.Offer("call-0", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n$videoMedia"))

            val after = assertIs<CallUiState.Connected>(h.controller.state.value)
            assertEquals(VideoMode.AUDIO, after.videoMode)
            assertFalse(after.localVideoEnabled, "An audio-only negotiation must stop our camera too")
            assertEquals(LocalCameraState.OFF, after.localCameraState)
            assertFalse(after.remoteVideoEnabled)
            assertEquals(RemoteVideoState.OFF, after.remoteVideoState)
            assertFalse(h.engine.localVideoRunning)
            assertEquals(1, h.engine.stopLocalVideoCount)
            assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
            assertEquals(before.call.callId, after.call.callId)
            assertEquals(before.connectedAtElapsedRealtimeMs, after.connectedAtElapsedRealtimeMs)
            assertEquals(answers + 1, h.engine.createAnswerCount)
            assertEquals(0, h.engine.disposeCount, "Audio remains connected")
            h.engine.emit(WebRtcEvent.LocalFirstFrame)
            assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind, "Late camera frame cannot restore speaker")
        }
    }

    @Test
    fun `remote audio downgrade preserves manual speaker receiver and headset`() {
        for (route in listOf(AudioRouteKind.SPEAKER, AudioRouteKind.EARPIECE, AudioRouteKind.BLUETOOTH)) {
            val h = Harness()
            prepareVideo(h)
            val selected = AudioRoute(route, "Selected output")
            if (h.engine.routes.none { it.kind == route }) h.engine.routes = h.engine.routes + selected
            h.controller.setAudioRoute(selected)
            h.engine.emit(WebRtcEvent.LocalFirstFrame)
            h.signaling.emit(CallSignalingEvent.Offer("call-0", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
            assertFalse(assertIs<CallUiState.Connected>(h.controller.state.value).localVideoEnabled)
            assertFalse(h.engine.localVideoRunning)
            assertEquals(route, h.engine.currentRoute.kind, "Downgrade must not replace explicit or external output")
        }
    }

    @Test
    fun `remote full audio downgrade while reconnecting clears saved camera state`() {
        val h = Harness()
        prepareVideo(h)
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        h.signaling.emit(CallSignalingEvent.Offer("call-0", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
        val reconnecting = assertIs<CallUiState.Reconnecting>(h.controller.state.value)
        val saved = assertIs<CallUiState.Connected>(reconnecting.since)
        assertEquals(VideoMode.AUDIO, saved.videoMode)
        assertFalse(saved.localVideoEnabled)
        assertEquals(LocalCameraState.OFF, saved.localCameraState)
        assertFalse(h.engine.localVideoRunning)
        assertEquals(AudioRouteKind.EARPIECE, h.engine.currentRoute.kind)
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        assertFalse(assertIs<CallUiState.Connected>(h.controller.state.value).localVideoEnabled)
    }

    @Test
    fun `remote camera off and video capable renegotiation keep our camera and automatic speaker`() {
        val h = Harness()
        prepareVideo(h)
        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        h.signaling.emit(CallSignalingEvent.VideoState("call-0", false))
        // An active video m-line remains video-capable even when the peer
        // is not sending. This is not the full audio-only downgrade.
        h.signaling.emit(CallSignalingEvent.Offer("call-0", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n"))
        val connected = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoMode.VIDEO, connected.videoMode)
        assertTrue(connected.localVideoEnabled)
        assertTrue(h.engine.localVideoRunning)
        assertEquals(0, h.engine.stopLocalVideoCount)
        assertEquals(AudioRouteKind.SPEAKER, h.engine.currentRoute.kind)
    }

    @Test
    fun `camera off sends video state but does not stop capture`() {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 renegotiation-answer"))
        h.controller.setCameraEnabled(false)
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertFalse(state.localVideoEnabled)
        assertEquals(VideoMode.VIDEO, state.videoMode, "camera off keeps the call video-capable")
        assertEquals(0, h.engine.stopLocalVideoCount, "camera off must not remove the video track")
        assertTrue(h.signaling.sentVideoStates.contains("call-0" to false))
    }

    @Test
    fun `video state event updates remote video enabled`() {
        val h = Harness()
        h.connectAudio()
        h.signaling.emit(CallSignalingEvent.VideoState("call-0", false))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertFalse(state.remoteVideoEnabled)
        h.signaling.emit(CallSignalingEvent.VideoState("call-0", true))
        assertTrue(assertIs<CallUiState.Connected>(h.controller.state.value).remoteVideoEnabled)
    }

    @Test
    fun `incoming request cancel is ignored after auto accept`() {
        val h = Harness()
        h.connectAudio()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeRequest("call-0"))
        h.signaling.emit(CallSignalingEvent.VideoUpgradeCancel("call-0"))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoUpgradeState.Upgrading, state.videoUpgrade)
    }

    @Test
    fun `reconnect preserves video mode`() = runBlocking {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 renegotiation-answer"))
        assertIs<CallUiState.Connected>(h.controller.state.value).let { assertEquals(VideoMode.VIDEO, it.videoMode) }

        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        assertIs<CallUiState.Reconnecting>(h.controller.state.value)
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(VideoMode.VIDEO, state.videoMode, "video mode must survive reconnect")
    }

    @Test
    fun `reconnect restores remote video as waiting not on`() {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 renegotiation-answer"))
        h.signaling.emit(CallSignalingEvent.VideoState("call-0", true))
        h.engine.emit(WebRtcEvent.RemoteFirstFrame)
        assertEquals(RemoteVideoState.ON, assertIs<CallUiState.Connected>(h.controller.state.value).remoteVideoState)

        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.DISCONNECTED))
        assertIs<CallUiState.Reconnecting>(h.controller.state.value)
        h.engine.emit(WebRtcEvent.ConnectionStateChanged(PeerConnectionState.CONNECTED))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(RemoteVideoState.WAITING, state.remoteVideoState, "reconnect must not expose a stale frame as live")
    }

    // ------------------------------------------------------------------
    // Code-review 2/3/4/6/9/13 regression tests
    // ------------------------------------------------------------------

    @Test
    fun `remote video tri-state waits for the first frame`() {
        val h = Harness()
        h.connectAudio()

        // Signaling says the peer camera is on: WAITING, not ON.
        h.signaling.emit(CallSignalingEvent.VideoState("call-0", true))
        var state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(RemoteVideoState.WAITING, state.remoteVideoState, "signaling alone must never show video as live")

        // Actual remote frame evidence: ON.
        h.engine.emit(WebRtcEvent.RemoteFirstFrame)
        state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(RemoteVideoState.ON, state.remoteVideoState)

        // Peer camera off: OFF.
        h.signaling.emit(CallSignalingEvent.VideoState("call-0", false))
        state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(RemoteVideoState.OFF, state.remoteVideoState)
    }

    @Test
    fun `local camera reaches ON only after the first frame`() {
        val h = Harness()
        h.connectAudio()
        h.controller.requestVideoUpgrade()
        h.signaling.emit(CallSignalingEvent.VideoUpgradeAccept("call-0"))
        h.signaling.emit(CallSignalingEvent.Answer("call-0", "v=0 renegotiation-answer"))
        var state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(LocalCameraState.STARTING, state.localCameraState)

        h.engine.emit(WebRtcEvent.LocalFirstFrame)
        state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(LocalCameraState.ON, state.localCameraState)
    }

    @Test
    fun `camera denied during upgrade shows a localized notice without hangup`() {
        val h = Harness()
        h.connectAudio()
        h.controller.notifyCameraDenied()
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(CallNotice.CAMERA_DENIED, state.notice)
    }

    @Test
    fun `detach forwards the renderer identity to the engine`() {
        val h = Harness()
        val old = Any()
        val new = Any()
        h.controller.attachLocalRenderer(old)
        h.controller.attachLocalRenderer(new)
        // A stale disposal of `old` must be forwarded with its identity so the
        // engine can decide it must not clear the newer `new` renderer.
        h.controller.detachLocalRenderer(old)
        h.controller.detachLocalRenderer(new)
        assertEquals(listOf<Any?>(old, new), h.engine.attachedLocalRenderers)
        assertEquals(listOf<Any?>(old, new), h.engine.detachedLocalRenderers)
    }

    @Test
    fun `audio route change updates the connected state`() {
        val h = Harness()
        h.connectAudio()
        assertEquals(AudioRouteKind.EARPIECE, assertIs<CallUiState.Connected>(h.controller.state.value).audioRoute)

        h.engine.emit(WebRtcEvent.AudioRouteChanged(AudioRoute(AudioRouteKind.BLUETOOTH, "Pixel Buds")))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(AudioRouteKind.BLUETOOTH, state.audioRoute)
    }

    @Test
    fun `setAudioRoute updates the connected audio route`() {
        val h = Harness()
        h.connectAudio()
        h.controller.setAudioRoute(AudioRoute(AudioRouteKind.SPEAKER, "Speaker"))
        val state = assertIs<CallUiState.Connected>(h.controller.state.value)
        assertEquals(AudioRouteKind.SPEAKER, state.audioRoute)
        assertTrue(state.isSpeakerOn)
    }

    // --- P1-2: logout must terminate any call locally ---------------------

    private fun ringingCallDto(id: String = "call-1") = CallDto(
        id = id,
        callerUserId = "bob",
        calleeUserId = "me",
        mediaKind = "audio",
        state = "ringing",
        endReason = null,
        createdAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun `logout terminates an incoming ringing call to idle`(): Unit = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = ringingCallDto()
        h.controller.showIncomingCallFromPush("call-1", "audio")?.join()
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        h.controller.terminateForLogout()
        assertIs<CallUiState.Idle>(h.controller.state.value)
    }

    @Test
    fun `logout terminates an outgoing ringing call to idle`() {
        val h = Harness()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        assertIs<CallUiState.OutgoingRinging>(h.controller.state.value)
        h.controller.terminateForLogout()
        assertIs<CallUiState.Idle>(h.controller.state.value)
    }

    @Test
    fun `logout terminates an active call to idle and disposes the engine`() {
        val h = Harness()
        h.connectAudio()
        assertIs<CallUiState.Connected>(h.controller.state.value)
        h.controller.terminateForLogout()
        assertIs<CallUiState.Idle>(h.controller.state.value)
        assertEquals(1, h.engine.disposeCount)
    }

    @Test
    fun `logout with no active call stays idle`() {
        val h = Harness()
        assertIs<CallUiState.Idle>(h.controller.state.value)
        h.controller.terminateForLogout()
        assertIs<CallUiState.Idle>(h.controller.state.value)
    }

    @Test
    fun `a new call can start after logout teardown`() {
        val h = Harness()
        h.connectAudio()
        h.controller.terminateForLogout()
        h.controller.startCall("bob", "Bob", CallMediaKind.AUDIO)
        assertIs<CallUiState.OutgoingRinging>(h.controller.state.value)
    }

    @Test
    fun `delayed old-account call event after logout is ignored`(): Unit = runBlocking {
        val h = Harness()
        h.callApi.getCallResult = ringingCallDto("alice-call")
        h.controller.showIncomingCallFromPush("alice-call", "audio")?.join()
        assertIs<CallUiState.IncomingRinging>(h.controller.state.value)
        h.controller.terminateForLogout()
        assertIs<CallUiState.Idle>(h.controller.state.value)
        // After the switch the reconcile fails auth (old account token gone):
        // a delayed Alice push must NOT resurrect a call under the new account.
        h.callApi.getCallShouldFail = true
        h.controller.showIncomingCallFromPush("alice-call", "audio")?.join()
        assertIs<CallUiState.Idle>(h.controller.state.value)
    }
}
