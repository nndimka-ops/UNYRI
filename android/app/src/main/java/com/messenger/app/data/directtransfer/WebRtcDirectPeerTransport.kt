package com.messenger.app.data.directtransfer

import android.content.Context
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Own factory/PC/channel, no tracks, audio session, camera, renderers or call state.
 * Only STUN URLs are accepted. Both signalled candidates and the actually selected
 * pair must be non-relay before any application payload can leave this transport.
 * One consumer owns [events]; file I/O/AEAD and durable acceptance belong to the coordinator.
 */
class WebRtcDirectPeerTransport(context: Context, stunUrls: List<String>) : DirectPeerTransport {
    private val appContext = context.applicationContext
    private val servers = stunUrls.also { require(it.size <= 8 && it.all(DirectPeerPolicy::isStunUrl)) }.toList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val negotiation = Mutex()
    private val writer = DirectPeerWriter()
    @Volatile private var executionGuard: (() -> Unit)? = null
    internal fun bindExecutionGuard(guard: () -> Unit) { check(executionGuard == null); executionGuard = guard }
    private val output = Channel<DirectPeerEvent>(32)
    // One legitimate application window is sixteen 16KiB chunks; tolerate its native callback burst.
    private val incoming = Channel<DirectPeerEvent.Frame>(20)
    override val events: Flow<DirectPeerEvent> = output.receiveAsFlow()
    private val closed = AtomicBoolean(false)
    private val selectedDirect = AtomicBoolean(false)
    private val readyPublished = AtomicBoolean(false)
    private val remoteAuthenticated = AtomicBoolean(false)
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var peer: PeerConnection? = null
    @Volatile private var channel: DataChannel? = null
    private var makingOffer = false
    @Volatile internal var provenDirectPair: Pair<String, String>? = null
        private set
    private val gatheringComplete = CompletableDeferred<Unit>()

    init {
        scope.launch {
            for (frame in incoming) {
                try {
                    withTimeout(5000) {
                        while (!readyPublished.get() || !selectedDirect.get() || !remoteAuthenticated.get()) {
                            checkOpen(); delay(10)
                        }
                    }
                    checkOpen()
                    check(selectedDirect.get() && remoteAuthenticated.get()) { "Direct path lost" }
                    output.send(frame)
                } catch (_: Exception) { terminate(DirectPeerFailure.RECEIVER_TOO_SLOW); break }
            }
        }
    }

    override suspend fun createOffer(): String = withContext(Dispatchers.IO) { negotiation.withLock {
        val connection = lifecycle.withLock {
            checkOpen()
            check(peer == null) { "Transfer connection already initialized" }
            makingOffer = true
            initialize().also { value ->
                bindChannel(value.createDataChannel(DirectPeerPolicy.CHANNEL, DataChannel.Init().apply {
                    ordered = true; maxRetransmits = -1; maxRetransmitTimeMs = -1
                    protocol = DirectPeerPolicy.CHANNEL
                }) ?: error("Unable to create transfer channel"))
            }
        }
        createDescription(connection, true).also { require(DirectPeerPolicy.isDataOnlySdp(it)) }
    } }

    override suspend fun acceptOffer(sdp: String): String = withContext(Dispatchers.IO) { negotiation.withLock {
        require(DirectPeerPolicy.isDataOnlySdp(sdp)) { "Invalid direct transfer offer" }
        val connection = lifecycle.withLock {
            checkOpen(); check(peer == null) { "Transfer connection already initialized" }; initialize()
        }
        setRemote(connection, SessionDescription(SessionDescription.Type.OFFER, sdp))
        remoteAuthenticated.set(true)
        createDescription(connection, false).also { require(DirectPeerPolicy.isDataOnlySdp(it)) }
    } }

