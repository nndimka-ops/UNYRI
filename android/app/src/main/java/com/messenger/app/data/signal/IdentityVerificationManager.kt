package com.messenger.app.data.signal

import com.messenger.app.data.local.signal.PeerIdentityDao
import com.messenger.app.data.local.signal.PeerIdentityEntity
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator

const val IDENTITY_UNVERIFIED = "UNVERIFIED"
const val IDENTITY_VERIFIED = "VERIFIED"
const val IDENTITY_CHANGED = "CHANGED"

/** Thrown when a peer device's Signal identity key changed since it was
 *  pinned (reinstall / device compromise). Sending is blocked until the user
 *  accepts the new key. Carries enough info for the UI to surface a prompt. */
class IdentityChangedException(
    val userId: String,
    val signalDeviceIds: List<Int>,
    message: String = "Identity key changed for $userId",
) : Exception(message)

/**
 * Local trust state for remote Signal devices (Этап L.24-L.41): the
 * UNVERIFIED / VERIFIED / CHANGED machine + the standard libsignal numeric
 * safety number. This is the ONLY layer that computes fingerprints; it never
 * does any custom crypto - the safety number is libsignal's
 * `NumericFingerprintGenerator` (5200 iterations, version 2), exactly as
 * Signal computes it.
 */
