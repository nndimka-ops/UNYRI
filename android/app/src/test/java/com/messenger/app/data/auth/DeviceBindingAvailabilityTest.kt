package com.messenger.app.data.auth

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.DeviceKeyDto
import com.messenger.app.data.api.LoginRequestDto
import com.messenger.app.data.api.RegisterRequestDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DeviceBindingAvailabilityTest {
    @Test fun `software only sign in remains unbound throughout the saved session`() {
        var unavailable = false
        var creations = 0
        fun create(): DeviceKeyDto { creations++; throw HardwareDeviceKeyUnavailableException() }
        val key = DeviceBindingAvailability.createOrUnbound(::create) { unavailable = true }
        assertNull(key)
        assertFalse(isTerminalAuthFailure(HardwareDeviceKeyUnavailableException()))
        val login = LoginRequestDto("private-login", "password", "installation", deviceKey = key)
        val signup = RegisterRequestDto("private-login", "password", "Name", "installation", deviceKey = key)
        assertFalse(Json.parseToJsonElement(Json.encodeToString(LoginRequestDto.serializer(), login)).jsonObject.containsKey("device_key"))
        assertFalse(Json.parseToJsonElement(Json.encodeToString(RegisterRequestDto.serializer(), signup)).jsonObject.containsKey("device_key"))
        // Reconstruct the policy to model reopening the same persisted session.
        repeat(4) {
            val saved = DeviceBindingAvailability({ null }, { unavailable }, ::create) { unavailable = true }
            assertNull(saved.binding(createForLegacy = true))
            assertTrue(canReuseDeviceAccessToken(false, unavailable))
        }
        assertEquals(1, creations)
        // A new explicit sign-in probes hardware again, independent of the saved outcome.
        assertNull(DeviceBindingAvailability.createOrUnbound(::create) { unavailable = true })
        assertEquals(2, creations)
    }

    @Test fun `first software only legacy refresh attempts creation once`() {
        var unavailable = false
        var creations = 0
        fun session() = DeviceBindingAvailability<DeviceKeyDto>(
            openEnrolled = { null }, hardwareUnavailable = { unavailable },
            create = { creations++; throw HardwareDeviceKeyUnavailableException() },
            rememberUnavailable = { unavailable = true },
        )
        assertNull(session().binding(createForLegacy = false))
        assertEquals(0, creations)
        repeat(4) { assertNull(session().binding(createForLegacy = true)) }
        assertEquals(1, creations)
        assertTrue(canReuseDeviceAccessToken(false, unavailable))
        assertFalse(canReuseDeviceAccessToken(false, false))
    }

    @Test fun `missing enrolled key remains terminal without fallback creation`() {
        var creations = 0
        var unavailable = false
        val missingKey = AuthApiException.Api("session_revoked", "Session key unavailable", 401)
        val session = DeviceBindingAvailability<DeviceKeyDto>(
            openEnrolled = { throw missingKey }, hardwareUnavailable = { unavailable },
            create = { creations++; error("Must never enroll a replacement key") },
            rememberUnavailable = { unavailable = true },
        )
        try { session.binding(createForLegacy = true); fail("Missing key accepted") }
        catch (failure: AuthApiException.Api) {
            assertSame(missingKey, failure)
            assertTrue(isTerminalAuthFailure(failure))
        }
        assertEquals(0, creations)
        assertFalse(unavailable)
    }

    @Test fun `unexpected key creation failures do not mark hardware unavailable`() {
        var unavailable = false
        val failure = IllegalStateException("Storage failed")
        try {
            DeviceBindingAvailability.createOrUnbound<DeviceKeyDto>({ throw failure }) { unavailable = true }
            fail("Unexpected key failure swallowed")
        } catch (error: IllegalStateException) { assertSame(failure, error) }
        assertFalse(unavailable)
    }

    @Test fun `server requiring hardware gets a distinct terminal presentation code`() {
        val revoked = AuthApiException.Api("session_revoked", "Revoked", 401)
        val required = deviceBindingFailure(revoked, true) as AuthApiException.Api
        assertEquals(HARDWARE_BINDING_REQUIRED, required.code)
        assertTrue(isTerminalAuthFailure(required))
        assertEquals(401, required.httpStatus)
        assertSame(revoked, deviceBindingFailure(revoked, false))
        val deleted = AuthApiException.Api("account_deleted", "Deleted", 401)
        assertSame(deleted, deviceBindingFailure(deleted, true))
        assertEquals(HARDWARE_BINDING_REQUIRED, deviceBindingFailureCode("session_revoked", true))
    }

    @Test fun `software only sign in sign up and refresh send no key or proof without using the network`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"access_token":"next-access","access_token_expires_at":"2026-11-19T12:05:00Z","refresh_token":"next-refresh"}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        var unavailable = false
        val session = DeviceBindingAvailability<DeviceSessionBinding>(
            openEnrolled = { null }, hardwareUnavailable = { unavailable },
            create = { throw HardwareDeviceKeyUnavailableException() }, rememberUnavailable = { unavailable = true },
        )
        val binding = session.binding(true)
        val login = LoginRequestDto("private-login", "password", "installation", deviceKey = binding?.key)
        deviceBoundPost(client, Json, "https://example.invalid", "/auth/login",
            Json.encodeToString(LoginRequestDto.serializer(), login), binding, "installation", "")
        val signup = RegisterRequestDto("private-login", "password", "Name", "installation", deviceKey = binding?.key)
        deviceBoundPost(client, Json, "https://example.invalid", "/auth/register",
            Json.encodeToString(RegisterRequestDto.serializer(), signup), binding, "installation", "")
        val response = refreshDeviceSession(client, Json, "https://example.invalid", session.binding(true), "old-refresh", "attempt")
        assertEquals("next-refresh", response.refreshToken)
        assertEquals(listOf("/auth/login", "/auth/register", "/auth/refresh"), requests.map { it.url.encodedPath })
        for (request in requests) {
            assertNull(request.header(DeviceProofBuilder.HEADER))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertFalse(Json.parseToJsonElement(body).jsonObject.containsKey("device_key"))
        }
        assertTrue(unavailable)
    }
}
