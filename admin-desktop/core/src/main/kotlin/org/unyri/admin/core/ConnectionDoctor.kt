package org.unyri.admin.core

import kotlinx.serialization.json.*
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime

/** Literal/configuration validation only: no DNS, sockets, clock or environment access. */
object DoctorValidation {
    data class PublicAddress(val origin: String, val host: String, val port: Int)
    fun port(value: Int?) = value?.takeIf { it in 1..65535 }
    fun port(value: String?) = value?.takeIf { it.length in 1..10 && it.all { c -> c in '0'..'9' } }
        ?.toIntOrNull()?.let { port(it) }
    fun bracket(host: String) = host.removeSurrounding("[", "]").let { if (':' in it) "[$it]" else it }
    fun time(value: String?): Instant? = value?.takeIf { it.length <= 64 }?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
    }
    private fun bytes(raw: String?): ByteArray? {
        if (raw == null || raw.length !in 2..45) return null
        if (':' !in raw) return raw.takeIf(HostValidation::ipv4)?.split('.')?.map { it.toInt().toByte() }?.toByteArray()
        // Restrict to literal syntax before calling the JDK parser, which otherwise resolves DNS.
        if (raw.any { it !in "0123456789abcdefABCDEF:." }) return null
        return runCatching { InetAddress.getByName(raw) }.getOrNull()?.takeIf { it is Inet6Address }?.address
    }
    private fun normalized(b: ByteArray): String {
        if (b.size == 4) return b.joinToString(".") { (it.toInt() and 255).toString() }
        val groups = (0..7).map { ((b[it * 2].toInt() and 255) shl 8) or (b[it * 2 + 1].toInt() and 255) }
        var start = -1; var length = 1
        var i = 0
        while (i < 8) {
            if (groups[i] != 0) { i++; continue }
            val at = i
            while (i < 8 && groups[i] == 0) i++
            if (i - at > length) { start = at; length = i - at }
        }
        if (start < 0) return groups.joinToString(":") { it.toString(16) }
        return groups.take(start).joinToString(":") { it.toString(16) } + "::" +
            groups.drop(start + length).joinToString(":") { it.toString(16) }
    }
    private fun privateV4(b: List<Int>) = b[0] == 10 || b[0] == 192 && b[1] == 168 || b[0] == 172 && b[1] in 16..31
    fun publicIP(raw: String?): String? {
        val b = bytes(raw) ?: return null
        val o = b.map { it.toInt() and 255 }
        val safe = if (b.size == 4) !privateV4(o) && o[0] !in listOf(0, 127) && o[0] < 224 &&
            !(o[0] == 169 && o[1] == 254) && !(o[0] == 100 && o[1] in 64..127)
        else o.any { it != 0 } && !(o.dropLast(1).all { it == 0 } && o.last() == 1) &&
            o[0] and 254 != 252 && !(o[0] == 254 && o[1] and 192 == 128) && o[0] != 255 &&
            !(o.take(12).all { it == 0 }) // Rust IpAddr::to_ipv4 also rejects IPv4-compatible IPv6.
        return normalized(b).takeIf { safe }
    }
    fun lanIP(raw: String?, subnet: String? = null): String? {
        val b = bytes(raw) ?: return null
        val o = b.map { it.toInt() and 255 }
        val private = if (b.size == 4) privateV4(o) else o[0] and 254 == 252
        fun inSubnet(cidr: String): Boolean {
            val network = bytes(cidr.substringBefore('/')) ?: return false
            val bits = cidr.substringAfter('/', "").toIntOrNull() ?: return false
            if (b.size != network.size || bits !in 1..b.size * 8) return false
            return (0 until bits).all { bit ->
                val mask = 1 shl (7 - bit % 8)
                b[bit / 8].toInt() and mask == network[bit / 8].toInt() and mask
            }
        }
        return normalized(b).takeIf { private && !inSubnet("172.17.0.0/16") && !inSubnet(subnet ?: "172.30.219.0/24") }
    }
    fun origin(raw: String?): PublicAddress? {
        if (raw == null || raw.length > 2048 || !raw.startsWith("https://") ||
            raw.any { it.isWhitespace() || it.isISOControl() || it == '\\' } || '@' in raw) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.removeSurrounding("[", "]")?.lowercase() ?: return null
        if (uri.rawPath !in listOf("", "/") || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        val p = port(if (uri.port == -1) 443 else uri.port) ?: return null
        if (uri.rawAuthority.endsWith(':')) return null
        val literal = bytes(host)
        if (literal != null) { if (publicIP(host) == null) return null }
        else if (':' in host || host.length > 253 || host == "localhost" || host.endsWith(".localhost") ||
            host.all { it in "0123456789." } || !host.split('.').all {
                it.length in 1..63 && it.first() != '-' && it.last() != '-' && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' }
            }) return null
        val name = bracket(if (literal == null) host else normalized(literal))
        return PublicAddress("https://$name" + if (p == 443) "" else ":$p", name, p)
    }
}

