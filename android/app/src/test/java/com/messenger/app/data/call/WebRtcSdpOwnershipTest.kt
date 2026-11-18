package com.messenger.app.data.call

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import livekit.org.webrtc.*

private class OwnershipPeer : PeerConnection(NativePeerConnectionFactory { 0L }) {
    var touches = 0
    var signalingReads = 0
    var rollbacks = 0
    var disposed = false
    var holdRemote = false
    lateinit var remoteCompletion: SdpObserver
    override fun signalingState(): SignalingState {
        signalingReads++
        return if (disposed) SignalingState.CLOSED else SignalingState.HAVE_LOCAL_OFFER
    }
    override fun setLocalDescription(observer: SdpObserver, description: SessionDescription) {
        touches++
        if (description.type == SessionDescription.Type.ROLLBACK) rollbacks++
        observer.onSetSuccess()
    }
    override fun setRemoteDescription(observer: SdpObserver, description: SessionDescription) {
        touches++
        if (holdRemote) remoteCompletion = observer else observer.onSetSuccess()
    }
    override fun createOffer(observer: SdpObserver, constraints: MediaConstraints) {
        touches++
        observer.onCreateSuccess(SessionDescription(SessionDescription.Type.OFFER, "v=0\r\n"))
    }
    override fun createAnswer(observer: SdpObserver, constraints: MediaConstraints) {
        touches++
        observer.onCreateSuccess(SessionDescription(SessionDescription.Type.ANSWER, "v=0\r\n"))
    }
    override fun getTransceivers(): List<RtpTransceiver> { touches++; return emptyList() }
    override fun getSenders(): List<RtpSender> { touches++; return emptyList() }
    override fun getReceivers(): List<RtpReceiver> { touches++; return emptyList() }
    override fun dispose() { disposed = true }
}

/** Exercise compiled WebRtcEngineImpl methods, not a duplicate queue implementation.
 * Skip Android audio/EGL construction and install controlled native peers only.
 * Replacement models the peer=null / new mutex / new peer fields in dispose/initialize;
 * these tests do not claim to run Android media teardown or JNI. */
class WebRtcSdpOwnershipTest {
    private fun field(name: String) = WebRtcEngineImpl::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun engine(peer: OwnershipPeer, mutex: Mutex): WebRtcEngineImpl {
        // Android's compile stubs omit this host-JVM-only API; resolve it at test runtime.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val instance = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, WebRtcEngineImpl::class.java)
        return (instance as WebRtcEngineImpl).also {
            field("peerConnection").set(it, peer)
            field("sdpMutex").set(it, mutex)
            field("mediaProfile").set(it, CallMediaLadder.wifi)
        }
    }
    private fun replace(engine: WebRtcEngineImpl, old: OwnershipPeer, next: OwnershipPeer, mutex: Mutex) {
        field("peerConnection").set(engine, null)
        old.dispose()
        field("sdpMutex").set(engine, mutex)
        field("peerConnection").set(engine, next)
        field("remoteSdp").set(engine, "replacement-description")
    }

