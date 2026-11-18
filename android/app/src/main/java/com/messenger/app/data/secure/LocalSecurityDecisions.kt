package com.messenger.app.data.secure

import com.messenger.app.data.signal.SecureKeyValueStore
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** User decisions, independent of server projections, sessions and the Signal ratchet.
 * The caller supplies a stable (origin, user) owner, never an epoch or device id. */
class LocalSecurityDecisions(private val storage: SecureKeyValueStore, private val owner: () -> String?) {
    @Serializable data class RevokedDevice(val userId: String, val serverDeviceId: String,
        val signalDeviceId: Int? = null, val identityFingerprint: String? = null)
    @Serializable private data class Decisions(val blocks: Map<String, Boolean> = emptyMap(), val revoked: List<RevokedDevice> = emptyList(), val blocksMigrated: Boolean = false)
    private fun key(): String = "security:" + fingerprint(checkNotNull(owner()) { "No security account" }.toByteArray())
    private fun load(key: String) = storage.getBytes(key)?.let { Json.decodeFromString(Decisions.serializer(), it.toString(Charsets.UTF_8)) } ?: Decisions()
    fun migrateBlocksOnce(cached: () -> Set<String>) = storage.withAccess {
        val key = key(); val old = load(key)
        if (!old.blocksMigrated) storage.putNamespaceBinding(key, Json.encodeToString(Decisions.serializer(),
            old.copy(blocks = cached().associate { canonicalId(it) to true } + old.blocks, blocksMigrated = true)).toByteArray())
    }
    fun blockDecision(user: String): Boolean? = storage.withAccess { load(key()).blocks[canonicalId(user)] }
    fun hasRestrictions(): Boolean = storage.withAccess { load(key()).let { it.blocks.any { (_, blocked) -> blocked } || it.revoked.isNotEmpty() } }
    fun blockedUsers(): Set<String> = storage.withAccess { load(key()).blocks.filterValues { it }.keys }
    fun setBlocked(user: String, blocked: Boolean) = storage.withAccess {
        val key = key(); val old = load(key)
        storage.putNamespaceBinding(key, Json.encodeToString(Decisions.serializer(), old.copy(blocks = old.blocks + (canonicalId(user) to blocked))).toByteArray())
    }
    fun revoke(value: RevokedDevice) = storage.withAccess {
        val record = value.copy(userId = canonicalId(value.userId), serverDeviceId = canonicalId(value.serverDeviceId))
        val key = key(); val old = load(key)
        val previous = old.revoked.find { it.serverDeviceId == record.serverDeviceId }
        val merged = record.copy(userId = previous?.userId ?: record.userId, signalDeviceId = previous?.signalDeviceId ?: record.signalDeviceId,
            identityFingerprint = previous?.identityFingerprint ?: record.identityFingerprint)
        if (previous == merged) return@withAccess
        storage.putNamespaceBinding(key, Json.encodeToString(Decisions.serializer(), old.copy(revoked = old.revoked.filterNot {
            it.serverDeviceId == record.serverDeviceId
        } + merged)).toByteArray())
    }
    fun isRevoked(user: String, device: String? = null, signal: Int? = null, identity: ByteArray? = null): Boolean = storage.withAccess {
        val fingerprint = identity?.let(::fingerprint)
        load(key()).revoked.any {
            device != null && it.serverDeviceId == canonicalId(device) ||
                signal != null && it.userId == canonicalId(user) && it.signalDeviceId == signal ||
                fingerprint != null && it.identityFingerprint == fingerprint
        }
    }
    companion object {
        private fun canonicalId(value: String): String = runCatching { java.util.UUID.fromString(value.trim()).toString() }.getOrDefault(value)
        fun owner(origin: String, user: String) = origin.trimEnd('/') + "|" + canonicalId(user)
        fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
