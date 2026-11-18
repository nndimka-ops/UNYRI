package com.messenger.app.data.signal

import kotlinx.coroutines.asContextElement
import java.util.Base64
import kotlin.random.Random
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

internal fun receiveChatTag(chatId: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(chatId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * Persistent Signal Protocol store: identity key pair, registration id,
 * signed prekey, one-time prekeys, the (single, reusable) last-resort Kyber
 * prekey, per-remote-device session state, and trust-on-first-use identity
 * pinning. Implements the five individual libsignal store interfaces
 * directly (not the combined `SignalProtocolStore`, which also requires
 * `SenderKeyStore` group-chat methods this app doesn't need).
 *
 * All key material is opaque bytes as far as [SecureKeyValueStore] is
 * concerned - never logged, never written to plain Room. Production wiring
 * uses [AndroidKeystoreSignalStore]; tests can substitute an in-memory fake
 * to exercise the real libsignal crypto without an Android device.
 */
class SignalKeyStore(
    store: SecureKeyValueStore,
    private val accountEpoch: () -> Int = { 0 },
    private val accountNamespace: () -> String = { "" },
    private val identityPins: com.messenger.app.data.secure.DurableIdentityPins? = null,
) : IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore {
    constructor(store: SecureKeyValueStore, accountNamespace: () -> String) : this(store, { 0 }, accountNamespace, null)
    private val operationOwner = ThreadLocal<String?>()
    private val operationEpoch = ThreadLocal<Int?>()
    private val backing = store
    private class ReceiveWrites {
        val bytes = linkedMapOf<String, ByteArray?>()
        val ints = linkedMapOf<String, Int>()
    }
    private val receiveWrites = ThreadLocal<ReceiveWrites?>()
    fun <T> nativeOperation(block: () -> T): T = backing.withAccess { checkOperationEpoch(); block() }
    // Recheck the captured epoch INSIDE the same short lock as logout's wipe.
    // This closes the check/write gap even when the same account signs back in.
    private val store = object : SecureKeyValueStore {
        private fun <T> access(block: () -> T): T = backing.withAccess { checkOperationEpoch(); block() }
        override fun getBytes(key: String) = access { receiveWrites.get()?.let { if (it.bytes.containsKey(key)) return@access it.bytes[key] }; backing.getBytes(key) }
        override fun putBytes(key: String, value: ByteArray) = access { receiveWrites.get()?.let { it.bytes[key] = value; return@access }; backing.putBytes(key, value) }
        override fun putNamespaceBinding(key: String, value: ByteArray) = access { backing.putNamespaceBinding(key, value) }
        override fun getInt(key: String) = access { receiveWrites.get()?.let { if (it.bytes.containsKey(key)) return@access null; it.ints[key]?.let { value -> return@access value } }; backing.getInt(key) }
        override fun putInt(key: String, value: Int) = access { receiveWrites.get()?.let { it.ints[key] = value; return@access }; backing.putInt(key, value) }
        override fun remove(key: String) = access { receiveWrites.get()?.let { it.bytes[key] = null; it.ints.remove(key); return@access }; backing.remove(key) }
        override fun contains(key: String) = access { receiveWrites.get()?.let { if (it.bytes.containsKey(key)) return@access it.bytes[key] != null; if (it.ints.containsKey(key)) return@access true }; backing.contains(key) }
        override fun keysWithPrefix(prefix: String) = access { backing.keysWithPrefix(prefix) }
    }

    /** Native callbacks are synchronous and stay on this IO worker while staged. */
    fun receiveJournaled(key: String, routing: ByteArray? = null, decrypt: () -> ByteArray): ByteArray = backing.withAccess {
        checkOperationEpoch()
        val journal = ns("receive_$key")
        backing.getBytes(journal)?.let { return@withAccess it }
        val usage = backing.byteUsageWithPrefix(ns("receive_"))
        check(usage.entries < 512 && usage.bytes < 32L * 1024 * 1024) { "Receive recovery capacity reached" }
        val writes = ReceiveWrites()
        check(receiveWrites.get() == null)
        receiveWrites.set(writes)
        try {
            val plaintext = decrypt()
            check(usage.bytes + plaintext.size <= 32L * 1024 * 1024) { "Receive recovery capacity reached" }
            writes.bytes[journal] = plaintext
            routing?.let { writes.bytes[ns("receive-routing_$key")] = it }
            checkOperationEpoch()
            backing.commitReceive(writes.bytes, writes.ints)
            plaintext
        } finally { receiveWrites.remove() }
    }

    fun acknowledgeReceive(key: String) = backing.withAccess {
        checkOperationEpoch()
        val journal = ns("receive_$key")
        val routing = ns("receive-routing_$key")
        if (backing.contains(journal) || backing.contains(routing)) backing.commitReceive(mapOf(journal to null,routing to null), emptyMap())
    }
    fun receivePlaintext(key: String): ByteArray? = store.getBytes(ns("receive_$key"))
    fun retainedReceiveRouting(): List<ByteArray> = backing.withAccess {
        checkOperationEpoch()
        backing.keysWithPrefix(ns("receive-routing_")).map { checkNotNull(backing.getBytes(it)) }
    }
    fun discardReceiveChat(chatId: String) = backing.withAccess {
        checkOperationEpoch()
        val tag = receiveChatTag(chatId)
        val removed = (backing.keysWithPrefix(ns("receive_${tag}_")) + backing.keysWithPrefix(ns("receive-routing_${tag}_"))).associateWith { null as ByteArray? }
        if (removed.isNotEmpty()) backing.commitReceive(removed, emptyMap())
    }
    private fun checkOperationEpoch() {
        if (operationEpoch.get()?.let { it != accountEpoch() } == true) throw kotlinx.coroutines.CancellationException("Signal session changed")
    }

    suspend fun <T> withAccount(expectedEpoch: Int? = null, block: suspend () -> T): T = kotlinx.coroutines.withContext(
        operationOwner.asContextElement(operationOwner.get() ?: accountNamespace()) +
            operationEpoch.asContextElement(operationEpoch.get() ?: expectedEpoch ?: accountEpoch())
    ) { checkOperationEpoch(); block() }

    /** All local key material is namespaced by the LOCAL account
     *  (`userId:deviceId`) so that switching accounts on one physical
     *  installation can never share an identity key, prekeys, sessions, or
     *  trust state. The empty namespace (never logged in) is a shared no-op;
     *  `bootstrapKeysIfNeeded` always runs post-login, when the namespace is
     *  already populated. */
    private fun ns(raw: String): String {
        val n = backing.withAccess { checkOperationEpoch(); accountNamespace() }
        val expected = operationOwner.get()
        if (expected != null && expected != n) throw kotlinx.coroutines.CancellationException("Signal account changed")
        return if (n.isEmpty()) raw else "$n:$raw"
    }

    // ---------------------------------------------------------------------
    // Identity / registration id lifecycle
    // ---------------------------------------------------------------------

    /** True once this signed-in account has a persisted identity. Logout or
     * reinstall clears the backing encrypted file, so a new login uses fresh keys. */
    fun hasIdentity(): Boolean = store.contains(ns(KEY_IDENTITY_KEY_PAIR))

    /** Idempotent: generates an identity key pair + registration id only if
     *  none exists yet for the current account. Safe to call on every
     *  login/app start. */
    fun ensureIdentity() {
        if (!store.contains(ns(KEY_IDENTITY_KEY_PAIR))) {
            store.putBytes(ns(KEY_IDENTITY_KEY_PAIR), IdentityKeyPair.generate().serialize())
        }
        if (!store.contains(ns(KEY_REGISTRATION_ID))) {
            // Signal convention: a random id in [1, 16380], generated once.
            store.putInt(ns(KEY_REGISTRATION_ID), Random.nextInt(1, 16381))
        }
    }

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val bytes = store.getBytes(ns(KEY_IDENTITY_KEY_PAIR))
            ?: error("Signal identity not initialized - call ensureIdentity() first.")
        return IdentityKeyPair(bytes)
    }

    override fun getLocalRegistrationId(): Int =
        store.getInt(ns(KEY_REGISTRATION_ID)) ?: error("Signal identity not initialized - call ensureIdentity() first.")

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange = nativeOperation {
        val key = trustKey(address)
        val previous = store.getBytes(key)
        identityPins?.pin(address.name, address.deviceId, identityKey.serialize())
        store.putBytes(key, identityKey.serialize())
        if (previous != null && !previous.contentEquals(identityKey.serialize())) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    fun acceptIdentity(address: SignalProtocolAddress, identityKey: IdentityKey) = nativeOperation {
        ns("")
        identityPins?.accept(address.name, address.deviceId, identityKey.serialize())
        saveIdentity(address, identityKey)
    }

    fun durableIdentityMatches(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean = nativeOperation {
        ns("")
        identityPins?.trusts(address.name, address.deviceId, identityKey.serialize()) != false
    }

    fun verifiedIdentityPending(user: String, device: Int): Boolean = nativeOperation {
        ns("")
        identityPins?.get(user, device)?.let { it.verified && (it.corrupt || it.pendingIdentityKeyB64 != null) } == true
    }

    /** TOFU applies only to a user with no identity yet. Additional addresses and
     * pending identities must be admitted through explicit comparison. */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        if (!durableIdentityMatches(address, identityKey)) return false
        if (loadPeerVerification(address.name, address.deviceId)?.status == IDENTITY_CHANGED) return false
        val stored = getIdentity(address)?.serialize() ?: return !hasPeerIdentity(address.name)
        return stored.contentEquals(identityKey.serialize())
    }

    fun hasPeerIdentity(userId: String): Boolean = nativeOperation {
        store.keysWithPrefix(ns("identity_${userId}_")).isNotEmpty() || identityPins?.hasPeer(userId) == true
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = nativeOperation {
        (store.getBytes(trustKey(address)) ?: identityPins?.get(address.name, address.deviceId)?.identityKeyB64?.let { Base64.getDecoder().decode(it) })?.let { IdentityKey(it) }
    }

    /** Upgrade legacy trust before any wipe or network job. Verified records first. */
    fun migrateIdentityPins() = nativeOperation {
        val pins = identityPins ?: return@nativeOperation
        val legacy = store.keysWithPrefix(ns("identity_")).mapNotNull { key ->
            val raw = key.removePrefix(ns("identity_"))
            val device = raw.substringAfterLast('_').toIntOrNull() ?: return@mapNotNull null
            val user = raw.substringBeforeLast('_')
            val verification = store.getBytes(verificationKey(user, device))?.let { PeerVerificationRecord.decode(it) }
            Triple(user, device, verification)
        }.sortedBy { if (it.third?.status == IDENTITY_VERIFIED) 0 else 1 }
        legacy.forEach { (user, device, verification) ->
            if (pins.get(user, device) == null) {
                val bytes = store.getBytes(trustKey(SignalProtocolAddress(user, device)))
                if (bytes == null || runCatching { IdentityKey(bytes) }.isFailure ||
                    (store.contains(verificationKey(user, device)) && verification == null)) pins.quarantine(user, device)
                else {
                    if (runCatching { pins.pin(user, device, bytes) }.isFailure) pins.quarantineAccount()
                    else if (verification != null && runCatching { pins.verification(verification) }.isFailure) pins.quarantine(user, device)
                }
            }
        }
        // Unknown-device warnings may have a pending verification but no accepted
        // trust key/session yet. They must survive the first upgrade's wipe too.
        store.keysWithPrefix(ns("peer_verification_")).forEach { key ->
            val raw = key.removePrefix(ns("peer_verification_"))
            val device = raw.substringAfterLast('_').toIntOrNull() ?: return@forEach
            val user = raw.substringBeforeLast('_')
            if (pins.get(user, device) == null) {
                val record = store.getBytes(key)?.let { PeerVerificationRecord.decode(it) }
                if (record == null || record.userId != user || record.signalDeviceId != device ||
                    runCatching { pins.verification(record) }.isFailure) pins.quarantine(user, device)
            }
        }
    }

    private fun trustKey(address: SignalProtocolAddress) = ns("identity_${address.name}_${address.deviceId}")

    // ---------------------------------------------------------------------
    // Peer verification: the legacy session copy remains readable for upgrade.
    // Production trust comes from the separate installation identityPins store.
    // ---------------------------------------------------------------------

    private fun verificationKey(userId: String, signalDeviceId: Int) =
        ns("peer_verification_${userId}_$signalDeviceId")

    fun loadPeerVerification(userId: String, signalDeviceId: Int): PeerVerificationRecord? = nativeOperation {
        identityPins?.get(userId, signalDeviceId)?.let { return@nativeOperation it.verification() }
        store.getBytes(verificationKey(userId, signalDeviceId))?.let { PeerVerificationRecord.decode(it) }
    }

    fun savePeerVerification(record: PeerVerificationRecord) {
        nativeOperation {
            val key = verificationKey(record.userId, record.signalDeviceId)
            identityPins?.verification(record)
            store.putBytes(key, record.encode())
        }
    }

    fun clearPeerVerification(userId: String, signalDeviceId: Int) {
        store.remove(verificationKey(userId, signalDeviceId))
    }

    /** All durable verification records for the CURRENT account only (the
     *  namespace prefix guarantees cross-account isolation). */
    fun allPeerVerifications(): List<PeerVerificationRecord> = nativeOperation {
        ns("")
        val durable = identityPins?.all()?.mapNotNull { it.verification() }.orEmpty()
        (durable + store.keysWithPrefix(ns("peer_verification_")).mapNotNull { key ->
            store.getBytes(key)?.let { PeerVerificationRecord.decode(it) }
        }).distinctBy { it.userId to it.signalDeviceId }
    }

    // ---------------------------------------------------------------------
    // One-time EC prekeys
    // ---------------------------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        store.getBytes(otpKey(preKeyId))?.let(::PreKeyRecord) ?: throw InvalidKeyIdException("No such prekey: $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        store.putBytes(otpKey(preKeyId), record.serialize())
    }

    override fun containsPreKey(preKeyId: Int): Boolean = store.contains(otpKey(preKeyId))

    override fun removePreKey(preKeyId: Int) {
        store.remove(otpKey(preKeyId))
    }

    private fun otpKey(id: Int) = ns("$PREFIX_OTP$id")

    /** Generates and persists a fresh batch of one-time prekeys with
     *  never-before-used ids (a monotonic counter, so a re-upload can never
     *  collide with ids the server may still have outstanding from a
     *  previous batch), returning the newly created records to upload. */
    fun generateOneTimePreKeys(count: Int): List<PreKeyRecord> {
        var nextId = store.getInt(ns(KEY_NEXT_OTP_ID)) ?: 1
        val records = (0 until count).map { offset ->
            val id = nextId + offset
            val record = PreKeyRecord(id, ECKeyPair.generate())
            store.putBytes(otpKey(id), record.serialize())
            record
        }
        store.putInt(ns(KEY_NEXT_OTP_ID), nextId + count)
        return records
    }

    fun oneTimePreKeyCountLocal(): Int = store.keysWithPrefix(ns(PREFIX_OTP)).size

    // ---------------------------------------------------------------------
    // Signed prekey (single active one, rotated wholesale - matches the
    // backend's one-row-per-device UPSERT model)
    // ---------------------------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        store.getBytes(signedPreKeyKey(signedPreKeyId))?.let(::SignedPreKeyRecord)
            ?: throw InvalidKeyIdException("No such signed prekey: $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        store.keysWithPrefix(ns(PREFIX_SIGNED_PREKEY)).mapNotNull { store.getBytes(it) }.map(::SignedPreKeyRecord)

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        store.putBytes(signedPreKeyKey(signedPreKeyId), record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = store.contains(signedPreKeyKey(signedPreKeyId))

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        store.remove(signedPreKeyKey(signedPreKeyId))
    }

    private fun signedPreKeyKey(id: Int) = ns("$PREFIX_SIGNED_PREKEY$id")

    fun hasSignedPreKey(): Boolean = containsSignedPreKey(ACTIVE_SIGNED_PREKEY_ID)

    /** Generates (or rotates) the single active signed prekey, self-signed
     *  with the identity private key, and returns it for upload. */
    fun generateSignedPreKey(): SignedPreKeyRecord {
        val keyPair = ECKeyPair.generate()
        val signature = getIdentityKeyPair().privateKey.calculateSignature(keyPair.publicKey.serialize())
        val record = SignedPreKeyRecord(ACTIVE_SIGNED_PREKEY_ID, System.currentTimeMillis(), keyPair, signature)
        storeSignedPreKey(ACTIVE_SIGNED_PREKEY_ID, record)
        return record
    }

    // ---------------------------------------------------------------------
    // Kyber (PQXDH) prekey - single reusable "last-resort" key, per the
    // documented Этап F scope reduction (a full consumable Kyber pool would
    // add real complexity for marginal benefit at this stage).
    // ---------------------------------------------------------------------

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        store.getBytes(kyberPreKeyKey(kyberPreKeyId))?.let(::KyberPreKeyRecord)
            ?: throw InvalidKeyIdException("No such kyber prekey: $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        store.keysWithPrefix(ns(PREFIX_KYBER_PREKEY)).mapNotNull { store.getBytes(it) }.map(::KyberPreKeyRecord)

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        store.putBytes(kyberPreKeyKey(kyberPreKeyId), record.serialize())
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = store.contains(kyberPreKeyKey(kyberPreKeyId))

    /** A last-resort key is never removed on use - instead each
     *  (kyberPreKeyId, signedPreKeyId, baseKey) tuple is recorded and
     *  replaying the exact same tuple is rejected, per the interface's
     *  documented anti-replay contract for last-resort keys. */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val usedKey = ns("$PREFIX_KYBER_USED${kyberPreKeyId}_${signedPreKeyId}_${Base64.getEncoder().encodeToString(baseKey.serialize())}")
        if (store.contains(usedKey)) {
            throw ReusedBaseKeyException()
        }
        store.putInt(usedKey, 1)
    }

    private fun kyberPreKeyKey(id: Int) = ns("$PREFIX_KYBER_PREKEY$id")

    fun hasKyberPreKey(): Boolean = containsKyberPreKey(ACTIVE_KYBER_PREKEY_ID)

    fun generateKyberPreKey(): KyberPreKeyRecord {
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = getIdentityKeyPair().privateKey.calculateSignature(keyPair.publicKey.serialize())
        val record = KyberPreKeyRecord(ACTIVE_KYBER_PREKEY_ID, System.currentTimeMillis(), keyPair, signature)
        storeKyberPreKey(ACTIVE_KYBER_PREKEY_ID, record)
        return record
    }

    // ---------------------------------------------------------------------
    // Sessions (per remote user + remote *device*, never per-user)
    // ---------------------------------------------------------------------

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        store.getBytes(sessionKey(address))?.let(::SessionRecord) ?: SessionRecord()

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> =
        addresses.map { loadSession(it) }.toMutableList()

    override fun getSubDeviceSessions(name: String): MutableList<Int> =
        store.keysWithPrefix(ns("$PREFIX_SESSION$name."))
            .mapNotNull { it.substringAfterLast('.').toIntOrNull() }
            .toMutableList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        store.putBytes(sessionKey(address), record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = store.contains(sessionKey(address))

    override fun deleteSession(address: SignalProtocolAddress) {
        store.remove(sessionKey(address))
    }

    override fun deleteAllSessions(name: String) {
        store.keysWithPrefix(ns("$PREFIX_SESSION$name.")).forEach(store::remove)
    }

    private fun sessionKey(address: SignalProtocolAddress) = ns("$PREFIX_SESSION${address.name}.${address.deviceId}")

    // ---------------------------------------------------------------------
    // Remote device-id mapping cache: Signal Protocol addresses a device by
    // a small int (`signal_device_id`), the server/HTTP layer addresses it
    // by UUID (`devices.id`) - this maps between the two per remote user,
    // populated whenever a bundle is fetched (see SignalSessionManager).
    // ---------------------------------------------------------------------

    fun cachedSignalDeviceId(userId: String, deviceIdUuid: String): Int? =
        store.getInt(deviceMapKey(userId, deviceIdUuid))

    fun cacheDeviceMapping(userId: String, deviceIdUuid: String, signalDeviceId: Int) {
        com.messenger.app.data.api.HostileWire.device(signalDeviceId)
        store.putInt(deviceMapKey(userId, deviceIdUuid), signalDeviceId)
    }

    private fun deviceMapKey(userId: String, deviceIdUuid: String) = ns("${PREFIX_DEVICE_MAP}${userId}_$deviceIdUuid")

    private companion object {
        const val KEY_IDENTITY_KEY_PAIR = "identity_key_pair"
        const val KEY_REGISTRATION_ID = "registration_id"
        const val KEY_NEXT_OTP_ID = "next_otp_id"
        const val PREFIX_OTP = "otp_"
        const val PREFIX_SIGNED_PREKEY = "signed_prekey_"
        const val PREFIX_KYBER_PREKEY = "kyber_prekey_"
        const val PREFIX_KYBER_USED = "kyber_used_"
        const val PREFIX_SESSION = "session_"
        const val PREFIX_DEVICE_MAP = "device_map_"
        const val ACTIVE_SIGNED_PREKEY_ID = 1
        const val ACTIVE_KYBER_PREKEY_ID = 1
    }
}
