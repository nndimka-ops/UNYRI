package com.messenger.app.data.directtransfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPeerPolicyTest {
    private val host = "candidate:1 1 udp 2122260223 192.168.0.10 50123 typ host generation 0"
    private val reflexive = "candidate:2 1 udp 1686052607 203.0.113.5 50124 typ srflx raddr 192.168.0.10 rport 50123"
    private val sdp = listOf("v=0", "m=application 9 UDP/DTLS/SCTP webrtc-datachannel", "a=ice-ufrag:abcd",
        "a=ice-pwd:abcdefghijklmnopqrstuv", "a=fingerprint:sha-256 " + List(32) { "AB" }.joinToString(":"),
        "a=sctp-port:5000", "a=$host").joinToString("\r\n")

    @Test fun hostAndReflexiveCandidatesRemainAvailable() {
        assertTrue(DirectPeerPolicy.isDirectCandidate(host))
        assertTrue(DirectPeerPolicy.isDirectCandidate(reflexive))
        assertTrue(DirectPeerPolicy.isDataOnlySdp(sdp))
    }

    @Test fun turnAndCredentialUrlsNeverEnterConfiguration() {
        assertTrue(DirectPeerPolicy.isStunUrl("stun:example.test:3478"))
        assertTrue(DirectPeerPolicy.isStunUrl("stuns:[2001:db8::1]:5349"))
        listOf("turn:example.test:3478", "turns:example.test", "stun:user:secret@example.test", "stun:host/turn", "stun:host?transport=udp", "stun:host\nturn:evil").forEach {
            assertFalse(it, DirectPeerPolicy.isStunUrl(it))
        }
    }

    @Test fun relayCannotHideInTrickleOrEmbeddedDescription() {
        val relay = host.replace("typ host", "typ relay")
        assertFalse(DirectPeerPolicy.isDirectCandidate(relay))
        assertFalse(DirectPeerPolicy.isDataOnlySdp(sdp + "\r\na=$relay"))
        assertFalse(DirectPeerPolicy.isDirectCandidate(host + " typ relay"))
        assertFalse(DirectPeerPolicy.isDirectCandidate(host.replace("typ host", "typ unknown")))
    }

    @Test fun malformedCandidatePortsAndControlsAreRejected() {
        listOf(host.replace(" 50123 ", " 65536 "), host.replace(" 1 udp", " 9 udp"), host.replace("udp", "udp\n"),
            host.replace("2122260223", "999999999999999999999999"), host + "\u0000", "candidate:").forEach {
            assertFalse(it, DirectPeerPolicy.isDirectCandidate(it))
        }
    }

    @Test fun audioAndVideoDescriptionsCannotActivateMedia() {
        assertFalse(DirectPeerPolicy.isDataOnlySdp(sdp.replace("m=application", "m=audio")))
        assertFalse(DirectPeerPolicy.isDataOnlySdp(sdp + "\r\nm=video 9 UDP/TLS/RTP/SAVPF 96"))
        assertFalse(DirectPeerPolicy.isDataOnlySdp(sdp + "\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel"))
    }

    @Test fun authenticatedFingerprintAndBoundedSdpRequired() {
        assertFalse(DirectPeerPolicy.isDataOnlySdp(sdp.replace("a=fingerprint:sha-256", "a=fingerprint:sha-1")))
        assertFalse(DirectPeerPolicy.isDataOnlySdp(sdp.replace("a=ice-pwd:", "b=ice-pwd:")))
        assertFalse(DirectPeerPolicy.isDataOnlySdp(sdp + "\r\na=" + "x".repeat(65536)))
        assertFalse(DirectPeerPolicy.isDataOnlySdp(sdp + "\u0000"))
        assertFalse(DirectPeerPolicy.isDirectType("relay"))
        assertFalse(DirectPeerPolicy.isDirectType(null))
    }
}
