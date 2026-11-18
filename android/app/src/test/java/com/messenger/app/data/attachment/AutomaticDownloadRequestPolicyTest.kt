package com.messenger.app.data.attachment

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticDownloadRequestPolicyTest {
    @Test fun dispatcherQueuedAutomaticRechecksPolicyWithoutBlockingManualRequest() {
        val server=MockWebServer()
        val started=CountDownLatch(1); val release=CountDownLatch(1)
        server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if(request.path=="/block") { started.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
                return MockResponse().setBody("media")
            }
        }
        server.start()
        val dispatcher=okhttp3.Dispatcher().apply { maxRequests=1; maxRequestsPerHost=1 }
        val client=OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor(automaticDownloadPolicyInterceptor).build()
        val finished=CountDownLatch(3); val automaticFailure=AtomicReference<java.io.IOException?>(); val manualBody=AtomicReference<String?>()
        val allowed=java.util.concurrent.atomic.AtomicBoolean(true)
        fun callback(failure: AtomicReference<java.io.IOException?>?=null,body: AtomicReference<String?>?=null)=object:Callback {
            override fun onFailure(call:Call,error:java.io.IOException) { failure?.set(error); finished.countDown() }
            override fun onResponse(call:Call,response:Response) { response.use { body?.set(it.body?.string()) }; finished.countDown() }
        }
        try {
            client.newCall(Request.Builder().url(server.url("/block")).build()).enqueue(callback())
            assertTrue(started.await(5,TimeUnit.SECONDS))
            val automatic=Request.Builder().url(server.url("/automatic")).tag(AutomaticDownloadRequestPolicy::class.java,AutomaticDownloadRequestPolicy { if(!allowed.get()) throw AutomaticDownloadDeferred() }).build()
            client.newCall(automatic).enqueue(callback(automaticFailure))
            client.newCall(Request.Builder().url(server.url("/manual")).build()).enqueue(callback(body=manualBody))
            allowed.set(false); release.countDown()
            assertTrue(finished.await(5,TimeUnit.SECONDS))
            assertTrue(automaticFailure.get() is AutomaticDownloadDeferred)
            assertEquals("media",manualBody.get())
            assertEquals(2,server.requestCount)
        } finally { release.countDown(); dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); server.shutdown() }
    }
}
