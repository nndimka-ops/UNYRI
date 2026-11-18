package com.messenger.app.data.secure

import com.messenger.app.data.signal.*
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKey

/** Installation-level trust, in the same protected store as batch-2 decisions.
 * Ratchet/session wipes must never erase this store. All access is synchronous. */
class DurableIdentityPins(
    private val storage: SecureKeyValueStore,
    private val owner: () -> String?,
    private val log: (String) -> Unit = { android.util.Log.w("IdentityPins", it) },
    private val limit: Int = MAX_RECORDS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var inspectedOwner: String? = null
    @Serializable data class Pin(
        val version: Int,
        val peerUserId: String,
        val signalDeviceId: Int,
        val identityFingerprint: String?,
        val identityKeyB64: String?,
        val verified: Boolean,
        val firstSeen: Long,
        val pendingIdentityKeyB64: String?,
        val corrupt: Boolean,
    ) {
        fun verification(): PeerVerificationRecord {
            val key = pendingIdentityKeyB64 ?: identityKeyB64
            return PeerVerificationRecord(peerUserId, signalDeviceId,
                if (corrupt || pendingIdentityKeyB64 != null) IDENTITY_CHANGED else if (verified) IDENTITY_VERIFIED else IDENTITY_UNVERIFIED,
                identityKeyB64 = key)
        }
    }
    private fun prefix(account: String = checkNotNull(owner()) { "No identity pin account" }) =
        "identity-pins-v1:" + LocalSecurityDecisions.fingerprint(account.toByteArray()) + ":"
    private fun suffix(user: String, device: Int): String {
        require(user.isNotEmpty() && user.toByteArray().size <= 256 && device in 1..16383)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(canonicalId(user).toByteArray()) + ":$device"
    }
    private fun read(key: String, user: String, device: Int): Pin? {
        if (!storage.contains(key)) return null
        return try {
            val bytes = checkNotNull(storage.getBytes(key))
            require(bytes.size <= MAX_RECORD_BYTES)
            Json.decodeFromString(Pin.serializer(), bytes.toString(Charsets.UTF_8)).also { p ->
                // Local records have one canonical encoding: reject duplicate fields,
                // invalid UTF-8, omitted flags, alternative numbers and extra payload.
                require(bytes.contentEquals(Json.encodeToString(Pin.serializer(), p).toByteArray()))
                require(p.version == 1 && p.peerUserId == canonicalId(user) && p.signalDeviceId == device && p.firstSeen >= 0)
                if (!p.corrupt) {
                    val identity = Base64.getDecoder().decode(checkNotNull(p.identityKeyB64))
                    require(identity.size == 33 && IdentityKey(identity).serialize().contentEquals(identity))
                    require(p.identityKeyB64 == Base64.getEncoder().encodeToString(identity))
                    require(p.identityFingerprint == LocalSecurityDecisions.fingerprint(identity))
                } else require(p.verified && p.identityFingerprint == null && p.identityKeyB64 == null)
                p.pendingIdentityKeyB64?.let {
                    val pending = Base64.getDecoder().decode(it)
                    require(pending.size == 33 && it == Base64.getEncoder().encodeToString(pending)); IdentityKey(pending)
                }
            }
        } catch (_: Exception) {
            // Drop corrupt payload, retain the address as a fail-closed tombstone.
            // Even an unreadable verified flag can never become first contact.
            log("Dropped corrupt identity pin; explicit identity acceptance required")
            tombstone(user, device).also { write(key, it) }
        }
    }
    private fun write(key: String, pin: Pin) {
        val bytes = Json.encodeToString(Pin.serializer(), pin).toByteArray()
        require(bytes.size <= MAX_RECORD_BYTES)
        storage.putNamespaceBinding(key, bytes)
    }
    private fun inspectAccount() {
        val current = prefix()
        if (inspectedOwner != current) { all(); inspectedOwner = current }
    }
    fun get(user: String, device: Int): Pin? = storage.withAccess {
        inspectAccount()
        read(prefix() + suffix(user, device), user, device)
    }
    fun all(): List<Pin> = storage.withAccess {
        val prefix = prefix()
        storage.keysWithPrefix(prefix).mapNotNull { key ->
            if (key == prefix + "quarantine") return@mapNotNull null
            val address = key.removePrefix(prefix)
            val user = runCatching { Base64.getUrlDecoder().decode(address.substringBeforeLast(':')).toString(Charsets.UTF_8) }.getOrNull()
            val device = address.substringAfterLast(':').toIntOrNull()
            if (user == null || device == null || runCatching { suffix(user, device) == address }.getOrDefault(false).not()) {
                log("Dropped corrupt identity pin address; account trust requires explicit erase")
                storage.commitReceive(mapOf(key to null, (prefix + "quarantine") to byteArrayOf(1)), emptyMap())
                return@mapNotNull null
            }
            read(key, user, device)
        }
    }
    fun hasPeer(user: String): Boolean = storage.withAccess {
        inspectAccount()
        storage.contains(prefix() + "quarantine") || storage.keysWithPrefix(prefix() + suffix(user, 1).substringBeforeLast(':') + ":").isNotEmpty()
    }
    fun trusts(user: String, device: Int, bytes: ByteArray): Boolean = storage.withAccess {
        val pin = get(user, device)
        !storage.contains(prefix() + "quarantine") && (pin?.let {
            !it.corrupt && it.pendingIdentityKeyB64 == null && it.identityFingerprint == LocalSecurityDecisions.fingerprint(bytes)
        } ?: true)
    }

    /** Fill-only TOFU. No caller, including a native callback, can replace a pin. */
    fun pin(user: String, device: Int, bytes: ByteArray) = storage.withAccess {
        check(!storage.contains(prefix() + "quarantine")) { "Corrupt account identity pins" }
        get(user, device)?.let {
            check(trusts(user, device, bytes)) { "Identity pin mismatch" }
            return@withAccess
        }
        val key = prefix() + suffix(user, device)
        val value = Pin(version = 1, peerUserId = canonicalId(user), signalDeviceId = device, identityFingerprint = LocalSecurityDecisions.fingerprint(bytes),
            identityKeyB64 = Base64.getEncoder().encodeToString(bytes), verified = false, firstSeen = now(), pendingIdentityKeyB64 = null, corrupt = false)
        require(bytes.size == 33); IdentityKey(bytes)
        insert(key, value)
    }
    private fun insert(key: String, value: Pin) {
        val records = all()
        check(!storage.contains(prefix() + "quarantine")) { "Corrupt account identity pins" }
        val victim = if (records.size >= limit) records.filter { !it.verified && it.pendingIdentityKeyB64 == null && !it.corrupt }.minByOrNull { it.firstSeen } else null
        check(records.size < limit || victim != null) { "Identity pin capacity reached; verified pins retained" }
        // Atomic eviction + insertion; no crash window can drop a pin without its replacement.
        val changes = linkedMapOf<String, ByteArray?>()
        victim?.let { changes[prefix() + suffix(it.peerUserId, it.signalDeviceId)] = null }
        val bytes = Json.encodeToString(Pin.serializer(), value).toByteArray()
        require(bytes.size <= MAX_RECORD_BYTES)
        changes[key] = bytes
        storage.commitReceive(changes, emptyMap())
    }
    fun verification(record: PeerVerificationRecord) = storage.withAccess {
        require(record.status in setOf(IDENTITY_VERIFIED, IDENTITY_CHANGED, IDENTITY_UNVERIFIED))
        val old = get(record.userId, record.signalDeviceId)
        val bytes = Base64.getDecoder().decode(checkNotNull(record.identityKeyB64))
        require(bytes.size == 33); IdentityKey(bytes)
        if (old == null && record.status != IDENTITY_CHANGED) pin(record.userId, record.signalDeviceId, bytes)
        val pinned = get(record.userId, record.signalDeviceId)
        if (record.status == IDENTITY_CHANGED) {
            if (pinned == null) {
                pin(record.userId, record.signalDeviceId, bytes)
            }
            val current = checkNotNull(get(record.userId, record.signalDeviceId))
            write(prefix() + suffix(record.userId, record.signalDeviceId), current.copy(pendingIdentityKeyB64 = record.identityKeyB64))
        } else if (record.status == IDENTITY_VERIFIED) {
            check(trusts(record.userId, record.signalDeviceId, bytes)) { "Cannot verify changed identity" }
            write(prefix() + suffix(record.userId, record.signalDeviceId), checkNotNull(pinned).copy(verified = true))
        }
    }
    /** Only the user's explicit accept action may replace fingerprint/verified state. */
    fun accept(user: String, device: Int, identity: ByteArray) = storage.withAccess {
        val old = get(user, device)
        if (old == null) pin(user, device, identity)
        else {
            require(identity.size == 33); IdentityKey(identity)
            write(prefix() + suffix(user, device), old.copy(identityFingerprint = LocalSecurityDecisions.fingerprint(identity),
                identityKeyB64 = Base64.getEncoder().encodeToString(identity), verified = false, pendingIdentityKeyB64 = null, corrupt = false))
        }
    }
    fun quarantine(user: String, device: Int) = storage.withAccess {
        log("Dropped corrupt legacy identity pin; explicit identity acceptance required")
        val key = prefix() + suffix(user, device)
        if (storage.contains(key)) write(key, tombstone(user, device))
        else if (runCatching { insert(key, tombstone(user, device)) }.isFailure) quarantineAccount()
    }
    fun quarantineAccount() = storage.withAccess {
        if (storage.contains(prefix() + "quarantine")) return@withAccess
        log("Identity pin migration exceeds protected capacity; account trust requires explicit erase")
        storage.putNamespaceBinding(prefix() + "quarantine", byteArrayOf(1))
    }
    private fun tombstone(user: String, device: Int) = Pin(1, canonicalId(user), device, null, null, true, 0, null, true)
    private fun canonicalId(user: String): String = runCatching { java.util.UUID.fromString(user).toString() }.getOrDefault(user)
    /** Successful server deletion or an explicit local erase, never ordinary sign-out. */
    fun eraseAccount(account: String = checkNotNull(owner())) = storage.withAccess {
        storage.commitReceive(storage.keysWithPrefix(prefix(account)).associateWith { null }, emptyMap())
        inspectedOwner = null
    }
    companion object { const val MAX_RECORDS = 4096; const val MAX_RECORD_BYTES = 2048 }
}
