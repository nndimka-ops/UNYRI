package com.messenger.app.data.chat

import com.messenger.app.data.api.ChatsApi
import com.messenger.app.data.local.chat.ChatMemberEntity
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.data.sync.InMemoryChatDao
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class KnownPeerBlockProjectionTest {
    @Test fun refreshCannotChangeLocalBlockOrExplicitUnblock() = runBlocking {
        val dao = InMemoryChatDao(); val auth = FakeAuthRepository()
        val security = com.messenger.app.data.secure.LocalSecurityDecisions(com.messenger.app.data.signal.InMemorySecureKeyValueStore()) { "owner" }
        dao.upsertChatMetadata("known", "direct", "today", null)
        dao.upsertMember(ChatMemberEntity("known", "peer", "member"))
        var serverBlocked = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"chat_id":"known","type":"direct","created_at":"today","is_blocked":$serverBlocked,"other_user":{"id":"peer","username":"peer","display_name":"Peer"}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = ChatRepositoryImpl(ChatsApi(client, Json { ignoreUnknownKeys = true }, "https://isolated.invalid"), dao, auth, localSecurity = security)
        repo.updateKnownPeerBlock("peer", true, auth.currentSessionEpoch())
        repeat(3) { assertTrue(repo.getChat("known").getOrThrow().isBlocked) }
        repo.updateKnownPeerBlock("peer", false, auth.currentSessionEpoch())
        serverBlocked = true
        repeat(3) { assertFalse(repo.getChat("known").getOrThrow().isBlocked) }
        assertEquals(false, security.blockDecision("peer"))
    }

    @Test fun `account projection only changes existing matching direct chats and keeps read markers`() = runBlocking {
        val dao = InMemoryChatDao()
        dao.upsertChatMetadata("known", "direct", "today", null)
        dao.upsertMember(ChatMemberEntity("known", "peer", "member"))
        dao.updateLastReadSequence("known", 91)
        dao.recordPeerReceipt("known", 92, 90)
        dao.updatePreferences("known", true, "later", false, null)
        dao.upsertChatMetadata("other", "direct", "today", null)
        dao.upsertMember(ChatMemberEntity("other", "other-peer", "member"))
        dao.upsertChatMetadata("saved", "saved", "today", null)
        dao.upsertMember(ChatMemberEntity("saved", "peer", "member"))
        val auth = FakeAuthRepository()
        val api = ChatsApi(OkHttpClient(), Json, "https://never.invalid")
        val repo = ChatRepositoryImpl(api, dao, auth)
        assertTrue(repo.updateKnownPeerBlock("peer", true, auth.currentSessionEpoch()))
        val known = dao.cachedChat("known")!!
        assertTrue(known.isBlocked); assertTrue(known.isMuted); assertEquals("later", known.mutedUntil)
        assertEquals(91L, known.lastReadSequence); assertEquals(92L, known.peerDeliveredSequence); assertEquals(90L, known.peerReadSequence)
        assertFalse(dao.cachedChat("other")!!.isBlocked); assertFalse(dao.cachedChat("saved")!!.isBlocked)
        repo.updateKnownPeerBlock("unknown", true, auth.currentSessionEpoch())
        assertNull(dao.cachedChat("unknown"))
        val original = auth.currentSessionEpoch(); auth.switchSession()
        assertFalse(repo.updateKnownPeerBlock("peer", false, original)); assertTrue(dao.cachedChat("known")!!.isBlocked)
    }

    @Test fun `metadata request started before block acknowledgement cannot overwrite that acknowledgement`() = runBlocking {
        val dao = InMemoryChatDao()
        dao.upsertChatMetadata("known", "direct", "today", null)
        dao.upsertMember(ChatMemberEntity("known", "peer", "member"))
        val auth = FakeAuthRepository()
        lateinit var repo: ChatRepositoryImpl
        var callCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            callCount++
            runBlocking { repo.updateKnownPeerBlock("peer", true, auth.currentSessionEpoch()) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"chat_id":"known","type":"direct","created_at":"today","is_blocked":false}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        repo = ChatRepositoryImpl(ChatsApi(client, Json { ignoreUnknownKeys = true }, "https://isolated.invalid"), dao, auth)
        val projections = mutableListOf<Pair<String, Boolean>>()
        repo.onPeerBlockChanged = { id, blocked -> projections += id to blocked }
        val returned = repo.getChat("known").getOrThrow()
        assertTrue(returned.isBlocked); assertEquals("peer", returned.blockedUserId)
        assertEquals(1, callCount); assertTrue(dao.cachedChat("known")!!.isBlocked); assertEquals("peer", dao.cachedChat("known")!!.blockedUserId)
        assertEquals(listOf("peer" to true), projections)
    }

    @Test fun `a confirmed refreshed unblock projects after commit and unchanged refresh does not repeat it`() = runBlocking {
        val dao = InMemoryChatDao()
        dao.upsertChatMetadata("known", "direct", "today", null)
        dao.updatePreferences("known", false, null, true, "peer")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"chat_id":"known","type":"direct","created_at":"today","is_blocked":false,"other_user":{"id":"peer","username":"anna","display_name":"Anna"}}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = ChatRepositoryImpl(ChatsApi(client, Json { ignoreUnknownKeys = true }, "https://isolated.invalid"), dao, FakeAuthRepository())
        val projections = mutableListOf<Pair<String, Boolean>>()
        repo.onPeerBlockChanged = { id, blocked ->
            assertEquals(blocked, runBlocking { dao.cachedChat("known")!!.isBlocked })
            projections += id to blocked
        }
        repo.getChat("known").getOrThrow()
        repo.getChat("known").getOrThrow()
        assertEquals(listOf("peer" to false), projections)
    }

    @Test fun `retired-session refreshed unblock cannot project into the next account`() = runBlocking {
        val dao = InMemoryChatDao()
        dao.upsertChatMetadata("known", "direct", "today", null)
        dao.updatePreferences("known", false, null, true, "peer")
        val auth = FakeAuthRepository()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            auth.switchSession()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"chat_id":"known","type":"direct","created_at":"today","is_blocked":false}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = ChatRepositoryImpl(ChatsApi(client, Json { ignoreUnknownKeys = true }, "https://isolated.invalid"), dao, auth)
        val projections = mutableListOf<Pair<String, Boolean>>()
        repo.onPeerBlockChanged = { id, blocked -> projections += id to blocked }
        assertTrue(repo.getChat("known").isFailure)
        assertTrue(projections.isEmpty())
        assertTrue(dao.cachedChat("known")!!.isBlocked)
    }
}
