package com.messenger.app.data.auth

import com.messenger.app.data.api.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.UUID

/** Fresh proof per HTTP attempt, with at most one server-clock correction. No timer or polling. */
internal suspend fun deviceBoundPost(client: OkHttpClient, json: Json, origin: String, path: String,
    body: String, binding: DeviceSessionBinding?, session: String, token: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    // Automatic connection recovery would resend a consumed proof. App retries retain request_id and sign again.
    val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    suspend fun attempt(serverTime: Long?): String {
        val request = Request.Builder().url("${origin.trimEnd('/')}$path")
            .post(body.toRequestBody("application/json".toMediaType()))
        binding?.let { request.header(DeviceProofBuilder.HEADER, it.proof("POST", path, session, token, serverTime)) }
        return transport.executeCancellableApi(request.build(), json)
    }
    try { attempt(null) } catch (error: AuthApiException.Api) {
        if (binding == null || error.httpStatus != 401 || error.code != "device_proof_stale" || error.serverTime == null) throw error
        attempt(error.serverTime)
    }
}

internal suspend fun refreshDeviceSession(client: OkHttpClient, json: Json, origin: String,
    binding: DeviceSessionBinding?, token: String, requestId: String?,
    appVersion: String? = null, osVersion: String? = null): RefreshResponseDto {
    val request = RefreshRequestDto(token, requestId, binding?.key, appVersion, osVersion)
    val raw = deviceBoundPost(client, json, origin, "/auth/refresh",
        json.encodeToString(RefreshRequestDto.serializer(), request), binding, if (binding == null) "" else refreshSessionID(token), token)
    return json.decodeFromString(RefreshResponseDto.serializer(), raw)
}

internal fun refreshSessionID(token: String): String = canonicalSessionID(token.substringBefore('.'))
internal fun accessSessionID(token: String, json: Json): String {
    require(token.length <= 16384)
    val payload = token.split('.').takeIf { it.size == 3 }?.get(1) ?: error("Invalid access token")
    val claims = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).jsonObject
    return canonicalSessionID(claims["session_id"]?.jsonPrimitive?.content ?: error("Missing session"))
}
private fun canonicalSessionID(value: String): String = UUID.fromString(value).toString().also { require(it == value) }
