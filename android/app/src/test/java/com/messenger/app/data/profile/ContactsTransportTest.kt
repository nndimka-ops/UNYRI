package com.messenger.app.data.profile

import com.messenger.app.data.api.*
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.data.websocket.ContactsChangeHint
import com.messenger.app.data.websocket.parseContactsChanged
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.repository.ChatRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContactsTransportTest {
    private val peer = "11111111-1111-4111-8111-111111111111"
    private val self = "22222222-2222-4222-8222-222222222222"

    @Test fun `HTTP contracts preserve changed false and use limit cursor and idempotent methods`() = runTest {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val body = when {
                request.url.encodedPath == "/users/contacts" -> """{"items":[{"user":{"id":"$peer","username":"anna","display_name":"Anna","bio":null,"avatar_url":null,"profile_version":2,"avatar_version":1,"has_avatar":true},"source":"chat","added_at":"2026-11-11T00:00:00Z"}],"next_cursor":"$peer"}"""
                request.method == "GET" -> """{"user_id":"$peer","is_contact":true}"""
                request.method == "POST" -> """{"user_id":"$peer","is_contact":true,"changed":false,"source":"manual","added_at":"2026-11-11T00:00:00Z"}"""
                else -> """{"user_id":"$peer","is_contact":false,"changed":false,"source":null,"added_at":null}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = UsersApi(client, ApiJson, "https://isolated.invalid")
        val page = api.contacts("fixture", peer, "https://isolated.invalid")
        assertEquals(peer, page.nextCursor); assertEquals("chat", page.items.single().source)
        assertTrue(page.items.single().user.hasAvatar)
        assertEquals("200", requests[0].url.queryParameter("limit")); assertEquals(peer, requests[0].url.queryParameter("cursor"))
        assertTrue(api.contactStatus("fixture", peer, "https://isolated.invalid").isContact)
        assertFalse(api.setContact("fixture", peer, true, "https://isolated.invalid").changed)
        assertFalse(api.setContact("fixture", peer, false, "https://isolated.invalid").changed)
        assertEquals(listOf("GET", "GET", "POST", "DELETE"), requests.map { it.method })
        assertEquals(listOf("/users/contacts", "/users/$peer/contact", "/users/$peer/contact", "/users/$peer/contact"), requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.header("Authorization") == "Bearer fixture" })
    }

    @Test fun `realtime parser normalizes UUID and bare malformed or wrong frames name nobody`() {
        fun parse(value: String) = parseContactsChanged(Json.parseToJsonElement(value).jsonObject)
        val id = "6F9619FF-8B86-D011-B42D-00C04FC964FF"
        assertEquals(ContactsChangeHint(id.lowercase(), true), parse("""{"type":"contacts.changed","user_id":"$id","is_contact":true}"""))
        for (frame in listOf("""{"type":"contacts.changed"}""", """{"type":"profile.changed","user_id":"$peer","is_contact":true}""",
            """{"type":"contacts.changed","user_id":"not-uuid","is_contact":true}""", """{"type":"contacts.changed","user_id":"$peer","is_contact":"true"}""")) {
            assertNull(parse(frame))
        }
    }

    private class Remote : ContactsRemote {
        var page: suspend () -> ContactsPageDto = { ContactsPageDto(emptyList()) }
        var status: suspend (String) -> ContactStatusDto = { ContactStatusDto(it, false) }
        var pageReads = 0
        var statusReads = 0
        var writes = 0
        override suspend fun contacts(accessToken: String, cursor: String?, origin: String): ContactsPageDto {
            pageReads++
            return page()
        }
        override suspend fun contactStatus(accessToken: String, userId: String, origin: String): ContactStatusDto {
            statusReads++
            return status(userId)
        }
        override suspend fun setContact(accessToken: String, userId: String, contact: Boolean, origin: String): ContactChangeDto {
            writes++
            return ContactChangeDto(userId, contact, false)
        }
    }

    private fun chats(rows: List<ChatSummary>): ChatRepository = java.lang.reflect.Proxy.newProxyInstance(
        ChatRepository::class.java.classLoader, arrayOf(ChatRepository::class.java)) { _, method, _ ->
        check(method.name == "getChats") { "Contacts must not load chat metadata or history" }
        flowOf(rows)
    } as ChatRepository

    @Test fun `only 404 falls back to visible cached chat partners and hides duplicates self and blocks`() = runTest {
        val user = PublicUser(peer, "anna", "Anna", null)
        val blocked = PublicUser("33333333-3333-4333-8333-333333333333", "boris", "Boris", null)
        val rows = listOf(ChatSummary("a", "direct", user, "", null), ChatSummary("b", "direct", user, "", null),
            ChatSummary("c", "direct", blocked, "", null, isBlocked = true))
        val remote = Remote().apply { page = { throw AuthApiException.Api("not_found", "old endpoint", 404) } }
        val repo = ContactsRepository(remote, FakeAuthRepository(self), chats(rows), this, { "https://isolated.invalid" })
        assertEquals(listOf(peer), repo.page(null).items.map { it.id })
        remote.page = { throw AuthApiException.Api("offline", "retry", 503) }
        assertTrue(runCatching { repo.page(null) }.isFailure)
        remote.page = { throw AuthApiException.Api("not_found", "not an HTTP 404", 400) }
        assertTrue(runCatching { repo.page(null) }.isFailure)
    }

    @Test fun `transport ignores a response from retired session`() = runTest {
        val auth = FakeAuthRepository(self)
        val remote = Remote()
        val gate = CompletableDeferred<Unit>()
        remote.page = { gate.await(); ContactsPageDto(emptyList()) }
        val repo = ContactsRepository(remote, auth, chats(emptyList()), this, { "https://isolated.invalid" })
        val request = async { runCatching { repo.page(null) } }; runCurrent()
        auth.switchSession(peer); gate.complete(Unit)
        assertTrue(request.await().isFailure)
        val oldStore = repo.store; auth.switchSession(self)
        assertNotSame(oldStore, repo.store)
    }

    @Test fun `generic management hint retains a local block until a confirmed unblock`() = runTest {
        val remote = Remote().apply { page = {
            ContactsPageDto(listOf(ContactEntryDto(PublicUserDto(peer, "anna", "Anna"), "manual", "")))
        } }
        val repo = ContactsRepository(remote, FakeAuthRepository(self), chats(emptyList()), this, { "https://isolated.invalid" })
        val store = repo.store
        repo.blockChanged(peer, true)
        assertTrue(repo.page(null).items.isEmpty())
        repo.blocksChanged()
        assertTrue(repo.page(null).items.isEmpty()) // The old cached false cannot revoke our block.
        repo.blockChanged(peer, false)
        assertEquals(listOf(peer), repo.page(null).items.map { it.id })
        assertEquals(ContactsStore.Phase.IDLE, store.phase.value)
        assertTrue(store.revision.value > 0)
    }

    @Test fun `manual add succeeds for storage but a locally blocked peer never reappears`() = runTest {
        val user = PublicUser(peer, "anna", "Anna", null)
        val remote = Remote().apply { page = {
            ContactsPageDto(listOf(ContactEntryDto(PublicUserDto(peer, "anna", "Anna"), "manual", "")))
        } }
        val repo = ContactsRepository(remote, FakeAuthRepository(self),
            chats(listOf(ChatSummary("known", "direct", user, "", null))), this, { "https://isolated.invalid" })
        val store = repo.store; store.ensureLoaded()
        repo.blockChanged(peer, true)
        assertTrue(store.set(user, true))
        assertEquals(1, remote.writes)
        assertFalse(peer in store.ids.value)
        assertTrue(store.entries.value.isEmpty())
        repo.blocksChanged(); runCurrent()
        assertFalse(peer in store.ids.value)
        assertTrue(repo.page(null).items.isEmpty())
        repo.blockChanged(peer, false); store.ensureLoaded()
        assertTrue(peer in store.ids.value)
    }

    @Test fun `a block acknowledged before first store access also masks a confirmed add`() = runTest {
        val user = PublicUser(peer, "anna", "Anna", null)
        val remote = Remote()
        val repo = ContactsRepository(remote, FakeAuthRepository(self), chats(emptyList()), this, { "https://isolated.invalid" })
        repo.blockChanged(peer, true)
        assertTrue(repo.store.set(user, true))
        assertFalse(peer in repo.store.ids.value)
        assertTrue(repo.store.entries.value.isEmpty())
    }

    @Test fun `restored cached block masks add hints writes and status without paging the book`() = runTest {
        val user = PublicUser(peer, "anna", "Anna", null)
        val remote = Remote().apply { status = { ContactStatusDto(it, true) } }
        val repo = ContactsRepository(remote, FakeAuthRepository(self),
            chats(listOf(ChatSummary("known", "direct", user, "", null, isBlocked = true, blockedUserId = peer))),
            this, { "https://isolated.invalid" })
        val store = repo.store
        repo.changed(peer, true); runCurrent()
        assertFalse(peer in store.ids.value)
        assertTrue(store.entries.value.isEmpty())
        assertTrue(store.set(user, true))
        assertFalse(peer in store.ids.value)
        store.check(user)
        assertFalse(peer in store.ids.value)
        assertEquals(1, remote.statusReads)
        assertEquals(0, remote.pageReads)
        assertEquals(1, remote.writes)
    }

    @Test fun `old-server People fallback never claims membership and profile asks only one status`() = runTest {
        val user = PublicUser(peer, "anna", "Anna", null)
        val remote = Remote().apply {
            page = { throw AuthApiException.Api("not_found", "old endpoint", 404) }
            status = { throw AuthApiException.Api("not_found", "old endpoint", 404) }
        }
        val repo = ContactsRepository(remote, FakeAuthRepository(self),
            chats(listOf(ChatSummary("a", "direct", user, "", null))), this, { "https://isolated.invalid" })
        val store = repo.store
        store.listAppeared(); store.ensureLoaded(); store.listDisappeared()
        assertEquals(listOf(peer), store.entries.value.map { it.id })
        assertTrue(store.ids.value.isEmpty())
        assertEquals(ContactsStore.Phase.LOADED, store.phase.value)
        store.check(user)
        assertEquals(1, remote.statusReads)
        assertEquals(1, remote.pageReads)
        assertFalse(peer in store.ids.value)
        assertFalse(peer in store.nonMembers.value)
        assertTrue(store.checking.value.isEmpty())
        assertEquals(listOf(peer), store.entries.value.map { it.id })
        store.listAppeared(); store.ensureLoaded()
        assertEquals(1, remote.pageReads)
        // A partial old server may answer membership; a non-contact is still a fallback chat partner.
        remote.status = { ContactStatusDto(it, false) }
        store.check(user)
        assertTrue(peer in store.nonMembers.value)
        assertEquals(listOf(peer), store.entries.value.map { it.id })
        assertEquals(1, remote.pageReads)
    }
}
