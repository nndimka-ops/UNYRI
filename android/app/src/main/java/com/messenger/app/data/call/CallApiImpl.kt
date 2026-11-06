package com.messenger.app.data.call

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.CallDto
import com.messenger.app.data.api.CreateCallRequestDto
import com.messenger.app.data.api.IceServerDto
import com.messenger.app.data.api.IceServersResponseDto
import com.messenger.app.data.api.parseApiException
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** Real REST client for `/calls` endpoints - fetches a fresh access token
 *  per call (via [AuthRepository.currentAccessToken], which transparently
 *  refreshes) rather than caching one, same pattern as ProfileRepositoryImpl. */
class CallApiImpl(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
    private val authRepository: AuthRepository,
) : CallApi {

    suspend fun history(peer: String? = null, before: String? = null, expectedEpoch: Int? = null): com.messenger.app.data.api.CallHistoryPage {
        val url = "${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/calls/history".toHttpUrl().newBuilder()
        peer?.let { url.addQueryParameter("other_user_id", it) }
        before?.let { url.addQueryParameter("before", it) }
        return json.decodeFromString(execute(authorized(url.build().toString(), expectedEpoch).get().build()))
    }

    suspend fun personalClears(expectedEpoch: Int): List<com.messenger.app.data.api.ChatsApi.PersonalClear> = json.decodeFromString(execute(
        authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/cleared", expectedEpoch).get().build()))

    suspend fun markRead(id: String, expectedEpoch: Int) {
        require(runCatching { java.util.UUID.fromString(id) }.isSuccess)
        execute(authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/calls/$id/read", expectedEpoch)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    suspend fun historyDeletions(after: Long, expectedEpoch: Int? = null): CallDeletionPage = json.decodeFromString(execute(
        authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/calls/history/deletions?after=$after", expectedEpoch).get().build()))

    suspend fun deleteHistory(id: String, everyone: Boolean, expectedEpoch: Int) {
        require(runCatching { java.util.UUID.fromString(id) }.isSuccess)
        execute(authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/calls/$id/history/delete", expectedEpoch)
            .post("{\"everyone\":$everyone}".toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    override suspend fun createCall(calleeUserId: String, mediaKind: String): CallDto {
        val body = json.encodeToString(CreateCallRequestDto.serializer(), CreateCallRequestDto(calleeUserId, mediaKind))
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/calls").post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        return json.decodeFromString(execute(request))
    }

    override suspend fun getActiveCall(): CallDto? {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/calls/active").get().build()
        val raw = execute(request)
        return if (raw == "null") null else json.decodeFromString(raw)
    }

    override suspend fun getCall(callId: String): CallDto {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/calls/$callId").get().build()
        return json.decodeFromString(execute(request))
    }

    override suspend fun accept(callId: String): CallDto = postAction(callId, "accept")
    override suspend fun reject(callId: String): CallDto = postAction(callId, "reject")
    override suspend fun cancel(callId: String): CallDto = postAction(callId, "cancel")
    override suspend fun hangup(callId: String): CallDto = postAction(callId, "hangup")
    override suspend fun markConnected(callId: String): CallDto = postAction(callId, "connected")
    override suspend fun markFailed(callId: String): CallDto = postAction(callId, "failed")

    override suspend fun markFailedIfCurrent(callId: String, isCurrent: () -> Boolean): CallDto? {
        val epoch = authRepository.currentSessionEpoch()
        if (!isCurrent()) return null
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/calls/$callId/failed", epoch)
            .post("".toRequestBody(JSON_MEDIA_TYPE)).build()
        // currentAccessToken may have suspended for a refresh while the user
        // signed out/switched server. Never send this call with the new token.
        if (!isCurrent()) return null
        return json.decodeFromString(execute(request))
    }

    override suspend fun iceServers(): List<IceServerDto> {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/calls/ice-servers").get().build()
        val response: IceServersResponseDto = json.decodeFromString(execute(request))
        return response.iceServers
    }

    private suspend fun postAction(callId: String, action: String): CallDto {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/calls/$callId/$action")
            .post("".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeFromString(execute(request))
    }

    private suspend fun authorized(url: String, expectedEpoch: Int? = null): Request.Builder {
        val token = authRepository.currentAccessToken()
            ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        if (expectedEpoch != null && authRepository.currentSessionEpoch() != expectedEpoch) throw kotlinx.coroutines.CancellationException("Session changed")
        return Request.Builder().url(url).header("Authorization", "Bearer $token")
    }

    private suspend fun execute(request: Request): String = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                val kind = when {
                    call.isCanceled() -> "cancelled"
                    e is java.net.UnknownHostException -> "dns"
                    e is javax.net.ssl.SSLException -> "tls"
                    e is java.net.SocketTimeoutException -> "timeout"
                    e is java.net.ConnectException || e is java.net.SocketException -> "connection"
                    e is java.io.InterruptedIOException -> "interrupted"
                    else -> "other"
                }
                android.util.Log.i("CallNetwork", "io=${e.javaClass.simpleName} cause=${e.cause?.javaClass?.simpleName ?: "none"} kind=$kind")
                continuation.resumeWith(Result.failure(AuthApiException.Network(e.message ?: "Network error")))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val result = runCatching {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) throw parseApiException(json, it.code, body)
                        body
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }
}

@kotlinx.serialization.Serializable
data class CallDeletion(val sequence: Long, @kotlinx.serialization.SerialName("call_id") val callId: String)
@kotlinx.serialization.Serializable
data class CallDeletionPage(val items: List<CallDeletion>, @kotlinx.serialization.SerialName("has_more") val hasMore: Boolean)
@kotlinx.serialization.Serializable
internal data class CallDeletionState(val cursor: Long = 0, val ids: Set<String> = emptySet())