/** Only validated primitives enter this model; raw JSON/env values are never retained. */
data class ConnectionDoctorAddresses(
    val schema: Int? = 1,
    val publicAddress: DoctorValidation.PublicAddress? = null,
    val publicInvalid: Boolean = false,
    val external: IP = IP(), val lan: LAN = LAN(), val calls: Calls = Calls(),
    val rooms: Boolean? = null, val ingress: String? = null, val provider: String? = null, val hosting: String? = null
) {
    data class IP(val address: String? = null, val source: String? = null, val state: String? = null)
    data class LAN(val address: String? = null, val port: Int? = null, val source: String? = null,
        val observedAt: Instant? = null, val state: String? = null)
    data class Calls(val turnPort: Int? = null, val transports: Set<String>? = null, val relayMin: Int? = null,
        val relayMax: Int? = null, val relayProtocol: String? = null)
    companion object {
        const val UNSUPPORTED = "unsupported"
        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        private fun JsonObject.choice(key: String, vararg choices: String): String? = text(key)?.let { if (it in choices) it else UNSUPPORTED }
        fun decode(element: JsonElement?): ConnectionDoctorAddresses? {
            if (element == null || element == JsonNull) return null
            val a = element as? JsonObject ?: return ConnectionDoctorAddresses(schema = null)
            fun obj(key: String) = a[key] as? JsonObject ?: JsonObject(emptyMap())
            val rawOrigin = a.text("public_origin")
            val p = DoctorValidation.origin(rawOrigin)
            val valid = p?.takeIf { DoctorValidation.bracket(a.text("public_host").orEmpty().lowercase()) == it.host && a.number("public_port") == it.port }
            val e = obj("external_ip"); val l = obj("lan"); val c = obj("calls"); val ingress = obj("ingress")
            val transports = (c["transports"] as? JsonArray)?.takeIf { it.size <= 8 }?.map {
                (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content?.takeIf { v -> v in setOf("udp", "tcp") } ?: UNSUPPORTED
            }?.toSet()
            return ConnectionDoctorAddresses(a.number("schema"), valid,
                valid == null && listOf("public_origin", "public_host", "public_port").any { a[it] != null && a[it] != JsonNull },
                IP(DoctorValidation.publicIP(e.text("address")), e.choice("source", "installer_config"), e.choice("state", "unverified", "unknown", "invalid")),
                LAN(DoctorValidation.lanIP(l.text("address")), DoctorValidation.port(l.number("port")), l.choice("source", "default_route"),
                    DoctorValidation.time(l.text("observed_at")), l.choice("state", "detected", "unknown", "invalid", "not_applicable")),
                Calls(DoctorValidation.port(c.number("turn_port")), transports, DoctorValidation.port(c.number("relay_min")),
                    DoctorValidation.port(c.number("relay_max")), c.choice("relay_protocol", "udp", "tcp")),
                (obj("rooms")["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
                ingress.choice("mode", "direct", "tunnel"), ingress.choice("provider", "tailscale_funnel", "cloudflare"), a.choice("hosting", "home_pc", "vps", "unknown"))
        }
    }
}

object ConnectionDoctorPresentation {
    data class Row(val key: String, val arguments: List<String> = emptyList())
    data class Snapshot(val internet: List<Row>, val home: List<Row>, val connection: List<Row>, val publicCopy: String? = null, val homeCopy: String? = null)
    const val unavailable = "This check is not available yet. Update the app."
    const val pathUnknown = "Connected to the server. The connection path is not yet known."
    const val unsupported = "The server does not report these addresses yet. Update the server or view them in the PC app."
    fun make(a: ConnectionDoctorAddresses?, activeOrigin: String?, serverName: String? = null, now: Instant,
        formatTime: (Instant) -> String): Snapshot {
        fun row(key: String, vararg args: Any) = Row(key, args.map { it.toString() })
        val internet = mutableListOf<Row>(); val home = mutableListOf<Row>(); val connection = mutableListOf<Row>()
        val active = DoctorValidation.origin(activeOrigin)
        if (active != null) {
            // Desktop identity; all other shared sentences use the iOS English keys.
            connection += row("This computer → %@", serverName?.takeIf { HostValidation.serverName(it) } ?: active.host)
            connection += row("Server: %@, port %@.", active.host, active.port)
            connection += row(pathUnknown)
        } else connection += row("The connection path is not yet known.")
        if (a == null) return Snapshot(listOf(row(unsupported)), emptyList(), connection + row("You manage this server. Its installation location is not yet known."))
        if (a.schema != 1) return Snapshot(listOf(row(unavailable)), emptyList(), connection + row(unavailable))
        val p = a.publicAddress
        if (p != null) {
            internet += row("Address: %@", p.host); internet += row("Port: %@", p.port)
            internet += row("To connect: %@", p.origin); internet += row("You can give this address to other people.")
        } else internet += row(if (a.publicInvalid) "The address is invalid. Correct it in the server settings." else "The internet address is not configured yet.")
        when (a.external.state) {
            "unverified" -> if (a.external.source == "installer_config" && a.external.address != null) {
                internet += row("External IP for calls: %@", a.external.address)
                internet += row("Set during installation. Not checked now.")
            } else internet += row(unavailable)
            "unknown", "invalid", null -> internet += row("The external IP is not known yet.")
            else -> internet += row(unavailable)
        }
        var tunnel = false
        when (a.ingress) {
            "tunnel" -> {
                val provider = mapOf("tailscale_funnel" to "Tailscale Funnel", "cloudflare" to "Cloudflare Tunnel")[a.provider]
                if (provider != null && p != null) {
                    internet += row("Entry through %@: %@, port %@.", provider, p.host, p.port)
                    internet += row("The entry IP belongs to the tunnel service."); tunnel = true
                } else internet += row(unavailable)
            }
            "direct", null -> Unit
            else -> internet += row(unavailable)
        }
        val c = a.calls
        if (c.turnPort != null && c.relayMin != null && c.relayMax != null && c.relayMin <= c.relayMax &&
            !c.transports.isNullOrEmpty() && c.transports.all { it in setOf("udp", "tcp") } && c.relayProtocol in setOf("udp", "tcp")) {
            val key = when (c.transports) {
                setOf("udp", "tcp") -> if (c.relayProtocol == "udp") "Personal call ports: %@ UDP and TCP; %@–%@ UDP." else "Personal call ports: %@ UDP and TCP; %@–%@ TCP."
                setOf("udp") -> if (c.relayProtocol == "udp") "Personal call ports: %@ UDP; %@–%@ UDP." else "Personal call ports: %@ UDP; %@–%@ TCP."
                else -> if (c.relayProtocol == "udp") "Personal call ports: %@ TCP; %@–%@ UDP." else "Personal call ports: %@ TCP; %@–%@ TCP."
            }
            internet += row(key, c.turnPort, c.relayMin, c.relayMax); internet += row("Calls need all of these rules.")
        } else internet += row(if (c.relayProtocol?.let { it !in setOf("udp", "tcp") } == true || c.transports?.any { it !in setOf("udp", "tcp") } == true) unavailable else "Call ports are not configured yet.")
        internet += row(when (a.rooms) { true -> "Room ports: 17881 TCP; 17882 UDP."; false -> "Rooms are disabled on this server."; null -> unavailable })
        if (tunnel) internet += row("Messages work through this tunnel. Calls may fail to connect.")
        var homeCopy: String? = null
        if (a.hosting == "vps" || a.lan.state == "not_applicable") home += row("This server is in a data centre. There is no home address for phones.")
        else when (a.lan.state) {
            "detected" -> {
                val l = a.lan
                if (l.source != null && l.source != "default_route") home += row(unavailable)
                else if (l.address != null && l.port != null && l.source == "default_route") {
                    home += row("PC address: %@", l.address); home += row("Port: %@", l.port)
                    home += row("Detected automatically on the PC.")
                    l.observedAt?.let { home += row("Detected during the check at %@.", formatTime(it)) }
                    if (l.observedAt == null || l.observedAt < now.minusSeconds(86400) || l.observedAt > now.plusSeconds(300))
                        home += row("The home address may have changed. Update it in the PC app.")
                    home += row("The home address was found. Secure home access is not configured yet.")
                    if (p != null) homeCopy = "${p.origin}\n${p.host}:${l.port}\n${DoctorValidation.bracket(l.address)}:${l.port}"
                } else home += row("The home address may have changed. Update it in the PC app.")
            }
            "unknown", null -> home += row("Home address not found. Connect the PC to your router and check again.")
            "invalid" -> home += row("The home address may have changed. Update it in the PC app.")
            else -> home += row(unavailable)
        }
        connection += row(when (a.hosting) {
            "home_pc" -> "This is your server on a PC. The PC must stay on."
            "vps" -> "This is your server in a data centre."
            "unknown", null -> "You manage this server. Its installation location is not yet known."
            else -> unavailable
        })
        return Snapshot(internet, home, connection, p?.let { "https://${it.host}:${it.port}" }, homeCopy)
    }
}
