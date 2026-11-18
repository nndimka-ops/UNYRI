package com.messenger.app.push

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.RefreshRequestDto
import com.messenger.app.data.api.RefreshResponseDto
import com.messenger.app.data.api.RegisterPushTokenRequestDto
import com.messenger.app.data.api.executeCancellableApi
import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal interface SavedPushSession {
    val refreshMutex: Mutex
    fun isCurrent(): Boolean
    fun accessToken(): String?
    fun expiresAt(): Instant?
    fun refreshToken(): String?
    fun refreshRequestId(): String
    fun saveTokens(access: String, expiry: Instant, refresh: String)
}

/** Fixed owner endpoint, no active-account interceptors and no credential-bearing redirects. */
internal class SavedAccountPushClient(client: OkHttpClient, private val json: Json) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun register(owner: SavedPushOwner, session: SavedPushSession, token: String) {
        fun current() = check(session.isCurrent()) { "Saved push owner changed" }
        suspend fun access(rejected: String? = null): String = session.refreshMutex.withLock {
            current()
            val cached = session.accessToken()
            val expiry = session.expiresAt()
            if (cached != null && expiry != null && Instant.now().isBefore(expiry.minusSeconds(60)) &&
                (rejected == null || rejected != cached)) return@withLock cached
            val refresh = session.refreshToken() ?: error("Saved session unavailable")
            val body = json.encodeToString(RefreshRequestDto.serializer(), RefreshRequestDto(refresh, session.refreshRequestId()))
            val response = client.executeCancellableApi(Request.Builder().url("${owner.account.origin}/auth/refresh")
                .post(body.toRequestBody("application/json".toMediaType())).build(), json)
            val renewed = json.decodeFromString(RefreshResponseDto.serializer(), response)
            current()
            check(session.refreshToken() == refresh) { "Saved session changed during refresh" }
            withContext(NonCancellable) {
                session.saveTokens(renewed.accessToken, Instant.parse(renewed.accessTokenExpiresAt), renewed.refreshToken)
            }
            renewed.accessToken
        }
        suspend fun upload(access: String) {
            current()
            val body = json.encodeToString(RegisterPushTokenRequestDto.serializer(), RegisterPushTokenRequestDto("fcm", token, "android"))
            client.executeCancellableApi(Request.Builder().url("${owner.account.origin}/devices/me/push-token")
                .header("Authorization", "Bearer $access").put(body.toRequestBody("application/json".toMediaType())).build(), json)
            current()
        }
        val first = access()
        try { upload(first) }
        catch (failure: AuthApiException.Api) {
            if (failure.httpStatus != 401 && failure.code != "unauthorized") throw failure
            upload(access(rejected = first))
        }
    }
}
