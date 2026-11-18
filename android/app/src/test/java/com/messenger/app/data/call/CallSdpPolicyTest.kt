package com.messenger.app.data.call

import kotlin.test.*

class CallSdpPolicyTest {
    private fun sdp(version: String, session: String = "peer") = "v=0\r\no=- $session $version IN IP4 0.0.0.0\r\nm=audio 9 RTP/AVP 111\r\n"

    @Test fun `origin parser retains unsigned 64 bit versions and rejects malformed ones`() {
        assertEquals(CallSdpOrigin("peer", ULong.MAX_VALUE), CallSdpOrigin.parse(sdp("18446744073709551615")))
        for (bad in listOf("-1", "not-a-version", "18446744073709551616")) assertNull(CallSdpOrigin.parse(sdp(bad)))
        assertNull(CallSdpOrigin.parse("v=0\no=- peer\n"))
    }

    @Test fun `answers advance across successive rounds while identical offers can be answered again`() {
        val order = CallRemoteSdpOrder()
        for (round in 1..4) {
            assertFalse(order.isStale(sdp(round.toString()), answer = true))
            order.applied(sdp(round.toString()))
            for (older in 0..round) assertTrue(order.isStale(sdp(older.toString()), answer = true))
            assertFalse(order.isStale(sdp(round.toString()), answer = false))
            assertTrue(order.isStale(sdp((round - 1).toString()), answer = false))
        }
    }

    @Test fun `new peer session and descriptions without a usable origin match iOS compatibility`() {
        val order = CallRemoteSdpOrder()
        order.applied(sdp("9"))
        assertFalse(order.isStale(sdp("1", "replacement"), answer = true))
        assertFalse(order.isStale("legacy", answer = true))
        order.applied("legacy")
        assertTrue(order.isStale(sdp("8"), answer = false))
    }

    @Test fun `capability is exactly once at session level with CRLF or LF and idempotent`() {
        val marker = CallSdpCapabilities.PERFECT_NEGOTIATION
        for (separator in listOf("\r\n", "\n")) {
            for (input in listOf(sdp("1"), sdp("1") + "$marker\r\n$marker\r\n", "v=0", "v=0\r\n$marker\r\n")) {
                val sent = CallSdpCapabilities.advertising(input.replace("\r\n", separator))
                assertEquals(1, sent.lineSequence().count { it == marker })
                val media = sent.indexOf("m=")
                assertTrue(media < 0 || sent.indexOf(marker) < media)
                assertEquals(sent, CallSdpCapabilities.advertising(sent))
                assertTrue(CallSdpCapabilities.advertises(sent))
            }
        }
    }
}
