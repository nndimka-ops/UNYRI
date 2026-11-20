package com.messenger.app.data.auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Platform-independent wire contract. The signer receives bytes, never a private key. */
object DeviceProofBuilder {
    const val HEADER = "X-UNYRI-Device-Proof"
    fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun canonical(method: String, target: String, session: String, timestamp: Long, jti: String, token: String): ByteArray {
        require(method in setOf("POST", "GET"))
        require(target in setOf("/auth/login", "/auth/register", "/auth/refresh", "ws"))
        require((target == "ws") == (method == "GET"))
        require(UUID.fromString(session).toString() == session)
        require(UUID.fromString(jti).toString() == jti)
        val digest = base64(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(UTF_8)))
        return listOf("UNYRI-DBS-1", method, target, session, timestamp.toString(), jti, digest).joinToString("\n").toByteArray(UTF_8)
    }

    fun proof(method: String, target: String, session: String, timestamp: Long, token: String,
              jti: String = UUID.randomUUID().toString(), sign: (ByteArray) -> ByteArray): String {
        val signature = sign(canonical(method, target, session, timestamp, jti, token))
        require(signature.size in 8..80)
        return base64("{\"ts\":$timestamp,\"jti\":\"$jti\",\"sig\":\"${base64(signature)}\"}".toByteArray(UTF_8))
    }
}
