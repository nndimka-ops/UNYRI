package com.messenger.app.data.api

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

/** Real TLS + HTTP servers: the redirect receiver must never receive a request. */
class CredentialRedirectPolicyTest {
    private val fixtureProof = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val fixturePassword = "redirect-fixture-password"
    private val authResponse = """{"access_token":"fixture-bearer","access_token_expires_at":"2099-01-01T00:00:00Z","refresh_token":"fixture-refresh","device_id":"fixture-device","user":{"id":"fixture-user","username":"fixture","display_name":"Fixture"}}"""
    private fun operation(path: String, invoke: suspend () -> Unit) = path to invoke

    private class Fixture : AutoCloseable {
        val source = MockWebServer()
        val target = MockWebServer()
        private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .callTimeout(5, TimeUnit.SECONDS).build()
        fun start(targetTls: Boolean) {
            source.useHttps(serverTls.sslSocketFactory(), false)
            if (targetTls) target.useHttps(serverTls.sslSocketFactory(), false)
            val loopback = java.net.InetAddress.getByName("127.0.0.1")
            source.start(loopback, 0); target.start(loopback, 0)
        }
        val origin: String get() = source.url("/").newBuilder().host("localhost").build().toString().trimEnd('/')
        override fun close() {
            source.shutdown(); target.shutdown()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun `307 and 308 cannot forward auth proof or password bodies to foreign HTTPS or cleartext HTTP`() = runBlocking {
        for (targetTls in listOf(false, true)) for (status in listOf(307, 308)) {
            Fixture().use { f ->
                f.start(targetTls)
                val auth = AuthApi(f.client, Json, f.origin)
                val devices = DevicesApi(f.client, Json, f.origin)
                val destination = f.target.url("/stolen").newBuilder().host("127.0.0.1").build()
                val calls: List<Pair<String, suspend () -> Unit>> = listOf(
                    operation("/auth/installations") { auth.createInstallationCredential(f.origin); Unit },
                    operation("/auth/login") { auth.login(LoginRequestDto("fixture", fixturePassword, "installation", installationCredential = fixtureProof)); Unit },
                    operation("/auth/register") { auth.register(RegisterRequestDto("fixture", fixturePassword, "Fixture", "installation", installationCredential = fixtureProof)); Unit },
                    operation("/devices/me/installation") { devices.bindInstallationCredential(f.origin, "fixture-bearer", fixtureProof) },
                )
                calls.forEach { (path, invoke) ->
                    f.source.enqueue(MockResponse().setResponseCode(status).addHeader("Location", destination).setBody("redirect"))
                    // An accidentally forwarded request can finish; the test then
                    // detects both its arrival and the false successful result.
                    f.target.enqueue(MockResponse().setBody(authResponse))
                    val result = runCatching { invoke() }
                    assertTrue("Auth/proof redirect must be a controlled failure", result.exceptionOrNull() is AuthApiException)
                    val initial = f.source.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals(path, initial.path)
                    if (path.endsWith("/login") || path.endsWith("/register")) {
                        val body = initial.body.readUtf8()
                        assertTrue(body.contains(fixturePassword)); assertTrue(body.contains(fixtureProof))
                    }
                    if (path.endsWith("/installation")) assertTrue(initial.body.readUtf8().contains(fixtureProof))
                    assertEquals(0, f.target.requestCount)
                }
                assertNull(f.target.takeRequest(100, TimeUnit.MILLISECONDS))
                // A derived credential client must not globally disable media redirects.
                assertTrue(f.client.followRedirects); assertTrue(f.client.followSslRedirects)
            }
        }
    }

    @Test fun `fixture reproduces default OkHttp body replay even though Authorization is removed`() {
        for (status in listOf(307, 308)) Fixture().use { f ->
            f.start(targetTls = true)
            val destination = f.target.url("/stolen").newBuilder().host("127.0.0.1").build()
            f.source.enqueue(MockResponse().setResponseCode(status).addHeader("Location", destination))
            f.target.enqueue(MockResponse().setBody("ok"))
            val request = Request.Builder().url("${f.origin}/auth/login").header("Authorization", "Bearer fixture-bearer")
                .post("""{"password":"$fixturePassword","installation_credential":"$fixtureProof"}""".toRequestBody("application/json".toMediaType())).build()
            f.client.newCall(request).execute().use { assertEquals(200, it.code) }
            val replayed = f.target.takeRequest(1, TimeUnit.SECONDS)!!
            val body = replayed.body.readUtf8()
            assertTrue(body.contains(fixturePassword)); assertTrue(body.contains(fixtureProof))
            assertNull(replayed.getHeader("Authorization"))
        }
    }

    @Test fun `scheme-relative redirects are also rejected before any second request`() = runBlocking {
        Fixture().use { f ->
            f.start(targetTls = true)
            f.source.enqueue(MockResponse().setResponseCode(308).addHeader("Location", "//127.0.0.1:${f.target.port}/stolen"))
            val api = AuthApi(f.client, Json, f.origin)
            assertTrue(runCatching { api.login(LoginRequestDto("fixture", fixturePassword, "installation", installationCredential = fixtureProof)) }.isFailure)
            assertNotNull(f.source.takeRequest(1, TimeUnit.SECONDS)); assertNull(f.target.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun `direct HTTPS controls remain supported for bootstrap login register and current-device binding`() = runBlocking {
        Fixture().use { f ->
            f.start(targetTls = false)
            val auth = AuthApi(f.client, Json, f.origin)
            val devices = DevicesApi(f.client, Json, f.origin)
            f.source.enqueue(MockResponse().setBody("""{"installation_credential":"$fixtureProof"}"""))
            assertEquals(fixtureProof, auth.createInstallationCredential(f.origin))
            f.source.enqueue(MockResponse().setBody(authResponse))
            assertEquals("fixture-user", auth.login(LoginRequestDto("fixture", fixturePassword, "installation", installationCredential = fixtureProof)).user.id)
            f.source.enqueue(MockResponse().setBody(authResponse))
            assertEquals("fixture-user", auth.register(RegisterRequestDto("fixture", fixturePassword, "Fixture", "installation", installationCredential = fixtureProof)).user.id)
            f.source.enqueue(MockResponse().setBody("null"))
            devices.bindInstallationCredential(f.origin, "fixture-bearer", fixtureProof)
            assertEquals(4, f.source.requestCount); assertEquals(0, f.target.requestCount)
        }
    }
}
