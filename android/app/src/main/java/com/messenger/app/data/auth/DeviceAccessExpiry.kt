package com.messenger.app.data.auth

import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The server validates the JWT. Local scheduling uses its bounded lifetime, never phone/server clock agreement. */
internal object DeviceAccessExpiry {
    /** Matches the server's BOUND_ACCESS_TTL_SECONDS (300 s since 19 Nov). */
    const val BOUND_LIFETIME_CAP_SECONDS = 300L

    fun localExpiry(access: String, receivedAt: Instant = Instant.now()): Instant {
        require(access.length <= 16384)
        val parts = access.split('.')
        require(parts.size == 3)
        val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).jsonObject
        val issued = claims["iat"]?.jsonPrimitive?.longOrNull ?: error("Missing token issue time")
        val expires = claims["exp"]?.jsonPrimitive?.longOrNull ?: error("Missing token expiry")
        require(issued >= 0 && expires >= issued && expires <= 253402300799L)
        return receivedAt.plusSeconds((expires - issued).coerceAtMost(BOUND_LIFETIME_CAP_SECONDS))
    }
}
