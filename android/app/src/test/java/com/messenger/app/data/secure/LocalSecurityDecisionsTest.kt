package com.messenger.app.data.secure
import com.messenger.app.data.signal.InMemorySecureKeyValueStore
import kotlin.test.*
class LocalSecurityDecisionsTest {
    @Test fun restartAndAccountIsolation() {
        val disk = InMemorySecureKeyValueStore(); var owner = LocalSecurityDecisions.owner("https://one.invalid/", "me")
        val policy = LocalSecurityDecisions(disk) { owner }
        policy.setBlocked("peer", true)
        policy.revoke(LocalSecurityDecisions.RevokedDevice("me", "device", 7, LocalSecurityDecisions.fingerprint(byteArrayOf(9))))
        val restarted = LocalSecurityDecisions(disk) { owner }
        assertTrue(restarted.blockDecision("peer") == true)
        assertTrue(restarted.isRevoked("me", "device"))
        assertTrue(restarted.isRevoked("me", "renamed", 7))
        assertTrue(restarted.isRevoked("me", "renamed", 8, byteArrayOf(9)))
        assertFalse(restarted.isRevoked("other", "other-device", 7, byteArrayOf(8)))
        assertTrue(restarted.isRevoked("server-spoofed-user", "renamed", 8, byteArrayOf(9)))
        owner = LocalSecurityDecisions.owner("https://two.invalid", "me")
        assertNull(restarted.blockDecision("peer")); assertFalse(restarted.isRevoked("me", "device"))
        owner = LocalSecurityDecisions.owner("https://one.invalid", "other")
        assertNull(restarted.blockDecision("peer")); assertFalse(restarted.isRevoked("me", "device"))
        owner = LocalSecurityDecisions.owner("https://one.invalid", "me")
        assertTrue(restarted.blockDecision("peer") == true)
        restarted.setBlocked("peer", false)
        assertEquals(false, LocalSecurityDecisions(disk) { owner }.blockDecision("peer"))
    }
    @Test fun tombstoneEnrichmentCannotReplaceFingerprint() {
        val policy = LocalSecurityDecisions(InMemorySecureKeyValueStore()) { "account" }
        policy.revoke(LocalSecurityDecisions.RevokedDevice("me", "device"))
        policy.revoke(LocalSecurityDecisions.RevokedDevice("me", "device", 2, LocalSecurityDecisions.fingerprint(byteArrayOf(1))))
        policy.revoke(LocalSecurityDecisions.RevokedDevice("me", "device", 3, LocalSecurityDecisions.fingerprint(byteArrayOf(2))))
        assertTrue(policy.isRevoked("me", signal = 2)); assertTrue(policy.isRevoked("me", identity = byteArrayOf(1)))
        assertFalse(policy.isRevoked("me", signal = 3)); assertTrue(policy.isRevoked("me", "device"))
    }
    @Test fun legacyMigrationRunsOnlyOnceAndCannotUndoExplicitChoices() {
        val policy = LocalSecurityDecisions(InMemorySecureKeyValueStore()) { "account" }
        policy.setBlocked("unblocked", false)
        policy.migrateBlocksOnce { setOf("legacy", "unblocked") }
        assertEquals(true, policy.blockDecision("legacy")); assertEquals(false, policy.blockDecision("unblocked"))
        policy.migrateBlocksOnce { error("No future server projection may be imported") }
        assertNull(policy.blockDecision("server-added"))
    }
    @Test fun uuidCaseAndClaimedUserCannotBypassRevocationOrBlocking() {
        val user = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val device = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val policy = LocalSecurityDecisions(InMemorySecureKeyValueStore()) { "account" }
        policy.setBlocked(user, true)
        assertEquals(true, policy.blockDecision(user.uppercase()))
        policy.revoke(LocalSecurityDecisions.RevokedDevice(user.uppercase(), device.uppercase(), 2, LocalSecurityDecisions.fingerprint(byteArrayOf(1))))
        assertTrue(policy.isRevoked(user, device))
        assertTrue(policy.isRevoked("other-user", device))
        assertTrue(policy.isRevoked("other-user", "new-device", 7, byteArrayOf(1)))
        assertFalse(policy.isRevoked("other-user", "new-device", 2, byteArrayOf(2)))
    }
    @Test fun durableWriteFailureFailsClosed() {
        val disk = object : com.messenger.app.data.signal.SecureKeyValueStore by InMemorySecureKeyValueStore() {
            override fun putNamespaceBinding(key: String, value: ByteArray) { error("disk full") }
        }
        val policy = LocalSecurityDecisions(disk) { "account" }
        assertFails { policy.setBlocked("peer", true) }; assertNull(policy.blockDecision("peer"))
    }
}
