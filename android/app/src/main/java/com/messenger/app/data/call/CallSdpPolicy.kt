package com.messenger.app.data.call

/** libwebrtc increments the o= version for every description, including after rollback. */
data class CallSdpOrigin(val session: String, val version: ULong) {
    companion object {
        fun parse(sdp: String): CallSdpOrigin? {
            val origin = sdp.lineSequence().firstOrNull { it.startsWith("o=") } ?: return null
            val fields = origin.drop(2).split(Regex("\\s+"))
            if (fields.size < 3) return null
            return CallSdpOrigin(fields[1], fields[2].toULongOrNull() ?: return null)
        }
    }
}

/** Answers must advance the peer version; an identical offer may be resent for a lost answer.
 * Unknown origins and a new peer session retain iOS's compatibility behavior. */
class CallRemoteSdpOrder {
    private var applied: CallSdpOrigin? = null
    fun isStale(sdp: String, answer: Boolean): Boolean {
        val previous = applied ?: return false
        val origin = CallSdpOrigin.parse(sdp) ?: return false
        if (previous.session != origin.session) return false
        return if (answer) origin.version <= previous.version else origin.version < previous.version
    }
    fun applied(sdp: String) { CallSdpOrigin.parse(sdp)?.let { applied = it } }
}

object CallSdpCapabilities {
    const val PERFECT_NEGOTIATION = "a=x-unyri-perfect-negotiation"
    fun advertises(sdp: String) = sdp.lineSequence().any { it.trim() == PERFECT_NEGOTIATION }

    /** Only the transmitted copy changes, as on iOS; retain it verbatim for retries. */
    fun advertising(sdp: String): String {
        val separator = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(separator).filterNot { it.trim() == PERFECT_NEGOTIATION }.toMutableList()
        val media = lines.indexOfFirst { it.startsWith("m=") }
        val index = if (media >= 0) media else if (lines.lastOrNull() == "") lines.lastIndex else lines.size
        lines.add(index, PERFECT_NEGOTIATION)
        if (lines.lastOrNull() != "") lines.add("")
        return lines.joinToString(separator)
    }
}
