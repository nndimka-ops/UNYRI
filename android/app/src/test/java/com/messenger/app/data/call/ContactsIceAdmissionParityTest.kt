package com.messenger.app.data.call

import com.messenger.app.data.api.*
import com.messenger.app.data.profile.ContactsRepository
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.repository.ChatRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.*

class ContactsIceAdmissionParityTest {
    private val peer = "6f9619ff-8b86-4011-b42d-00c04fc964ff"
    private class Remote : ContactsRemote {
        var reads = 0
        override suspend fun contacts(accessToken: String, cursor: String?, origin: String): ContactsPageDto {
            reads++; throw AuthApiException.Api("not_found", "old server", 404)
        }
        override suspend fun contactStatus(accessToken: String, userId: String, origin: String): ContactStatusDto = error("No status fetch permitted")
        override suspend fun setContact(accessToken: String, userId: String, contact: Boolean, origin: String): ContactChangeDto = error("No write permitted")
    }

    @Test fun `Everyone Contacts Nobody matrix treats unknown membership as relay only for Contacts`() {
        assertEquals(CallPeerToPeer.EVERYONE, CallPeerToPeer.resolved(null))
        assertEquals(CallPeerToPeer.EVERYONE, CallPeerToPeer.resolved("invalid"))
        for (debug in listOf(false, true)) for (member in listOf(null, false, true)) {
            for (setting in CallPeerToPeer.entries) {
                val expected = if (setting == CallPeerToPeer.NOBODY || setting == CallPeerToPeer.CONTACTS && member != true)
                    IceTransportPolicy.RELAY else IceTransportPolicy.ALL
                val config = WebRtcIceTransportPolicy.buildConfig(emptyList(), debug, false, setting, member)
                assertEquals(expected, config.transports)
                assertFalse(config.hasTurnServer) // Missing TURN never relaxes privacy.
            }
        }
        assertEquals(IceTransportPolicy.RELAY, WebRtcIceTransportPolicy.resolve(true, true, CallPeerToPeer.EVERYONE, true))
    }

    @Test fun `known membership snapshot fences account origin and session and never grants from a chat`() = runTest {
        val auth = FakeAuthRepository("owner"); val remote = Remote()
        var origin = "https://one.invalid"
        val rows = listOf(ChatSummary("chat", "direct", PublicUser(peer, "peer", "Peer", null), "", null))
        val chats = Proxy.newProxyInstance(ChatRepository::class.java.classLoader, arrayOf(ChatRepository::class.java)) { _, method, _ ->
            check(method.name == "getChats"); flowOf(rows)
        } as ChatRepository
        val repo = ContactsRepository(remote, auth, chats, this, { origin })
        assertFalse(repo.isKnownCallContact(peer)); assertEquals(0, remote.reads)
        val store = repo.store
        assertFalse(repo.isKnownCallContact(peer)) // Not loaded.
        store.reload() // Legacy chat rows are deliberately non-authoritative.
        assertEquals(1, store.entries.value.size); assertFalse(repo.isKnownCallContact(peer))
        store.receive(peer, true)
        assertTrue(repo.isKnownCallContact(peer.uppercase()))
        store.receive(peer, false); assertFalse(repo.isKnownCallContact(peer))
        store.receive(peer, true)
        origin = "https://two.invalid"; assertFalse(repo.isKnownCallContact(peer))
        origin = "https://one.invalid"; assertTrue(repo.isKnownCallContact(peer))
        auth.switchSession("owner"); assertFalse(repo.isKnownCallContact(peer)) // Same owner, new generation.
        repo.store.receive(peer, true); assertTrue(repo.isKnownCallContact(peer))
        auth.switchSession("another-owner"); assertFalse(repo.isKnownCallContact(peer))
        assertEquals(1, remote.reads) // All call admission checks above were synchronous snapshots.
    }
}
