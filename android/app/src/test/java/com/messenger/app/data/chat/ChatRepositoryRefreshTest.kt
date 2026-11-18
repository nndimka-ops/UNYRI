package com.messenger.app.data.chat

import com.messenger.app.data.api.ChatsApi
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.data.sync.InMemoryChatDao
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ChatRepositoryRefreshTest {
    private fun api(status: Int = 200, body: (okhttp3.Request) -> String): ChatsApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Test").body(body(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return ChatsApi(client, Json { ignoreUnknownKeys = true }, "https://isolated.invalid")
    }
    private fun page(id: String, next: String = "null") =
        """{"items":[{"chat_id":"$id","type":"direct","created_at":"2026-01-01"}],"next_offset":$next}"""

    @Test fun newDeviceDoesNotRequestAccountChatList() = runBlocking {
        var requests = 0
        val dao = InMemoryChatDao()
        dao.upsertChatMetadata("old-server-row", "direct", "2020-01-01", null)
        val repo = ChatRepositoryImpl(api { requests++; page("old-server-row") }, dao, FakeAuthRepository())
        repo.refreshChats().getOrThrow()
        assertEquals(0, requests)
        assertNotNull(dao.cachedChat("old-server-row")) // no destructive cache cleanup
    }

    @Test fun refreshRequestsOnlyKnownIdsInBoundedBatches() = runBlocking {
        val batches = mutableListOf<List<String>>()
        val dao = InMemoryChatDao().apply { knownChatIds = (0..50).map { "id%03d".format(it) } }
        val repo = ChatRepositoryImpl(api { request ->
            assertEquals("/chats/known", request.url.encodedPath)
            val ids = request.url.queryParameter("chat_ids")!!.split(","); batches += ids
            page(ids.first())
        }, dao, FakeAuthRepository())
        repo.refreshChats().getOrThrow()
        assertEquals(listOf(50, 1), batches.map { it.size })
        assertEquals(dao.knownChatIds, batches.flatten())
        assertNotNull(dao.cachedChat("id000")); assertNotNull(dao.cachedChat("id050"))
    }

    @Test fun failedLaterBatchKeepsAlreadyReceivedChats() = runBlocking {
        val dao = InMemoryChatDao().apply { knownChatIds = (0..50).map { "id%03d".format(it) } }
        var requests = 0
        val repo = ChatRepositoryImpl(api { request ->
            requests++
            if (requests == 1) page(request.url.queryParameter("chat_ids")!!.split(",").first())
            else throw java.io.IOException("offline")
        }, dao, FakeAuthRepository())
        assertTrue(repo.refreshChats().isFailure)
        assertNotNull(dao.cachedChat("id000"))
    }

    @Test fun unrequestedChatsAreNeverPersisted() = runBlocking {
        val dao = InMemoryChatDao().apply { knownChatIds = listOf("known") }
        val repo = ChatRepositoryImpl(api { page("old-unrequested") }, dao, FakeAuthRepository())
        assertTrue(repo.refreshChats().isFailure)
        assertNull(dao.cachedChat("old-unrequested"))
    }

    @Test fun firstIncomingChatCanHydrateWithoutExistingMetadata() = runBlocking {
        val dao = InMemoryChatDao()
        val repo = ChatRepositoryImpl(api { request ->
            assertEquals("new", request.url.queryParameter("chat_ids")); page("new")
        }, dao, FakeAuthRepository())
        repo.refreshKnownChats(listOf("new")).getOrThrow()
        assertNotNull(dao.cachedChat("new"))
    }

    @Test fun olderServerDoesNotTriggerAnUnfilteredFallback() = runBlocking {
        val dao = InMemoryChatDao().apply { knownChatIds = listOf("known") }
        val paths = mutableListOf<String>()
        val repo = ChatRepositoryImpl(api(status=404) { request ->
            paths += request.url.encodedPath; "{}"
        }, dao, FakeAuthRepository())
        assertTrue(repo.refreshChats().isFailure)
        assertEquals(listOf("/chats/known"), paths)
    }

    @Test fun accountSwitchDuringTokenLookupNeverStartsRequest() = runBlocking {
        val original = FakeAuthRepository()
        val auth = object : AuthRepository by original {
            override suspend fun currentAccessToken(): String {
                original.switchSession(); return "old-token"
            }
        }
        var requested = false
        val repo = ChatRepositoryImpl(api { requested = true; page("old") }, InMemoryChatDao(), auth)
        assertTrue(repo.refreshChats().isFailure)
        assertFalse(requested)
    }

    @Test fun lateResponseAfterAccountSwitchIsNeitherPublishedNorReturned() = runBlocking {
        val auth = FakeAuthRepository()
        val dao = InMemoryChatDao()
        val repo = ChatRepositoryImpl(api {
            auth.switchSession()
            """{"chat_id":"old","type":"direct","created_at":"2026-01-01"}"""
        }, dao, auth)
        assertTrue(repo.getChat("old").isFailure)
        assertNull(dao.cachedChat("old"))
    }

    @Test fun preferencesUpdateImmediatelyAndPreserveReceipts() = runBlocking {
        val dao = InMemoryChatDao()
        dao.upsertChatMetadata("a", "direct", "2026-01-01", null)
        dao.updateLastReadSequence("a", 42)
        dao.recordPeerReceipt("a", 51, 50)
        var requests = 0
        val repo = ChatRepositoryImpl(api { requests++; "null" }, dao, FakeAuthRepository(), workspaceDao = PreferenceWorkspace(dao))
        repo.setMute("a", null).getOrThrow()
        repo.blockUser("a", "peer").getOrThrow()
        assertTrue(dao.cachedChat("a")!!.isMuted)
        assertEquals("peer", dao.cachedChat("a")!!.blockedUserId)
        repo.clearMute("a").getOrThrow()
        assertTrue(dao.cachedChat("a")!!.isBlocked)
        repo.unblockUser("a").getOrThrow()
        val chat = dao.cachedChat("a")!!
        assertFalse(chat.isMuted); assertFalse(chat.isBlocked)
        assertNull(chat.mutedUntil); assertNull(chat.blockedUserId)
        assertEquals(42L, chat.lastReadSequence)
        assertEquals(51L, chat.peerDeliveredSequence)
        assertEquals(50L, chat.peerReadSequence)
        assertEquals(2, requests) // Only block/unblock require a server response.
    }

    @Test fun unavailableDurableStoreDoesNotPretendSuccess() = runBlocking {
        val dao = InMemoryChatDao()
        dao.upsertChatMetadata("a", "direct", "2026-01-01", null)
        val repo = ChatRepositoryImpl(api { throw java.io.IOException("offline") }, dao, FakeAuthRepository())
        assertTrue(repo.setMute("a", null).isFailure)
        assertFalse(dao.cachedChat("a")!!.isMuted)
    }

    @Test fun preferenceResponseAfterAccountSwitchDoesNotChangeCache() = runBlocking {
        val dao = InMemoryChatDao()
        val auth = FakeAuthRepository()
        dao.upsertChatMetadata("a", "direct", "2026-01-01", null)
        val repo = ChatRepositoryImpl(api { auth.switchSession(); "null" }, dao, auth)
        assertTrue(repo.blockUser("a", "peer").isFailure)
        assertTrue(dao.cachedChat("a")!!.isBlocked) // The old account decision predates the switch.
    }

    @Test fun preferenceDoesNotRecreateMissingChat() = runBlocking {
        val dao = InMemoryChatDao()
        val repo = ChatRepositoryImpl(api { "null" }, dao, FakeAuthRepository())
        assertTrue(repo.setMute("gone", null).isFailure)
        assertNull(dao.cachedChat("gone"))
    }
    @Test fun refreshStartedBeforeAcknowledgementCannotUndoMute() = runBlocking {
        val dao = InMemoryChatDao().apply { knownChatIds = listOf("a") }
        dao.upsertChatMetadata("a", "direct", "2026-01-01", null)
        val workspace = PreferenceWorkspace(dao)
        lateinit var repo: ChatRepositoryImpl
        repo = ChatRepositoryImpl(api { request ->
            if (request.url.encodedPath == "/chats/known") {
                runBlocking { repo.replayPreferences() }
                page("a") // stale response, no mute
            } else "null"
        }, dao, FakeAuthRepository(), workspaceDao = workspace)
        repo.setMute("a").getOrThrow()
        repo.refreshChats().getOrThrow()
        assertTrue(workspace.pendingPreferences().isEmpty())
        assertTrue(dao.cachedChat("a")!!.isMuted)
    }

    @Test fun offlinePinsReplayLatestIntentAfterReopen() = runBlocking {
        val dao = InMemoryChatDao()
        val workspace = PreferenceWorkspace(dao)
        val auth = FakeAuthRepository()
        val prefs = preferencePins()
        var online = false
        val sent = mutableListOf<String>()
        val service = api { request ->
            if (!online) throw java.io.IOException("offline")
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer); sent += buffer.readUtf8()
            "{\"chat_ids\":[]}"
        }
        val repo = ChatRepositoryImpl(service, dao, auth, workspaceDao = workspace, pinPreferences = prefs)
        assertEquals(setOf("a", "b"), repo.setPins(setOf("a", "b"), true))
        assertEquals(setOf("b"), repo.setPins(setOf("a"), false))
        repo.replayPreferences()
        assertEquals(2, workspace.pendingPreferences().size)
        val reopened = ChatRepositoryImpl(service, dao, auth, workspaceDao = workspace, pinPreferences = prefs)
        assertEquals(setOf("b"), reopened.localPins())
        online = true
        reopened.replayPreferences()
        assertEquals(2, sent.size)
        assertTrue(sent.any { it.contains("false") && it.contains("a") })
        assertTrue(sent.any { it.contains("true") && it.contains("b") })
        assertTrue(workspace.pendingPreferences().isEmpty())
    }

    @Test fun offlineMuteSurvivesRepositoryRecreationAndReplaysLatestIntent() = runBlocking {
        val dao = InMemoryChatDao()
        dao.upsertChatMetadata("a", "direct", "2026-01-01", null)
        val workspace = PreferenceWorkspace(dao)
        val auth = FakeAuthRepository()
        var online = false
        val paths = mutableListOf<String>()
        val service = api { request ->
            if (!online) throw java.io.IOException("offline")
            paths += request.url.encodedPath
            "null"
        }
        val repo = ChatRepositoryImpl(service, dao, auth, workspaceDao = workspace)
        repo.setMute("a").getOrThrow()
        assertTrue(dao.cachedChat("a")!!.isMuted)
        repo.replayPreferences()
        assertEquals(1, workspace.allValues().size)
        repo.clearMute("a").getOrThrow()
        assertFalse(dao.cachedChat("a")!!.isMuted)
        online = true
        ChatRepositoryImpl(service, dao, auth, workspaceDao = workspace).replayPreferences()
        assertEquals(listOf("/chats/a/unmute"), paths)
        assertTrue(workspace.allValues().isEmpty())
    }
    @Test fun oldAcknowledgementDoesNotEraseNewerMuteIntent() = runBlocking {
        val dao = InMemoryChatDao()
        dao.upsertChatMetadata("a", "direct", "2026-01-01", null)
        val workspace = PreferenceWorkspace(dao)
        lateinit var repo: ChatRepositoryImpl
        var first = true
        val sent = mutableListOf<String>()
        repo = ChatRepositoryImpl(api { request ->
            sent += request.url.encodedPath
            if (first) { first = false; runBlocking { repo.clearMute("a").getOrThrow() } }
            "null"
        }, dao, FakeAuthRepository(), workspaceDao = workspace)
        repo.setMute("a").getOrThrow()
        repo.replayPreferences()
        assertFalse(dao.cachedChat("a")!!.isMuted)
        assertEquals(listOf("/chats/a/mute", "/chats/a/unmute"), sent)
        assertTrue(workspace.allValues().isEmpty())
        repo.replayPreferences()
        assertTrue(workspace.allValues().isEmpty())
        assertEquals(2, sent.size)
    }

}