class IdentityVerificationManager(
    private val keyStore: SignalKeyStore,
    private val dao: PeerIdentityDao,
    private val myUserIdProvider: () -> String?,
    private val scope: CoroutineScope,
    private val currentEpoch: () -> Int = { 0 },
    private val withSessionWrite: suspend (Int, suspend () -> Unit) -> Unit = { _, block -> block() },
) {
    /** P1-4/B.1: invoked with (remoteUserId, signalDeviceId) after the user
     *  accepts a changed peer identity, so pending decrypt recovery for that
     *  peer can run immediately (without waiting for a new message batch).
     *  Wired by MessengerApp; keeps this manager free of messaging/UI deps. */
    var onTrustAccepted: ((String, Int) -> Unit)? = null
    // Keep asynchronous crypto observations bound to their starting account.
    // A later warning must not sample the replacement account's epoch.
    private val operationEpoch = ThreadLocal<Int?>()
    internal suspend fun <T> withOperation(expectedEpoch: Int? = null, block: suspend () -> T): T = kotlinx.coroutines.withContext(
        operationEpoch.asContextElement(operationEpoch.get() ?: expectedEpoch ?: currentEpoch())
    ) {
        if (writeEpoch() != currentEpoch()) throw kotlinx.coroutines.CancellationException("Identity session changed")
        block()
    }
    private fun writeEpoch(): Int = operationEpoch.get() ?: currentEpoch()

    fun status(userId: String, signalDeviceId: Int): Flow<String?> = dao.observeStatus(userId, signalDeviceId)

    fun observeForUser(userId: String): Flow<List<PeerIdentityEntity>> = dao.observeForUser(userId)

    suspend fun refreshKnownDevices(userId: String) = withOperation {
        keyStore.withAccount {
            keyStore.getSubDeviceSessions(userId).forEach { recordFirstContact(userId, it) }
        }
    }

    /** Trust-on-first-use: after a fresh session was established, pin the
     *  identity as UNVERIFIED. Never overwrites a VERIFIED/CHANGED row. */
    suspend fun recordFirstContact(userId: String, signalDeviceId: Int) = withSessionWrite(writeEpoch()) {
        if (dao.get(userId, signalDeviceId) != null) return@withSessionWrite
        val identity = keyStore.getIdentity(address(userId, signalDeviceId)) ?: return@withSessionWrite
        val safety = computeSafetyNumber(userId, identity)
        dao.upsert(PeerIdentityEntity(userId, signalDeviceId, IDENTITY_UNVERIFIED, safety, b64(identity.serialize()), null))
    }

    /** A key change was detected (outbound bundle or inbound prekey message
     *  signed by a new identity). Block sends + surface the new safety
     *  number for the user's out-of-band decision. */
    suspend fun markChanged(userId: String, signalDeviceId: Int, newIdentityKey: IdentityKey) = withSessionWrite(writeEpoch()) {
        val safety = computeSafetyNumber(userId, newIdentityKey)
        dao.upsert(PeerIdentityEntity(userId, signalDeviceId, IDENTITY_CHANGED, safety, b64(newIdentityKey.serialize()), null))
        // Durable inside this signed-in session, including process restart.
        // Explicit logout wipes this trust record with all account keys.
        keyStore.savePeerVerification(
            PeerVerificationRecord(userId, signalDeviceId, IDENTITY_CHANGED, safety, b64(newIdentityKey.serialize()), null),
        )
    }

    /** Accept a new key: trust it via the standard libsignal identity store
     *  (which returns REPLACED_EXISTING for a change), drop the old session
     *  (the next send re-establishes with the new key), and reset the local
     *  state to UNVERIFIED. Uses the key stored in Room (recorded when the
     *  change was detected), so the UI never re-fetches anything. */
    suspend fun acceptNewKey(userId: String, signalDeviceId: Int, expectedIdentity: String? = null) = withSessionWrite(currentEpoch()) {
        val current = dao.get(userId, signalDeviceId)
        check(expectedIdentity == null || (current?.identityKeyB64 == expectedIdentity && current.status == IDENTITY_CHANGED)) { "Security key changed again. Reopen this screen." }
        val stored = current?.identityKeyB64?.let { IdentityKey(unB64(it)) }
            ?: keyStore.getIdentity(address(userId, signalDeviceId)) ?: return@withSessionWrite
        keyStore.saveIdentity(address(userId, signalDeviceId), stored)
        keyStore.deleteSession(address(userId, signalDeviceId))
        val safety = computeSafetyNumber(userId, stored)
        dao.upsert(PeerIdentityEntity(userId, signalDeviceId, IDENTITY_UNVERIFIED, safety, b64(stored.serialize()), null))
        // P1-9: UNVERIFIED is the default and not durably persisted.
        keyStore.clearPeerVerification(userId, signalDeviceId)
        // P1-4/B.1: accepting the key makes previously undecryptable
        // envelopes for this peer decryptable - recover them now.
        onTrustAccepted?.invoke(userId, signalDeviceId)
    }

    /** Manual out-of-band verification. Rejected for a CHANGED identity -
     *  the user must accept the new key first (Signal's rule). */
    suspend fun markVerified(userId: String, signalDeviceId: Int) = withSessionWrite(currentEpoch()) {
        val existing = dao.get(userId, signalDeviceId) ?: return@withSessionWrite
        if (existing.status == IDENTITY_CHANGED) {
            throw IllegalStateException("Cannot verify a changed identity - accept the new key first.")
        }
        val updated = existing.copy(status = IDENTITY_VERIFIED, verifiedAt = Instant.now().toString())
        dao.upsert(updated)
        // P1-9: persist durably (account-scoped) so VERIFIED survives logout.
        keyStore.savePeerVerification(
            PeerVerificationRecord(userId, signalDeviceId, IDENTITY_VERIFIED, updated.safetyNumber, updated.identityKeyB64, updated.verifiedAt),
        )
    }

    /** P1-9: restore durable peer verification state (survives a normal
     *  logout) into the Room projection. Called on every login. Never
     *  auto-verifies: a record is restored VERIFIED only when the currently
     *  pinned identity key still matches the key that was verified; a
     *  mismatch is downgraded to CHANGED. Peers with no session yet are
     *  skipped (trust-on-first-use will pin them later). */
    suspend fun hydrateFromDurableStorage() = withSessionWrite(currentEpoch()) {
        keyStore.allPeerVerifications().forEach { record ->
            val current = keyStore.getIdentity(address(record.userId, record.signalDeviceId)) ?: return@forEach
            val currentB64 = b64(current.serialize())
            val isChangedRecord = record.status == IDENTITY_CHANGED
            // VERIFIED survives only while the pinned key still matches the
            // key that was verified; a mismatch downgrades to CHANGED. A
            // CHANGED record stays CHANGED and keeps its recorded (new) key so
            // acceptNewKey still resolves the right key after re-login.
            val keyMismatch = record.identityKeyB64 != null && record.identityKeyB64 != currentB64
            val status = when {
                isChangedRecord -> IDENTITY_CHANGED
                keyMismatch -> IDENTITY_CHANGED
                else -> record.status
            }
            val keyB64 = when {
                isChangedRecord -> record.identityKeyB64 ?: currentB64
                keyMismatch -> currentB64
                else -> record.identityKeyB64 ?: currentB64
            }
            val safety = record.safetyNumber ?: computeSafetyNumber(record.userId, current)
            dao.upsert(
                PeerIdentityEntity(
                    userId = record.userId,
                    signalDeviceId = record.signalDeviceId,
                    status = status,
                    safetyNumber = safety,
                    identityKeyB64 = keyB64,
                    verifiedAt = if (status == IDENTITY_VERIFIED) record.verifiedAt else null,
                ),
            )
        }
    }

    /** P1-9.1: one-time/lazy backfill of PRE-EXISTING Room verification rows
     *  (users who verified before durable storage existed) into the durable
     *  account-scoped Signal namespace. Runs on login, before Room can be
     *  cleared. Fill-only and idempotent: an existing durable record is never
     *  overwritten by the older Room projection, CHANGED is never downgraded
     *  to VERIFIED, and a different/new identity is never auto-verified. */
    suspend fun backfillExistingVerificationState() = withSessionWrite(currentEpoch()) {
        dao.all().forEach { row ->
            if (row.status != IDENTITY_VERIFIED && row.status != IDENTITY_CHANGED) return@forEach
            if (keyStore.loadPeerVerification(row.userId, row.signalDeviceId) != null) return@forEach

            val current = keyStore.getIdentity(address(row.userId, row.signalDeviceId))
            val currentB64 = current?.let { b64(it.serialize()) }
            when (row.status) {
                IDENTITY_VERIFIED -> {
                    if (currentB64 != null && row.identityKeyB64 == currentB64) {
                        // Key still matches -> migrate VERIFIED as-is.
                        keyStore.savePeerVerification(
                            PeerVerificationRecord(row.userId, row.signalDeviceId, IDENTITY_VERIFIED, row.safetyNumber, row.identityKeyB64, row.verifiedAt),
                        )
                    } else if (currentB64 != null) {
                        // Key changed since verification -> migrate as CHANGED
                        // (block sends, prompt re-verify), never VERIFIED.
                        keyStore.savePeerVerification(
                            PeerVerificationRecord(row.userId, row.signalDeviceId, IDENTITY_CHANGED, row.safetyNumber, currentB64, null),
                        )
                    }
                    // else: no pinned key yet -> skip (TOFU re-pins later).
                }
                IDENTITY_CHANGED -> {
                    // CHANGED migrates as CHANGED, carrying the recorded (new)
                    // key so acceptNewKey still works after re-login.
                    keyStore.savePeerVerification(
                        PeerVerificationRecord(row.userId, row.signalDeviceId, IDENTITY_CHANGED, row.safetyNumber, row.identityKeyB64 ?: currentB64, null),
                    )
                }
            }
        }
    }

    /** Send guard (Этап L.33): throws if any device of [userId] is CHANGED.
     *  Called BEFORE building envelopes, so no ciphertext is ever produced
     *  for a peer whose identity changed (no data loss - the plaintext is
     *  still local and the send simply fails until resolved). */
    suspend fun ensureNoChangedForUser(userId: String, recipientDeviceIds: Set<Int>? = null) {
        val changed = dao.changedForUser(userId).filter { recipientDeviceIds == null || it.signalDeviceId in recipientDeviceIds }
        if (changed.isNotEmpty()) {
            throw IdentityChangedException(userId, changed.map { it.signalDeviceId })
        }
    }

    suspend fun safetyNumberFor(userId: String, signalDeviceId: Int): String? {
        dao.get(userId, signalDeviceId)?.safetyNumber?.let { return it }
        val identity = keyStore.getIdentity(address(userId, signalDeviceId)) ?: return null
        return computeSafetyNumber(userId, identity)
    }

    private fun computeSafetyNumber(userId: String, theirIdentity: IdentityKey): String? {
        val myUserId = myUserIdProvider() ?: return null
        val generator = NumericFingerprintGenerator(5200)
        val fingerprint = generator.createFor(
            /* version = */ 2,
            /* localStableIdentifier = */ myUserId.toByteArray(),
            /* localIdentityKey = */ keyStore.getIdentityKeyPair().publicKey,
            /* remoteStableIdentifier = */ userId.toByteArray(),
            /* remoteIdentityKey = */ theirIdentity,
        )
        return fingerprint.displayableFingerprint.displayText
    }

    private fun address(userId: String, signalDeviceId: Int) = SignalProtocolAddress(userId, signalDeviceId)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unB64(value: String): ByteArray = Base64.getDecoder().decode(value)
}
