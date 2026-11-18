package com.messenger.app.data.signal

import com.messenger.app.data.local.signal.PeerIdentityDao
import com.messenger.app.data.local.signal.PeerIdentityEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress

class IdentityVerificationManagerTest {

    @Test fun `refresh restores missing verification rows and never downgrades verified peers`() = runBlocking {
        val local = SignalKeyStore(InMemorySecureKeyValueStore()).also { it.ensureIdentity() }
        val peer = SignalKeyStore(InMemorySecureKeyValueStore()).also { it.ensureIdentity() }
        val address = SignalProtocolAddress("peer", 1)
        local.saveIdentity(address, peer.identityKeyPair.publicKey)
        local.storeSession(address, org.signal.libsignal.protocol.state.SessionRecord())
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(local, dao, "me")
        manager.refreshKnownDevices("peer")
        assertEquals(IDENTITY_UNVERIFIED, dao.get("peer", 1)!!.status)
        manager.markVerified("peer", 1)
        manager.refreshKnownDevices("peer")
        assertEquals(IDENTITY_VERIFIED, dao.get("peer", 1)!!.status)
    }

    @Test fun `confirmation cannot accept a key that changed again while dialog was open`() = runBlocking {
        val local = SignalKeyStore(InMemorySecureKeyValueStore()).also { it.ensureIdentity() }
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(local, dao, "me")
        val (_, first) = bobIdentityKey()
        manager.markChanged("peer", 1, first)
        val snapshot = dao.get("peer", 1)!!.identityKeyB64!!
        val (_, second) = bobIdentityKey()
        manager.markChanged("peer", 1, second)
        val result = runCatching { manager.acceptNewKey("peer", 1, snapshot) }
        assertTrue(result.isFailure)
        assertEquals(IDENTITY_CHANGED, dao.get("peer", 1)!!.status)
        manager.acceptNewKey("peer", 1, dao.get("peer", 1)!!.identityKeyB64!!)
        assertEquals(IDENTITY_UNVERIFIED, dao.get("peer", 1)!!.status)
    }

    @Test fun `inactive or excluded devices do not block recipients but remain changed`() = runBlocking {
        val local = SignalKeyStore(InMemorySecureKeyValueStore()).also { it.ensureIdentity() }
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(local, dao, "me")
        val (_, changed) = bobIdentityKey()
        manager.markChanged("peer", 2, changed)
        manager.ensureNoChangedForUser("peer", setOf(1))
        manager.ensureNoChangedForUser("peer", emptySet())
        assertEquals(IDENTITY_CHANGED, dao.get("peer", 2)!!.status)
        assertTrue(runCatching { manager.ensureNoChangedForUser("peer", setOf(1, 2)) }.exceptionOrNull() is IdentityChangedException)
    }

    private fun managerFor(
        keyStore: SignalKeyStore,
        dao: PeerIdentityDao,
        myUserId: String,
    ) = IdentityVerificationManager(
        keyStore = keyStore,
        dao = dao,
        myUserIdProvider = { myUserId },
        scope = CoroutineScope(SupervisorJob()),
    )

    private fun bobIdentityKey(): Pair<SignalKeyStore, IdentityKey> {
        val bobStore = SignalKeyStore(InMemorySecureKeyValueStore())
        bobStore.ensureIdentity()
        return bobStore to bobStore.identityKeyPair.publicKey
    }

    @Test
    fun `first contact is pinned as unverified with a safety number`() = runBlocking {
        val aliceStore = SignalKeyStore(InMemorySecureKeyValueStore())
        aliceStore.ensureIdentity()
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")

        val (_, bobIdentity) = bobIdentityKey()
        aliceStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)

        manager.recordFirstContact("bob", 1)

