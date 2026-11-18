package com.messenger.app.data.auth

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.LoginRequestDto
import com.messenger.app.data.api.AuthApi
import com.messenger.app.data.api.DevicesApi
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class InstallationCredentialCoordinatorTest {
    private val a = "https://a.invalid"
    private val b = "https://b.invalid"
    private val proof = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private class Store : InstallationCredentialStorage {
        val saved = mutableMapOf<String, String>()
        var writes = 0
        var failWrite = false
        override fun read(origin: String) = saved[origin]
        override fun write(origin: String, credential: String) {
            if (failWrite) error("Storage unavailable")
            writes++; saved[origin] = credential
        }
    }

    @Test fun `proof is stable across accounts and coordinator restarts and isolated by server origin`() = runBlocking {
        val store = Store(); var created = 0
        fun coordinator() = InstallationCredentialCoordinator(store, { created++; proof }, { _, _, _ -> })
        val first = coordinator()
        assertEquals(proof, first.prepare(a) { true }); first.clearSessionState()
        assertEquals(proof, first.prepare(a) { true })
        assertEquals(proof, coordinator().prepare(a) { true })
        assertEquals(1, created); assertEquals(1, store.writes)
        coordinator().prepare(b) { true }
        assertEquals(2, created); assertEquals(setOf(a, b), store.saved.keys)
    }

    @Test fun `parallel bootstrap is coalesced and each owner binds once until logout`() = runBlocking {
        val store = Store(); var created = 0; var bound = 0
        val coordinator = InstallationCredentialCoordinator(store, { delay(10); created++; proof }, { _, _, _ -> bound++ })
        coroutineScope { List(6) { async { coordinator.prepare(a) { true } } }.awaitAll() }
        assertEquals(1, created)
        coordinator.bindCurrent(a, "bearer-a", "alice|device1|1") { true }
        coordinator.bindCurrent(a, "bearer-a", "alice|device1|1") { true }
        assertEquals(1, bound)
        coordinator.clearSessionState()
        coordinator.bindCurrent(a, "bearer-b", "bob|device2|2") { true }
        assertEquals(2, bound); assertEquals(1, created)
    }

    @Test fun `unsupported bootstrap can use legacy while bind404 after bootstrap is a real error`() = runBlocking {
        var bootstrap = 0; var binds = 0
        val legacy = InstallationCredentialCoordinator(Store(), { bootstrap++; throw AuthApiException.Network("not found", 404) }, { _, _, _ -> binds++ })
        assertNull(legacy.prepare(a) { true }); assertNull(legacy.prepare(a) { true })
        legacy.bindCurrent(a, "bearer", "owner") { true }
        assertEquals(1, bootstrap); assertEquals(0, binds)
        val current = InstallationCredentialCoordinator(Store(), { proof }, { _, _, _ -> throw AuthApiException.Network("not found", 404) })
        assertTrue(runCatching { current.bindCurrent(a, "bearer", "owner") { true } }.isFailure)
        // The saved proof must still be supplied: a bind error cannot downgrade.
        assertEquals(proof, current.prepare(a) { true })
    }

    @Test fun `network error and invalid server proof never become legacy or saved credentials`() = runBlocking {
        var attempts = 0; val store = Store()
        val network = InstallationCredentialCoordinator(store, { attempts++; throw AuthApiException.Network("offline") }, { _, _, _ -> })
        assertTrue(runCatching { network.prepare(a) { true } }.isFailure)
        assertTrue(runCatching { network.prepare(a) { true } }.isFailure)
        assertEquals(2, attempts); assertTrue(store.saved.isEmpty())
        val invalid = InstallationCredentialCoordinator(store, { "bad" }, { _, _, _ -> })
        assertTrue(runCatching { invalid.prepare(a) { true } }.isFailure); assertTrue(store.saved.isEmpty())
    }

    @Test fun `invalid stored credential is not silently regenerated and failed persistence cannot bind`() = runBlocking {
        val store = Store().apply { saved[a] = "broken" }; var bootstraps = 0; var binds = 0
        val coordinator = InstallationCredentialCoordinator(store, { bootstraps++; proof }, { _, _, _ -> binds++ })
        assertTrue(runCatching { coordinator.prepare(a) { true } }.isFailure)
        assertEquals(0, bootstraps); assertEquals("broken", store.saved[a])
        store.saved.clear(); store.failWrite = true
        assertTrue(runCatching { coordinator.bindCurrent(a, "bearer", "owner") { true } }.isFailure)
        assertEquals(0, binds); assertTrue(store.saved.isEmpty())
    }

    @Test fun `owner changed during bootstrap does not persist or bind its late result`() = runBlocking {
        val store = Store(); val current = AtomicBoolean(true); var binds = 0
        val coordinator = InstallationCredentialCoordinator(store, { current.set(false); proof }, { _, _, _ -> binds++ })
        assertTrue(runCatching { coordinator.bindCurrent(a, "bearer", "old-owner") { current.get() } }.isFailure)
        assertTrue(store.saved.isEmpty()); assertEquals(0, binds)
    }

    @Test fun `invalid bind proof fails without retry or replacement and logout fence prevents cached binding`() = runBlocking {
        val store = Store(); var created = 0; var binds = 0
        val coordinator = InstallationCredentialCoordinator(store, { created++; proof }, { _, _, _ ->
            binds++; throw AuthApiException.Api("validation_error", "Invalid installation credential", 400)
        })
        assertTrue(runCatching { coordinator.bindCurrent(a, "bearer", "owner") { true } }.isFailure)
        assertEquals(proof, coordinator.prepare(a) { true }); assertEquals(1, created); assertEquals(1, binds)
    }

    @Test fun `logout invalidates an in-flight binding without waiting for its network operation`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var binds = 0
        val coordinator = InstallationCredentialCoordinator(Store(), { proof }, { _, _, _ ->
            binds++; if (binds == 1) { entered.complete(Unit); release.await() }
        })
        val old = async { runCatching { coordinator.bindCurrent(a, "old-bearer", "old-owner") { true } } }
        entered.await()
        coordinator.clearSessionState() // no network gate or blocking logout
        release.complete(Unit)
        assertTrue(old.await().isFailure)
        coordinator.bindCurrent(a, "new-bearer", "new-owner") { true }
        assertEquals(2, binds)
    }

    @Test fun `proof is never bootstrapped or transmitted over cleartext HTTP`() = runBlocking {
        var bootstraps = 0
        val coordinator = InstallationCredentialCoordinator(Store(), { bootstraps++; proof }, { _, _, _ -> error("must not bind") })
        assertTrue(runCatching { coordinator.prepare("http://192.168.0.106:8080") { true } }.isFailure)
        assertEquals(0, bootstraps)
    }

    @Test fun `legacy login omits optional proof even when other defaults are encoded`() {
        val json = Json { encodeDefaults = true }
        val legacy = json.encodeToString(LoginRequestDto.serializer(), LoginRequestDto("alice", "fixture", "legacy-installation"))
        assertFalse(legacy.contains("installation_credential")); assertTrue(legacy.contains("installation_id"))
        val secured = json.encodeToString(LoginRequestDto.serializer(), LoginRequestDto("alice", "fixture", "legacy-installation", installationCredential = proof))
        assertTrue(secured.contains("installation_credential"))
    }

    @Test fun `API refuses proof-bearing login bootstrap and bind before any cleartext request`() = runBlocking {
        var requests = 0
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { requests++; error("must not send") }.build()
        val api = AuthApi(client, Json, "http://isolated.invalid")
        assertTrue(runCatching { api.createInstallationCredential("http://isolated.invalid") }.isFailure)
        assertTrue(runCatching { api.login(LoginRequestDto("alice", "fixture", "id", installationCredential = proof)) }.isFailure)
        val devices = DevicesApi(client, Json, "http://isolated.invalid")
        assertTrue(runCatching { devices.bindInstallationCredential("http://isolated.invalid", "bearer", proof) }.isFailure)
        assertEquals(0, requests)
    }

    @Test fun `explicit developer local HTTP policy skips proof but public HTTP and untrusted forms cannot opt out`() {
        for (origin in listOf("http://127.0.0.1:8080", "http://localhost:8080", "http://192.168.0.106:8080", "http://10.0.2.2:8080", "http://coco.local:8080")) {
            assertFalse(installationProofRequired(origin, allowLocalHttp = true))
        }
        for (origin in listOf("http://example.com", "http://192.168.0.106.evil.invalid", "http://user@192.168.0.106:8080", "http://192.168.0.106:8080/?proof=fixture")) {
            assertTrue(runCatching { installationProofRequired(origin, allowLocalHttp = true) }.exceptionOrNull() is AuthApiException)
        }
    }

    @Test fun `public production policy rejects local HTTP and HTTPS never skips proof in either mode`() {
        assertTrue(runCatching { installationProofRequired("http://192.168.0.106:8080", allowLocalHttp = false) }.exceptionOrNull() is AuthApiException)
        for (allowed in listOf(false, true)) {
            assertTrue(installationProofRequired("https://example.com", allowed))
            assertTrue(installationProofRequired("https://192.168.0.106:8443", allowed))
        }
    }
}
