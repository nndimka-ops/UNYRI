package com.messenger.app.data.call

import com.messenger.app.data.api.IceServerDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebRtcIceTransportPolicyTest {
    private val turnServer = IceServerDto(
        urls = listOf("turn:10.0.2.2:3478?transport=udp", "turn:10.0.2.2:3478?transport=tcp"),
        username = "expiry",
        credential = "secret",
    )
    private val stunServer = IceServerDto(urls = listOf("stun:10.0.2.2:3478"))

    @Test
    fun `default policy is ALL`() {
        assertEquals(IceTransportPolicy.ALL, WebRtcIceTransportPolicy.resolve(isDebug = true, forceRelayFlag = false))
        assertEquals(IceTransportPolicy.ALL, WebRtcIceTransportPolicy.resolve(isDebug = false, forceRelayFlag = false))
    }

    @Test
    fun `forced policy is RELAY for debug builds`() {
        assertEquals(IceTransportPolicy.RELAY, WebRtcIceTransportPolicy.resolve(isDebug = true, forceRelayFlag = true))
    }

    @Test
    fun `relay policy is passed into the built rtc configuration`() {
        val config = WebRtcIceTransportPolicy.buildConfig(
            iceServers = listOf(turnServer, stunServer),
            isDebug = true,
            forceRelayFlag = true,
        )

        assertEquals(IceTransportPolicy.RELAY, config.transports)
        assertTrue(config.hasTurnServer)
        assertTrue(config.hasStunServer)
    }

    @Test
    fun `release ignores debug relay flag with Everyone default`() {
        assertEquals(IceTransportPolicy.ALL, WebRtcIceTransportPolicy.resolve(isDebug = false, forceRelayFlag = true))
    }

    @Test
    fun `missing turn config is handled safely`() {
        // Forced RELAY with no TURN server at all: still reported faithfully,
        // never crashes, and the engine will fail ICE instead of falling back.
        val forcedNoTurn = WebRtcIceTransportPolicy.buildConfig(
            iceServers = emptyList(),
            isDebug = true,
            forceRelayFlag = true,
        )
        assertEquals(IceTransportPolicy.RELAY, forcedNoTurn.transports)
        assertFalse(forcedNoTurn.hasTurnServer)
        assertFalse(forcedNoTurn.hasStunServer)

        // Default (ALL) with empty servers is also safe.
        val defaultEmpty = WebRtcIceTransportPolicy.buildConfig(
            iceServers = emptyList(),
            isDebug = true,
            forceRelayFlag = false,
        )
        assertEquals(IceTransportPolicy.ALL, defaultEmpty.transports)
        assertFalse(defaultEmpty.hasTurnServer)
    }
}
