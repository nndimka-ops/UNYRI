package com.messenger.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** The correctness-path REST client: `GET /sync` - forward catch-up over
 *  the global device-scoped cursor. See GlobalSyncManager for how this is
 *  driven (startup, WS reconnect, mutex-guarded so at most one runs at a
 *  time). */
class SyncApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
) : SyncSource {
    override suspend fun sync(accessToken: String, afterSequence: Long, limit: Int): SyncResponseDto {
        val url = "${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/sync".toHttpUrl().newBuilder()
            .addQueryParameter("after_sequence", afterSequence.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
        return json.decodeFromString(execute(request))
    }

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
