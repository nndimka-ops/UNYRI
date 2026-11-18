package com.messenger.app.data.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import kotlin.test.*

class ServerInputLimitsTest {
    @Test fun `chunked and gzip expanded success and error bodies are bounded`() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient()
            try {
                for (code in listOf(200, 500)) for (gzip in listOf(false, true)) {
                    val max = if (code == 200) ServerInputLimits.API_BYTES else ServerInputLimits.ERROR_BYTES
                    val plain = "x".repeat(max + 1)
                    val bytes = if (gzip) Buffer().also { compressed -> GzipSink(compressed).buffer().use { it.writeUtf8(plain) } }
                        else Buffer().writeUtf8(plain)
                    val response = MockResponse().setResponseCode(code).setChunkedBody(bytes, 8192)
                    if (gzip) response.setHeader("Content-Encoding", "gzip")
                    server.enqueue(response)
                    assertFailsWith<AuthApiException.Network> {
                        client.executeCancellableApi(Request.Builder().url(server.url("/sync")).build(), Json)
                    }
                }
            } finally { client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `declared lengths reject before receiving payload and health diagnostics share limits`() {
        MockWebServer().use { server ->
            val client = OkHttpClient()
            try {
                for (path in listOf("/health", "/diagnostics", "/performance", "/server-info")) {
                    server.enqueue(MockResponse().setHeader("Content-Length", Int.MAX_VALUE))
                    client.newCall(Request.Builder().url(server.url(path)).build()).execute().use {
                        assertFailsWith<ServerInputLimitException> { it.readBoundedJson(ServerInputLimits.ERROR_BYTES) }
                    }
                }
            } finally { client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `preflight bounds unknown nested fields collections strings and primitive tokens`() {
        assertFailsWith<ServerInputLimitException> { ServerInputLimits.validateJson("[".repeat(33) + "0" + "]".repeat(33)) }
        assertFailsWith<ServerInputLimitException> { ServerInputLimits.validateJson("[" + List(4097) { "0" }.joinToString(",") + "]") }
        assertFailsWith<ServerInputLimitException> { ServerInputLimits.validateJson("{\"unknown\":\"" + "a".repeat(20) + "\"}", maxString = 10) }
        assertFailsWith<ServerInputLimitException> { ServerInputLimits.validateJson("1".repeat(129)) }
        ServerInputLimits.validateJson("{\"escaped\":\"\\\"[{},]\\\\\",\"array\":[1,2]}")
        ServerInputLimits.validateJson("[".repeat(32) + "0" + "]".repeat(32))
        ServerInputLimits.validateJson("[" + List(4096) { "0" }.joinToString(",") + "]")
    }

    @Test fun `unknown deeply nested HTTP fields fail before deserialization`() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient()
            try {
                server.enqueue(MockResponse().setBody("{\"unknown\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}"))
                assertFailsWith<AuthApiException.Network> {
                    client.executeCancellableApi(Request.Builder().url(server.url("/sync")).build(), Json)
                }
            } finally { client.dispatcher.executorService.shutdown() }
        }
        Unit
    }
}
