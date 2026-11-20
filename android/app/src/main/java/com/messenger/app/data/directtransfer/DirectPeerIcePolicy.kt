package com.messenger.app.data.directtransfer

import com.messenger.app.data.api.IceServerDto
import com.messenger.app.data.call.CallPeerToPeer
import com.messenger.app.data.call.IceTransportPolicy
import com.messenger.app.data.call.WebRtcIceTransportPolicy

/** The same contact admission as calls, frozen before creating a native connection. */
internal data class DirectPeerIceConfig(val servers: List<IceServerDto>, val relayOnly: Boolean) {
    val relayAvailable: Boolean get() = servers.any { server -> server.urls.any(DirectPeerIcePolicy::isTurnUrl) }
    fun requireAvailable(): DirectPeerIceConfig = apply {
        if (relayOnly && !relayAvailable) throw DirectStorageError("relay_unavailable")
    }
}

internal object DirectPeerIcePolicy {
    fun isTurnUrl(url: String): Boolean = url.length in 6..512 &&
        url.all { it.code in 33..126 } && (url.startsWith("turn:") || url.startsWith("turns:")) &&
        url.substringAfter(':').isNotEmpty() && url.none { it in "@/#" }

    fun resolve(servers: List<IceServerDto>, setting: CallPeerToPeer, peerIsContact: Boolean?,
        isDebug: Boolean = false, forceRelay: Boolean = false): DirectPeerIceConfig {
        val relayOnly = WebRtcIceTransportPolicy.resolve(isDebug, forceRelay, setting, peerIsContact) == IceTransportPolicy.RELAY
        val bounded = servers.take(8).mapNotNull { server ->
            val urls = server.urls.filter { if (relayOnly) isTurnUrl(it) else DirectPeerPolicy.isStunUrl(it) }.distinct().take(8)
            if (urls.isEmpty()) null else if (relayOnly) server.copy(urls = urls)
            else IceServerDto(urls = urls)
        }
        return DirectPeerIceConfig(bounded, relayOnly)
    }

    fun selectedPairAllowed(local: String?, remote: String?, relayOnly: Boolean): Boolean =
        (if (relayOnly) local == "relay" else DirectPeerPolicy.isDirectType(local)) && DirectPeerPolicy.isTransportType(remote)

    /** Signalling a relay candidate must not disclose its related host address. */
    fun localSdp(sdp: String, relayOnly: Boolean): String {
        if (!relayOnly) return sdp
        return sdp.lineSequence().mapNotNull { line -> when {
            line.startsWith("a=candidate:") -> if (DirectPeerPolicy.candidateType(line) != "relay") null else {
                val fields = line.split(' ').filter(String::isNotEmpty)
                val safe = fields.take(8).toMutableList()
                var index = 8
                while (index < fields.size) {
                    if (fields[index] in setOf("raddr", "rport")) index += 2
                    else { safe += fields[index]; index++ }
                }
                safe.joinToString(" ")
            }
            line.startsWith("c=") -> "c=IN IP4 0.0.0.0"
            line.startsWith("o=") -> line.split(' ').take(3).joinToString(" ") + " IN IP4 0.0.0.0"
            line.startsWith("a=remote-candidates:") || line.startsWith("a=rtcp:") -> null
            else -> line
        } }.joinToString("\r\n")
    }
}
