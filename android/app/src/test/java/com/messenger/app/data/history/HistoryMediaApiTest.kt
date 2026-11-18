package com.messenger.app.data.history

import com.messenger.app.data.api.HistoryBackupApi
import com.messenger.app.data.sync.FakeAuthRepository
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HistoryMediaApiTest {
    @Test fun `call restore validation fails closed against old or unrelated server results`() = runBlocking {
        var body = """{"deleted":[]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
            assertTrue(buffer.readUtf8().contains("\"call_ids\":[\"$owner\"]"))
            response(chain, body.toByteArray())
        }.build()
        try {
            val api = HistoryBackupApi(client, FakeAuthRepository(owner), "https://unit.invalid")
            assertTrue(runCatching { api.restoreCallCheck(listOf(owner)) }.isFailure)
            body = """{"deleted":[],"deleted_call_ids":["$blob"]}"""
            assertTrue(runCatching { api.restoreCallCheck(listOf(owner)) }.isFailure)
            body = """{"deleted":[],"deleted_call_ids":["$owner"]}"""
            assertEquals(setOf(owner), api.restoreCallCheck(listOf(owner)))
        } finally { client.dispatcher.executorService.shutdown() }
    }
    @Test fun `source completeness is a server check not an empty local success`() = runBlocking {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/history-backup/source-check",chain.request().url.encodedPath)
            assertEquals("Bearer token",chain.request().header("Authorization"))
            response(chain,"""{"complete":false}""".toByteArray())
        }.build()
        try { assertFalse(HistoryBackupApi(client,FakeAuthRepository(owner),"https://unit.invalid").sourceComplete(emptyList())) }
        finally { client.dispatcher.executorService.shutdown() }
    }
    @Test fun `restore check rejects unrequested deletion identifiers`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain,"""{"deleted":[{"id":"$blob","sequence":20}]}""".toByteArray()) }.build()
        try { assertTrue(runCatching { HistoryBackupApi(client,FakeAuthRepository(owner),"https://unit.invalid").restoreCheck(listOf(owner)) }.isFailure) }
        finally { client.dispatcher.executorService.shutdown() }
    }
    private val owner = "22222222-2222-4222-8222-222222222222"
    private val blob = "33333333-3333-4333-8333-333333333333"
    private fun entry(bytes:ByteArray) = HistoryMediaEntry(owner,blob,bytes.size.toLong(),MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
    private fun response(chain:Interceptor.Chain, bytes:ByteArray) = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(bytes.toResponseBody()).build()
    @Test fun `media download carries no bearer and verifies manifest digest`() = runBlocking {
        val bytes = "encrypted media".toByteArray()
        val root = Files.createTempDirectory("history-media-api").toFile()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Authorization")); response(chain,bytes)
        }.build()
        try {
            val file = HistoryBackupApi(client,FakeAuthRepository(owner),"https://unit.invalid").receiveMedia("https://storage.invalid/file",entry(bytes),root)
            assertArrayEquals(bytes,file.readBytes())
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun `corruption leaves no published file`() = runBlocking {
        val bytes = "wrong media".toByteArray(); val root = Files.createTempDirectory("history-media-bad").toFile()
        val client = OkHttpClient.Builder().addInterceptor { response(it,bytes) }.build()
        try {
            val api = HistoryBackupApi(client,FakeAuthRepository(owner),"https://unit.invalid")
            assertTrue(runCatching { api.receiveMedia("https://storage.invalid/file",entry("right media".toByteArray()),root) }.isFailure)
            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertTrue(runCatching { api.receiveMedia("http://storage.invalid/file",entry(bytes),root) }.isFailure)
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun `media cancellation cancels network immediately`() = runBlocking {
        val started=CompletableDeferred<Call>(); val release=CountDownLatch(1)
        val root=Files.createTempDirectory("history-media-cancel").toFile()
        val client=OkHttpClient.Builder().addInterceptor { chain -> started.complete(chain.call()); check(release.await(5,TimeUnit.SECONDS)); response(chain,byteArrayOf(1)) }.build()
        try {
            val api=HistoryBackupApi(client,FakeAuthRepository(owner),"https://unit.invalid")
            val job=launch { api.receiveMedia("https://storage.invalid/file",entry(byteArrayOf(1)),root) }
            val call=withTimeout(5000){started.await()}; job.cancelAndJoin(); assertTrue(call.isCanceled())
        } finally { release.countDown(); root.delete(); client.dispatcher.executorService.shutdown() }
    }
}
