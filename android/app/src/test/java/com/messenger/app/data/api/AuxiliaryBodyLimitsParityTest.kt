package com.messenger.app.data.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import kotlin.test.*

class AuxiliaryBodyLimitsParityTest {
    private fun response(path: String, bytes: Int, code: Int = 200) = Response.Builder()
        .request(Request.Builder().url("https://fixture.invalid$path").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
        .body((" ".repeat(bytes - 2) + "{}").toResponseBody()).build()

    @Test fun `recovery health and admin exact decoded byte boundaries`() {
        assertEquals(65536, ServerInputLimits.RECOVERY_BYTES)
        assertEquals(16384, ServerInputLimits.HEALTH_BYTES)
        assertEquals(8388608, ServerInputLimits.ADMIN_BYTES)
        for ((path, limit) in listOf("/recovery" to ServerInputLimits.RECOVERY_BYTES,
            "/health" to ServerInputLimits.HEALTH_BYTES, "/admin/status" to ServerInputLimits.ADMIN_BYTES)) {
            response(path, limit).use { assertEquals(limit, it.readBoundedJson(limit).length) }
            response(path, limit + 1).use { assertFailsWith<ServerInputLimitException> { it.readBoundedJson(limit) } }
            response(path, limit + 1).use { assertFailsWith<ServerInputLimitException> { it.readBoundedJson(if (path == "/recovery") limit else ServerInputLimits.API_BYTES) } }
            response(path, ServerInputLimits.ERROR_BYTES + 1, 500).use {
                assertFailsWith<ServerInputLimitException> { it.readBoundedJson(limit) }
            }
        }
    }

    @Test fun `unknown length bodies are bounded too`() {
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = Buffer().write(ByteArray(ServerInputLimits.HEALTH_BYTES + 1))
        }
        body.use { assertFailsWith<ServerInputLimitException> { ServerInputLimits.readBytes(it, ServerInputLimits.HEALTH_BYTES) } }
    }

    @Test fun `delivery acknowledgement closes unread body for success and failure`() = runBlocking {
        for (code in listOf(200, 409, 500)) {
            var reads = 0; var closed = false
            val source = object : ForwardingSource(Buffer().writeUtf8("untrusted")) {
                override fun read(sink: Buffer, byteCount: Long): Long { reads++; error("Acknowledgement body was read") }
                override fun close() { closed = true; super.close() }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = Long.MAX_VALUE
                override fun source() = source
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body).build()
            }.build()
            try {
                val api = MessagesApi(client, Json, "https://fixture.invalid")
                if (code == 200) assertEquals(42L, api.markDelivered("fixture-token", "chat", 42).sequence)
                else assertEquals(code, assertFailsWith<AuthApiException.Api> { api.markDelivered("fixture-token", "chat", 42) }.httpStatus)
                assertEquals(0, reads); assertTrue(closed)
            } finally { client.dispatcher.executorService.shutdown() }
        }
    }
}
