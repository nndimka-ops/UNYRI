package com.messenger.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

@Serializable
data class RegisterPushTokenRequestDto(
    val provider: String,
    val token: String,
    val platform: String? = null,
)

/** Thin REST client for the device-scoped push registration endpoint
 *  (`PUT/DELETE /devices/me/push-token`, Этап K.2). The device_id is always
 *  derived server-side from the access token, never sent in the body. */
class PushApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
) {
    suspend fun registerPushToken(accessToken: String, provider: String, token: String) {
        val body = json.encodeToString(RegisterPushTokenRequestDto.serializer(), RegisterPushTokenRequestDto(provider, token, "android"))
        val request = Request.Builder()
            .url("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/devices/me/push-token")
            .header("Authorization", "Bearer $accessToken")
            .put(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request)
    }

    suspend fun deletePushToken(accessToken: String) {
        val request = Request.Builder()
            .url("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/devices/me/push-token")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        execute(request)
    }

    private suspend fun execute(request: Request): Unit = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            throw AuthApiException.Network(e.message ?: "Network error")
        }
        response.use {
            if (!it.isSuccessful) {
                val bodyString = it.body?.string().orEmpty()
                throw parseApiException(json, it.code, bodyString)
            }
        }
    }
}