internal class PreferenceWorkspace(private val dao: InMemoryChatDao) : com.messenger.app.data.local.chat.ChatWorkspaceDao {
    override suspend fun directPreviewPage(chat: String, after: String) = values.values.filter { it.chatId == chat && it.name.startsWith("direct_transfer:") && it.name > after }.sortedBy { it.name }.take(64)
    override fun observeSecurityFreeze(chatId: String) = kotlinx.coroutines.flow.flowOf(values[chatId to "security_frozen"]?.value == "1")
    override suspend fun directRecoveryUpperRowId(): Long = values.values
        .filter { it.name.startsWith("direct_transfer:") }
        .maxOfOrNull { rowIds.getValue(it.chatId to it.name) } ?: 0L
    override suspend fun directRecoveryPage(afterRowId: Long, upperRowId: Long): List<com.messenger.app.data.local.chat.ChatWorkspaceRecoveryRow> =
        values.values.asSequence().filter { it.name.startsWith("direct_transfer:") }
            .map { value -> com.messenger.app.data.local.chat.ChatWorkspaceRecoveryRow(rowIds.getValue(value.chatId to value.name), value.chatId, value.name, value.value) }
            .filter { it.rowId > afterRowId && it.rowId <= upperRowId }.sortedBy { it.rowId }.take(8).toList()
    override suspend fun historyDirectTransferPage(offset: Int) = allDirectTransfers().drop(offset).take(8)
    override suspend fun allDirectTransfers() = values.values.filter { it.name.startsWith("direct_transfer:") }
    override fun observeAllDirectTransfers() = kotlinx.coroutines.flow.flow { emit(allDirectTransfers()) }
    override fun directTransfers(chatId: String) = kotlinx.coroutines.flow.flow { emit(allDirectTransfers().filter { it.chatId == chatId }) }
    override fun conferenceDecisions(chatId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.messenger.app.data.local.chat.ChatWorkspaceValue>())
    private val values = linkedMapOf<Pair<String,String>, com.messenger.app.data.local.chat.ChatWorkspaceValue>()
    private val cacheProtection = kotlinx.coroutines.flow.MutableStateFlow<List<com.messenger.app.data.local.chat.ChatWorkspaceValue>>(emptyList())
    override fun observeCacheProtection() = cacheProtection
    private val rowIds = linkedMapOf<Pair<String,String>, Long>()
    override suspend fun projectMute(chat: String, enabled: Boolean, until: String?) {
        val old = dao.cachedChat(chat) ?: return
        dao.updatePreferences(chat, enabled, until, old.isBlocked, old.blockedUserId)
    }
    override suspend fun clearCallActivity() = Unit
    override suspend fun mediaJobs(chatId: String) = emptyList<com.messenger.app.data.local.chat.ChatWorkspaceValue>()
    override suspend fun mediaPresentations(chatId: String) = emptyList<com.messenger.app.data.local.chat.ChatWorkspaceValue>()
    override suspend fun historyArchiveValues() = emptyList<com.messenger.app.data.local.chat.ChatWorkspaceValue>()
    override suspend fun allValues() = values.values.toList()
    override suspend fun pendingPreferences() = values.values.filter { it.name.startsWith("pending_preference:") }
    override fun observePendingPreferences() = kotlinx.coroutines.flow.flowOf(values.values.any { it.name.startsWith("pending_preference:") })
    override suspend fun removeCompletedMediaJobs() = Unit
    override fun schedules(chatId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.messenger.app.data.local.chat.ChatWorkspaceValue>())
    override suspend fun get(chatId: String, name: String) = values[chatId to name]?.value
    override suspend fun put(value: com.messenger.app.data.local.chat.ChatWorkspaceValue) {
        val nextRowId = (rowIds.values.maxOrNull() ?: 0L) + 1L
        val key = value.chatId to value.name
        values[key] = value; rowIds[key] = nextRowId
        cacheProtection.value = values.values.toList()
    }
    override suspend fun remove(chatId: String, name: String) { val key = chatId to name; values.remove(key); rowIds.remove(key); cacheProtection.value = values.values.toList() }
}

private fun preferencePins(): android.content.SharedPreferences {
    val rows = mutableMapOf<String, Set<String>>()
    lateinit var editor: android.content.SharedPreferences.Editor
    editor = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor::class.java.classLoader,
        arrayOf(android.content.SharedPreferences.Editor::class.java)) { _, method, args ->
        when(method.name) {
            "putStringSet" -> { @Suppress("UNCHECKED_CAST") val value = args[1] as Set<String>; rows[args[0] as String] = value.toSet(); editor }
            "commit" -> true
            "apply" -> null
            else -> editor
        }
    } as android.content.SharedPreferences.Editor
    return java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader,
        arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
        when(method.name) { "edit" -> editor; "getStringSet" -> rows[args[0] as String] ?: emptySet<String>(); else -> null }
    } as android.content.SharedPreferences
}
