package com.messenger.app.data.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.api.*
import com.messenger.app.data.secure.AndroidInstallationCredentialStore
import com.messenger.app.data.secure.SecureTokenStore
import com.messenger.app.domain.model.AuthSessionState
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallationCredentialInstrumentedTest {
    private val proof = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 4).toByte() })

    @Test fun keystoreProofIsInNoBackupStorageEncryptedAndSurvivesNewStoreInstance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = "https://installation-storage-fixture.invalid"
        val second = "https://another-installation-storage-fixture.invalid"
        val store = AndroidInstallationCredentialStore(context)
        try {
            store.write(first, proof)
            assertEquals(proof, AndroidInstallationCredentialStore(context).read(first))
            assertNull(store.read(second))
            val file = store.fileFor(first)
            assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + java.io.File.separator))
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(proof))
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(runCatching { store.read(first) }.isFailure)
        } finally {
            store.fileFor(first).delete(); java.io.File(store.fileFor(first).path + ".bak").delete()
        }
    }

    @Test fun loginLogoutAndNewAccountKeepOnlyServiceProofAndRestoredSessionBindsCurrentJwtDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "installation-auth-fixture-${java.util.UUID.randomUUID()}"
        val tokenStore = SecureTokenStore(context, prefsName)
        val requests = mutableListOf<Request>()
        var loginCount = 0
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val origin = ServerEndpoint.current().trimEnd('/')
        assertTrue("Fixture requires HTTPS to exercise proof transport", origin.startsWith("https://"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val body = when (request.url.encodedPath) {
                "/auth/installations" -> """{"installation_credential":"$proof"}"""
                "/auth/login" -> {
                    loginCount++
                    """{"access_token":"fixture-bearer-$loginCount","access_token_expires_at":"2099-01-01T00:00:00Z","refresh_token":"fixture-refresh-$loginCount","device_id":"fixture-device-$loginCount","user":{"id":"fixture-user-$loginCount","username":"fixture$loginCount","display_name":"Fixture"}}"""
                }
                else -> "null"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val authApi = AuthApi(client, json, origin)
        val devicesApi = DevicesApi(client, json, origin)
        val memory = mutableMapOf<String, String>()
        val storage = object : InstallationCredentialStorage {
            override fun read(origin: String) = memory[origin]
            override fun write(origin: String, credential: String) { memory[origin] = credential }
        }
        val coordinator = InstallationCredentialCoordinator(storage, authApi::createInstallationCredential, devicesApi::bindInstallationCredential)
        val repo = AuthRepositoryImpl(authApi, tokenStore, installationCredentials = coordinator)
        try {
            repo.login("fixture1", "fixture-password").getOrThrow()
            val installationId = tokenStore.installationId()
            repo.logout()
            assertNull(tokenStore.userId()); assertNull(tokenStore.refreshToken()); assertNull(tokenStore.deviceId())
            assertEquals(proof, memory[origin]); assertEquals(installationId, tokenStore.installationId())
            repo.login("fixture2", "fixture-password").getOrThrow()
            val restored = AuthRepositoryImpl(authApi, tokenStore, installationCredentials = coordinator)
            restored.reconcileInstallationCredential().getOrThrow()
            assertEquals("fixture-user-2", (restored.authState.value as AuthSessionState.LoggedIn).user.id)
            assertEquals(1, requests.count { it.url.encodedPath == "/auth/installations" })
            val loginBodies = requests.filter { it.url.encodedPath == "/auth/login" }.map { request ->
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer); json.parseToJsonElement(buffer.readUtf8()).jsonObject
            }
            assertTrue(loginBodies.all { it["installation_credential"]?.jsonPrimitive?.content == proof })
            assertTrue(loginBodies.all { it["installation_id"]?.jsonPrimitive?.content == installationId })
            val binding = requests.single { it.url.encodedPath == "/devices/me/installation" }
            assertEquals("Bearer fixture-bearer-2", binding.header("Authorization"))
            assertNull(binding.url.query); assertFalse(binding.url.toString().contains(proof))
            assertEquals(1, requests.count { it.url.encodedPath == "/auth/logout" })
        } finally { tokenStore.clearSession(); context.deleteSharedPreferences(prefsName) }
    }
}
