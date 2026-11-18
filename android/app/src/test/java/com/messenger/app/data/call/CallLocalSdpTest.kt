package com.messenger.app.data.call

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import livekit.org.webrtc.*

private class ControlledLocalSdpPeer : PeerConnection(NativePeerConnectionFactory { 0L }) {
    lateinit var created: SdpObserver
    lateinit var applied: SdpObserver
    val localDescriptions = mutableListOf<SessionDescription>()
    override fun createOffer(observer: SdpObserver, constraints: MediaConstraints) { created = observer }
    override fun createAnswer(observer: SdpObserver, constraints: MediaConstraints) { created = observer }
    override fun setLocalDescription(observer: SdpObserver, description: SessionDescription) {
        localDescriptions += description
        applied = observer
    }
}

class CallLocalSdpTest {
    @Test fun `native local descriptions contain profile DTX but never the transmitted capability marker`() = runTest {
        val raw = "v=0\r\nm=audio 9 RTP/AVP 111\r\na=rtpmap:111 opus/48000/2\r\n"
        for (type in listOf(SessionDescription.Type.OFFER, SessionDescription.Type.ANSWER)) {
            val peer = ControlledLocalSdpPeer()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                CallLocalSdp.createAndApply(peer, type, true, { true })
            }
            peer.created.onCreateSuccess(SessionDescription(type, raw))
            testScheduler.runCurrent()
            val native = peer.localDescriptions.single().description
            assertTrue(native.contains("a=fmtp:111 usedtx=1"))
            assertFalse(native.contains(CallSdpCapabilities.PERFECT_NEGOTIATION))
            peer.applied.onSetSuccess()
            assertEquals(native, result.await())
            assertTrue(CallSdpCapabilities.advertises(CallSdpCapabilities.advertising(result.await())))
        }
    }

    @Test fun `generation is checked after native creation and after native application`() = runTest {
        for (supersedeAfterCreation in listOf(true, false)) {
            val peer = ControlledLocalSdpPeer()
            var current = true
            var completed = 0
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    CallLocalSdp.createAndApply(peer, SessionDescription.Type.OFFER, false, { current }) { completed++ }
                }
            }
            if (supersedeAfterCreation) current = false
            peer.created.onCreateSuccess(SessionDescription(SessionDescription.Type.OFFER, "v=0\r\n"))
            testScheduler.runCurrent()
            if (supersedeAfterCreation) assertTrue(peer.localDescriptions.isEmpty())
            else {
                assertEquals(1, peer.localDescriptions.size)
                current = false
                peer.applied.onSetSuccess()
            }
            assertTrue(result.await().isFailure)
            assertEquals(0, completed)
        }
    }
}
