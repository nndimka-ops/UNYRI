package com.messenger.app.data.devices

import com.messenger.app.data.api.DevicesApi
import com.messenger.app.data.sync.FakeAuthRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class LocalRevocationRepositoryTest {
    @Test fun localRemovalPrecedesHttpAndRelistedDeviceKeepsWarning() = runBlocking {
        var removed = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "DELETE") assertTrue(removed)
            val body = if (request.method == "GET") """{"devices":[{"id":"removed","display_name":"Old phone","platform":"android","created_at":"today","is_current":false}]}""" else "null"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = DevicesRepositoryImpl(DevicesApi(client, Json { ignoreUnknownKeys = true }, "https://isolated.invalid"), FakeAuthRepository())
        repo.locallyRevoke = { device, _ -> assertEquals("removed", device); removed = true }
        repo.isLocallyRevoked = { it == "removed" && removed }
        repo.revokeDevice("removed").getOrThrow()
        assertTrue(repo.listDevices().getOrThrow().single().locallyRevoked)
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
}
