package com.messenger.app.data.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** The HTTP callback reads bytes off-thread, but its caller may resume on Main.
 * History responses can contain many encrypted envelopes; decode away from UI
 * while retaining withContext's cancellation check before publishing a result. */
internal suspend inline fun <reified T> Json.decodeApiResponse(body: String): T =
    withContext(Dispatchers.Default) { decodeFromString<T>(body) }

/** Used by the message outbox's HTTP dependencies, so pausing cannot wait for socket timeout. */
internal suspend fun OkHttpClient.executeCancellableApi(request: Request, json: Json): String = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
            continuation.resumeWith(Result.failure(AuthApiException.Network(error.message ?: "Network error")))
        }
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            val result = runCatching {
                response.use {
                    if (!continuation.isActive) throw kotlinx.coroutines.CancellationException("API request cancelled")
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) throw parseApiException(json, it.code, body)
                    body
                }
            }.recoverCatching { error ->
                if (error is java.io.IOException) throw AuthApiException.Network(error.message ?: "Network error")
                throw error
            }
            continuation.resumeWith(result)
        }
    })
}
