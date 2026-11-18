package com.messenger.app.data.chat

import com.messenger.app.data.api.ChatsApi
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.data.sync.InMemoryChatDao
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class HostileChatBindingTest {
    @Test fun `legacy peerless history shell acquires its first real binding then rejects substitution`() = runBlocking {
        val dao = InMemoryChatDao(); dao.upsertChatMetadata("chat","group","2020-01-01",null)
        val disk = PreferenceWorkspace(dao); var response = dto("alice")
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(response.toResponseBody()).build() }.build()
        fun repo() = ChatRepositoryImpl(ChatsApi(client,Json { ignoreUnknownKeys = true },"https://unit.invalid"),dao,FakeAuthRepository(),workspaceDao=disk)
        repo().getChat("chat").getOrThrow(); repo().requireSendable("chat","alice")
        response = dto("mallory"); assertIs<ChatBindingException>(repo().getChat("chat").exceptionOrNull())
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
    @Test fun `frozen conversations do not starve other refreshes and settings unblock clears redaction`() = runBlocking {
        val dao = InMemoryChatDao(); val disk = PreferenceWorkspace(dao); val auth = FakeAuthRepository()
        var response = dto("alice")
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(response.toResponseBody()).build() }.build()
        val repo = ChatRepositoryImpl(ChatsApi(client,Json { ignoreUnknownKeys = true },"https://unit.invalid"),dao,auth,workspaceDao=disk)
        repo.getChat("chat").getOrThrow()
        response = dto(null); repo.getChat("chat").getOrThrow()
        repo.updateKnownPeerBlock("alice",false,auth.currentSessionEpoch())
        repo.requireSendable("chat","alice")
        response = dto("mallory"); assertTrue(repo.getChat("chat").isFailure)
        response = """{"items":[${dto("alice")},${dto("bob","other").dropLast(1)},"is_blocked":true}]}"""
        repo.refreshKnownChats(listOf("chat","other")).getOrThrow()
        assertTrue(dao.cachedChat("other")!!.isBlocked)
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
    private fun dto(peer: String?, id: String = "chat") = """{"chat_id":"$id","type":"direct","created_at":"2026-01-01","other_user":${peer?.let { """{"id":"$it","username":"$it","display_name":"$it"}""" } ?: "null"}}"""
    @Test fun `substitution freezes across repository reopen but redaction and unblock do not`() = runBlocking {
        val dao = InMemoryChatDao(); val disk = PreferenceWorkspace(dao); val auth = FakeAuthRepository()
        var response = dto("alice")
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(response.toResponseBody()).build() }.build()
        fun repo() = ChatRepositoryImpl(ChatsApi(client, Json { ignoreUnknownKeys = true }, "https://unit.invalid"), dao, auth, workspaceDao = disk)
        val repository = repo()
        repository.getChat("chat").getOrThrow(); repository.requireSendable("chat", "alice")
        response = dto(null); assertNull(repository.getChat("chat").getOrThrow().otherUser)
        assertNotEquals("1", disk.get("chat", "security_frozen"))
        assertFailsWith<IllegalStateException> { repository.requireSendable("chat", "alice") }
        response = dto("alice"); repo().getChat("chat").getOrThrow(); repo().requireSendable("chat", "alice")
        response = dto("mallory"); assertIs<ChatBindingException>(repo().getChat("chat").exceptionOrNull())
        response = dto("alice"); assertIs<ChatBindingException>(repo().getChat("chat").exceptionOrNull())
        assertFailsWith<ChatBindingException> { repo().requireSendable("chat", "alice") }
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
    @Test fun `create and get reject mismatched requested identities before returning or saving`() = runBlocking {
        val dao = InMemoryChatDao()
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(dto("mallory", "other-chat").toResponseBody()).build() }.build()
        val repo = ChatRepositoryImpl(ChatsApi(client, Json { ignoreUnknownKeys = true }, "https://unit.invalid"), dao, FakeAuthRepository(), workspaceDao = PreferenceWorkspace(dao))
        assertIs<ChatBindingException>(repo.createOrOpenDirectChat("alice").exceptionOrNull())
        assertIs<ChatBindingException>(repo.getChat("requested-chat").exceptionOrNull())
        assertNull(dao.cachedChat("other-chat"))
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
}
