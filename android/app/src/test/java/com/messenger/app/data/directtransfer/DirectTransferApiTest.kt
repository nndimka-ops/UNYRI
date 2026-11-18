package com.messenger.app.data.directtransfer

import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.AuthUser
import com.messenger.app.domain.repository.AuthRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DirectTransferApiTest {
    @Test fun executionRevocationDuringSuspendingTokenPreparationPreventsActualHttpEnqueue() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(capability))
            val auth = FixtureAuth()
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            auth.beforeToken = { entered.complete(Unit); release.await() }
            val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
            val api = DirectTransferApi(OkHttpClient(), auth, server.url("/").toString().trimEnd('/'))
            api.executionAllowed = { _, _ -> allowed.get() }
            val request = async { runCatching { api.requireSupported(1) }.exceptionOrNull() }
            entered.await(); allowed.set(false); release.complete(Unit)
            assertTrue(request.await() is DirectOperationRetired)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals(0, server.requestCount)
        }
    }
    private val capability = """{"version":1,"max_file_bytes":68719476736,"direct_only":true}"""
    @Test fun actualTinyAndChunkedResponsesAreBoundedWithoutExactReadEof() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(capability))
            server.enqueue(MockResponse().setChunkedBody(capability, 7))
            val auth = FixtureAuth()
            val api = DirectTransferApi(OkHttpClient(), auth, server.url("/").toString().trimEnd('/'))
            api.requireSupported(1); api.requireSupported(1)
            assertEquals("/direct-transfers/capabilities", server.takeRequest().path)
            assertEquals("Bearer fixture-token", server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun declaredAndChunkedOversizeBodiesRejectedAtBoundary() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(4 * 1024 * 1024 + 1)))
            server.enqueue(MockResponse().setChunkedBody("x".repeat(4 * 1024 * 1024 + 1), 8192))
            val api = DirectTransferApi(OkHttpClient(), FixtureAuth(), server.url("/").toString().trimEnd('/'))
            assertTrue(runCatching { api.requireSupported(1) }.isFailure)
            assertTrue(runCatching { api.requireSupported(1) }.isFailure)
        }
    }
    @Test fun policyDenialAndRatePauseRemainTyped() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("{}"))
            val api = DirectTransferApi(OkHttpClient(), FixtureAuth(), server.url("/").toString().trimEnd('/'))
            val denied = runCatching { api.requireSupported(1) }.exceptionOrNull() as DirectTransferApiError
            assertEquals(403, denied.status)
            val limited = runCatching { api.requireSupported(1) }.exceptionOrNull() as DirectTransferApiError
            assertEquals(429, limited.status); assertEquals(120L, limited.retryAfterSeconds)
        }
    }
    @Test fun cancellationStopsActualHttpCallAndAccountChangeRejectsResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(capability).setBodyDelay(500, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody(capability).setBodyDelay(300, TimeUnit.MILLISECONDS))
            val auth = FixtureAuth()
            val client = OkHttpClient()
            val api = DirectTransferApi(client, auth, server.url("/").toString().trimEnd('/'))
            val cancelled = withTimeoutOrNull(100) { api.requireSupported(1); true }
            assertNull(cancelled)
            withTimeoutOrNull(2000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
            assertEquals(0, client.dispatcher.runningCallsCount())
            val next = async { runCatching { api.requireSupported(1) }.exceptionOrNull() }
            delay(50); auth.epoch = 2
            assertTrue(next.await() is CancellationException)
        }
    }

    private class FixtureAuth : AuthRepository {
        var epoch = 1
        var beforeToken: suspend () -> Unit = {}
        override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser("aaaaaaaa-1111-4111-8111-aaaaaaaaaaa1", "fixture", "Fixture")))
        override suspend fun currentAccessToken(): String { beforeToken(); return "fixture-token" }
        override fun currentDeviceId() = "bbbbbbbb-2222-4222-8222-bbbbbbbbbbb2"
        override fun currentSessionEpoch() = epoch
        override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? = if (epochAtStart == epoch) block() else null
        override suspend fun clearRoom(block: suspend () -> Unit) { block() }
        override suspend fun register(username: String, password: String, displayName: String) = Result.success(Unit)
        override suspend fun login(username: String, password: String) = Result.success(Unit)
        override suspend fun logout() = Unit
    }
}
