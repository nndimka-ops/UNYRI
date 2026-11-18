package com.messenger.app.push

import com.messenger.app.data.api.AuthApiException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class SavedAccountPushClientTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val allowedEndpoints = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun endpoint(url: okhttp3.HttpUrl) = "${url.scheme}|${url.host}|${url.port}"
    private fun client() = SavedAccountPushClient(OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS)
        .addInterceptor { chain -> check(endpoint(chain.request().url) in allowedEndpoints); chain.proceed(chain.request()) }.build(), json)
    private fun owner(server: MockWebServer, key: String): SavedPushOwner {
        val url = server.url("/")
        allowedEndpoints += endpoint(url)
        return SavedPushOwner(SavedPushAccount(key, url.toString().trimEnd('/')), "user-$key", "device-$key")
    }
    private fun renewed(key: String) = """{"access_token":"access-$key-new","access_token_expires_at":"2100-01-01T00:00:00Z","refresh_token":"refresh-$key-new"}"""
    private class Session(val key: String, expired: Boolean = true) : SavedPushSession {
        override val refreshMutex = Mutex()
        @Volatile var current = true
        var access = "access-$key"
        var refresh = "refresh-$key"
        var expiry = if (expired) Instant.EPOCH else Instant.parse("2100-01-01T00:00:00Z")
        var saved = 0
        override fun isCurrent() = current
        override fun accessToken() = access
        override fun expiresAt() = expiry
        override fun refreshToken() = refresh
        override fun refreshRequestId() = "request-$key"
        override fun saveTokens(access: String, expiry: Instant, refresh: String) {
            this.access = access; this.expiry = expiry; this.refresh = refresh; saved++
        }
    }

    @Test fun `inactive and active saved owners use only their own origin refresh and device credentials`() = runBlocking {
        MockWebServer().use { a -> MockWebServer().use { b ->
            a.start(); b.start()
            for ((server, key) in listOf(a to "a", b to "b")) {
                server.enqueue(MockResponse().setBody(renewed(key)))
                server.enqueue(MockResponse().setResponseCode(204))
                val session = Session(key)
                client().register(owner(server, key), session, "installation-token")
                val refresh = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/auth/refresh", refresh.path)
                assertTrue(refresh.body.readUtf8().contains("refresh-$key"))
                assertNull(refresh.getHeader("Authorization"))
                val upload = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/devices/me/push-token", upload.path)
                assertEquals("Bearer access-$key-new", upload.getHeader("Authorization"))
                assertTrue(upload.body.readUtf8().contains("installation-token"))
                assertEquals(1, session.saved)
            }
            assertEquals(2, a.requestCount); assertEquals(2, b.requestCount)
        } }
    }

    @Test fun `unauthorized cached access refreshes this owner once and retries registration`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"expired"}}"""))
            server.enqueue(MockResponse().setBody(renewed("a")))
            server.enqueue(MockResponse().setResponseCode(204))
            val session = Session("a", expired = false)
            client().register(owner(server, "a"), session, "token")
            assertEquals("Bearer access-a", server.takeRequest().getHeader("Authorization"))
            assertEquals("/auth/refresh", server.takeRequest().path)
            assertEquals("Bearer access-a-new", server.takeRequest().getHeader("Authorization"))
            assertEquals(1, session.saved)
        }
    }

    @Test fun `refresh credentials cannot follow a redirect to another origin`() = runBlocking {
        MockWebServer().use { a -> MockWebServer().use { b ->
            a.start(); b.start()
            a.enqueue(MockResponse().setResponseCode(307).addHeader("Location", b.url("/stolen")))
            b.enqueue(MockResponse().setBody(renewed("wrong")))
            val session = Session("a")
            try { client().register(owner(a, "a"), session, "token"); fail("Redirect must fail") }
            catch (error: AuthApiException) { assertEquals(307, when (error) {
                is AuthApiException.Api -> error.httpStatus
                is AuthApiException.Network -> error.httpStatus
            }) }
            assertEquals(0, b.requestCount)
            assertEquals("refresh-a", session.refresh)
            assertEquals(0, session.saved)
        } }
    }

    @Test fun `authorization header cannot follow a registration redirect`() = runBlocking {
        MockWebServer().use { a -> MockWebServer().use { b ->
            a.start(); b.start()
            a.enqueue(MockResponse().setResponseCode(307).addHeader("Location", b.url("/stolen")))
            b.enqueue(MockResponse().setResponseCode(204))
            try { client().register(owner(a, "a"), Session("a", false), "token"); fail("Redirect must fail") }
            catch (error: AuthApiException) { assertEquals(307, when (error) {
                is AuthApiException.Api -> error.httpStatus
                is AuthApiException.Network -> error.httpStatus
            }) }
            assertEquals(0, b.requestCount)
        } }
    }

    @Test fun `owner change during refresh cannot persist credentials or upload a binding`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val session = Session("a")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    session.current = false
                    return MockResponse().setBody(renewed("a"))
                }
            }
            try { client().register(owner(server, "a"), session, "token"); fail("Owner must still match") }
            catch (_: IllegalStateException) { }
            assertEquals(0, session.saved)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `concurrent registration shares refresh rotation rather than consuming a refresh token twice`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/auth/refresh")
                    MockResponse().setBody(renewed("a")) else MockResponse().setResponseCode(204)
            }
            val session = Session("a")
            val expected = owner(server, "a")
            val api = client()
            awaitAll(async { api.register(expected, session, "token") }, async { api.register(expected, session, "token") })
            assertEquals(1, session.saved)
            assertEquals(3, server.requestCount)
        }
    }
}
