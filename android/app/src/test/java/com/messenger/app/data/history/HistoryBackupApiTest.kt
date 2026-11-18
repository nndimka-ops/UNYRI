package com.messenger.app.data.history

import com.messenger.app.data.api.HistoryBackupApi
import com.messenger.app.data.sync.FakeAuthRepository
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HistoryBackupApiTest {
    private val server="11111111-1111-4111-8111-111111111111"
    private val owner="22222222-2222-4222-8222-222222222222"
    private val json="""{"server_id":"$server","revision":0,"desired_enabled":true,"active":null,"staged":null,"client_restore_required":true}"""
    private fun response(chain: Interceptor.Chain, body: ResponseBody=json.toResponseBody(), code: Int=200) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).build()
    @Test fun `maps server state and never sends recovery key`() = runBlocking {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/history-backup",chain.request().url.encodedPath)
            assertEquals("Bearer token",chain.request().header("Authorization"))
            assertEquals("no-store",chain.request().header("Cache-Control"))
            assertNull(chain.request().body)
            response(chain)
        }.build()
        val state=HistoryBackupApi(client,FakeAuthRepository(owner),"https://unit.invalid").status()
        assertEquals(server,state.server_id); assertTrue(state.desired_enabled)
        client.dispatcher.executorService.shutdown()
    }
    @Test fun `oversized streaming response is bounded even without content length`() = runBlocking {
        val oversized=object: ResponseBody() {
            override fun contentType(): MediaType?=null
            override fun contentLength()=-1L
            override fun source()=Buffer().write(ByteArray(70*1024))
        }
        val client=OkHttpClient.Builder().addInterceptor { response(it,oversized) }.build()
        assertTrue(runCatching { HistoryBackupApi(client,FakeAuthRepository(owner),"https://unit.invalid").status() }.isFailure)
        client.dispatcher.executorService.shutdown()
    }
    @Test fun `account change while waiting rejects response`() = runBlocking {
        val auth=FakeAuthRepository(owner)
        val client=OkHttpClient.Builder().addInterceptor { chain -> auth.switchSession(); response(chain) }.build()
        assertTrue(runCatching { HistoryBackupApi(client,auth,"https://unit.invalid").status() }.isFailure)
        client.dispatcher.executorService.shutdown()
    }
    @Test fun `captured session cannot use next account token`() = runBlocking {
        var calls=0
        val auth=FakeAuthRepository(owner)
        val client=OkHttpClient.Builder().addInterceptor { chain -> calls++; response(chain) }.build()
        val api=HistoryBackupApi(client,auth,"https://unit.invalid")
        auth.switchSession(); auth.setToken("next-account")
        assertTrue(runCatching { api.status() }.isFailure); assertEquals(0,calls)
        client.dispatcher.executorService.shutdown()
    }
    @Test fun `cancellation cancels okhttp call without waiting for response`() = runBlocking {
        val started=CompletableDeferred<Call>(); val release=CountDownLatch(1)
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call()); check(release.await(5,TimeUnit.SECONDS)); response(chain)
        }.build()
        try {
            val api=HistoryBackupApi(client,FakeAuthRepository(owner),"https://unit.invalid")
            val job=launch { api.status() }
            val call=withTimeout(5000){started.await()}
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally { release.countDown(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun `redirect response is rejected`() = runBlocking {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            response(chain,"".toResponseBody(),302).newBuilder().header("Location","https://other.invalid").build()
        }.build()
        assertTrue(runCatching { HistoryBackupApi(client,FakeAuthRepository(owner),"https://unit.invalid").status() }.isFailure)
        client.dispatcher.executorService.shutdown()
    }
    @Test fun `old server has a dedicated unsupported feature error`() = runBlocking {
        val client=OkHttpClient.Builder().addInterceptor { chain -> response(chain,"".toResponseBody(),404) }.build()
        val failure=runCatching { HistoryBackupApi(client,FakeAuthRepository(owner),"https://unit.invalid").status() }.exceptionOrNull()
        assertTrue(failure is com.messenger.app.data.api.HistoryBackupUnsupportedServer)
        client.dispatcher.executorService.shutdown()
    }
}