        val row = dao.get("bob", 1)
        assertNotNull(row)
        assertEquals(IDENTITY_UNVERIFIED, row!!.status)
        assertNotNull(row.safetyNumber)
    }

    @Test
    fun `identity change blocks send until accepted`() = runBlocking {
        val aliceStore = SignalKeyStore(InMemorySecureKeyValueStore())
        aliceStore.ensureIdentity()
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")

        val (_, bobIdentity) = bobIdentityKey()
        aliceStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        manager.recordFirstContact("bob", 1)

        // A second, different identity key = reinstall/compromise.
        val (_, bobNewIdentity) = bobIdentityKey()
        manager.markChanged("bob", 1, bobNewIdentity)

        assertEquals(IDENTITY_CHANGED, dao.get("bob", 1)!!.status)
        try {
            manager.ensureNoChangedForUser("bob")
            throw AssertionError("expected IdentityChangedException")
        } catch (e: IdentityChangedException) {
            assertEquals(listOf(1), e.signalDeviceIds)
        }

        // Accept the new key -> unverified again, send allowed.
        manager.acceptNewKey("bob", 1)
        assertEquals(IDENTITY_UNVERIFIED, dao.get("bob", 1)!!.status)
        manager.ensureNoChangedForUser("bob") // does not throw
    }

    @Test
    fun `cannot verify a changed identity`() = runBlocking {        val aliceStore = SignalKeyStore(InMemorySecureKeyValueStore())
        aliceStore.ensureIdentity()
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")

        val (_, bobIdentity) = bobIdentityKey()
        aliceStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        manager.recordFirstContact("bob", 1)
        manager.markVerified("bob", 1)
        assertEquals(IDENTITY_VERIFIED, dao.get("bob", 1)!!.status)
        assertNotNull(dao.get("bob", 1)!!.verifiedAt)

        val (_, bobNewIdentity) = bobIdentityKey()
        manager.markChanged("bob", 1, bobNewIdentity)
        try {
            manager.markVerified("bob", 1)
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("accept the new key"))
        }
    }

    @Test
    fun `safety numbers are symmetric between both parties`() = runBlocking {
        val aliceStore = SignalKeyStore(InMemorySecureKeyValueStore())
        aliceStore.ensureIdentity()
        val bobStore = SignalKeyStore(InMemorySecureKeyValueStore())
        bobStore.ensureIdentity()

        val aliceDao = InMemoryPeerIdentityDao()
        val bobDao = InMemoryPeerIdentityDao()
        val aliceManager = managerFor(aliceStore, aliceDao, "alice")
        val bobManager = managerFor(bobStore, bobDao, "bob")

        // Alice pins bob's identity; bob pins alice's identity.
        aliceStore.saveIdentity(SignalProtocolAddress("bob", 1), bobStore.identityKeyPair.publicKey)
        bobStore.saveIdentity(SignalProtocolAddress("alice", 1), aliceStore.identityKeyPair.publicKey)

        aliceManager.recordFirstContact("bob", 1)
        bobManager.recordFirstContact("alice", 1)

        val aliceSees = aliceDao.get("bob", 1)!!.safetyNumber
        val bobSees = bobDao.get("alice", 1)!!.safetyNumber
        assertNotNull(aliceSees)
        assertEquals(aliceSees, bobSees)
    }

    @Test
    fun `no row means no block`() = runBlocking {
        val aliceStore = SignalKeyStore(InMemorySecureKeyValueStore())
        aliceStore.ensureIdentity()
        val manager = managerFor(aliceStore, InMemoryPeerIdentityDao(), "alice")
        manager.ensureNoChangedForUser("bob") // does not throw
        assertNull(manager.safetyNumberFor("bob", 1))
    }

    @Test
    fun `verified identity becomes CHANGED on key change and loses verifiedAt`() = runBlocking {
        val aliceStore = SignalKeyStore(InMemorySecureKeyValueStore())
        aliceStore.ensureIdentity()
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")

        val (_, bobIdentity) = bobIdentityKey()
        aliceStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        manager.recordFirstContact("bob", 1)
        manager.markVerified("bob", 1)
        assertEquals(IDENTITY_VERIFIED, dao.get("bob", 1)!!.status)
        assertNotNull(dao.get("bob", 1)!!.verifiedAt)

        val (_, bobNewIdentity) = bobIdentityKey()
        manager.markChanged("bob", 1, bobNewIdentity)

        assertEquals(IDENTITY_CHANGED, dao.get("bob", 1)!!.status)
        assertNull("verification must not survive a key change", dao.get("bob", 1)!!.verifiedAt)

        // Accept -> UNVERIFIED (never VERIFIED again).
        manager.acceptNewKey("bob", 1)
        assertEquals(IDENTITY_UNVERIFIED, dao.get("bob", 1)!!.status)
        assertNull(dao.get("bob", 1)!!.verifiedAt)
    }

    @Test
    fun `changed state survives an app restart`() = runBlocking {
        val backing = mutableMapOf<String, Any>()
        val aliceStore = SignalKeyStore(InMemorySecureKeyValueStore(backing))
        aliceStore.ensureIdentity()
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")

        val (_, bobIdentity) = bobIdentityKey()
        aliceStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        manager.recordFirstContact("bob", 1)
        val (_, bobNewIdentity) = bobIdentityKey()
        manager.markChanged("bob", 1, bobNewIdentity)
        assertEquals(IDENTITY_CHANGED, dao.get("bob", 1)!!.status)

        // Simulate process restart: a brand-new manager over the SAME dao/state.
        val restartedManager = managerFor(aliceStore, dao, "alice")
        assertEquals(IDENTITY_CHANGED, dao.get("bob", 1)!!.status)
        try {
            restartedManager.ensureNoChangedForUser("bob")
            throw AssertionError("expected IdentityChangedException after restart")
        } catch (e: IdentityChangedException) {
            assertEquals(listOf(1), e.signalDeviceIds)
        }
    }

    @Test
    fun `an unchanged identity is never falsely marked CHANGED`() = runBlocking {
        val aliceStore = SignalKeyStore(InMemorySecureKeyValueStore())
        aliceStore.ensureIdentity()
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")

        val (bobStore, bobIdentity) = bobIdentityKey()
        aliceStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        manager.recordFirstContact("bob", 1)

        // The SAME identity key (normal reconnect / repeated bundle fetch) must
        // not flip the status away from UNVERIFIED.
        val sameIdentity = IdentityKey(bobStore.identityKeyPair.publicKey.serialize())
        manager.recordFirstContact("bob", 1) // no-op for an existing row
        assertEquals(IDENTITY_UNVERIFIED, dao.get("bob", 1)!!.status)
        assertTrue(aliceStore.isTrustedIdentity(SignalProtocolAddress("bob", 1), sameIdentity, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))
    }

    // --- P1-9: verified state survives normal logout (account-scoped) ------

    private fun namespacedKeyStore(backing: InMemorySecureKeyValueStore, namespace: String): SignalKeyStore =
        SignalKeyStore(backing, accountNamespace = { namespace }).also { it.ensureIdentity() }

    private fun verifyBob(manager: IdentityVerificationManager, keyStore: SignalKeyStore) = runBlocking {
        val (_, bobIdentity) = bobIdentityKey()
        keyStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        manager.recordFirstContact("bob", 1)
        manager.markVerified("bob", 1)
    }

    @Test
    fun `verified state survives normal logout and login`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val namespace = "alice:1"
        val keyStore1 = namespacedKeyStore(backing, namespace)
        val dao1 = InMemoryPeerIdentityDao()
        val manager1 = managerFor(keyStore1, dao1, "alice")
        verifyBob(manager1, keyStore1)
        assertEquals(IDENTITY_VERIFIED, dao1.get("bob", 1)!!.status)
        val safetyBefore = dao1.get("bob", 1)!!.safetyNumber

        // Logout wipes Room (new, empty dao); the signal namespace survives.
        val dao2 = InMemoryPeerIdentityDao()
        val keyStore2 = namespacedKeyStore(backing, namespace)
        val manager2 = managerFor(keyStore2, dao2, "alice")
        manager2.hydrateFromDurableStorage()

        assertEquals(IDENTITY_VERIFIED, dao2.get("bob", 1)!!.status)
        assertEquals(safetyBefore, dao2.get("bob", 1)!!.safetyNumber)
        // Normal logout must NOT wipe the Signal identity.
        assertTrue(keyStore2.hasIdentity())
        assertNotNull(keyStore2.getIdentityKeyPair())
    }

    @Test
    fun `verified records are account-scoped and never leak to another account`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val aliceKeyStore = namespacedKeyStore(backing, "alice:1")
        val aliceDao = InMemoryPeerIdentityDao()
        val aliceManager = managerFor(aliceKeyStore, aliceDao, "alice")
        verifyBob(aliceManager, aliceKeyStore)
        assertEquals(IDENTITY_VERIFIED, aliceDao.get("bob", 1)!!.status)

        // Bob logs in with a different namespace: his fresh Room hydrates nothing.
        val bobKeyStore = namespacedKeyStore(backing, "bob:1")
        val bobDao = InMemoryPeerIdentityDao()
        managerFor(bobKeyStore, bobDao, "bob").hydrateFromDurableStorage()
        assertNull(bobDao.get("bob", 1))
    }

    @Test
    fun `switching back to alice restores her verified state`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val aliceKeyStore = namespacedKeyStore(backing, "alice:1")
        val aliceDao1 = InMemoryPeerIdentityDao()
        val aliceManager = managerFor(aliceKeyStore, aliceDao1, "alice")
        verifyBob(aliceManager, aliceKeyStore)

        val bobKeyStore = namespacedKeyStore(backing, "bob:1")
        val bobDao = InMemoryPeerIdentityDao()
        managerFor(bobKeyStore, bobDao, "bob").hydrateFromDurableStorage()
        assertNull(bobDao.get("bob", 1))

        val aliceDao2 = InMemoryPeerIdentityDao()
        val aliceManager2 = managerFor(namespacedKeyStore(backing, "alice:1"), aliceDao2, "alice")
        aliceManager2.hydrateFromDurableStorage()
        assertEquals(IDENTITY_VERIFIED, aliceDao2.get("bob", 1)!!.status)
    }

    @Test
    fun `identity change after verification restores as changed not verified`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val namespace = "alice:1"
        val keyStore1 = namespacedKeyStore(backing, namespace)
        val dao1 = InMemoryPeerIdentityDao()
        val manager1 = managerFor(keyStore1, dao1, "alice")
        verifyBob(manager1, keyStore1)
        assertEquals(IDENTITY_VERIFIED, dao1.get("bob", 1)!!.status)

        // While Alice is logged out, Bob's key changes: the trust store now pins K2.
        val (_, bobIdentity2) = bobIdentityKey()
        val keyStore2 = namespacedKeyStore(backing, namespace)
        keyStore2.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity2)

        val dao2 = InMemoryPeerIdentityDao()
        val manager2 = managerFor(keyStore2, dao2, "alice")
        manager2.hydrateFromDurableStorage()

        // Never a blind VERIFIED across a different identity key.
        assertEquals(IDENTITY_CHANGED, dao2.get("bob", 1)!!.status)
        try {
            manager2.ensureNoChangedForUser("bob")
            throw AssertionError("expected IdentityChangedException after key change")
        } catch (e: IdentityChangedException) {
            assertEquals(listOf(1), e.signalDeviceIds)
        }
    }

    @Test
    fun `accepting a changed key after relogin clears durable changed state`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val namespace = "alice:1"
        val keyStore1 = namespacedKeyStore(backing, namespace)
        val dao1 = InMemoryPeerIdentityDao()
        val manager1 = managerFor(keyStore1, dao1, "alice")
        verifyBob(manager1, keyStore1)

        val (_, bobIdentity2) = bobIdentityKey()
        val keyStore2 = namespacedKeyStore(backing, namespace)
        keyStore2.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity2)
        val dao2 = InMemoryPeerIdentityDao()
        val manager2 = managerFor(keyStore2, dao2, "alice")
        manager2.hydrateFromDurableStorage()
        assertEquals(IDENTITY_CHANGED, dao2.get("bob", 1)!!.status)

        // acceptNewKey behaves exactly as before -> UNVERIFIED + durable cleared.
        manager2.acceptNewKey("bob", 1)
        assertEquals(IDENTITY_UNVERIFIED, dao2.get("bob", 1)!!.status)
        assertNull(keyStore2.loadPeerVerification("bob", 1))
    }

    // --- P1-9.1: backfill of pre-existing Room verification state ----------

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    /** Seeds a pre-existing (old-code) Room-only row — NOT via the manager, so
     *  it has no durable counterpart yet. */
    private fun seedRoomRow(dao: PeerIdentityDao, userId: String, deviceId: Int, status: String, identityKeyB64: String?) = runBlocking {
        dao.upsert(PeerIdentityEntity(userId, deviceId, status, null, identityKeyB64, if (status == IDENTITY_VERIFIED) "2026-01-01T00:00:00Z" else null))
    }

    @Test
    fun `existing room verified with same pinned identity migrates as verified`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val namespace = "alice:1"
        val keyStore = namespacedKeyStore(backing, namespace)
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(keyStore, dao, "alice")

        val (_, bobIdentity) = bobIdentityKey()
        val bobB64 = b64(bobIdentity.serialize())
        keyStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        seedRoomRow(dao, "bob", 1, IDENTITY_VERIFIED, bobB64)

        manager.backfillExistingVerificationState()

        val durable = keyStore.loadPeerVerification("bob", 1)
        assertNotNull(durable)
        assertEquals(IDENTITY_VERIFIED, durable!!.status)
        assertEquals(bobB64, durable.identityKeyB64)
    }

    @Test
    fun `existing room verified with a different pinned identity is not migrated as verified`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val namespace = "alice:1"
        val keyStore = namespacedKeyStore(backing, namespace)
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(keyStore, dao, "alice")

        val (_, bobIdentity1) = bobIdentityKey()
        seedRoomRow(dao, "bob", 1, IDENTITY_VERIFIED, b64(bobIdentity1.serialize()))
        // The trust store now pins a DIFFERENT key.
        val (_, bobIdentity2) = bobIdentityKey()
        keyStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity2)

        manager.backfillExistingVerificationState()

        val durable = keyStore.loadPeerVerification("bob", 1)
        assertNotNull(durable)
        assertTrue("a changed key must never migrate as VERIFIED", durable!!.status == IDENTITY_CHANGED)
    }

    @Test
    fun `existing room changed state does not become verified`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val namespace = "alice:1"
        val keyStore = namespacedKeyStore(backing, namespace)
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(keyStore, dao, "alice")

        val (_, bobIdentity) = bobIdentityKey()
        keyStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        seedRoomRow(dao, "bob", 1, IDENTITY_CHANGED, b64(bobIdentity.serialize()))

        manager.backfillExistingVerificationState()

        val durable = keyStore.loadPeerVerification("bob", 1)
        assertNotNull(durable)
        assertEquals(IDENTITY_CHANGED, durable!!.status)
    }

    @Test
    fun `existing durable state is never overwritten by older room state`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val namespace = "alice:1"
        val keyStore = namespacedKeyStore(backing, namespace)
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(keyStore, dao, "alice")

        val (_, bobIdentity) = bobIdentityKey()
        keyStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        // Durable truth already exists (VERIFIED)...
        keyStore.savePeerVerification(PeerVerificationRecord("bob", 1, IDENTITY_VERIFIED, null, b64(bobIdentity.serialize()), "2026-01-01T00:00:00Z"))
        // ...and Room holds an OLDER projection (CHANGED, stale) that must not win.
        seedRoomRow(dao, "bob", 1, IDENTITY_CHANGED, b64(bobIdentity.serialize()))

        manager.backfillExistingVerificationState()

        assertEquals(IDENTITY_VERIFIED, keyStore.loadPeerVerification("bob", 1)!!.status)
    }

    @Test
    fun `alice records never migrate into bob namespace`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val aliceStore = namespacedKeyStore(backing, "alice:1")
        val aliceDao = InMemoryPeerIdentityDao()
        val aliceManager = managerFor(aliceStore, aliceDao, "alice")
        val (_, bobIdentity) = bobIdentityKey()
        aliceStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        seedRoomRow(aliceDao, "bob", 1, IDENTITY_VERIFIED, b64(bobIdentity.serialize()))
        aliceManager.backfillExistingVerificationState()
        assertNotNull(aliceStore.loadPeerVerification("bob", 1))

        // Bob logs in on a fresh (cleared) Room: nothing to migrate, and
        // Alice's durable records are not visible in Bob's namespace.
        val bobStore = namespacedKeyStore(backing, "bob:1")
        val bobDao = InMemoryPeerIdentityDao()
        val bobManager = managerFor(bobStore, bobDao, "bob")
        bobManager.backfillExistingVerificationState()
        assertNull(bobStore.loadPeerVerification("bob", 1))
        assertTrue(bobStore.allPeerVerifications().isEmpty())
    }

    @Test
    fun `backfill can run twice safely`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val namespace = "alice:1"
        val keyStore = namespacedKeyStore(backing, namespace)
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(keyStore, dao, "alice")

        val (_, bobIdentity) = bobIdentityKey()
        val bobB64 = b64(bobIdentity.serialize())
        keyStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        seedRoomRow(dao, "bob", 1, IDENTITY_VERIFIED, bobB64)

        manager.backfillExistingVerificationState()
        val first = keyStore.loadPeerVerification("bob", 1)
        manager.backfillExistingVerificationState()
        val second = keyStore.loadPeerVerification("bob", 1)

        assertEquals(first, second)
        assertEquals(IDENTITY_VERIFIED, second!!.status)
    }

    @Test
    fun `migrate then logout and login same account preserves verification`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val namespace = "alice:1"
        val keyStore1 = namespacedKeyStore(backing, namespace)
        val dao1 = InMemoryPeerIdentityDao()
        val manager1 = managerFor(keyStore1, dao1, "alice")

        val (_, bobIdentity) = bobIdentityKey()
        val bobB64 = b64(bobIdentity.serialize())
        keyStore1.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        seedRoomRow(dao1, "bob", 1, IDENTITY_VERIFIED, bobB64)
        manager1.backfillExistingVerificationState()

        // Logout wipes Room; login hydrates from the migrated durable record.
        val dao2 = InMemoryPeerIdentityDao()
        val keyStore2 = namespacedKeyStore(backing, namespace)
        val manager2 = managerFor(keyStore2, dao2, "alice")
        manager2.hydrateFromDurableStorage()

        assertEquals(IDENTITY_VERIFIED, dao2.get("bob", 1)!!.status)
        assertTrue("normal logout must not wipe Signal identity", keyStore2.hasIdentity())
    }

    @Test
    fun `normal logout still does not wipe signal identity after backfill`() = runBlocking {
        val backing = InMemorySecureKeyValueStore()
        val namespace = "alice:1"
        val keyStore = namespacedKeyStore(backing, namespace)
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(keyStore, dao, "alice")

        val (_, bobIdentity) = bobIdentityKey()
        keyStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        seedRoomRow(dao, "bob", 1, IDENTITY_VERIFIED, b64(bobIdentity.serialize()))
        manager.backfillExistingVerificationState()

        val keyPairBefore = keyStore.getIdentityKeyPair().serialize().toList()
        // Logout clears Room only; the Signal identity is untouched.
        val dao2 = InMemoryPeerIdentityDao()
        val keyStore2 = namespacedKeyStore(backing, namespace)
        assertEquals(keyPairBefore, keyStore2.getIdentityKeyPair().serialize().toList())
    }

    // --- P1-4/B.1: acceptNewKey must trigger pending decrypt recovery -----

    @Test
    fun `accepting a changed key fires the trust-accepted callback`() = runBlocking {
        val aliceStore = SignalKeyStore(InMemorySecureKeyValueStore())
        aliceStore.ensureIdentity()
        val dao = InMemoryPeerIdentityDao()
        val manager = managerFor(aliceStore, dao, "alice")
        val (_, bobIdentity) = bobIdentityKey()
        aliceStore.saveIdentity(SignalProtocolAddress("bob", 1), bobIdentity)
        manager.recordFirstContact("bob", 1)
        val (_, bobNewIdentity) = bobIdentityKey()
        manager.markChanged("bob", 1, bobNewIdentity)

        var callbackUserId: String? = null
        var callbackDeviceId: Int? = null
        manager.onTrustAccepted = { uid, did -> callbackUserId = uid; callbackDeviceId = did }

        manager.acceptNewKey("bob", 1)

        assertEquals("bob", callbackUserId)
        assertEquals(1, callbackDeviceId)
        assertEquals(IDENTITY_UNVERIFIED, dao.get("bob", 1)!!.status)
    }
}
