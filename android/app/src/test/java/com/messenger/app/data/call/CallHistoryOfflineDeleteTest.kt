package com.messenger.app.data.call

import com.messenger.app.data.api.CallDto
import com.messenger.app.data.api.CallHistoryPage
import com.messenger.app.data.sync.FakeAuthRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CallHistoryOfflineDeleteTest {
    private val id = "aab6b3d2-6313-4d2d-8e14-72f66e4d9a84"

    private inner class Fixture : AutoCloseable {
        val root = java.nio.file.Files.createTempDirectory("call-delete-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val auth = FakeAuthRepository("alice")
        val json = Json { ignoreUnknownKeys = true }
        @Volatile var offline = false
        @Volatile var deleteCode = 200
        @Volatile var entered: CountDownLatch? = null
        @Volatile var release: CountDownLatch? = null
        @Volatile var historyEntered: CountDownLatch? = null
        @Volatile var historyRelease: CountDownLatch? = null
        @Volatile var readEntered: CountDownLatch? = null
        @Volatile var readRelease: CountDownLatch? = null
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val deleteBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val row = CallDto(id, "bob", "alice", "video", "ended", "missed",
            createdAt = "2026-10-10T10:00:00Z", unread = true, chatId = "chat", chatVisible = true)
        var rows = listOf(row)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            requests.add(path)
            if (offline) throw IOException("Offline")
            if (path.endsWith("/history/delete")) {
                val buffer = okio.Buffer()
                chain.request().body!!.writeTo(buffer)
                deleteBodies.add(buffer.readUtf8())
                entered?.countDown()
                check(release?.await(5, TimeUnit.SECONDS) != false)
            }
            val body = when {
                path.endsWith("/cleared") -> "[]"
                path.endsWith("/deletions") -> "{\"items\":[],\"has_more\":false}"
                path.endsWith("/history/delete") -> if (deleteCode == 404) "{\"error\":\"not_found\"}" else "{}"
                path.endsWith("/read") -> "{}"
                else -> json.encodeToString(CallHistoryPage.serializer(), CallHistoryPage(rows, visibleSince = "1970-01-01T00:00:00Z"))
            }
            if (path.endsWith("/calls/history")) {
                historyEntered?.countDown()
                check(historyRelease?.await(5, TimeUnit.SECONDS) != false)
            }
            if (path.endsWith("/read")) {
                readEntered?.countDown()
                check(readRelease?.await(5, TimeUnit.SECONDS) != false)
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (path.endsWith("/history/delete")) deleteCode else 200).message("Test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = CallApiImpl(client, json, "https://test.invalid", auth)
        fun repository() = CallHistoryRepository(root, "https://test.invalid", api, auth, scope)
        fun queue() = root.listFiles().orEmpty().first { it.name.contains(".pending-delete-") && !it.name.endsWith(".tmp") }
        suspend fun drainDeleteJobs(repositoryCount: Int = 1) = withTimeout(3000) {
            while (scope.coroutineContext[Job]!!.children.count() > repositoryCount) delay(10)
        }
        override fun close() { release?.countDown(); historyRelease?.countDown(); readRelease?.countDown(); runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }; client.dispatcher.executorService.shutdown(); root.deleteRecursively() }
    }

    @Test fun `offline delete survives restart suppresses stale page and read then replays same UUID`() = runBlocking {
        Fixture().use { f ->
            val repo = f.repository(); repo.refresh()
            f.offline = true
            repo.markViewed(setOf(id)) // A failed acknowledgement must not outlive deletion.
            repo.deleteHistory(id, true)
            assertTrue(repo.calls.value.isEmpty())
            assertEquals(mapOf(id to true), f.json.decodeFromString<Map<String, Boolean>>(f.queue().readText()))
            val reopened = f.repository()
            // Refresh is serialized behind initial cache loading; offline failure must not resurrect it.
            assertFailsWith<com.messenger.app.data.api.AuthApiException.Network> { reopened.refresh() }
            assertTrue(reopened.calls.value.isEmpty())
            f.drainDeleteJobs(2)
            f.offline = false; f.requests.clear()
            reopened.refresh()
            assertTrue(reopened.calls.value.isEmpty()) // Server deliberately still returns stale call.
            assertTrue(f.requests.any { it == "/calls/$id/history/delete" })
            assertFalse(f.requests.any { it.endsWith("/read") })
            assertEquals(emptyMap(), f.json.decodeFromString<Map<String, Boolean>>(f.queue().readText()))
            val again = f.repository(); again.refresh()
            assertTrue(again.calls.value.isEmpty())
        }
    }

    @Test fun `404 is obsolete command and leaves local tombstone`() = runBlocking {
        Fixture().use { f ->
            val repo = f.repository(); repo.refresh(); f.deleteCode = 404
            repo.deleteHistory(id, false); repo.refresh()
            assertTrue(repo.calls.value.isEmpty())
            assertEquals(emptyMap(), f.json.decodeFromString<Map<String, Boolean>>(f.queue().readText()))
        }
    }

    @Test fun `another installation cannot replay a pending delete`() = runBlocking {
        Fixture().use { f ->
            val repo = f.repository(); repo.refresh(); f.offline = true
            repo.deleteHistory(id, true)
            assertFailsWith<com.messenger.app.data.api.AuthApiException.Network> { repo.refresh() }
            f.drainDeleteJobs()
            f.offline = false; f.requests.clear()
            val differentDevice = object : com.messenger.app.domain.repository.AuthRepository by f.auth {
                override fun currentDeviceId() = "device-2"
            }
            val other = CallHistoryRepository(f.root, "https://test.invalid", f.api, differentDevice, f.scope)
            other.refresh()
            assertEquals(listOf(id), other.calls.value.map { it.id })
            assertFalse(f.requests.any { it.endsWith("/history/delete") })
            assertEquals(mapOf(id to true), f.json.decodeFromString<Map<String, Boolean>>(f.queue().readText()))
        }
    }

    @Test fun `owner changes during deletion response cannot acknowledge old owner journal`() = runBlocking {
        Fixture().use { f ->
            val repo = f.repository(); repo.refresh()
            f.entered = CountDownLatch(1); f.release = CountDownLatch(1)
            repo.deleteHistory(id, false)
            assertTrue(withContext(Dispatchers.IO) { f.entered!!.await(3, TimeUnit.SECONDS) })
            f.auth.switchSession("other-owner")
            f.release!!.countDown()
            // Taking repository mutex waits for the obsolete network operation to finish.
            repo.refresh()
            assertEquals(mapOf(id to false), f.json.decodeFromString<Map<String, Boolean>>(f.queue().readText()))
            assertTrue(repo.calls.value.isEmpty())
        }
    }

    @Test fun `second delete is durable and hidden while first HTTP response is suspended`() = runBlocking {
        Fixture().use { f ->
            val second = "7ed5539c-5343-4a86-a54a-853f842269d9"
            f.rows = listOf(f.row, f.row.copy(id = second))
            val repo = f.repository(); repo.refresh()
            f.entered = CountDownLatch(1); f.release = CountDownLatch(1)
            repo.deleteHistory(id, false)
            assertTrue(withContext(Dispatchers.IO) { f.entered!!.await(3, TimeUnit.SECONDS) })
            withTimeout(1000) { repo.deleteHistory(second, true) }
            assertTrue(repo.calls.value.isEmpty())
            assertEquals(mapOf(id to false, second to true), f.json.decodeFromString<Map<String, Boolean>>(f.queue().readText()))
            // No first response has been released: local progress cannot depend on network completion.
            assertEquals(1L, f.release!!.count)
            f.release!!.countDown(); f.drainDeleteJobs()
            repo.refresh()
            assertTrue(repo.calls.value.isEmpty())
            assertTrue(f.requests.contains("/calls/$second/history/delete"))
        }
    }

    @Test fun `old personal delete response does not acknowledge newer everyone intent`() = runBlocking {
        Fixture().use { f ->
            val repo = f.repository(); repo.refresh()
            f.entered = CountDownLatch(1); f.release = CountDownLatch(1)
            repo.deleteHistory(id, false)
            assertTrue(withContext(Dispatchers.IO) { f.entered!!.await(3, TimeUnit.SECONDS) })
            withTimeout(1000) { repo.deleteHistory(id, true) }
            assertEquals(mapOf(id to true), f.json.decodeFromString<Map<String, Boolean>>(f.queue().readText()))
            f.release!!.countDown(); f.drainDeleteJobs()
            assertEquals(listOf("{\"everyone\":false}", "{\"everyone\":true}"), f.deleteBodies.toList())
            assertEquals(2, f.requests.count { it == "/calls/$id/history/delete" })
            assertEquals(emptyMap(), f.json.decodeFromString<Map<String, Boolean>>(f.queue().readText()))
            assertTrue(repo.calls.value.isEmpty())
        }
    }

    @Test fun `late unread snapshot cannot undo completed read acknowledgement or survive restart`() = runBlocking {
        Fixture().use { f ->
            val repo = f.repository(); repo.refresh()
            assertTrue(repo.calls.value.single().unread)
            f.historyEntered = CountDownLatch(1); f.historyRelease = CountDownLatch(1)
            val staleRefresh = async { repo.refresh() }
            assertTrue(withContext(Dispatchers.IO) { f.historyEntered!!.await(3, TimeUnit.SECONDS) })
            withTimeout(1000) { repo.markViewed(setOf(id)) }
            f.drainDeleteJobs()
            assertTrue(f.requests.contains("/calls/$id/read"))
            assertFalse(repo.calls.value.single().unread)
            f.historyRelease!!.countDown(); staleRefresh.await()
            assertFalse(repo.calls.value.single().unread)
            val reopened = f.repository(); reopened.refresh()
            assertFalse(reopened.calls.value.single().unread)
        }
    }

    @Test fun `older ringing page cannot roll back an ended call`() = runBlocking {
        Fixture().use { f ->
            val repo = f.repository(); repo.refresh()
            f.rows = listOf(f.row.copy(state = "ringing", unread = false, endReason = null))
            repo.refresh()
            assertEquals("ended", repo.calls.value.single().state)
            assertEquals("missed", repo.calls.value.single().endReason)
        }
    }

    @Test fun `local read returns before HTTP and cancelling UI does not cancel application acknowledgement`() = runBlocking {
        Fixture().use { f ->
            val repo = f.repository(); repo.refresh()
            f.readEntered = CountDownLatch(1); f.readRelease = CountDownLatch(1)
            val returned = CompletableDeferred<Unit>()
            val uiCollector = launch {
                repo.markViewed(setOf(id))
                returned.complete(Unit)
                awaitCancellation()
            }
            withTimeout(1000) { returned.await() }
            assertFalse(repo.calls.value.single().unread)
            assertTrue(withContext(Dispatchers.IO) { f.readEntered!!.await(3, TimeUnit.SECONDS) })
            uiCollector.cancelAndJoin()
            assertEquals(1L, f.readRelease!!.count)
            f.readRelease!!.countDown(); f.drainDeleteJobs()
            val readJournal = f.root.listFiles().orEmpty().single { it.name.endsWith(".read-state") }.readText()
            val decoded = f.json.decodeFromString<Map<String, Set<String>>>(readJournal)
            assertEquals(emptySet(), decoded["pending"].orEmpty())
            assertEquals(setOf(id), decoded["seen"])
        }
    }

    @Test fun `bounded chat clear hides old ended calls after restart but keeps later calls`() = runBlocking {
        Fixture().use { f ->
            val later = f.row.copy(id = "b6884f5d-c98b-4d89-a664-8cb71664ec25", createdAt = "2026-10-10T10:01:00Z")
            f.rows = listOf(f.row, later)
            val repo = f.repository(); repo.refresh()
            repo.applyLocalChatClears(listOf(com.messenger.app.data.api.ChatsApi.PersonalClear("chat", 0, f.row.createdAt)), f.auth.currentSessionEpoch())
            assertEquals(listOf(later.id), repo.calls.value.map { it.id })
            val reopened = f.repository(); reopened.refresh()
            assertEquals(listOf(later.id), reopened.calls.value.map { it.id })
            assertTrue(reopened.calls.value.single().unread)
        }
    }
}
