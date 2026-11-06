package com.messenger.app.data.signal

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * P1-9: the durable, account-scoped projection of a peer's verification state
 * (VERIFIED / CHANGED). It lives in the Signal namespace (the same
 * Keystore-backed [SecureKeyValueStore] that already holds identity keys and
 * sessions), so it survives a normal logout while Room — the intentionally
 * cleared UI projection — is wiped.
 *
 * [identityKeyB64] is the identity key the verification was made against: a
 * hydrate restores VERIFIED only when the currently-pinned trust key still
 * matches; a mismatch is downgraded to CHANGED (never a blind VERIFIED).
 * UNVERIFIED peers are deliberately not persisted (trust-on-first-use re-pins
 * them on the next contact).
 */
@Serializable
data class PeerVerificationRecord(
    val userId: String,
    val signalDeviceId: Int,
    val status: String,
    val safetyNumber: String? = null,
    val identityKeyB64: String? = null,
    val verifiedAt: String? = null,
) {
    fun encode(): ByteArray = json.encodeToString(this).toByteArray(Charsets.UTF_8)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(bytes: ByteArray): PeerVerificationRecord? = runCatching {
            json.decodeFromString<PeerVerificationRecord>(bytes.toString(Charsets.UTF_8))
        }.getOrNull()
    }
}
