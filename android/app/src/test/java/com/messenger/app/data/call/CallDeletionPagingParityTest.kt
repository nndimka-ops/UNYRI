package com.messenger.app.data.call

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import com.messenger.app.data.sync.FakeAuthRepository
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CallDeletionPagingParityTest {
    private fun page(vararg sequences: Long, more: Boolean = true) =
        CallDeletionPage(sequences.map { CallDeletion(it, "call-$it") }, more)

    @Test fun `repeated descending duplicate nonpositive oversized and empty more reject before write`() = runTest {
        for (invalid in listOf(page(1), page(3, 2), page(2, 2), page(0), page(-1),
            page(*(2L..202L).toList().toLongArray()), page(more = true))) {
            var writes = 0
            assertFailsWith<IllegalStateException> {
                syncCallDeletionPages(1, { invalid }, { _, _ -> writes++ })
            }
            assertEquals(0, writes)
        }
        var fetches = 0
        var writes = 0
        assertFailsWith<IllegalStateException> {
            syncCallDeletionPages(0, { fetches++; page(1) }, { _, _ -> writes++ })
        }
        assertEquals(2, fetches); assertEquals(1, writes)
    }

    @Test fun `twenty full pages stop at four thousand then new trigger resumes durably`() = runTest {
        var durable = 0L
        val requested = mutableListOf<Long>()
        val fetch: suspend (Long) -> CallDeletionPage = { after ->
            requested += after
            CallDeletionPage((after + 1..after + 200).map { CallDeletion(it, "call-$it") }, true)
        }
        assertFailsWith<IllegalStateException> {
            syncCallDeletionPages(durable, fetch, { _, next -> durable = next })
        }
        assertEquals(4000L, durable); assertEquals(20, requested.size)
        syncCallDeletionPages(durable, { after -> requested += after; page(after + 1, more = false) }, { _, next -> durable = next })
        assertEquals(4000L, requested.last()); assertEquals(4001L, durable)
    }

    @Test fun `page budget applies even to small pages and a terminal empty page succeeds`() = runTest {
        var durable = 0L
        var requests = 0
        assertFailsWith<IllegalStateException> {
            syncCallDeletionPages(0, { after -> requests++; page(after + 1) }, { _, next -> durable = next })
        }
        assertEquals(20, requests); assertEquals(20L, durable)
        syncCallDeletionPages(durable, { page(more = false) }, { _, _ -> fail("Empty page cannot write") })
    }

    @Test fun `failed write never advances and late responses are rejected before write`() = runTest {
        var durable = 7L
        assertFailsWith<IllegalArgumentException> {
            syncCallDeletionPages(durable, { page(8) }, { _, _ -> throw IllegalArgumentException("disk") })
        }
        syncCallDeletionPages(durable, { assertEquals(7L, it); page(8, more = false) }, { _, next -> durable = next })
        var now = 0L
        assertFailsWith<IllegalStateException> {
            syncCallDeletionPages(durable, { now = 10_000_000_000L; page(9) }, { _, _ -> fail("Late page written") }, nanoTime = { now })
        }
        assertEquals(8L, durable)
    }

    @Test fun `cancelled fetch and cancelled page commit cannot start another page`() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var writes = 0; var requests = 0
        val job = launch {
            syncCallDeletionPages(0, { requests++; entered.complete(Unit); release.await(); page(1) }, { _, _ -> writes++ })
        }
        entered.await(); job.cancelAndJoin(); release.complete(Unit)
        assertEquals(1, requests); assertEquals(0, writes)
        val afterCommit = launch {
            syncCallDeletionPages(0, { requests++; page(1) }, { _, _ -> writes++; currentCoroutineContext().cancel() })
        }
        afterCommit.join(); assertEquals(2, requests); assertEquals(1, writes)
        // CallHistoryRepository owns no shared refresh job: another caller has its own context.
        syncCallDeletionPages(0, { page(1, more = false) }, { _, _ -> writes++ })
        assertEquals(2, writes)
    }

    @Test fun `retired account is rejected after fetch`() = runTest {
        var current = true
        assertFailsWith<CancellationException> {
            syncCallDeletionPages(0, { current = false; page(1) }, { _, _ -> fail("Stale page written") },
                requireSession = { if (!current) throw CancellationException("Retired session") })
        }
    }

    @Test fun `repository failed atomic write leaves durable cursor unchanged across reopen`() = runBlocking {
        val root = Files.createTempDirectory("deletion-parity-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val auth = FakeAuthRepository("owner"); val origin = "https://fixture.invalid"
        val requests = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when {
                request.url.encodedPath.endsWith("/cleared") -> "[]"
                request.url.encodedPath.endsWith("/deletions") -> {
                    requests += request.url.queryParameter("after")!!.toLong()
                    """{"items":[{"sequence":1,"call_id":"gone"}],"has_more":false}"""
                }
                else -> """{"items":[],"visible_since":"1970-01-01T00:00:00Z"}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        try {
            val endpoint = com.messenger.app.data.api.ServerEndpoint.resolve(origin)
            val name = java.security.MessageDigest.getInstance("SHA-256").digest("$endpoint:owner".toByteArray()).joinToString("") { "%02x".format(it) }
            val blockedTemp = File(root, "$name.json.deleted.tmp").apply { mkdirs() }
            val api = CallApiImpl(client, Json { ignoreUnknownKeys = true }, origin, auth)
            val repo = CallHistoryRepository(root, origin, api, auth, scope)
            assertFailsWith<java.io.IOException> { repo.refresh() }
            assertFalse(File(root, "$name.json.deleted").exists())
            assertTrue(blockedTemp.delete())
            CallHistoryRepository(root, origin, api, auth, scope).refresh()
            assertEquals(listOf(0L, 0L), requests.toList())
            assertEquals(1L, Json.decodeFromString(CallDeletionState.serializer(), File(root, "$name.json.deleted").readText()).cursor)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); client.dispatcher.executorService.shutdown(); root.deleteRecursively() }
    }

    @Test fun `cancelling queued repository caller does not cancel active caller`() = runBlocking {
        val root = Files.createTempDirectory("deletion-callers-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var deletions = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when {
                request.url.encodedPath.endsWith("/cleared") -> "[]"
                request.url.encodedPath.endsWith("/deletions") -> {
                    deletions++; entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    """{"items":[],"has_more":false}"""
                }
                else -> """{"items":[],"visible_since":"1970-01-01T00:00:00Z"}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        try {
            val auth = FakeAuthRepository("owner"); val origin = "https://fixture.invalid"
            val repo = CallHistoryRepository(root, origin, CallApiImpl(client, Json, origin, auth), auth, scope)
            val active = async(Dispatchers.Default) { repo.refresh() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            val queued = async(start = CoroutineStart.UNDISPATCHED) { repo.refresh() }
            queued.cancelAndJoin()
            assertTrue(active.isActive)
            release.countDown(); withTimeout(3000) { active.await() }
            assertEquals(1, deletions)
        } finally { release.countDown(); scope.coroutineContext[Job]!!.cancelAndJoin(); client.dispatcher.executorService.shutdown(); root.deleteRecursively() }
    }
}
