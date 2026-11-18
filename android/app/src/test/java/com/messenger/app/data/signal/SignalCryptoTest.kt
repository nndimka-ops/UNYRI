package com.messenger.app.data.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

/**
 * Exercises the REAL Signal Protocol (via the desktop `libsignal-client`
 * jar that `libsignal-android` transitively depends on - it bundles native
 * code for this dev machine's platform too, so these run as plain fast JVM
 * unit tests, no emulator needed) against our real [SignalKeyStore]
 * persistence layer. Per the Этап F rule "don't consider a test passed just
 * because encrypt()/decrypt() work inside one unit test" - these deliberately
 * do NOT prove the full pipeline (that's the two-device UI automation); they
 * prove the cryptographic core (session establishment, tamper/replay
 * rejection, multi-device independence, identity persistence) in isolation
 * with zero mocking of libsignal itself.
 */
class SignalCryptoTest {

    private fun freshStore() = SignalKeyStore(InMemorySecureKeyValueStore())

    /** Simulates what the server's `/keys/bundles/{user}` endpoint would
     *  hand back for [remote]'s device [deviceId] - built directly from the
     *  remote store here (bypassing the network) since this test targets
     *  the crypto core, not the HTTP layer (which messages_test.rs/keys_test.rs
     *  already cover on the backend side). */
    private fun buildBundle(remote: SignalKeyStore, deviceId: Int, includeOneTime: Boolean = true): PreKeyBundle {
        remote.ensureIdentity()
        if (!remote.hasSignedPreKey()) remote.generateSignedPreKey()
        if (!remote.hasKyberPreKey()) remote.generateKyberPreKey()
        val signedPreKey = remote.loadSignedPreKey(1)
        val kyberPreKey = remote.loadKyberPreKey(1)
        val oneTime = if (includeOneTime) remote.generateOneTimePreKeys(1).first() else null
        return PreKeyBundle(
            remote.localRegistrationId,
            deviceId,
            oneTime?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
            oneTime?.keyPair?.publicKey,
            signedPreKey.id,
            signedPreKey.keyPair.publicKey,
            signedPreKey.signature,
            remote.identityKeyPair.publicKey,
            kyberPreKey.id,
            kyberPreKey.keyPair.publicKey,
            kyberPreKey.signature,
        )
    }

    private fun cipherFor(local: SignalKeyStore, remoteAddress: SignalProtocolAddress) =
        SessionCipher(local, local, local, local, local, remoteAddress)

    @Test
    fun `valid encrypted message round trips`() {
        val alice = freshStore()
        val bob = freshStore()
        alice.ensureIdentity()

        val bobAddress = SignalProtocolAddress("bob", 1)
        SessionBuilder(alice, alice, alice, alice, bobAddress).process(buildBundle(bob, 1))

        val ciphertext = cipherFor(alice, bobAddress).encrypt("Hello Signal".toByteArray(Charsets.UTF_8))
        assertTrue(ciphertext is PreKeySignalMessage, "the first message on a fresh session must be a PreKeySignalMessage")

        val aliceAddress = SignalProtocolAddress("alice", 1)
        val plaintext = cipherFor(bob, aliceAddress).decrypt(PreKeySignalMessage(ciphertext.serialize()))
        assertEquals("Hello Signal", String(plaintext, Charsets.UTF_8))
    }

    @Test
    fun `tampered ciphertext fails to decrypt`() {
        val alice = freshStore()
        val bob = freshStore()
        alice.ensureIdentity()

        val bobAddress = SignalProtocolAddress("bob", 1)
        SessionBuilder(alice, alice, alice, alice, bobAddress).process(buildBundle(bob, 1))
        val ciphertext = cipherFor(alice, bobAddress).encrypt("Hello Signal".toByteArray(Charsets.UTF_8))

        val tampered = ciphertext.serialize()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()

        val aliceAddress = SignalProtocolAddress("alice", 1)
        assertFailsWith<Exception>("flipping one byte of ciphertext must never decrypt successfully") {
            cipherFor(bob, aliceAddress).decrypt(PreKeySignalMessage(tampered))
        }
    }

