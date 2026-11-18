package com.messenger.app.data.signal

import com.messenger.app.data.secure.DurableIdentityPins
import com.messenger.app.data.secure.LocalSecurityDecisions
import com.messenger.app.data.api.*
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import kotlin.test.*

class DurableIdentityPinsTest {
    private val address = SignalProtocolAddress("peer", 1)
    private fun key() = IdentityKeyPair.generate().publicKey
    private fun pins(disk: SecureKeyValueStore, owner: () -> String? = { "https://one.invalid|me" }, limit: Int = 4096, now: () -> Long = { 10 }) =
        DurableIdentityPins(disk, owner, log = {}, limit = limit, now = now)
    private fun identity(store: SignalKeyStore, dao: InMemoryPeerIdentityDao) = IdentityVerificationManager(store, dao, { "me" }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()))
    private fun signal(pins: DurableIdentityPins, disk: SecureKeyValueStore = InMemorySecureKeyValueStore()) =
        SignalKeyStore(disk, accountNamespace = { "new-login-device" }, identityPins = pins).also { it.ensureIdentity() }

    @Test fun forcedWipeAndReloginRestoreVerifiedPinWithoutSessionOrUiHydration() = runBlocking {
        val durable = InMemorySecureKeyValueStore(); val sessions = InMemorySecureKeyValueStore()
        val pins = pins(durable); val before = signal(pins, sessions); val original = key()
        before.saveIdentity(address, original)
        val dao = InMemoryPeerIdentityDao(); val verification = identity(before, dao)
        verification.recordFirstContact("peer", 1); verification.markVerified("peer", 1)
        before.storeSession(address, org.signal.libsignal.protocol.state.SessionRecord())
        // Model the exact full Signal/session wipe shared by revoked auth and user sign-out.
        sessions.keysWithPrefix("").forEach(sessions::remove)
        val after = signal(pins(durable)); val afterDao = InMemoryPeerIdentityDao()
        assertFalse(after.containsSession(address))
        assertContentEquals(original.serialize(), after.getIdentity(address)!!.serialize())
        assertTrue(after.isTrustedIdentity(address, original, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))
        identity(after, afterDao).hydrateFromDurableStorage()
        assertEquals(IDENTITY_VERIFIED, afterDao.get("peer", 1)!!.status)
        assertEquals(10L, pins.get("peer", 1)!!.firstSeen)
    }

    @Test fun accountOriginAndSignalDeviceAreIsolatedButOwnDeviceIsNotPartOfOwner() {
        val disk = InMemorySecureKeyValueStore(); var owner = LocalSecurityDecisions.owner("https://one.invalid/", "me")
        val pins = pins(disk, { owner }); val original = key()
        signal(pins).saveIdentity(address, original)
        assertNotNull(signal(pins).getIdentity(address))
        assertNull(signal(pins).getIdentity(SignalProtocolAddress("peer", 2)))
        owner = LocalSecurityDecisions.owner("https://one.invalid", "other"); assertNull(signal(pins).getIdentity(address))
        owner = LocalSecurityDecisions.owner("https://two.invalid", "me"); assertNull(signal(pins).getIdentity(address))
        owner = LocalSecurityDecisions.owner("https://one.invalid", "me"); assertContentEquals(original.serialize(), signal(pins).getIdentity(address)!!.serialize())
    }

    private suspend fun changedKeyScenario(verified: Boolean) {
        val disk = InMemorySecureKeyValueStore(); val pins = pins(disk); val oldKey = key()
        val before = signal(pins); before.saveIdentity(address, oldKey)
        val originalDao = InMemoryPeerIdentityDao(); val beforeIdentity = identity(before, originalDao)
        beforeIdentity.recordFirstContact("peer", 1); if (verified) beforeIdentity.markVerified("peer", 1)
        val after = signal(pins(disk)); val dao = InMemoryPeerIdentityDao(); val verification = identity(after, dao)
        val remote = SignalKeyStore(InMemorySecureKeyValueStore()).also { it.ensureIdentity(); it.generateSignedPreKey(); it.generateKyberPreKey() }
        val signed = remote.loadSignedPreKey(1); val kyber = remote.loadKyberPreKey(1)
        val b64 = Base64.getEncoder()
        val bundle = DeviceBundleDto(deviceId = "remote", signalDeviceId = 1, registrationId = remote.localRegistrationId,
            identityKey = b64.encodeToString(remote.identityKeyPair.publicKey.serialize()), signedPrekeyId = 1,
            signedPrekeyPublic = b64.encodeToString(signed.keyPair.publicKey.serialize()), signedPrekeySignature = b64.encodeToString(signed.signature),
            kyberPrekeyId = 1, kyberPrekeyPublic = b64.encodeToString(kyber.keyPair.publicKey.serialize()), kyberPrekeySignature = b64.encodeToString(kyber.signature))
        val json = Json { ignoreUnknownKeys = true }
        val response = json.encodeToString(UserBundlesResponseDto.serializer(), UserBundlesResponseDto(listOf(bundle)))
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(ResponseBody.create(null, response)).build() }.build()
        val manager = SignalSessionManager(after, KeysApi(client, json, "https://unit.invalid"), verification)
        assertFailsWith<IdentityChangedException> { manager.verifyBundleIdentitiesOrThrow("peer", listOf(bundle)) }
        assertEquals(IDENTITY_CHANGED, dao.get("peer", 1)!!.status)
        assertFailsWith<IdentityChangedException> { verification.ensureNoChangedForUser("peer") }
        if (verified) assertFailsWith<IdentityChangedException> { manager.buildEnvelopesForUser("peer", "fixture", "must not encrypt".toByteArray()) }
        else assertFails { manager.buildEnvelopesForUser("peer", "fixture", "must not encrypt".toByteArray()) }
        assertFalse(after.containsSession(address))
        assertEquals(LocalSecurityDecisions.fingerprint(oldKey.serialize()), pins.get("peer", 1)!!.identityFingerprint)
        assertEquals(verified, pins.get("peer", 1)!!.verified)
        // Pending warning is durable too and is never silently re-pinned by another login.
        val again = signal(pins(disk)); val againDao = InMemoryPeerIdentityDao(); val againVerification = identity(again, againDao)
        againVerification.hydrateFromDurableStorage(); assertEquals(IDENTITY_CHANGED, againDao.get("peer", 1)!!.status)
        againVerification.acceptNewKey("peer", 1, againDao.get("peer", 1)!!.identityKeyB64)
        assertEquals(IDENTITY_UNVERIFIED, againDao.get("peer", 1)!!.status)
        assertTrue(again.isTrustedIdentity(address, remote.identityKeyPair.publicKey, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))
        assertFalse(pins.get("peer", 1)!!.verified)
    }
    @Test fun changedVerifiedPeerBlocksActualFanoutUntilExplicitAcceptance() = runBlocking { changedKeyScenario(true) }
    @Test fun changedUnverifiedPeerUsesExistingChangedWarningAndAcceptance() = runBlocking { changedKeyScenario(false) }

    @Test fun inboundNewKeyCannotBeAcceptedBeforeProjectionHydration() = runBlocking {
        val disk = InMemorySecureKeyValueStore(); val pins = pins(disk)
        signal(pins).saveIdentity(address, key())
        val receiver = signal(pins(disk)); receiver.generateSignedPreKey(); receiver.generateKyberPreKey()
        val sender = SignalKeyStore(InMemorySecureKeyValueStore()).also { it.ensureIdentity() }
        val signed = receiver.loadSignedPreKey(1); val kyber = receiver.loadKyberPreKey(1)
        val bundle = org.signal.libsignal.protocol.state.PreKeyBundle(receiver.localRegistrationId, 1,
            org.signal.libsignal.protocol.state.PreKeyBundle.NULL_PRE_KEY_ID, null, 1, signed.keyPair.publicKey, signed.signature,
            receiver.identityKeyPair.publicKey, 1, kyber.keyPair.publicKey, kyber.signature)
        val target = SignalProtocolAddress("me", 1)
        SessionBuilder(sender, sender, sender, sender, target).process(bundle)
        val encrypted = SessionCipher(sender, sender, sender, sender, sender, target).encrypt("attack".toByteArray())
        val dao = InMemoryPeerIdentityDao(); val verification = identity(receiver, dao)
        val client = OkHttpClient.Builder().addInterceptor { error("No HTTP allowed") }.build()
        val manager = SignalSessionManager(receiver, KeysApi(client, Json, "https://unit.invalid"), verification)
        assertFailsWith<IdentityChangedException> { manager.decrypt("peer", 1, 1, Base64.getEncoder().encodeToString(encrypted.serialize())) }
        assertEquals(IDENTITY_CHANGED, dao.get("peer", 1)!!.status)
        assertFalse(receiver.containsSession(address))
    }

    @Test fun serverAccountDeletionAndExplicitEraseRemoveOnlyThatAccountsPins() {
        val disk = InMemorySecureKeyValueStore(); var owner = "one"; val pins = pins(disk, { owner })
        pins.pin("peer", 1, key().serialize()); owner = "two"; pins.pin("peer", 1, key().serialize())
        pins.eraseAccount("one"); assertNotNull(pins.get("peer", 1)); owner = "one"; assertNull(pins.get("peer", 1))
        assertFalse(signal(pins).hasPeerIdentity("peer"))
    }

    @Test fun corruptVerifiedPayloadIsDroppedLoggedAndFailsClosedAcrossRestart() {
        for (bad in listOf("{", "{}", "x".repeat(2049))) {
            val disk = InMemorySecureKeyValueStore(); val warnings = mutableListOf<String>()
            val pins = DurableIdentityPins(disk, { "one" }, warnings::add)
            val identity = key(); pins.pin("peer", 1, identity.serialize())
            val recordKey = disk.keysWithPrefix("identity-pins-v1:").single()
            disk.putBytes(recordKey, bad.toByteArray())
            val tombstone = pins.get("peer", 1)!!
            assertTrue(tombstone.corrupt); assertTrue(tombstone.verified); assertEquals(1, warnings.size)
            val after = signal(DurableIdentityPins(disk, { "one" }, warnings::add))
            assertFalse(after.isTrustedIdentity(address, identity, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))
            assertTrue(after.hasPeerIdentity("peer")); assertFails { after.saveIdentity(address, identity) }
            assertEquals(1, warnings.size)
        }
    }

    @Test fun strictParsingRejectsMissingFlagsFingerprintMismatchAndUnknownFields() {
        for (mutate in listOf<(String) -> String>(
            { it.replace("\"verified\":false,", "") },
            { it.replace("\"version\":1", "\"version\":2") },
            { it.replace("\"signalDeviceId\":1", "\"signalDeviceId\":2") },
            { it.replace("\"identityFingerprint\":\"", "\"identityFingerprint\":\"0") },
            { it.dropLast(1) + ",\"verified\":false}" },
            { it.dropLast(1) + ",\"unknown\":true}" }
        )) {
            val disk = InMemorySecureKeyValueStore(); val pins = pins(disk); pins.pin("peer", 1, key().serialize())
            val stored = disk.keysWithPrefix("identity-pins-v1:").single(); disk.putBytes(stored, mutate(String(disk.getBytes(stored)!!)).toByteArray())
            assertTrue(pins.get("peer", 1)!!.corrupt)
        }
    }

    @Test fun capEvictsOldestUnverifiedAndNeverVerifiedOrPending() {
        val disk = InMemorySecureKeyValueStore(); var clock = 0L; val pins = pins(disk, limit = 3, now = { ++clock })
        val bytes = key().serialize(); val b64 = Base64.getEncoder().encodeToString(bytes)
        pins.pin("verified", 1, bytes); pins.verification(PeerVerificationRecord("verified", 1, IDENTITY_VERIFIED, identityKeyB64 = b64))
        pins.pin("old", 1, bytes); pins.pin("new", 1, bytes); pins.pin("newest", 1, bytes)
        assertNull(pins.get("old", 1)); assertTrue(pins.get("verified", 1)!!.verified); assertEquals(3, pins.all().size)
        pins.verification(PeerVerificationRecord("new", 1, IDENTITY_CHANGED, identityKeyB64 = b64))
        pins.verification(PeerVerificationRecord("newest", 1, IDENTITY_VERIFIED, identityKeyB64 = b64))
        assertFails { pins.pin("overflow", 1, bytes) }; assertEquals(3, pins.all().size)
    }

    @Test fun storageFailureNeverAuthorizesFirstContactOrErasesExistingPins() {
        val disk = InMemorySecureKeyValueStore()
        val failing = object : SecureKeyValueStore by disk { override fun commitReceive(bytes: Map<String, ByteArray?>, ints: Map<String, Int>) { error("disk full") } }
        val pins = pins(failing); val signal = signal(pins)
        assertFails { signal.saveIdentity(address, key()) }; assertNull(signal.getIdentity(address))
    }

    @Test fun legacyVerifiedAndRoomVerifiedAreMigratedBeforeWipe() = runBlocking {
        val sessions = InMemorySecureKeyValueStore(); val legacy = SignalKeyStore(sessions, accountNamespace = { "new-login-device" }); legacy.ensureIdentity()
        val original = key(); legacy.saveIdentity(address, original)
        val dao = InMemoryPeerIdentityDao(); val manager = identity(legacy, dao)
        manager.recordFirstContact("peer", 1); manager.markVerified("peer", 1)
        val disk = InMemorySecureKeyValueStore(); val upgraded = signal(pins(disk), sessions)
        upgraded.migrateIdentityPins(); assertEquals(IDENTITY_VERIFIED, upgraded.loadPeerVerification("peer", 1)!!.status)
        sessions.remove("new-login-device:peer_verification_peer_1")
        val disk2 = InMemorySecureKeyValueStore(); val roomUpgrade = signal(pins(disk2), sessions)
        roomUpgrade.migrateIdentityPins(); identity(roomUpgrade, dao).backfillExistingVerificationState()
        assertTrue(pins(disk2).get("peer", 1)!!.verified)
    }

    @Test fun unchangedPeerCanEstablishAndReceiveAfterReloginBeforeUiHydration() {
        val disk = InMemorySecureKeyValueStore(); val pins = pins(disk)
        val peer = SignalKeyStore(InMemorySecureKeyValueStore()).also { it.ensureIdentity(); it.generateSignedPreKey(); it.generateKyberPreKey() }
        signal(pins).saveIdentity(address, peer.identityKeyPair.publicKey)
        val after = signal(pins(disk)); val signed = peer.loadSignedPreKey(1); val kyber = peer.loadKyberPreKey(1)
        val bundle = org.signal.libsignal.protocol.state.PreKeyBundle(peer.localRegistrationId, 1,
            org.signal.libsignal.protocol.state.PreKeyBundle.NULL_PRE_KEY_ID, null, 1, signed.keyPair.publicKey, signed.signature,
            peer.identityKeyPair.publicKey, 1, kyber.keyPair.publicKey, kyber.signature)
        SessionBuilder(after, after, after, after, address).process(bundle)
        val sent = SessionCipher(after, after, after, after, after, address).encrypt("ordinary message".toByteArray())
        val remoteAddress = SignalProtocolAddress("me", 1)
        val received = SessionCipher(peer, peer, peer, peer, peer, remoteAddress).decrypt(PreKeySignalMessage(sent.serialize()))
        assertEquals("ordinary message", String(received))
    }

    @Test fun staleOperationCannotWritePinsIntoReplacementAccount() = runBlocking {
        val disk = InMemorySecureKeyValueStore(); var account = "a"; var epoch = 1
        val pins = pins(disk, { account })
        val store = SignalKeyStore(InMemorySecureKeyValueStore(), accountEpoch = { epoch }, accountNamespace = { account }, identityPins = pins)
        val key = Base64.getEncoder().encodeToString(key().serialize())
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            store.withAccount {
                account = "b"; epoch++
                store.savePeerVerification(PeerVerificationRecord("peer", 1, IDENTITY_VERIFIED, identityKeyB64 = key))
            }
        }
        assertTrue(pins.all().isEmpty())
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            store.withAccount {
                account = "c" // Check the owner guard independently of epoch changes.
                store.savePeerVerification(PeerVerificationRecord("peer", 1, IDENTITY_VERIFIED, identityKeyB64 = key))
            }
        }
        assertTrue(pins.all().isEmpty())
    }

    @Test fun malformedAddressQuarantinesAccountWithoutCrashingOrTrustingFreshPeers() {
        val disk = InMemorySecureKeyValueStore(); val pins = pins(disk)
        pins.pin("peer", 1, key().serialize())
        val prefix = disk.keysWithPrefix("identity-pins-v1:").single().substringBeforeLast(':').substringBeforeLast(':') + ":"
        disk.putBytes(prefix + "invalid-address", "{}".toByteArray())
        assertEquals(1, pins.all().size)
        assertFalse(pins.trusts("unknown", 1, key().serialize()))
        assertFails { pins.pin("unknown", 1, key().serialize()) }
    }

    @Test fun legacyPendingOnlyDeviceCannotBecomeFirstSeenAfterUpgradeAndWipe() {
        val sessions = InMemorySecureKeyValueStore()
        val legacy = SignalKeyStore(sessions, accountNamespace = { "new-login-device" })
        legacy.savePeerVerification(PeerVerificationRecord("peer", 1, IDENTITY_CHANGED,
            identityKeyB64 = Base64.getEncoder().encodeToString(key().serialize())))
        val disk = InMemorySecureKeyValueStore(); val upgraded = signal(pins(disk), sessions)
        upgraded.migrateIdentityPins()
        sessions.keysWithPrefix("").forEach(sessions::remove)
        val after = signal(pins(disk))
        assertEquals(IDENTITY_CHANGED, after.loadPeerVerification("peer", 1)!!.status)
        assertTrue(after.hasPeerIdentity("peer"))
    }

    @Test fun actualKeystoreAdapterSignoutWipeCannotEraseSeparateInstallationPins() {
        val values = mutableMapOf<String, Any>()
        fun editor(): android.content.SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any>(); var clear = false
            return java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor::class.java.classLoader,
                arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putInt" -> { pending[args!![0] as String] = args[1]; proxy }
                    "clear" -> { clear = true; proxy }
                    "commit", "apply" -> { if (clear) values.clear(); values.putAll(pending); if (method.name == "commit") true else null }
                    else -> error("Unexpected editor method ${method.name}")
                }
            } as android.content.SharedPreferences.Editor
        }
        val preferences = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString", "getInt" -> values[args!![0] as String] ?: args[1]
                "contains" -> values.containsKey(args!![0] as String)
                "edit" -> editor()
                else -> error("Unexpected preference method ${method.name}")
            }
        } as android.content.SharedPreferences
        val sessionStorage = AndroidKeystoreSignalStore(preferences)
        val disk = InMemorySecureKeyValueStore(); val pins = pins(disk)
        val before = signal(pins, sessionStorage); val original = key()
        before.saveIdentity(address, original)
        assertTrue(values.isNotEmpty())
        sessionStorage.eraseAllAccountSecrets()
        assertTrue(values.isEmpty())
        val after = signal(pins(disk), AndroidKeystoreSignalStore(preferences))
        assertContentEquals(original.serialize(), after.getIdentity(address)!!.serialize())
        assertFalse(after.containsSession(address))
    }
}
