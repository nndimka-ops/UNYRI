package com.messenger.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.data.secure.SecureTokenStore
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.MediaType.Companion.toMediaType

@RunWith(AndroidJUnit4::class)
class SessionPersistenceInstrumentedTest {
    @Test fun invalidRefreshDeletesCacheBeforeReauthentication() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "isolated-reauth-${UUID.randomUUID()}"
        try {
            val store = SecureTokenStore(context, name)
            store.saveSession("expired", Instant.EPOCH, "expired-refresh", "owner", "owner", "Owner", "device")
            var loginOwner = "owner"
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val refresh = chain.request().url.encodedPath.endsWith("/refresh")
                val body = if (refresh) """{"error":{"code":"invalid_refresh_token","message":"expired"}}"""
                    else json.encodeToString(com.messenger.app.data.api.AuthResponseDto.serializer(),
                        com.messenger.app.data.api.AuthResponseDto("access", Instant.now().plusSeconds(600).toString(), "refresh", "device",
                            com.messenger.app.data.api.UserDto(loginOwner, loginOwner, loginOwner)))
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(if (refresh) 401 else 200).message("test")
                    .body(okhttp3.ResponseBody.create("application/json".toMediaType(), body)).build()
            }.build()
            var clears = 0
            var failClear = false
            val repository = com.messenger.app.data.auth.AuthRepositoryImpl(
                com.messenger.app.data.api.AuthApi(client, json, "http://127.0.0.1"), store, clearAccountData = {
                    if (failClear) throw java.io.IOException("isolated cleanup failure")
                    clears++
                })
            val initialEpoch = repository.currentSessionEpoch()
            assertNull(repository.currentAccessToken())
            assertEquals(1, clears)
            assertNull(store.cacheOwnerId())
            assertEquals(com.messenger.app.domain.model.AuthSessionState.LoggedOut, repository.authState.value)
            assertTrue(repository.login("owner", "password").isSuccess)
            assertEquals(2, clears)
            assertTrue(repository.currentSessionEpoch() > initialEpoch)
            loginOwner = "other"
            assertTrue(repository.login("other", "password").isSuccess)
            assertEquals(3, clears)
            repository.logout()
            assertEquals(4, clears)
            assertNull(store.cacheOwnerId())
            assertFalse(repository.cacheCleanupFailed.value)
            failClear = true
            repository.logout()
            assertTrue(repository.cacheCleanupFailed.value)
            assertTrue(SecureTokenStore(context, name).cacheCleanupPending())
            assertNull(store.refreshToken())
            assertEquals(com.messenger.app.domain.model.AuthSessionState.LoggedOut, repository.authState.value)
            failClear = false
            repository.logout()
            assertFalse(repository.cacheCleanupFailed.value)
            assertFalse(SecureTokenStore(context, name).cacheCleanupPending())
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun refreshIdentityAndSessionSnapshotAreDurable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "isolated-session-${UUID.randomUUID()}"
        try {
            val store = SecureTokenStore(context, name)
            store.saveSession("access", Instant.now().plusSeconds(600), "refresh", "owner", "name", "Name", "device")
            val attempt = store.refreshRequestId()
            val reopened = SecureTokenStore(context, name)
            assertEquals(attempt, reopened.refreshRequestId())
            assertEquals("owner", reopened.userId())
            assertEquals("device", reopened.deviceId())
            reopened.saveTokens("access-2", Instant.now().plusSeconds(600), "refresh-2")
            val rotated = SecureTokenStore(context, name)
            assertEquals("refresh-2", rotated.refreshToken())
            assertNotEquals(attempt, rotated.refreshRequestId())
            rotated.clearSession()
            assertNull(SecureTokenStore(context, name).refreshToken())
            assertNull(SecureTokenStore(context, name).userId())
        } finally { context.deleteSharedPreferences(name) }
    }
}