    override suspend fun applyAnswer(sdp: String) = withContext(Dispatchers.IO) { negotiation.withLock {
        require(DirectPeerPolicy.isDataOnlySdp(sdp)) { "Invalid direct transfer answer" }
        checkOpen(); check(makingOffer && !remoteAuthenticated.get()) { "Unexpected transfer answer" }
        val connection = peer ?: error("No transfer connection")
        setRemote(connection, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        remoteAuthenticated.set(true)
        maybeReady()
    } }

    override suspend fun addRemoteIce(candidate: String, mid: String?, line: Int) = withContext(Dispatchers.IO) { lifecycle.withLock {
        require(DirectPeerPolicy.isDirectCandidate(candidate) && line == 0 && (mid == null || mid.length <= 64)) { "Invalid direct ICE candidate" }
        checkOpen(); check(remoteAuthenticated.get()) { "Remote description required before ICE" }
        check(peer?.addIceCandidate(IceCandidate(mid.orEmpty(), line, candidate)) == true) { "Unable to add transfer candidate" }
    } }

    override suspend fun send(bytes: ByteArray, binary: Boolean) = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size <= if (binary) DirectPeerPolicy.MAX_BINARY_FRAME else DirectPeerPolicy.MAX_CONTROL_FRAME)
        writer.send(bytes.size, checkCurrent = {
            checkOpen()
            check(selectedDirect.get() && remoteAuthenticated.get() && readyPublished.get()) { "Direct path is not ready" }
            check(channel?.state() == DataChannel.State.OPEN) { "Transfer channel closed" }
            executionGuard?.invoke()
        }, buffered = { channel?.bufferedAmount() ?: error("No transfer channel") }, enqueue = {
            executionGuard?.invoke()
            check(channel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), binary)) == true) { "Transfer queue rejected frame" }
        })
    }

    override suspend fun close() = withContext(Dispatchers.IO) { terminate(DirectPeerFailure.CLOSED) }

    private fun initialize(): PeerConnection {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
        val createdFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        factory = createdFactory
        val config = PeerConnection.RTCConfiguration(servers.map { PeerConnection.IceServer.builder(it).createIceServer() }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            iceCandidatePoolSize = 0
        }
        return (createdFactory.createPeerConnection(config, observer) ?: error("Unable to initialize direct transfer")).also { peer = it }
    }

    private fun bindChannel(value: DataChannel) {
        if (closed.get() || channel != null || value.label() != DirectPeerPolicy.CHANNEL) {
            value.close(); value.dispose(); fail(DirectPeerFailure.INVALID_REMOTE); return
        }
        channel = value
        value.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) = Unit
            override fun onStateChange() {
                if (closed.get()) return
                when (value.state()) {
                    DataChannel.State.OPEN -> verifySelectedPair()
                    DataChannel.State.CLOSING, DataChannel.State.CLOSED -> fail(DirectPeerFailure.CONNECTION_LOST)
                    else -> Unit
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (closed.get()) return
                val size = buffer.data.remaining()
                if (size !in 1..(if (buffer.binary) DirectPeerPolicy.MAX_BINARY_FRAME else DirectPeerPolicy.MAX_CONTROL_FRAME)) {
                    fail(DirectPeerFailure.INVALID_REMOTE); return
                }
                val bytes = ByteArray(size)
                buffer.data.duplicate().get(bytes)
                if (!incoming.trySend(DirectPeerEvent.Frame(bytes, buffer.binary)).isSuccess) fail(DirectPeerFailure.RECEIVER_TOO_SLOW)
            }
        })
        if (value.state() == DataChannel.State.OPEN) verifySelectedPair()
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            if (closed.get()) return
            if (!DirectPeerPolicy.isDirectCandidate(candidate.sdp)) { fail(DirectPeerFailure.DIRECT_PATH_UNAVAILABLE); return }
            // Full SDP is sent only after gathering completes, not one mailbox message per candidate.
        }
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            selectedDirect.set(false)
            if (closed.get()) return
            if (!DirectPeerPolicy.isDirectCandidate(event.local.sdp) || !DirectPeerPolicy.isDirectCandidate(event.remote.sdp)) {
                fail(DirectPeerFailure.DIRECT_PATH_UNAVAILABLE); return
            }
            selectedDirect.set(true)
            provenDirectPair = DirectPeerPolicy.candidateType(event.local.sdp)!! to DirectPeerPolicy.candidateType(event.remote.sdp)!!
            maybeReady()
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state in setOf(PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED)) {
                selectedDirect.set(false); fail(DirectPeerFailure.CONNECTION_LOST)
            }
        }
        override fun onDataChannel(dataChannel: DataChannel) {
            // Never dispose a peer connection from its native callback thread.
            scope.launch { lifecycle.withLock { bindChannel(dataChannel) } }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gatheringComplete.complete(Unit)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) { fail(DirectPeerFailure.INVALID_REMOTE) }
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun verifySelectedPair() {
        scope.launch {
            repeat(30) {
                if (closed.get()) return@launch
                if (selectedDirect.get()) { maybeReady(); if (readyPublished.get()) return@launch }
                val connection = peer ?: return@launch
                val direct = suspendCancellableCoroutine<Boolean> { continuation ->
                    connection.getStats { report ->
                        if (continuation.isActive) continuation.resume(selectedPairDirect(report))
                    }
                }
                if (direct) { selectedDirect.set(true); maybeReady(); if (readyPublished.get()) return@launch }
                delay(100)
            }
            fail(DirectPeerFailure.DIRECT_PATH_UNAVAILABLE)
        }
    }

    private fun selectedPairDirect(report: RTCStatsReport): Boolean {
        val stats = report.statsMap
        val selectedIds = stats.values.filter { it.type == "transport" }.mapNotNull { it.members["selectedCandidatePairId"] as? String }
        val selected = selectedIds.mapNotNull(stats::get).ifEmpty {
            stats.values.filter { it.type == "candidate-pair" && it.members["selected"] == true }
        }
        return selected.size == 1 && selected.all { pair ->
            val local = (pair.members["localCandidateId"] as? String)?.let(stats::get)
            val remote = (pair.members["remoteCandidateId"] as? String)?.let(stats::get)
            val allowed = pair.members["state"] == "succeeded" &&
                DirectPeerPolicy.isDirectType(local?.members?.get("candidateType") as? String) &&
                DirectPeerPolicy.isDirectType(remote?.members?.get("candidateType") as? String)
            if (allowed) provenDirectPair = (local!!.members["candidateType"] as String) to (remote!!.members["candidateType"] as String)
            allowed
        }
    }

    private fun maybeReady() {
        if (!closed.get() && selectedDirect.get() && remoteAuthenticated.get() && channel?.state() == DataChannel.State.OPEN && readyPublished.compareAndSet(false, true)) {
            if (!output.trySend(DirectPeerEvent.Ready).isSuccess) fail(DirectPeerFailure.RECEIVER_TOO_SLOW)
        }
    }

    private suspend fun createDescription(connection: PeerConnection, offer: Boolean): String {
        val description = withTimeout(10_000) { suspendCancellableCoroutine<SessionDescription> { continuation ->
            val callback = object : QuietSdpObserver {
                override fun onCreateSuccess(value: SessionDescription?) {
                    if (continuation.isActive) {
                        if (value != null) continuation.resume(value)
                        else continuation.resumeWithException(IllegalStateException("No transfer description"))
                    }
                }
                override fun onCreateFailure(error: String?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Unable to create transfer description")) }
            }
            if (offer) connection.createOffer(callback, MediaConstraints()) else connection.createAnswer(callback, MediaConstraints())
        } }
        require(DirectPeerPolicy.isDataOnlySdp(description.description))
        withTimeout(10_000) { suspendCancellableCoroutine<Unit> { continuation ->
            connection.setLocalDescription(object : QuietSdpObserver {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Unable to apply local transfer description")) }
            }, description)
        } }
        // Do not hold the lifecycle mutex while waiting: cancellation can close native resources now.
        withTimeout(20_000) {
            if (connection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) gatheringComplete.await()
        }
        checkOpen()
        return (connection.localDescription?.description ?: error("No gathered transfer description")).also {
            require(DirectPeerPolicy.isDataOnlySdp(it)) { "Invalid gathered direct description" }
        }
    }

    private suspend fun setRemote(connection: PeerConnection, description: SessionDescription) {
        withTimeout(10_000) { suspendCancellableCoroutine<Unit> { continuation ->
            connection.setRemoteDescription(object : QuietSdpObserver {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Unable to apply remote transfer description")) }
            }, description)
        } }
        checkOpen()
    }

    private fun checkOpen() { check(!closed.get()) { "Direct transfer closed" } }
    private fun fail(reason: DirectPeerFailure) { scope.launch { terminate(reason) } }
    private suspend fun terminate(reason: DirectPeerFailure) = lifecycle.withLock {
        if (!closed.compareAndSet(false, true)) return@withLock
        selectedDirect.set(false)
        gatheringComplete.cancel()
        channel?.let { value -> value.unregisterObserver(); value.close(); value.dispose() }
        channel = null
        peer?.let { value -> value.close(); value.dispose() }
        peer = null
        factory?.dispose(); factory = null
        incoming.close()
        output.trySend(DirectPeerEvent.Closed(reason))
        output.close()
        scope.cancel()
    }

    private interface QuietSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
