package com.messenger.app.data.call
import kotlin.test.*
class RemoteIcePolicyTest {
    private fun candidate(ip: String) = "candidate:1 1 udp 2122260223 $ip 5000 typ host"
    @Test fun unsafeAndUnresolvedAddresses() {
        for (ip in listOf("0.0.0.0", "127.0.0.1", "127.255.2.3", "169.254.1.2", "::", "::1", "fe80::1", "febf::1",
            "::ffff:127.0.0.1", "::ffff:169.254.2.3", "64:ff9b::7f00:1", "localhost", "peer.local", "2130706433", "0x7f000001", "224.0.0.1", "ff02::1", "1.2.3.999")) {
            assertFalse(RemoteIcePolicy.allows(candidate(ip), false), ip)
            assertFalse(RemoteIcePolicy.allows(candidate(ip), true), ip)
        }
    }
    @Test fun privateOnlyForP2P() {
        for (ip in listOf("10.1.2.3", "172.16.1.1", "172.31.255.1", "192.168.1.1", "100.64.1.1", "fc00::1", "fdab::1", "fec0::1", "::ffff:192.168.1.1", "64:ff9b::c0a8:101")) {
            assertTrue(RemoteIcePolicy.allows(candidate(ip), false), ip)
            assertFalse(RemoteIcePolicy.allows(candidate(ip), true), ip)
        }
        for (ip in listOf("172.15.1.1", "172.32.1.1", "192.169.1.1", "8.8.8.8", "2001:4860:4860::8888")) {
            assertTrue(RemoteIcePolicy.allows(candidate(ip), false), ip)
            assertTrue(RemoteIcePolicy.allows(candidate(ip), true), ip)
        }
    }
    @Test fun sdpAndTrickleUseSameFilter() {
        val good = candidate("8.8.8.8"); val bad = candidate("127.0.0.1")
        for (separator in listOf("\r\n", "\n")) {
            val sdp = listOf("v=0", "a=$bad", "a=$good", "a=fingerprint:sha-256 unchanged", "").joinToString(separator)
            val result = RemoteIcePolicy.filterSdp(sdp, true)
            assertFalse(result.contains(bad)); assertTrue(result.contains(good)); assertTrue(result.contains("fingerprint:sha-256 unchanged"))
        }
        assertFalse(RemoteIcePolicy.allows("candidate:" + "x".repeat(2048), false))
        assertFalse(RemoteIcePolicy.allows("candidate:bad", false))
    }
}
