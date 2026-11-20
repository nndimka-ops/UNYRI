package com.messenger.app.data.directtransfer

import com.messenger.app.data.api.IceServerDto
import com.messenger.app.data.call.CallPeerToPeer
import kotlin.test.*

class DirectPeerIcePolicyTest {
    private val stun = IceServerDto(listOf("stun:stun.example.test:3478"))
    private val turn = IceServerDto(listOf("turn:relay.example.test:3478?transport=udp", "turns:relay.example.test:5349"), "user", "credential")
    private val host = "candidate:1 1 udp 10 192.168.1.7 5000 typ host"
    private val relay = "candidate:2 1 udp 10 203.0.113.8 5001 typ relay raddr 192.168.1.7 rport 5000 generation 0"
    private val sdp = listOf("v=0", "o=- 1 2 IN IP4 192.168.1.7", "c=IN IP4 192.168.1.7",
        "m=application 9 UDP/DTLS/SCTP webrtc-datachannel", "a=ice-ufrag:abcd", "a=ice-pwd:abcdefghijklmnopqrstuv",
        "a=fingerprint:sha-256 " + List(32) { "AB" }.joinToString(":"), "a=sctp-port:5000",
        "a=$host", "a=$relay", "a=remote-candidates:1 192.168.1.7 5000").joinToString("\r\n")

    @Test fun `calls and transfers share default and explicit contact admission`() {
        for (choice in CallPeerToPeer.entries) for (membership in listOf(null, false, true)) {
            val config = DirectPeerIcePolicy.resolve(listOf(stun, turn), choice, membership)
            val relayOnly = choice == CallPeerToPeer.NOBODY || choice == CallPeerToPeer.CONTACTS && membership != true
            assertEquals(relayOnly, config.relayOnly)
            assertEquals(!relayOnly, config.servers.contains(stun))
            assertEquals(relayOnly, config.servers.contains(turn))
            if (relayOnly) assertEquals(turn, config.servers.last()) // Only relay-only peers receive credentials.
            else assertTrue(config.servers.all { it.username == null && it.credential == null })
            config.requireAvailable()
        }
        assertTrue(DirectPeerIcePolicy.resolve(listOf(turn), CallPeerToPeer.resolved(null), null).relayOnly)
        assertTrue(DirectPeerIcePolicy.resolve(listOf(turn), CallPeerToPeer.EVERYONE, true, true, true).relayOnly)
    }

    @Test fun `missing relay fails closed with a clear reason instead of direct fallback`() {
        val config = DirectPeerIcePolicy.resolve(listOf(stun), CallPeerToPeer.CONTACTS, false)
        assertTrue(config.relayOnly); assertFalse(config.relayAvailable); assertTrue(config.servers.isEmpty())
        assertEquals("relay_unavailable", assertFailsWith<DirectStorageError> { config.requireAvailable() }.reason)
        assertTrue("relay_unavailable" in DIRECT_CONTROL_REASONS)
        DirectPeerIcePolicy.resolve(emptyList(), CallPeerToPeer.EVERYONE, false).requireAvailable()
    }

    @Test fun `relay only description removes direct and related host addresses before signalling`() {
        val safe = DirectPeerIcePolicy.localSdp(sdp, true)
        assertFalse(safe.contains("192.168.1.7")); assertFalse(safe.contains("typ host"))
        assertFalse(safe.contains("raddr")); assertFalse(safe.contains("rport")); assertFalse(safe.contains("a=remote-candidates"))
        assertTrue(safe.contains("203.0.113.8")); assertTrue(safe.contains("generation 0"))
        assertTrue(DirectPeerPolicy.isDataOnlySdp(safe, allowRelay = true))
        assertEquals(sdp, DirectPeerIcePolicy.localSdp(sdp, false))
        assertTrue(DirectPeerPolicy.isTransportCandidate(relay))
        assertFalse(DirectPeerPolicy.isDirectCandidate(relay))
        assertFalse(DirectPeerPolicy.isTransportCandidate(relay + " typ host"))
        assertFalse(DirectPeerPolicy.isTransportCandidate(" ".repeat(30)))
        assertFalse(DirectPeerPolicy.isDataOnlySdp(safe.replace("m=application", "m=audio"), allowRelay = true))
    }

    @Test fun `selected local pair enforces relay and supports asymmetric peers`() {
        for (remote in listOf("host", "srflx", "prflx", "relay")) {
            assertTrue(DirectPeerIcePolicy.selectedPairAllowed("relay", remote, true))
            assertFalse(DirectPeerIcePolicy.selectedPairAllowed("host", remote, true))
            assertTrue(DirectPeerIcePolicy.selectedPairAllowed("host", remote, false))
        }
        assertFalse(DirectPeerIcePolicy.selectedPairAllowed(null, "relay", true))
        assertFalse(DirectPeerIcePolicy.selectedPairAllowed("relay", "unknown", true))
        assertFalse(DirectPeerIcePolicy.selectedPairAllowed("relay", "host", false))
    }
}
