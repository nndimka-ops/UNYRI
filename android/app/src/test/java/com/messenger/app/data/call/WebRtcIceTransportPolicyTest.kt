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
    fun `default policy requires real contact membership`() {
        for (debug in listOf(true, false)) {
            assertEquals(IceTransportPolicy.RELAY, WebRtcIceTransportPolicy.resolve(isDebug = debug, forceRelayFlag = false))
            assertEquals(IceTransportPolicy.ALL, WebRtcIceTransportPolicy.resolve(isDebug = debug, forceRelayFlag = false, peerIsContact = true))
        }
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
        assertEquals(IceTransportPolicy.ALL, WebRtcIceTransportPolicy.resolve(isDebug = false, forceRelayFlag = true, setting = CallPeerToPeer.EVERYONE))
    }

    @Test
    fun `missing turn config is handled safely`() {
        // Required missing TURN is a dedicated preflight failure before native setup.
        val forcedNoTurn = WebRtcIceTransportPolicy.buildConfig(
            iceServers = emptyList(),
            isDebug = true,
            forceRelayFlag = true,
        )
        assertEquals(IceTransportPolicy.RELAY, forcedNoTurn.transports)
        assertFalse(forcedNoTurn.hasTurnServer)
        assertFalse(forcedNoTurn.hasStunServer)
        assertTrue(forcedNoTurn.relayUnavailable)

        // Default Contacts with unknown membership never falls back to direct.
        val defaultEmpty = WebRtcIceTransportPolicy.buildConfig(
            iceServers = emptyList(),
            isDebug = true,
            forceRelayFlag = false,
        )
        assertEquals(IceTransportPolicy.RELAY, defaultEmpty.transports)
        assertFalse(defaultEmpty.hasTurnServer)
        assertTrue(defaultEmpty.relayUnavailable)
        assertFalse(WebRtcIceTransportPolicy.buildConfig(listOf(stunServer), false, false,
            CallPeerToPeer.EVERYONE, null).relayUnavailable)
        assertFalse(WebRtcIceTransportPolicy.buildConfig(listOf(turnServer), false, false).relayUnavailable)
    }
}