    @Test
    fun `missing session cannot decrypt a whisper message`() {
        val bob = freshStore()
        bob.ensureIdentity()
        // No session has ever been established for this address.
        val strangerAddress = SignalProtocolAddress("stranger", 1)

        // A structurally-valid-looking SignalMessage this store has never
        // seen a session for - constructing a real one from an unrelated
        // established pair, then trying to decrypt it against a store with
        // no session at all.
        val alice = freshStore()
        alice.ensureIdentity()
        val bobAddress = SignalProtocolAddress("bob", 1)
        SessionBuilder(alice, alice, alice, alice, bobAddress).process(buildBundle(bob, 1))
        val prekeyMsg = cipherFor(alice, bobAddress).encrypt("hi".toByteArray()) as PreKeySignalMessage

        assertFailsWith<Exception>("decrypting without any established session must fail") {
            cipherFor(bob, strangerAddress).decrypt(SignalMessage(prekeyMsg.whisperMessage.serialize()))
        }
    }

    @Test
    fun `session establishment consumes the one-time prekey`() {
        val alice = freshStore()
        val bob = freshStore()
        alice.ensureIdentity()

        val bobAddress = SignalProtocolAddress("bob", 1)
        val bundle = buildBundle(bob, 1, includeOneTime = true)
        val usedOneTimeId = bundle.preKeyId
        assertTrue(bob.containsPreKey(usedOneTimeId), "precondition: the one-time prekey exists before use")

        SessionBuilder(alice, alice, alice, alice, bobAddress).process(bundle)
        val ciphertext = cipherFor(alice, bobAddress).encrypt("consume me".toByteArray())

        val aliceAddress = SignalProtocolAddress("alice", 1)
        cipherFor(bob, aliceAddress).decrypt(ciphertext as PreKeySignalMessage)

        assertFalse(bob.containsPreKey(usedOneTimeId), "the one-time prekey must be removed once used to establish a session")
    }

    @Test
    fun `exhausted one-time prekeys still establish a session via the signed prekey`() {
        val alice = freshStore()
        val bob = freshStore()
        alice.ensureIdentity()

        val bobAddress = SignalProtocolAddress("bob", 1)
        val bundleWithoutOneTime = buildBundle(bob, 1, includeOneTime = false)
        assertEquals(PreKeyBundle.NULL_PRE_KEY_ID, bundleWithoutOneTime.preKeyId)

        SessionBuilder(alice, alice, alice, alice, bobAddress).process(bundleWithoutOneTime)
        val ciphertext = cipherFor(alice, bobAddress).encrypt("no one-time needed".toByteArray())

        val aliceAddress = SignalProtocolAddress("alice", 1)
        val plaintext = cipherFor(bob, aliceAddress).decrypt(ciphertext as PreKeySignalMessage)
        assertEquals("no one-time needed", String(plaintext, Charsets.UTF_8))
    }

    @Test
    fun `repeated message is rejected as a duplicate`() {
        val alice = freshStore()
        val bob = freshStore()
        alice.ensureIdentity()

        val bobAddress = SignalProtocolAddress("bob", 1)
        SessionBuilder(alice, alice, alice, alice, bobAddress).process(buildBundle(bob, 1))
        val ciphertext = cipherFor(alice, bobAddress).encrypt("only once".toByteArray()).serialize()

        val aliceAddress = SignalProtocolAddress("alice", 1)
        cipherFor(bob, aliceAddress).decrypt(PreKeySignalMessage(ciphertext))

        assertFailsWith<Exception>("replaying the exact same message must be rejected") {
            cipherFor(bob, aliceAddress).decrypt(PreKeySignalMessage(ciphertext))
        }
    }

    @Test
    fun `multiple recipient devices get independent ciphertexts and sessions`() {
        val alice = freshStore()
        val bobDevice1 = freshStore()
        val bobDevice2 = freshStore()
        alice.ensureIdentity()

        val bob1Address = SignalProtocolAddress("bob", 1)
        val bob2Address = SignalProtocolAddress("bob", 2)
        SessionBuilder(alice, alice, alice, alice, bob1Address).process(buildBundle(bobDevice1, 1))
        val second = buildBundle(bobDevice2, 2)
        // This crypto-isolation fixture models an explicitly approved second device.
        alice.saveIdentity(bob2Address, bobDevice2.identityKeyPair.publicKey)
        SessionBuilder(alice, alice, alice, alice, bob2Address).process(second)

        val toDevice1 = cipherFor(alice, bob1Address).encrypt("for device 1".toByteArray())
        val toDevice2 = cipherFor(alice, bob2Address).encrypt("for device 2".toByteArray())
        assertNotEquals(
            String(toDevice1.serialize(), Charsets.ISO_8859_1),
            String(toDevice2.serialize(), Charsets.ISO_8859_1),
            "each device must get its own independently-encrypted envelope",
        )

        val aliceAddress = SignalProtocolAddress("alice", 1)
        val plaintext1 = cipherFor(bobDevice1, aliceAddress).decrypt(toDevice1 as PreKeySignalMessage)
        val plaintext2 = cipherFor(bobDevice2, aliceAddress).decrypt(toDevice2 as PreKeySignalMessage)
        assertEquals("for device 1", String(plaintext1, Charsets.UTF_8))
        assertEquals("for device 2", String(plaintext2, Charsets.UTF_8))

        // Device 2's store never established a session for this message -
        // it must not be able to decrypt device 1's envelope.
        assertFailsWith<Exception>("a device must never be able to decrypt another device's envelope") {
            cipherFor(bobDevice2, aliceAddress).decrypt(PreKeySignalMessage(toDevice1.serialize()))
        }
    }