    @Test fun `native work from A releases queued rollback after B replacement without touching B`() = runTest {
        for (guarded in listOf(false, true)) {
            val old = OwnershipPeer().apply { holdRemote = true }
            val replacement = OwnershipPeer() // B has an outstanding local offer.
            val mutexA = Mutex()
            val engine = engine(old, mutexA)
            var ownsA = true
            val applyingA = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.setRemoteDescription("answer", "call-A") }
            }
            assertTrue(mutexA.isLocked, "actual native remote application holds A's mutex")
            val rollbackA = async(start = CoroutineStart.UNDISPATCHED) {
                if (guarded) engine.rollbackLocalDescription { ownsA } else engine.rollbackLocalDescription()
            }
            assertFalse(rollbackA.isCompleted)
            ownsA = false
            val mutexB = Mutex(locked = true)
            replace(engine, old, replacement, mutexB)
            old.remoteCompletion.onSetSuccess()
            assertTrue(applyingA.await().isFailure)
            rollbackA.await()
            assertTrue(old.disposed)
            assertEquals(0, old.rollbacks)
            assertEquals(1, old.touches, "discard the rollback before accessing disposed A")
            assertEquals(0, replacement.rollbacks)
            assertEquals(0, replacement.touches, "A's rollback must not even read B's signaling state")
            assertEquals(0, replacement.signalingReads)
            assertEquals("replacement-description", field("remoteSdp").get(engine))
            assertTrue(mutexB.isLocked, "old work does not switch to B's mutex")
            mutexB.unlock()
        }
    }

    @Test fun `queued rollback runs once for its live owner and skips an expired owner on the same peer`() = runTest {
        for (stillOwned in listOf(false, true)) {
            val peer = OwnershipPeer()
            val mutex = Mutex(locked = true)
            val engine = engine(peer, mutex)
            var ownsNegotiation = true
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                engine.rollbackLocalDescription { ownsNegotiation }
            }
            assertFalse(queued.isCompleted)
            ownsNegotiation = stillOwned
            mutex.unlock()
            queued.await()
            assertEquals(if (stillOwned) 1 else 0, peer.rollbacks)
            assertEquals(if (stillOwned) 1 else 0, peer.touches)
            assertEquals(stillOwned, peer.signalingReads > 0)
        }
    }

    @Test fun `replacing only the SDP mutex invalidates queued rollback even with the same peer`() = runTest {
        val peer = OwnershipPeer()
        val mutexA = Mutex(locked = true)
        val engine = engine(peer, mutexA)
        val queued = async(start = CoroutineStart.UNDISPATCHED) { engine.rollbackLocalDescription() }
        field("sdpMutex").set(engine, Mutex())
        mutexA.unlock()
        queued.await()
        assertEquals(0, peer.rollbacks)
        assertEquals(0, peer.touches)
        assertEquals(0, peer.signalingReads)
    }

    private suspend fun queuedOperation(engine: WebRtcEngineImpl, operation: String, owner: (() -> Boolean)?) {
        when (operation) {
            "offer" -> if (owner == null) engine.createOffer() else engine.createOffer(owner)
            "answer" -> if (owner == null) engine.createAnswer() else engine.createAnswer(owner)
            "remote-offer" -> if (owner == null) engine.setRemoteDescription("offer", "remote") else engine.setRemoteDescription("offer", "remote", owner)
            "remote-answer" -> if (owner == null) engine.setRemoteDescription("answer", "remote") else engine.setRemoteDescription("answer", "remote", owner)
        }
    }

    @Test fun `queued offer answer and remote descriptions cannot select a replacement peer through default APIs`() = runTest {
        for (operation in listOf("offer", "answer", "remote-offer", "remote-answer")) {
            val old = OwnershipPeer()
            val replacement = OwnershipPeer()
            val mutexA = Mutex(locked = true)
            val engine = engine(old, mutexA)
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { queuedOperation(engine, operation, null) }
            }
            assertFalse(queued.isCompleted)
            replace(engine, old, replacement, Mutex())
            mutexA.unlock()
            assertTrue(queued.await().isFailure, operation)
            assertEquals(0, old.touches, operation)
            assertEquals(0, replacement.touches, operation)
            assertEquals(0, replacement.rollbacks, operation)
        }
    }

    @Test fun `all queued SDP producers recheck negotiation ownership inside the original lock`() = runTest {
        for (operation in listOf("offer", "answer", "remote-offer", "remote-answer")) {
            val peer = OwnershipPeer()
            val mutex = Mutex(locked = true)
            val engine = engine(peer, mutex)
            var owned = true
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { queuedOperation(engine, operation) { owned } }
            }
            owned = false
            mutex.unlock()
            assertTrue(queued.await().isFailure, operation)
            assertEquals(0, peer.touches, operation)
        }
    }
}
