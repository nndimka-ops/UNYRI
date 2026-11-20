package com.messenger.app.data.identity

import kotlin.test.*

class PeerIdentityPolicyTest {
    @Test fun legacyModeNeverDisplaysPrivateLogins() {
        assertEquals("", PeerIdentityPolicy.handle(null, null, "old_peer", true))
        assertEquals("", PeerIdentityPolicy.handle(null, null, "private_login", false))
        assertEquals("", PeerIdentityPolicy.handle(null, null, "private_login"))
    }

    @Test fun explicitPublicIdentityWinsAndLegacyModeIsDisplayNameOnly() {
        assertEquals("@alice", PeerIdentityPolicy.handle("alice", "123456789", "private_login"))
        assertEquals("@alice", PeerIdentityPolicy.handle("alice", null, "private_login"))
        assertEquals("", PeerIdentityPolicy.handle("alice", "123456789", "private_login", true))
        assertEquals("", PeerIdentityPolicy.handle(null, "123456789", "private_login", true))
    }

    @Test fun contactCaptionAlwaysNamesTheGroupedNumberThenTheUsername() {
        assertEquals("604 743 993", PeerIdentityPolicy.contactCaption(null, "604743993"))
        assertEquals("604 743 993 · @alice", PeerIdentityPolicy.contactCaption("alice", " 604743993 "))
        assertEquals("@alice", PeerIdentityPolicy.contactCaption("@alice", null))
        assertEquals("", PeerIdentityPolicy.contactCaption("", ""))
        assertEquals("", PeerIdentityPolicy.contactCaption("alice", "604743993", legacy = true))
    }

    @Test fun numberOnlyAndBlankNamesNeverProduceABareAtSign() {
        assertEquals("123\u202f456\u202f789", PeerIdentityPolicy.handle(null, "123456789", "private_login"))
        assertEquals("123\u202f456\u202f789", PeerIdentityPolicy.handle("", "123456789", ""))
        assertEquals("12", PeerIdentityPolicy.handle("  ", "12", "private_login"))
        for (name in listOf(null, "", " ", "@")) {
            assertEquals("", PeerIdentityPolicy.handle(name, "", "", true))
            assertEquals("", PeerIdentityPolicy.handle(name, null, "private_login", false))
        }
    }
}