    @Test
    fun `sender own second device receives a working echo session`() {
        // Two different physical devices of the SAME account: each has its
        // own independent identity/store (Signal sessions are per-device,
        // never per-user), matching the "one user, many devices" model.
        val aliceDevice1 = freshStore()
        val aliceDevice2 = freshStore()
        aliceDevice1.ensureIdentity()

        val device2Address = SignalProtocolAddress("alice", 2)
        SessionBuilder(aliceDevice1, aliceDevice1, aliceDevice1, aliceDevice1, device2Address)
            .process(buildBundle(aliceDevice2, 2))

        val echo = cipherFor(aliceDevice1, device2Address).encrypt("echo to my other device".toByteArray())

        val device1Address = SignalProtocolAddress("alice", 1)
        val plaintext = cipherFor(aliceDevice2, device1Address).decrypt(echo as PreKeySignalMessage)
        assertEquals("echo to my other device", String(plaintext, Charsets.UTF_8))
    }

    @Test
    fun `a session deleted (revoked) can no longer decrypt new messages`() {
        val alice = freshStore()
        val bob = freshStore()
        alice.ensureIdentity()

        val bobAddress = SignalProtocolAddress("bob", 1)
        SessionBuilder(alice, alice, alice, alice, bobAddress).process(buildBundle(bob, 1))
        cipherFor(alice, bobAddress).encrypt("establish".toByteArray()).let {
            cipherFor(bob, SignalProtocolAddress("alice", 1)).decrypt(it as PreKeySignalMessage)
        }

        val aliceAddress = SignalProtocolAddress("alice", 1)
        bob.deleteSession(aliceAddress)

        val next = cipherFor(alice, bobAddress).encrypt("after revoke".toByteArray())
        assertFailsWith<Exception>("a deleted/revoked session must not silently decrypt") {
            cipherFor(bob, aliceAddress).decrypt(SignalMessage(next.serialize()))
        }
    }

    @Test
    fun `identity persists across a simulated app restart`() {
        val backing = mutableMapOf<String, Any>()
        val beforeRestart = SignalKeyStore(InMemorySecureKeyValueStore(backing))
        beforeRestart.ensureIdentity()
        val identityBefore = beforeRestart.identityKeyPair.serialize()
        val registrationIdBefore = beforeRestart.localRegistrationId

        // A brand-new SignalKeyStore instance over the SAME backing map
        // simulates the process restarting and re-reading the same
        // encrypted-on-disk file - ensureIdentity() must be a no-op here.
        val afterRestart = SignalKeyStore(InMemorySecureKeyValueStore(backing))
        assertTrue(afterRestart.hasIdentity(), "identity must survive a restart")
        afterRestart.ensureIdentity()

        assertTrue(identityBefore.contentEquals(afterRestart.identityKeyPair.serialize()), "restart must never regenerate the identity key pair")
        assertEquals(registrationIdBefore, afterRestart.localRegistrationId, "restart must never regenerate the registration id")
    }

    @Test
    fun `a fresh store (simulating reinstall) gets a brand new identity`() {
        val original = SignalKeyStore(InMemorySecureKeyValueStore())
        original.ensureIdentity()

        // Reinstall = a brand new backing store (the encrypted file was
        // deleted with app data) - never silently recovers the old identity.
        val reinstalled = SignalKeyStore(InMemorySecureKeyValueStore())
        assertFalse(reinstalled.hasIdentity())
        reinstalled.ensureIdentity()

        assertFalse(
            original.identityKeyPair.serialize().contentEquals(reinstalled.identityKeyPair.serialize()),
            "a reinstall must produce a genuinely new identity, never reuse the old one",
        )
    }

