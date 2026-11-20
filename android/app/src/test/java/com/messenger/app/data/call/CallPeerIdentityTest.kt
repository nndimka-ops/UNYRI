package com.messenger.app.data.call

import com.messenger.app.data.api.CallDto
import com.messenger.app.data.api.PublicUserDto
import com.messenger.app.domain.model.PublicUser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CallPeerIdentityTest {
    private val call = CallDto("call", "peer", "owner", "video", "ended", createdAt = "2026-10-10T12:00:00Z", peer = PublicUserDto("peer", "alice", "Alice", profileVersion = 2))

    @Test fun `hidden or deleted chat still has caller identity after cache reopen`() {
        val restored = Json.decodeFromString<CallDto>(Json.encodeToString(call.copy(chatVisible = false)))
        assertEquals("Alice", callPeerIdentity(restored, "owner", null).name)
        assertEquals(false, restored.chatVisible)
    }

    @Test fun `newer profile wins and blank display uses username`() {
        val known = PublicUser("peer", "old", "Old name", null, profileVersion = 1)
        assertEquals("Alice", callPeerIdentity(call, "owner", known).name)
        assertEquals("New name", callPeerIdentity(call, "owner", known.copy(displayName = "New name", profileVersion = 3)).name)
        assertEquals("@alice", callPeerIdentity(call.copy(peer = call.peer!!.copy(displayName = "  ", publicUsername = "alice")), "owner", null).name)
        assertEquals("Old name", callPeerIdentity(call.copy(peer = null), "owner", known).name)
    }

    @Test fun `login2 never falls back to an old private login`() {
        val blank = call.copy(peer = call.peer!!.copy(displayName = " "))
        assertNull(callPeerIdentity(blank, "owner", null).name)
        assertNull(callPeerIdentity(blank, "owner", null, legacy = true).name)
    }

    @Test fun `wrong owner or mismatched profile never supplies another identity`() {
        assertNull(callPeerIdentity(call, "different-account", null).name)
        assertNull(callPeerIdentity(call.copy(peer = call.peer!!.copy(id = "unrelated")), "owner", PublicUser("other", "other", "Other", null)).name)
        assertNull(callPeerIdentity(call.copy(peer = null), "owner", null).name)
    }

    @Test fun `legacy and null peer snapshots retain same call identity only`() {
        val legacy = Json.decodeFromString<CallDto>("""{"id":"call","caller_user_id":"peer","callee_user_id":"owner","media_kind":"video","state":"ended","created_at":"2026-10-10T12:00:00Z"}""")
        assertNull(legacy.peer)
        assertEquals(call.peer, retainCallPeerIdentity(legacy, call).peer)
        assertNull(retainCallPeerIdentity(legacy.copy(callerUserId = "other"), call).peer)
        val renamed = call.copy(peer = call.peer!!.copy(displayName = "Renamed"))
        assertEquals("Renamed", retainCallPeerIdentity(renamed, call).peer!!.displayName)
    }
}
