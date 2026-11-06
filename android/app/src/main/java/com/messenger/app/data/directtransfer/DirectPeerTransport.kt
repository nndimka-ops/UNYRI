package com.messenger.app.data.directtransfer

import kotlinx.coroutines.flow.Flow

/** A dedicated data-only connection. Its descriptions must be authenticated by the transfer protocol. */
interface DirectPeerTransport {
    val events: Flow<DirectPeerEvent>
    suspend fun createOffer(): String
    suspend fun acceptOffer(sdp: String): String
    suspend fun applyAnswer(sdp: String)
    suspend fun addRemoteIce(candidate: String, mid: String?, line: Int)
    suspend fun send(bytes: ByteArray, binary: Boolean = true)
    suspend fun close()
}

sealed interface DirectPeerEvent {
    data object Ready : DirectPeerEvent
    data class Frame(val bytes: ByteArray, val binary: Boolean) : DirectPeerEvent
    data class Closed(val reason: DirectPeerFailure) : DirectPeerEvent
}

enum class DirectPeerFailure { CONNECTION_LOST, DIRECT_PATH_UNAVAILABLE, INVALID_REMOTE, RECEIVER_TOO_SLOW, CLOSED }

/** Bounded parser shared by SDP, ICE and selected-pair checks; accepts no TURN relay candidates. */
internal object DirectPeerPolicy {
    const val CHANNEL = "coco-file-v1"
    const val MAX_BINARY_FRAME = 32 * 1024
    const val MAX_CONTROL_FRAME = 64 * 1024
    const val HIGH_WATER = 512 * 1024L
    const val LOW_WATER = 128 * 1024L
    const val MAX_SDP = 32 * 1024
    private val directTypes = setOf("host", "srflx", "prflx")

    fun isDirectType(type: String?): Boolean = type in directTypes
    fun candidateType(raw: String): String? = if (isDirectCandidate(raw)) raw.removePrefix("a=").split(' ').filter(String::isNotEmpty)[7] else null

    fun isDirectCandidate(raw: String): Boolean {
        if (raw.length !in 20..4096 || raw.any { it.code < 32 || it.code > 126 }) return false
        val fields = raw.removePrefix("a=").split(' ').filter(String::isNotEmpty)
        if (!fields.first().startsWith("candidate:") || fields.size < 8) return false
        if ((fields[1].toIntOrNull() ?: return false) !in 1..2 || fields[2].lowercase() !in setOf("udp", "tcp")) return false
        if (fields[3].toLongOrNull()?.let { it in 0..0xffffffffL } != true) return false
        if ((fields[5].toIntOrNull() ?: return false) !in 1..65535 || fields[6] != "typ" || !isDirectType(fields[7])) return false
        // A second type token cannot disguise a relay in an extension.
        return fields.count { it == "typ" } == 1
    }

    fun isStunUrl(url: String): Boolean {
        if (url.length !in 6..512 || url.any { it.isWhitespace() || it.code < 33 || it.code > 126 }) return false
        if (!url.startsWith("stun:") && !url.startsWith("stuns:")) return false
        return url.substringAfter(':').isNotEmpty() && !url.contains('@') && !url.contains('/') &&
            !url.contains('#') && !url.contains('?')
    }

    fun isDataOnlySdp(sdp: String): Boolean {
        if (sdp.length !in 1..MAX_SDP || sdp.any { it == '\u0000' || (it.code < 32 && it !in "\r\n\t") || it.code > 126 }) return false
        val lines = sdp.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val media = lines.filter { it.startsWith("m=") }
        if (media.size != 1 || !media.single().startsWith("m=application ")) return false
        if (lines.none { it.startsWith("a=sctp-port:") } || lines.none { it.startsWith("a=ice-ufrag:") } ||
            lines.none { it.startsWith("a=ice-pwd:") }) return false
        val fingerprints = lines.filter { it.startsWith("a=fingerprint:") }
        if (fingerprints.isEmpty() || fingerprints.any {
                !Regex("a=fingerprint:sha-256 ([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}").matches(it)
            }) return false
        return lines.filter { it.startsWith("a=candidate:") }.all(::isDirectCandidate)
    }
}