    @Test
    fun `one-time prekey ids never collide across regenerated batches`() {
        val store = freshStore()
        store.ensureIdentity()
        val firstBatch = store.generateOneTimePreKeys(10).map { it.id }.toSet()
        val secondBatch = store.generateOneTimePreKeys(10).map { it.id }.toSet()
        assertTrue(firstBatch.intersect(secondBatch).isEmpty(), "a second generated batch must never reuse ids from the first")
    }

    @Test
    fun `account namespaces isolate identity sessions and trust state`() {
        // Two accounts over the SAME physical backing store (one encrypted
        // file on one device) must never share identity/session/trust state.
        val backing = mutableMapOf<String, Any>()
        val accountA = SignalKeyStore(InMemorySecureKeyValueStore(backing)) { "userA:devA" }
        val accountB = SignalKeyStore(InMemorySecureKeyValueStore(backing)) { "userB:devB" }

        accountA.ensureIdentity()
        accountB.ensureIdentity()

        assertFalse(
            accountA.identityKeyPair.serialize().contentEquals(accountB.identityKeyPair.serialize()),
            "different accounts must have distinct identity key pairs",
        )

        val remote = SignalProtocolAddress("remoteUser", 1)
        // IdentityKeyPair.getPublicKey() already returns IdentityKey in modern
        // libsignal-client (not ECPublicKey as in the legacy Java port).
        val remoteIdentity = accountB.identityKeyPair.publicKey

        // A trust pin saved by A must not be visible under B.
        accountA.saveIdentity(remote, remoteIdentity)
        assertNotNull(accountA.getIdentity(remote))
        assertNull(accountB.getIdentity(remote))

        // A session stored by A must not be visible under B.
        accountA.storeSession(remote, org.signal.libsignal.protocol.state.SessionRecord())
        assertTrue(accountA.containsSession(remote))
        assertFalse(accountB.containsSession(remote))
    }

    @Test
    fun `same account relogin recovers the same namespaced identity`() {
        val backing = mutableMapOf<String, Any>()

        // First session of account A on its server account-device.
        val first = SignalKeyStore(InMemorySecureKeyValueStore(backing)) { "userA:devA" }
        first.ensureIdentity()
        val identity = first.identityKeyPair.serialize()
        val registrationId = first.localRegistrationId

        // Logout -> relogin to the SAME account + SAME reused device row ->
        // same namespace -> the ORIGINAL identity must be recovered, not a new
        // one (no safety-number change from ordinary logout/relogin).
        val relogin = SignalKeyStore(InMemorySecureKeyValueStore(backing)) { "userA:devA" }
        assertTrue(relogin.hasIdentity(), "relogin must recover the persisted identity")
        relogin.ensureIdentity() // must be a no-op
        assertTrue(identity.contentEquals(relogin.identityKeyPair.serialize()), "relogin must never regenerate the identity key pair")
        assertEquals(registrationId, relogin.localRegistrationId, "relogin must never regenerate the registration id")
    }
    @Test fun `bidirectional ratchet survives logout and bundled origin change`() {
        val origin = "http://192.168.0.106:8080"
        val persisted = InMemorySecureKeyValueStore()
        val owner = "alice:device"
        val oldPrefix = SignalNamespaceResolver.prefix(origin)
        val original = SignalKeyStore(persisted) { oldPrefix + owner }
        original.ensureIdentity()
        var prefix = oldPrefix
        fun restored() = SignalKeyStore(persisted) { SignalNamespaceResolver(persisted).resolve(origin, owner, prefix) }
        val alice = restored()
        val bob = freshStore()
        val bobAddress = SignalProtocolAddress("bob", 1)
        val aliceAddress = SignalProtocolAddress("alice", 1)
        SessionBuilder(alice, alice, alice, alice, bobAddress).process(buildBundle(bob, 1))
        val first = SessionCipher(alice, alice, alice, alice, alice, bobAddress).encrypt("before logout".toByteArray())
        val bobCipher = SessionCipher(bob, bob, bob, bob, bob, aliceAddress)
        assertEquals("before logout", String(bobCipher.decrypt(first as PreKeySignalMessage)))
        val reply = bobCipher.encrypt("before relogin".toByteArray())
        prefix = "" // New build would previously select another store.
        val afterLogin = restored()
        val aliceCipher = SessionCipher(afterLogin, afterLogin, afterLogin, afterLogin, afterLogin, bobAddress)
        assertEquals("before relogin", String(aliceCipher.decrypt(reply as SignalMessage)))
        val next = aliceCipher.encrypt("after unblock".toByteArray())
        assertEquals("after unblock", String(bobCipher.decrypt(next as SignalMessage)))
        assertEquals(original.localRegistrationId, afterLogin.localRegistrationId)
    }

}
