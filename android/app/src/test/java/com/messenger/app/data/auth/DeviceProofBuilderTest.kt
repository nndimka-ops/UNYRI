package com.messenger.app.data.auth

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.parseApiException
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DeviceProofBuilderTest {
    private val session = "11111111-1111-4111-8111-111111111111"
    private val jti = "22222222-2222-4222-8222-222222222222"
    private val timestamp = 1795089600L
    private fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun canonical(method: String = "POST", path: String = "/auth/refresh", token: String = "abc") =
        DeviceProofBuilder.canonical(method, path, session, timestamp, jti, token)

    @Test fun `canonical contract has fixed order exact token hash and no trailing newline`() {
        assertEquals("UNYRI-DBS-1\nPOST\n/auth/refresh\n$session\n$timestamp\n$jti\nungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", String(canonical(), UTF_8))
        assertEquals("47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU",
            String(canonical(path = "/auth/login", token = ""), UTF_8).substringAfterLast('\n'))
    }

    @Test fun `proof verifies with real P256 DER signature and rejects another device or context`() {
        val device = key()
        val header = DeviceProofBuilder.proof("POST", "/auth/refresh", session, timestamp, "abc", jti) { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(device.private); update(bytes); sign() }
        }
        assertFalse(header.contains('='))
        val envelope = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(header), UTF_8)).jsonObject
        assertEquals(timestamp.toString(), envelope.getValue("ts").jsonPrimitive.content)
        assertEquals(jti, envelope.getValue("jti").jsonPrimitive.content)
        val signature = Base64.getUrlDecoder().decode(envelope.getValue("sig").jsonPrimitive.content)
        fun verifies(bytes: ByteArray, publicKey: java.security.PublicKey = device.public): Boolean =
            Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(bytes); verify(signature) }
        assertTrue(verifies(canonical()))
        assertFalse(verifies(canonical(), key().public))
        assertFalse(verifies(canonical(token = "stolen-other-token")))
        assertFalse(verifies(canonical(path = "/auth/login")))
        assertFalse(verifies(canonical(method = "GET", path = "ws")))
        assertFalse(verifies(DeviceProofBuilder.canonical("POST", "/auth/refresh", session, timestamp + 1, jti, "abc")))
        assertFalse(verifies(DeviceProofBuilder.canonical("POST", "/auth/refresh", jti, timestamp, session, "abc")))
    }

    @Test fun `retry creates a fresh random replay id even for same token and timestamp`() {
        val device = key()
        fun proof() = DeviceProofBuilder.proof("POST", "/auth/refresh", session, timestamp, "abc") { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(device.private); update(bytes); sign() }
        }
        fun id(header: String) = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(header), UTF_8)).jsonObject.getValue("jti").jsonPrimitive.content
        val ids = (1..100).map { id(proof()) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `canonicalization refuses newline injection arbitrary paths methods and noncanonical ids`() {
        for ((method, path, sid, nonce) in listOf(
            listOf("post", "/auth/refresh", session, jti),
            listOf("POST", "/auth/logout", session, jti),
            listOf("GET", "/auth/refresh", session, jti),
            listOf("POST", "ws", session, jti),
            listOf("POST", "/auth/refresh", "$session\nextra", jti),
            listOf("POST", "/auth/refresh", "1-1-1-1-1", jti),
            listOf("POST", "/auth/refresh", session, "$jti\nextra"),
        )) {
            try { DeviceProofBuilder.canonical(method, path, sid, timestamp, nonce, "abc"); fail("Invalid context accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `server clock challenge preserves session and parses exact server seconds`() {
        val error = parseApiException(Json { ignoreUnknownKeys = true }, 401,
            """{"error":{"code":"device_proof_stale","message":"","server_time":1795089600}}""") as AuthApiException.Api
        assertEquals(timestamp, error.serverTime)
        assertFalse(isTerminalAuthFailure(error))
        assertTrue(isTerminalAuthFailure(AuthApiException.Api("session_revoked", "", 401)))
    }
}
