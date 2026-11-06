package com.messenger.app.data.call

import com.messenger.app.data.api.IceServerDto

/** JVM-testable ICE transport policy (Этап J.1-2). Maps 1:1 onto
 *  `org.webrtc.PeerConnection.IceTransportsType` at the engine boundary so
 *  the ALL/RELAY switch can be unit-tested without loading native WebRTC. */
enum class IceTransportPolicy { ALL, RELAY }

/** Pure-Kotlin projection of the ICE-relevant parts of the
 *  `PeerConnection.RTCConfiguration` the engine builds - lets tests assert
 *  that a forced RELAY policy actually reaches the configuration (and that a
 *  missing TURN server is observed safely) without touching `org.webrtc.*`. */
data class WebRtcIceConfig(
    val transports: IceTransportPolicy,
    val hasStunServer: Boolean,
    val hasTurnServer: Boolean,
)

object WebRtcIceTransportPolicy {

    /** RELAY is only ever forced for debug builds with the opt-in flag set;
     *  release always resolves to ALL (never a permanent production relay). */
    fun resolve(isDebug: Boolean, forceRelayFlag: Boolean): IceTransportPolicy =
        if (isDebug && forceRelayFlag) IceTransportPolicy.RELAY else IceTransportPolicy.ALL

    /** Resolves the transport policy and classifies the supplied ICE servers
     *  into a JVM-testable config. A forced-RELAY request with no TURN server
     *  present is still reported faithfully (`transports = RELAY`,
     *  `hasTurnServer = false`) - the engine will then fail at ICE rather
     *  than silently falling back to host/prflx, which is exactly the
     *  J.1-2 negative-test behaviour. */
    fun buildConfig(
        iceServers: List<IceServerDto>,
        isDebug: Boolean,
        forceRelayFlag: Boolean,
    ): WebRtcIceConfig {
        val hasStun = iceServers.any { server -> server.urls.any { it.startsWith("stun:") } }
        val hasTurn = iceServers.any { server -> server.urls.any { it.startsWith("turn:") } }
        return WebRtcIceConfig(
            transports = resolve(isDebug, forceRelayFlag),
            hasStunServer = hasStun,
            hasTurnServer = hasTurn,
        )
    }
}