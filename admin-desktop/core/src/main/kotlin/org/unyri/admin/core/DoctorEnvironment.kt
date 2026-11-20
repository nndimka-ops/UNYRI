package org.unyri.admin.core

import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Narrow reader for an already-owned installation. Never source .env or use Compose config output. */
object DoctorEnvironment {
    // The last three nonsecret keys supply legacy origin, rooms and effective Docker subnet.
    // Installers derive PUBLIC_API_ORIGIN from UNYRI_DOMAIN in the canonical Compose file.
    val keys = setOf("UNYRI_LAN_ADDRESS", "UNYRI_LAN_PORT", "UNYRI_LAN_DETECTED_AT", "UNYRI_HOSTING_KIND",
        "UNYRI_INGRESS_MODE", "UNYRI_TUNNEL_PROVIDER", "PUBLIC_API_ORIGIN", "TURN_EXTERNAL_IP",
        "UNYRI_DOMAIN", "COMPOSE_PROFILES", "UNYRI_SUBNET")

    fun read(root: Path): ConnectionDoctorAddresses = Files.newBufferedReader(root.resolve(".env")).use {
        read(it, fixedManifest = !Files.exists(root.resolve(".selfhost-local-trial")))
    }

    fun read(reader: Reader, fixedManifest: Boolean = true): ConnectionDoctorAddresses {
        val values = mutableMapOf<String, String>()
        val seen = mutableSetOf<String>()
        // Bound the entire file and each line without materializing secret lines or values.
        // Only the prefix before '=' is buffered until its key passes the allowlist.
        var count = 0; var prefix = StringBuilder(); var value = StringBuilder()
        var key: String? = null; var inValue = false; var discard = false
        fun finish() {
            key?.let { k ->
                check(seen.add(k)) { "Duplicate address setting" }
                if (!discard) values[k] = value.toString().trim()
            }
            prefix = StringBuilder(); value = StringBuilder(); key = null; inValue = false; discard = false
        }
        while (true) {
            val n = reader.read()
            if (n < 0) { finish(); break }
            check(++count <= 262144) { "Installation settings too large" }
            val c = n.toChar()
            if (c == '\n') { finish(); continue }
            if (!inValue) {
                if (c == '=') { inValue = true; key = prefix.toString().takeIf { it in keys }; discard = key == null }
                else if (prefix.length < 40) prefix.append(c) else discard = true
            } else if (!discard) {
                check(value.length < 2048) { "Address setting too large" }
                value.append(c)
            }
        }
        return snapshot { requested -> check(requested in keys); values[requested] }
            .let { if (fixedManifest) it else it.copy(calls = ConnectionDoctorAddresses.Calls(), rooms = false) }
    }

    /** Lookup seam verifies the exact allowlist without ever asking for credentials. */
    fun snapshot(get: (String) -> String?): ConnectionDoctorAddresses {
        val v = keys.associateWith { get(it)?.trim()?.takeIf(String::isNotEmpty) }
        fun choice(key: String, vararg allowed: String) = v[key]?.takeIf { it in allowed }
        val rawOrigin = v["PUBLIC_API_ORIGIN"] ?: v["UNYRI_DOMAIN"]?.let { "https://$it" }
        val public = DoctorValidation.origin(rawOrigin)
        val externalRaw = v["TURN_EXTERNAL_IP"]
        val external = DoctorValidation.publicIP(externalRaw)
        val hosting = choice("UNYRI_HOSTING_KIND", "home_pc", "vps", "unknown")
        val lan = DoctorValidation.lanIP(v["UNYRI_LAN_ADDRESS"], v["UNYRI_SUBNET"])
        val port = DoctorValidation.port(v["UNYRI_LAN_PORT"])
        val time = DoctorValidation.time(v["UNYRI_LAN_DETECTED_AT"])
        val state = when {
            hosting == "vps" -> "not_applicable"
            v["UNYRI_LAN_ADDRESS"] != null && lan == null || v["UNYRI_LAN_PORT"] != null && port == null ||
                v["UNYRI_LAN_DETECTED_AT"] != null && time == null -> "invalid"
            lan != null && port != null && time != null -> "detected"
            else -> "unknown"
        }
        val ingress = choice("UNYRI_INGRESS_MODE", "direct", "tunnel")
        return ConnectionDoctorAddresses(publicAddress = public, publicInvalid = rawOrigin != null && public == null,
            external = ConnectionDoctorAddresses.IP(external, external?.let { "installer_config" },
                if (external != null) "unverified" else if (externalRaw != null) "invalid" else "unknown"),
            lan = if (state == "not_applicable") ConnectionDoctorAddresses.LAN(state = state)
                else ConnectionDoctorAddresses.LAN(lan, port, lan?.let { "default_route" }, time, state),
            // Effective ports of the supported installer-owned docker-compose.selfhost.yml profile.
            calls = ConnectionDoctorAddresses.Calls(3478, setOf("udp", "tcp"), 49152, 49200, "udp"),
            rooms = when (v["COMPOSE_PROFILES"]) { null -> false; "rooms" -> true; else -> null },
            ingress = ingress, provider = if (ingress == "tunnel") choice("UNYRI_TUNNEL_PROVIDER", "tailscale_funnel", "cloudflare") else null,
            hosting = hosting)
    }
}

/** Reserved fixture only. The caller captures time once per open/refresh, never in a row. */
object DoctorDemo {
    fun addresses(now: Instant) = ConnectionDoctorAddresses(
        publicAddress = DoctorValidation.origin("https://home.example.test"),
        external = ConnectionDoctorAddresses.IP("203.0.113.8", "installer_config", "unverified"),
        lan = ConnectionDoctorAddresses.LAN("192.168.0.10", 443, "default_route", now.minusSeconds(180), "detected"),
        calls = ConnectionDoctorAddresses.Calls(3478, setOf("udp", "tcp"), 49152, 49200, "udp"),
        rooms = false, ingress = "direct", hosting = "home_pc")
}
