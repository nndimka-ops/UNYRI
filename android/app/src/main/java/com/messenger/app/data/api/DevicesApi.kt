package com.messenger.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** REST client for device management + security events (Этап L). */
class DevicesApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
) {
    private val installationClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    /** Associate only the JWT's current device; callers fence origin/account/epoch. */
    suspend fun bindInstallationCredential(origin: String, accessToken: String, credential: String) {
        require(java.net.URI(origin).scheme == "https") { "Installation protection requires HTTPS" }
        val body = json.encodeToString(InstallationCredentialDto.serializer(), InstallationCredentialDto(credential))
        installationClient.executeCancellableApi(authorized("${origin.trimEnd('/')}/devices/me/installation", accessToken)
            .post(body.toRequestBody(JSON_MEDIA_TYPE)).build(), json)
    }

    suspend fun listDevices(accessToken: String): DeviceListResponseDto {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/devices", accessToken).get().build()
        return json.decodeFromString(execute(request))
    }

    suspend fun renameDevice(accessToken: String, deviceId: String, displayName: String): DeviceDto {
        val body = json.encodeToString(RenameDeviceRequestDto.serializer(), RenameDeviceRequestDto(displayName))
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/devices/$deviceId", accessToken).patch(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        return json.decodeFromString(execute(request))
    }

    suspend fun revokeDevice(accessToken: String, deviceId: String) {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/devices/$deviceId", accessToken).delete().build()
        execute(request)
    }

    suspend fun revokeOtherDevices(accessToken: String): RevokeOthersResponseDto {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/devices/revoke-others", accessToken).post("".toRequestBody()).build()
        return json.decodeFromString(execute(request))
    }

    suspend fun securityEvents(accessToken: String, limit: Int = 50, cursor: Long? = null): SecurityEventsResponseDto {
        val url = buildString {
            append("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/security/events?limit=$limit")
            if (cursor != null) append("&cursor=$cursor")
        }
        val request = authorized(url, accessToken).get().build()
        return json.decodeFromString(execute(request))
    }

    private fun authorized(url: String, accessToken: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            throw AuthApiException.Network(e.message ?: "Network error")
        }
        response.use {
            val bodyString = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw parseApiException(json, it.code, bodyString)
            }
            bodyString
        }
    }
}
