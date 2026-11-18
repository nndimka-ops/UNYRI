package com.messenger.app.data.call

import java.net.InetAddress

/** Numeric literals only: classification must never perform a DNS lookup. */
object RemoteIcePolicy {
    fun allows(candidate: String, relayOnly: Boolean): Boolean {
        if (candidate.length > 2048) return false
        val fields = candidate.trim().removePrefix("a=").split(Regex("\\s+"))
        if (fields.size < 8 || !fields[0].startsWith("candidate:") || fields[6] != "typ") return false
        return allowsAddress(fields[4], relayOnly)
    }
    fun allowsAddress(address: String, relayOnly: Boolean): Boolean {
        val value = address.removePrefix("[").removeSuffix("]")
        val bytes = if (value.contains(':')) {
            if (!value.matches(Regex("[0-9a-fA-F:.]+"))) return false
            runCatching { InetAddress.getByName(value).address }.getOrNull() ?: return false
        } else {
            val parts = value.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || it.toIntOrNull() !in 0..255 }) return false
            parts.map { it.toInt().toByte() }.toByteArray()
        }
        fun v4(data: ByteArray): Boolean {
            val a = data[0].toInt() and 255; val b = data[1].toInt() and 255
            if (a == 0 || a == 127 || a == 169 && b == 254 || a >= 224) return false
            return !relayOnly || !(a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 100 && b in 64..127)
        }
        if (bytes.size == 4) return v4(bytes)
        if (bytes.size != 16) return false
        val ip = InetAddress.getByAddress(bytes)
        if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isMulticastAddress) return false
        // IPv4-compatible and well-known NAT64 addresses must not wrap a denied IPv4.
        if (bytes.take(12).all { it == 0.toByte() } ||
            (bytes[0] == 0x00.toByte() && bytes[1] == 0x64.toByte() && bytes[2] == 0xff.toByte() && bytes[3] == 0x9b.toByte() && bytes.slice(4..11).all { it == 0.toByte() })) return v4(bytes.copyOfRange(12, 16))
        val first = bytes[0].toInt() and 255
        return !relayOnly || (first and 0xfe != 0xfc && !ip.isSiteLocalAddress)
    }
    fun filterSdp(sdp: String, relayOnly: Boolean): String = sdp.lineSequence().filter { !it.startsWith("a=candidate:") || allows(it, relayOnly) }.joinToString("\r\n")
}
