package com.messenger.app.data.call

import com.messenger.app.data.api.*
import com.messenger.app.data.history.*
import com.messenger.app.data.sync.FakeAuthRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CallHistoryArchivePrivacyTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private val old = HistoryArchiveCall(id(4), id(2), id(5), "audio", 1000, endedAtMs = 2000,
        endReason = "missed", chat = id(6), peer = HistoryArchivePeer(id(5), "", "Bob"))
    private fun archive(vararg calls: HistoryArchiveCall) = HistoryArchive(format = 2, server = id(1), owner = id(2),
        snapshot = id(3), messages = emptyList(), media = emptyList(), chats = listOf(HistoryArchiveChat(id(6), "direct", 0, old.peer)), calls = calls.toList())

    @Test fun `authenticated calls roundtrip validate participants and preserve old archives`() {
        val a = archive(old)
        assertEquals(a, HistoryArchive.decode(HistoryArchive.encode(a), id(1), id(2)))
        val key = ByteArray(32) { 7 }
        val context = HistoryBackupCrypto.Context(java.util.UUID.fromString(id(1)), java.util.UUID.fromString(id(2)), java.util.UUID.fromString(id(3)))
        val packet = HistoryBackupCrypto.encrypt(HistoryArchive.encode(a), key, context)
        assertFalse(String(packet).contains("Bob"))
        assertEquals(a, HistoryArchive.decode(HistoryBackupCrypto.decrypt(packet, key, context), id(1), id(2)))
        assertFalse(String(HistoryArchive.encode(archive())).contains("\"calls\""))
        assertThrows(Exception::class.java) { HistoryArchive.encode(archive(old.copy(caller = id(9)))) }
        assertThrows(Exception::class.java) { HistoryArchive.encode(archive(old.copy(peer = old.peer!!.copy(id = id(9))))) }
        assertThrows(Exception::class.java) { HistoryArchive.encode(archive(old, old)) }
        assertThrows(Exception::class.java) { HistoryArchive.encode(archive(old.copy(endedAtMs = 0))) }
        val merged = HistoryArchiveMerge.merge(archive(old), archive().copy(callDeletedIds = setOf(old.id)))
        assertTrue(merged.calls.isEmpty())
        assertTrue(HistoryArchiveMerge.merge(merged, a).calls.isEmpty())
        assertFalse(HistoryArchiveMerge.merge(archive(old), archive().copy(callChatClears = mapOf(id(6) to 1000))).calls.single().chatVisible)
        val deletedChat = HistoryArchiveMerge.merge(archive(old), archive().copy(chats = emptyList(), deletedChats = setOf(id(6))))
        assertEquals(old.id, deletedChat.calls.single().id); assertFalse(deletedChat.calls.single().chatVisible)
    }

    @Test fun `restoration admits only authenticated ids and later server pages cannot unlock other old calls`() = runBlocking {
        val directory = Files.createTempDirectory("coco-call-archive-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val auth = FakeAuthRepository(id(2))
            val json = Json { ignoreUnknownKeys = true }
            val fresh = CallDto(id(8), id(2), id(5), "audio", "ended", "missed", createdAt = "1970-01-01T00:00:10Z", endedAt = "1970-01-01T00:00:11Z")
            val oldPage = fresh.copy(id = id(9), createdAt = "1970-01-01T00:00:03Z", endedAt = "1970-01-01T00:00:04Z")
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val body = when {
                    chain.request().url.encodedPath.endsWith("/cleared") -> "[]"
                    chain.request().url.encodedPath.endsWith("/deletions") -> "{\"items\":[],\"has_more\":false}"
                    else -> json.encodeToString(CallHistoryPage.serializer(), CallHistoryPage(listOf(fresh, oldPage), historyRestored = true, visibleSince = "1970-01-01T00:00:05Z"))
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = CallApiImpl(client, json, "https://test.invalid", auth)
            val repo = CallHistoryRepository(directory, "https://test.invalid", api, auth, scope, restoredCallDeletions = { emptySet() })
            repo.refresh()
            assertEquals(listOf(fresh.id), repo.calls.value.map { it.id })
            repo.restoreHistory(archive(old), auth.currentSessionEpoch())
            assertEquals(listOf(fresh.id, old.id), repo.calls.value.map { it.id })
            assertFalse(repo.calls.value.last().unread)
            repo.refresh()
            assertEquals(listOf(fresh.id, old.id), repo.calls.value.map { it.id })
            val offline = CallHistoryRepository(directory, "https://test.invalid", api, auth, scope, restoredCallDeletions = { emptySet() })
            withTimeout(3000) { while (offline.calls.value.size != 2) delay(10) }
            assertEquals(listOf(fresh.id, old.id), offline.calls.value.map { it.id })
            assertEquals("Bob", offline.calls.value.last().peer?.displayName)
            val exported = offline.exportHistory(archive(), auth.currentSessionEpoch())
            assertEquals(setOf(fresh.id, old.id), exported.calls.map { it.id }.toSet())
            auth.logout()
            assertThrows(Exception::class.java) { runBlocking { repo.restoreHistory(archive(old), 1) } }
            assertTrue(repo.calls.value.isEmpty())
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun `message failure rolls back calls and restored deletion policies together`() = runBlocking {
        val directory = Files.createTempDirectory("coco-call-rollback-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = OkHttpClient()
        try {
            val auth = FakeAuthRepository(id(2))
            val api = CallApiImpl(client, Json { ignoreUnknownKeys = true }, "https://test.invalid", auth)
            val repo = CallHistoryRepository(directory, "https://test.invalid", api, auth, scope)
            repo.restoreHistory(archive(old), auth.currentSessionEpoch())
            val before = directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
            val next = archive().copy(callDeletedIds = setOf(old.id), callClearedBeforeMs = 5000,
                callChatClears = mapOf(id(6) to 5000))
            assertTrue(runCatching {
                repo.restoreHistory(next, auth.currentSessionEpoch()) { commit -> commit(); error("Room failure") }
            }.isFailure)
            assertEquals(listOf(old.id), repo.calls.value.map { it.id })
            assertEquals(before, directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() })
            val exported = repo.exportHistory(archive(), auth.currentSessionEpoch())
            assertEquals(listOf(old), exported.calls)
            assertTrue(exported.callDeletedIds.isEmpty()); assertEquals(0L, exported.callClearedBeforeMs)
            repo.restoreHistory(next, auth.currentSessionEpoch())
            val cleared = repo.exportHistory(archive(), auth.currentSessionEpoch())
            assertTrue(cleared.calls.isEmpty()); assertEquals(setOf(old.id), cleared.callDeletedIds)
            assertEquals(5000L, cleared.callClearedBeforeMs); assertEquals(mapOf(id(6) to 5000L), cleared.callChatClears)
        } finally { scope.cancel(); directory.deleteRecursively(); client.dispatcher.executorService.shutdown() }
    }
}
