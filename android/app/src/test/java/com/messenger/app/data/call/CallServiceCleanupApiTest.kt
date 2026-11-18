package com.messenger.app.data.call

import com.messenger.app.data.api.CallDto
import com.messenger.app.domain.repository.AuthRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class CallServiceCleanupApiTest {
    private class Fixture {
        var epoch = 1
        var ownerMatches = true
        var readingToken: () -> Unit = {}
        var requests = 0
        val auth = Proxy.newProxyInstance(AuthRepository::class.java.classLoader, arrayOf(AuthRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "currentSessionEpoch" -> epoch
                "currentAccessToken" -> { readingToken(); "fixture-token" }
                else -> error("Unexpected auth dependency ${method.name}")
            }
        } as AuthRepository
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("POST", chain.request().method)
            assertEquals("/calls/call-0/failed", chain.request().url.encodedPath)
            val row = CallDto("call-0", "me", "peer", "audio", "ended", createdAt = "2026-10-28T00:00:00Z")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.encodeToString(row).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = CallApiImpl(client, json, "https://owned-fixture.invalid", auth)
        fun close() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun `service failure cleanup rechecks epoch after credential resolution`() = runBlocking {
        val f = Fixture()
        try {
            f.readingToken = { f.epoch++ }
            assertFailsWith<CancellationException> { f.api.markFailedIfCurrent("call-0") { true } }
            assertEquals(0, f.requests, "A replacement session's token must never clean up an old call")
        } finally { f.close() }
    }

    @Test fun `service failure cleanup rechecks owner after credential resolution`() = runBlocking {
        val f = Fixture()
        try {
            f.readingToken = { f.ownerMatches = false }
            assertNull(f.api.markFailedIfCurrent("call-0") { f.ownerMatches })
            assertEquals(0, f.requests)
        } finally { f.close() }
    }

    @Test fun `current service failure submits exactly one terminal action`() = runBlocking {
        val f = Fixture()
        try {
            assertEquals("ended", f.api.markFailedIfCurrent("call-0") { f.ownerMatches }?.state)
            assertEquals(1, f.requests)
        } finally { f.close() }
    }
}
