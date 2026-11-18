package com.messenger.app.data.profile

import com.messenger.app.data.api.UsersApi
import com.messenger.app.data.sync.FakeAuthRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class PrivacyRepositoryTest {
    private fun snapshot(revision: Int, reads: Boolean) = """{"online_visibility":"everyone","last_seen_visibility":"nobody","always_allow":[],"never_allow":[],"send_read_receipts":$reads,"revision":$revision}"""
    @Test fun revisionWriteUsesCurrentServerSnapshotAndFailedSaveKeepsAcceptedState() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            val auth=FakeAuthRepository()
            val repo=PrivacyRepository(UsersApi(OkHttpClient(), Json { ignoreUnknownKeys=true }, server.url("/").toString().trimEnd('/')),auth) { server.url("/").toString().trimEnd('/') }
            server.enqueue(MockResponse().setBody(snapshot(4,true)))
            assertTrue(repo.refresh().isSuccess); server.takeRequest()
            server.enqueue(MockResponse().setBody(snapshot(6,true)))
            server.enqueue(MockResponse().setBody(snapshot(7,false)))
            assertTrue(repo.update { it.copy(sendReadReceipts=false) }.isSuccess)
            assertEquals("GET",server.takeRequest().method)
            val request=server.takeRequest(); assertEquals("PUT",request.method)
            val body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("6",body["expected_revision"]?.jsonPrimitive?.content)
            assertEquals("nobody",body["last_seen_visibility"]?.jsonPrimitive?.content)
            assertEquals(false,repo.state.value?.sendReadReceipts)
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            assertTrue(repo.refresh().isFailure)
            assertEquals(false,repo.state.value?.sendReadReceipts)
        } finally { server.shutdown() }
    }
    @Test fun staleResponseAndOldAccountSnapshotAreNeverExposed() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            val auth=FakeAuthRepository()
            val repo=PrivacyRepository(UsersApi(OkHttpClient(),Json { ignoreUnknownKeys=true },server.url("/").toString().trimEnd('/')),auth) { server.url("/").toString().trimEnd('/') }
            server.enqueue(MockResponse().setBody(snapshot(1,true)))
            assertTrue(repo.refresh().isSuccess); server.takeRequest()
            auth.switchSession("another"); assertNull(repo.state.value)
            server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    auth.switchSession("third"); return MockResponse().setBody(snapshot(2,false))
                }
            }
            assertTrue(repo.refresh().isFailure); assertNull(repo.state.value)
        } finally { server.shutdown() }
    }
    @Test fun serverChangeDuringRefreshCannotSendTheOldTokenToNewOrigin() = runBlocking {
        val first=MockWebServer(); val second=MockWebServer(); first.start(); second.start()
        try {
            var origin=first.url("/").toString().trimEnd('/')
            val fake=FakeAuthRepository()
            val auth=object : com.messenger.app.domain.repository.AuthRepository by fake {
                override suspend fun currentAccessToken(): String? { origin=second.url("/").toString().trimEnd('/'); return "qa-token" }
            }
            val repo=PrivacyRepository(UsersApi(OkHttpClient(),Json { ignoreUnknownKeys=true },origin),auth) { origin }
            assertTrue(repo.refresh().isFailure)
            assertEquals(0,first.requestCount); assertEquals(0,second.requestCount)
            assertNull(repo.state.value)
        } finally { first.shutdown(); second.shutdown() }
    }

}
