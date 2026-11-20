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
        fun resolved(stored: String?): CallPeerToPeer = entries.firstOrNull { it.wire == stored } ?: CONTACTS
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
) {
    val relayUnavailable: Boolean get() = transports == IceTransportPolicy.RELAY && !hasTurnServer
}

object WebRtcIceTransportPolicy {

    /** Privacy applies in every build; the debug flag may only tighten it. */
    fun resolve(isDebug: Boolean, forceRelayFlag: Boolean,
                setting: CallPeerToPeer = CallPeerToPeer.CONTACTS, peerIsContact: Boolean? = null): IceTransportPolicy =
        if ((isDebug && forceRelayFlag) || setting == CallPeerToPeer.NOBODY ||
            (setting == CallPeerToPeer.CONTACTS && peerIsContact != true)) IceTransportPolicy.RELAY else IceTransportPolicy.ALL

    /** Classifies ICE before media setup; a required missing TURN ends the call. */
    fun buildConfig(
        iceServers: List<IceServerDto>,
        isDebug: Boolean,
        forceRelayFlag: Boolean,
        setting: CallPeerToPeer = CallPeerToPeer.CONTACTS,
        peerIsContact: Boolean? = null,
    ): WebRtcIceConfig {
        val hasStun = iceServers.any { server -> server.urls.any { it.startsWith("stun:") } }
        val hasTurn = iceServers.any { server -> server.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } }
        return WebRtcIceConfig(
            transports = resolve(isDebug, forceRelayFlag, setting, peerIsContact),
            hasStunServer = hasStun,
            hasTurnServer = hasTurn,
        )
    }
}
