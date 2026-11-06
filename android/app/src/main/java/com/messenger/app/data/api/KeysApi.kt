package com.messenger.app.data.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** REST client for the Этап F Signal Protocol key endpoints. The server
 *  never parses or verifies this material - it only stores/relays opaque
 *  base64 bytes, so this client never logs request/response bodies. */
class KeysApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
) {
    suspend fun uploadKeys(accessToken: String, request: UploadKeysRequestDto): UploadKeysResponseDto {
        val body = json.encodeToString(UploadKeysRequestDto.serializer(), request)
        val httpRequest = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/keys", accessToken).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        return json.decodeFromString(execute(httpRequest))
    }

    suspend fun getBundles(accessToken: String, userId: String): UserBundlesResponseDto {
        val httpRequest = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/keys/bundles/$userId", accessToken).get().build()
        return json.decodeFromString(execute(httpRequest))
    }

    /** Fresh identities and active devices, without consuming one-time keys. */
    suspend fun getDeviceBundles(accessToken: String, userId: String): UserBundlesResponseDto {
        val httpRequest = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/keys/devices/$userId", accessToken).get().build()
        return json.decodeFromString(execute(httpRequest))
    }

    /** Consumes a prekey for precisely one fresh, active recipient; no batch fallback. */
    suspend fun getTargetBundle(accessToken: String, userId: String, deviceId: String): UserBundlesResponseDto {
        require(java.util.UUID.fromString(userId).toString() == userId && java.util.UUID.fromString(deviceId).toString() == deviceId)
        val httpRequest = authorized("${ServerEndpoint.resolve(baseUrl)}/keys/bundles/$userId/$deviceId", accessToken).get().build()
        return json.decodeFromString(execute(httpRequest))
    }

    suspend fun oneTimePrekeyCount(accessToken: String): OneTimePreKeyCountResponseDto {
        val httpRequest = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/keys/one-time-count", accessToken).get().build()
        return json.decodeFromString(execute(httpRequest))
    }

    private fun authorized(url: String, accessToken: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    private suspend fun execute(request: Request): String = client.executeCancellableApi(request, json)
}
