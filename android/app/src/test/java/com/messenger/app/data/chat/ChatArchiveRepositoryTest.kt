package com.messenger.app.data.chat

import com.messenger.app.data.api.ChatsApi
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.data.local.chat.ChatWorkspaceDao
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.data.sync.InMemoryChatDao
import com.messenger.app.ui.chats.ChatArchiveVisibility
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Actual repository and HTTP serialization with isolated database/transport doubles. */
class ChatArchiveRepositoryTest {
    private val a = "10000000-0000-4000-8000-000000000001"
    private val b = "10000000-0000-4000-8000-000000000002"
    private fun api(status: Int = 200, handler: (Request) -> String): ChatsApi = ChatsApi(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(handler(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build(), Json { ignoreUnknownKeys = true }, "https://isolated.invalid")
    private suspend fun chats() = InMemoryChatDao().also {
        it.upsertChatMetadata(a, "direct", "2026-01-01", null)
        it.upsertChatMetadata(b, "direct", "2026-01-01", null)
    }
    private fun Request.bodyText(): String = okio.Buffer().also { body!!.writeTo(it) }.readUtf8()

    @Test fun offlineArchiveAndRestoreSurviveRepositoryRecreationWithoutChangingMute() = runBlocking {
        val dao = chats(); dao.updatePreferences(a, true, null, false, null)
        val disk = PreferenceWorkspace(dao); val auth = FakeAuthRepository()
        var requests = 0
        val service = api { requests++; throw java.io.IOException("offline") }
        val repo = ChatRepositoryImpl(service, dao, auth, workspaceDao = disk)
        assertEquals(setOf(a, b), repo.setArchived(setOf(a, b), true))
        assertEquals(setOf(b), repo.setArchived(setOf(a), false))
        assertEquals(0, requests)
        repo.replayPreferences()
        assertEquals(2, disk.pendingPreferences().size)
        assertEquals(setOf(b), ChatRepositoryImpl(service, dao, auth, workspaceDao = disk).localArchives())
        assertTrue(dao.cachedChat(a)!!.isMuted)
        assertFalse(dao.cachedChat(b)!!.isMuted)
    }

    @Test fun archiveWireUsesGetAndPutWithExplicitFalseAndReplaysOnlyLatestIntent() = runBlocking {
        val dao = chats(); val disk = PreferenceWorkspace(dao); val requests = mutableListOf<Request>()
        val sent = mutableListOf<String>()
        val service = api { request ->
            requests += request
            if (request.method == "PUT") sent += request.bodyText()
            "{\"chat_ids\":[]}"
        }
        val repo = ChatRepositoryImpl(service, dao, FakeAuthRepository(), workspaceDao = disk)
        repo.setArchived(setOf(a), true); repo.setArchived(setOf(a), false); repo.replayPreferences()
        assertEquals(1, sent.size)
        assertTrue(sent.single().contains("\"archived\":false"))
        assertTrue(sent.single().contains(a))
        assertTrue(repo.localArchives().isEmpty()); assertTrue(disk.pendingPreferences().isEmpty())
        assertTrue(repo.refreshArchives().isEmpty())
        assertEquals(listOf("PUT", "GET"), requests.map { it.method })
        assertTrue(requests.all { it.url.encodedPath == "/chats/archives" })
    }

    @Test fun staleServerSnapshotCannotUndoAnArchiveChosenDuringRequest() = runBlocking {
        val dao = chats(); val disk = PreferenceWorkspace(dao)
        lateinit var repo: ChatRepositoryImpl
        repo = ChatRepositoryImpl(api {
            runBlocking { repo.setArchived(setOf(a), true) }
            "{\"chat_ids\":[]}"
        }, dao, FakeAuthRepository(), workspaceDao = disk)
        assertEquals(setOf(a), repo.refreshArchives())
        assertEquals(setOf(a), repo.localArchives())
        assertEquals(1, disk.pendingPreferences().size)
    }

    @Test fun newerRestoreDuringArchiveUploadIsReplayedWithoutWaitingForReconnect() = runBlocking {
        val dao = chats(); val disk = PreferenceWorkspace(dao); val sent = mutableListOf<String>()
        lateinit var repo: ChatRepositoryImpl
        repo = ChatRepositoryImpl(api { request ->
            sent += request.bodyText()
            if (sent.size == 1) runBlocking { repo.setArchived(setOf(a), false); repo.replayPreferences() }
            "{\"chat_ids\":[]}"
        }, dao, FakeAuthRepository(), workspaceDao = disk)
        repo.setArchived(setOf(a), true); repo.replayPreferences()
        assertEquals(2, sent.size)
        assertTrue(sent[0].contains("\"archived\":true")); assertTrue(sent[1].contains("\"archived\":false"))
        assertTrue(repo.localArchives().isEmpty()); assertTrue(disk.pendingPreferences().isEmpty())
    }

    @Test fun unsupportedOldServerRetainsArchiveIntentAndLocalState() = runBlocking {
        val dao = chats(); val disk = PreferenceWorkspace(dao)
        val repo = ChatRepositoryImpl(api(404) { "{\"error\":{\"code\":\"not_found\",\"message\":\"Older server\"}}" }, dao, FakeAuthRepository(), workspaceDao = disk)
        repo.setArchived(setOf(a), true); repo.replayPreferences()
        assertEquals(setOf(a), repo.localArchives()); assertEquals(1, disk.pendingPreferences().size)
    }

    @Test fun typedMissingMembershipClearsOnlyItsOwnStaleArchiveIntent() = runBlocking {
        val dao = chats(); val disk = PreferenceWorkspace(dao)
        val repo = ChatRepositoryImpl(api(404) { "{\"error\":{\"code\":\"chat_not_found\",\"message\":\"Chat not found\"}}" }, dao, FakeAuthRepository(), workspaceDao = disk)
        repo.setArchived(setOf(a), true); repo.replayPreferences()
        assertTrue(repo.localArchives().isEmpty()); assertTrue(disk.pendingPreferences().isEmpty())
        assertNotNull(dao.cachedChat(a)) // never deletes history or membership locally
    }

    @Test fun failedOldIntentCannotEraseNewerIntent() = runBlocking {
        val dao = chats(); val disk = PreferenceWorkspace(dao); var count = 0
        lateinit var repo: ChatRepositoryImpl
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            if (count == 1) runBlocking { repo.setArchived(setOf(a), false) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (count == 1) 404 else 200).message("Fixture")
                .body((if (count == 1) "{\"error\":{\"code\":\"chat_not_found\",\"message\":\"Gone\"}}" else "{\"chat_ids\":[]}").toResponseBody("application/json".toMediaType())).build()
        }.build()
        repo = ChatRepositoryImpl(ChatsApi(client, Json { ignoreUnknownKeys = true }, "https://isolated.invalid"), dao, FakeAuthRepository(), workspaceDao = disk)
        repo.setArchived(setOf(a), true); repo.replayPreferences()
        assertEquals(2, count); assertTrue(repo.localArchives().isEmpty()); assertTrue(disk.pendingPreferences().isEmpty())
    }

    @Test fun aDifferentAccountCannotReadOrReplayThePreviousArchive() = runBlocking {
        val dao = chats(); val disk = PreferenceWorkspace(dao); val auth = FakeAuthRepository(); var requests = 0
        val repo = ChatRepositoryImpl(api { requests++; "{\"chat_ids\":[]}" }, dao, auth, workspaceDao = disk)
        repo.setArchived(setOf(a), true); auth.switchSession("different-owner")
        assertTrue(repo.localArchives().isEmpty()); repo.replayPreferences()
        assertEquals(0, requests); assertEquals(1, disk.pendingPreferences().size)
    }

    @Test fun sessionChangeDuringGetCannotStoreTheOldServerSnapshot() = runBlocking {
        val dao = chats(); val disk = PreferenceWorkspace(dao); val auth = FakeAuthRepository()
        val repo = ChatRepositoryImpl(api { auth.switchSession(); "{\"chat_ids\":[\"$a\"]}" }, dao, auth, workspaceDao = disk)
        assertTrue(runCatching { repo.refreshArchives() }.isFailure)
        assertTrue(disk.allValues().isEmpty()); assertTrue(repo.localArchives().isEmpty())
    }

    @Test fun sessionRejectedBeforeWriteDoesNotClaimToArchive() = runBlocking {
        val dao = chats(); val disk = PreferenceWorkspace(dao); val auth = FakeAuthRepository().also { it.switchEpochOnWrite = true }
        val repo = ChatRepositoryImpl(api { error("No network expected") }, dao, auth, workspaceDao = disk)
        assertTrue(runCatching { repo.setArchived(setOf(a), true) }.isFailure)
        assertTrue(disk.allValues().isEmpty())
    }

    @Test fun savedOrUnknownTargetRejectsWholeBatchBeforeWriting() = runBlocking {
        val dao = chats(); dao.upsertChatMetadata("saved", "saved", "2026-01-01", null)
        val disk = PreferenceWorkspace(dao)
        val repo = ChatRepositoryImpl(api { error("No network expected") }, dao, FakeAuthRepository(), workspaceDao = disk)
        for (target in listOf("saved", "unknown")) {
            assertTrue(runCatching { repo.setArchived(setOf(a, target), true) }.isFailure)
            assertTrue(disk.allValues().isEmpty())
        }
    }

    @Test fun unavailableLocalJournalDoesNotReportSuccess() = runBlocking {
        val dao = chats(); val state = PreferenceWorkspace(dao)
        val disk = object : ChatWorkspaceDao by state {
            override suspend fun queueArchives(values: List<ChatWorkspaceValue>, snapshot: ChatWorkspaceValue) { error("Disk full") }
        }
        val repo = ChatRepositoryImpl(api { error("No network expected") }, dao, FakeAuthRepository(), workspaceDao = disk)
        assertTrue(runCatching { repo.setArchived(setOf(a), true) }.isFailure)
        assertTrue(state.allValues().isEmpty()); assertTrue(repo.localArchives().isEmpty())
    }

    @Test fun archiveIdsDoNotDiscoverOrHydrateOldHistory() = runBlocking {
        val dao = InMemoryChatDao(); val disk = PreferenceWorkspace(dao); val paths = mutableListOf<String>()
        val repo = ChatRepositoryImpl(api { request -> paths += request.url.encodedPath; "{\"chat_ids\":[\"$a\"]}" }, dao, FakeAuthRepository(), workspaceDao = disk)
        assertEquals(setOf(a), repo.refreshArchives())
        repo.refreshChats().getOrThrow()
        assertEquals(listOf("/chats/archives"), paths); assertNull(dao.cachedChat(a))
        assertFalse(ChatArchiveVisibility.includes(a, "saved", setOf(a), true))
        assertTrue(ChatArchiveVisibility.includes(a, "direct", setOf(a), true))
        assertFalse(ChatArchiveVisibility.includes(a, "direct", setOf(a), false))
        assertTrue(ChatArchiveVisibility.includes(b, "direct", setOf(a), false))
    }

    @Test fun newMessageMetadataDoesNotAutomaticallyRestoreArchivedChat() = runBlocking {
        val dao = chats(); val disk = PreferenceWorkspace(dao)
        val repo = ChatRepositoryImpl(api { request ->
            if (request.url.encodedPath == "/chats/$a") "{\"chat_id\":\"$a\",\"type\":\"direct\",\"created_at\":\"2026-01-01\",\"last_message_at\":\"2026-11-01T13:00:00Z\"}"
            else "{\"chat_ids\":[\"$a\"]}"
        }, dao, FakeAuthRepository(), workspaceDao = disk)
        repo.setArchived(setOf(a), true); repo.replayPreferences()
        repo.getChat(a).getOrThrow()
        assertEquals(setOf(a), repo.localArchives()); assertEquals(setOf(a), repo.refreshArchives())
    }

    @Test fun selectedServerOwnsItsArchiveCacheAndPendingCommands() = runBlocking {
        val selected = ServerEndpoint::class.java.getDeclaredField("selected").apply { isAccessible = true }
        val before = selected.get(null)
        try {
            selected.set(null, "https://first.invalid")
            val dao = chats(); val disk = PreferenceWorkspace(dao); var requests = 0
            val repo = ChatRepositoryImpl(api { requests++; "{\"chat_ids\":[]}" }, dao, FakeAuthRepository(), workspaceDao = disk)
            repo.setArchived(setOf(a), true)
            selected.set(null, "https://second.invalid")
            assertTrue(repo.localArchives().isEmpty()); repo.replayPreferences(); assertEquals(0, requests)
            selected.set(null, "https://first.invalid")
            assertEquals(setOf(a), repo.localArchives()); assertEquals(1, disk.pendingPreferences().size)
        } finally { selected.set(null, before) }
    }
}
