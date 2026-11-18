package com.messenger.app.data.call

import com.messenger.app.data.api.IceServerDto

/** JVM-testable ICE transport policy (Этап J.1-2). Maps 1:1 onto
 *  `livekit.org.webrtc.PeerConnection.IceTransportsType` at the engine boundary so
 *  the ALL/RELAY switch can be unit-tested without loading native WebRTC. */
enum class IceTransportPolicy { ALL, RELAY }

enum class CallPeerToPeer(val wire: String) {
    EVERYONE("everyone"), CONTACTS("contacts"), NOBODY("nobody");

    companion object {
        const val KEY = "callPeerToPeer.v1"
        fun resolved(stored: String?): CallPeerToPeer = entries.firstOrNull { it.wire == stored } ?: EVERYONE
    }
}

/** Pure-Kotlin projection of the ICE-relevant parts of the
 *  `PeerConnection.RTCConfiguration` the engine builds - lets tests assert
 *  that a forced RELAY policy actually reaches the configuration (and that a
 *  missing TURN server is observed safely) without touching `livekit.org.webrtc.*`. */
data class WebRtcIceConfig(
    val transports: IceTransportPolicy,
    val hasStunServer: Boolean,
    val hasTurnServer: Boolean,
)

object WebRtcIceTransportPolicy {

    /** Privacy applies in every build; the debug flag may only tighten it. */
    fun resolve(isDebug: Boolean, forceRelayFlag: Boolean,
                setting: CallPeerToPeer = CallPeerToPeer.EVERYONE, peerIsContact: Boolean? = null): IceTransportPolicy =
        if ((isDebug && forceRelayFlag) || setting == CallPeerToPeer.NOBODY ||
            (setting == CallPeerToPeer.CONTACTS && peerIsContact != true)) IceTransportPolicy.RELAY else IceTransportPolicy.ALL

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
        setting: CallPeerToPeer = CallPeerToPeer.EVERYONE,
        peerIsContact: Boolean? = null,
    ): WebRtcIceConfig {
        val hasStun = iceServers.any { server -> server.urls.any { it.startsWith("stun:") } }
        val hasTurn = iceServers.any { server -> server.urls.any { it.startsWith("turn:") } }
        return WebRtcIceConfig(
            transports = resolve(isDebug, forceRelayFlag, setting, peerIsContact),
            hasStunServer = hasStun,
            hasTurnServer = hasTurn,
        )
    }
}
