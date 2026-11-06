package com.messenger.app.data.signal

import android.util.Log
import com.messenger.app.BuildConfig
import com.messenger.app.data.api.DeviceBundleDto
import com.messenger.app.data.api.KeysApi
import com.messenger.app.data.api.KyberPreKeyUploadDto
import com.messenger.app.data.api.OneTimePreKeyUploadDto
import com.messenger.app.data.api.SignedPreKeyUploadDto
import com.messenger.app.data.api.UploadKeysRequestDto
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyBundle

/** A remote device this account knows how to address, resolved from a
 *  bundle fetch: [deviceId] is the server/HTTP identifier (`devices.id`
 *  UUID, used for envelope routing); [signalDeviceId] is the small int
 *  Signal Protocol itself addresses (`SignalProtocolAddress`). */
data class RemoteDevice(val deviceId: String, val signalDeviceId: Int)

/** One outbound Signal Protocol ciphertext, ready to become an
 *  `EnvelopeDto` on the wire. [messageType] is this app's own wire tag
 *  (1 = PreKeySignalMessage/session-establishing, 2 = SignalMessage/
 *  established-session) - deliberately decoupled from libsignal's own
 *  internal type constants, which the server has no reason to know about. */
data class EncryptedEnvelope(val recipientDeviceId: String, val messageType: Int, val ciphertext: String)

internal const val MESSAGE_TYPE_PREKEY = 1
private const val MESSAGE_TYPE_WHISPER = 2
private const val ONE_TIME_PREKEY_LOW_WATERMARK = 5L
private const val ONE_TIME_PREKEY_BATCH_SIZE = 20
private const val TAG = "SignalSessionManager"

/**
 * Orchestrates Signal Protocol session lifecycle and encrypt/decrypt on top
 * of [SignalKeyStore] + the `/keys` backend endpoints. This is the only
 * layer that touches libsignal directly - callers (MessageRepository) never
 * import `org.signal.libsignal.*` themselves.
 *
 *     UI -> ViewModel -> MessageRepository -> SignalSessionManager -> libsignal -> SignalKeyStore
 */
class SignalSessionManager(
    private val keyStore: SignalKeyStore,
    private val keysApi: KeysApi,
    val identityVerification: IdentityVerificationManager,
) : MessageDecryptor {
    /** Pin crypto and trust observations to the outbox's original session. */
    suspend fun <T> withSendingAccount(epoch: Int, block: suspend () -> T): T =
        keyStore.withAccount(epoch) { identityVerification.withOperation(epoch) { block() } }

    private val mutex = Mutex()
    private suspend fun <T> withAccountLock(block: suspend () -> T): T = identityVerification.withOperation {
        keyStore.withAccount { mutex.withLock { block() } }
    }

    /** Idempotent - call after every successful login/register. Generates
     *  identity/signed-prekey/kyber-prekey only the first time; tops up the
     *  one-time prekey pool whenever the server reports it's running low. */
    suspend fun bootstrapKeysIfNeeded(accessToken: String) = withAccountLock {
        keyStore.ensureIdentity()

        val needsSignedPreKey = !keyStore.hasSignedPreKey()
        val needsKyberPreKey = !keyStore.hasKyberPreKey()
        val remainingOnServer = runCatching { keysApi.oneTimePrekeyCount(accessToken).count }.getOrDefault(0L)
        // The LOCAL pool is the authoritative decryptability check: after a
        // reinstall (identity wipe) the server may still report a healthy pool
        // of one-time prekeys that this device no longer holds locally, so a
        // peer could be served a prekey we can no longer decrypt with. Top up
        // whenever EITHER side is running low.
        val localOneTime = keyStore.oneTimePreKeyCountLocal()
        val needsMoreOneTime = remainingOnServer < ONE_TIME_PREKEY_LOW_WATERMARK || localOneTime < ONE_TIME_PREKEY_LOW_WATERMARK

        // A healthy prekey count does not prove that the server holds this
        // installation's current identity/signed keys. Republish the SAME
        // records on session start, including after suspension/relogin.
        // Generate one-time keys only when the pool actually needs them.

        val signedPreKey = if (needsSignedPreKey) keyStore.generateSignedPreKey() else keyStore.loadSignedPreKey(1)
        val kyberPreKey = if (needsKyberPreKey) keyStore.generateKyberPreKey() else keyStore.loadKyberPreKey(1)
        val oneTimePreKeys = if (needsMoreOneTime) keyStore.generateOneTimePreKeys(ONE_TIME_PREKEY_BATCH_SIZE) else emptyList()

        keysApi.uploadKeys(
            accessToken,
            UploadKeysRequestDto(
                identityKey = b64(keyStore.identityKeyPair.publicKey.serialize()),
                registrationId = keyStore.localRegistrationId,
                signedPrekey = SignedPreKeyUploadDto(
                    keyId = signedPreKey.id,
                    publicKey = b64(signedPreKey.keyPair.publicKey.serialize()),
                    signature = b64(signedPreKey.signature),
                ),
                kyberPrekey = KyberPreKeyUploadDto(
                    keyId = kyberPreKey.id,
                    publicKey = b64(kyberPreKey.keyPair.publicKey.serialize()),
                    signature = b64(kyberPreKey.signature),
                ),
                oneTimePrekeys = oneTimePreKeys.map { OneTimePreKeyUploadDto(it.id, b64(it.keyPair.publicKey.serialize())) },
            ),
        )
    }

    /** Fetches (and caches the UUID<->signalDeviceId mapping for) every
     *  active device of [userId], establishing a Signal session for any
     *  device that doesn't already have one. Safe to call repeatedly - an
     *  existing session is never re-established (which would reset the
     *  ratchet and burn a one-time prekey for nothing). */
    suspend fun ensureSessionsForUser(userId: String, accessToken: String): List<RemoteDevice> = withAccountLock {
        val bundles = sendingBundles(userId, accessToken)
        bundles.forEach { bundle ->
            val address = SignalProtocolAddress(userId, bundle.signalDeviceId)
            if (!keyStore.containsSession(address)) {
                establishSession(userId, bundle)
            }
        }
        bundles.map { RemoteDevice(it.deviceId, it.signalDeviceId) }
    }

    /** Establishes a fresh libsignal session from a bundle, pinning the
     *  identity TOFU (UNVERIFIED) on success and blocking sends (CHANGED) if
     *  the bundled identity key differs from the already-pinned one. */
    private suspend fun establishSession(userId: String, bundle: DeviceBundleDto) {
        val address = SignalProtocolAddress(userId, bundle.signalDeviceId)
        try {
            SessionBuilder(keyStore, keyStore, keyStore, keyStore, address).process(toPreKeyBundle(bundle))
            identityVerification.recordFirstContact(userId, bundle.signalDeviceId)
        } catch (e: UntrustedIdentityException) {
            // Never touch e.untrustedIdentity (NULL in libsignal 0.86.5; reading
            // the Kotlin property itself throws NPE). The bundle is the
            // authoritative source of the new key.
            val changedKey = IdentityKey(unB64(bundle.identityKey))
            identityVerification.markChanged(userId, bundle.signalDeviceId, changedKey)
            throw IdentityChangedException(userId, listOf(bundle.signalDeviceId))
        }
    }

    /** Deterministic identity-observation layer (M.2 / Этап L.33): for each
     *  server-advertised bundle, compare the bundled identity key against the
     *  locally pinned identity for the same [SignalProtocolAddress]. A mismatch
     *  (same address, different key) marks the peer CHANGED and blocks the
     *  logical send BEFORE any session is established or ciphertext produced.
     *  A never-before-seen address is trust-on-first-use and passes. */
    internal suspend fun verifyBundleIdentitiesOrThrow(userId: String, bundles: List<DeviceBundleDto>) {
        val changed = mutableListOf<Int>()
        bundles.forEach { bundle ->
            val address = SignalProtocolAddress(userId, bundle.signalDeviceId)
            val bundledIdentity = IdentityKey(unB64(bundle.identityKey))
            val trusted = keyStore.isTrustedIdentity(address, bundledIdentity, IdentityKeyStore.Direction.SENDING)
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "identity check user=$userId device=${bundle.signalDeviceId} " +
                        "known=${identityFingerprint(keyStore.getIdentity(address))} " +
                        "incoming=${identityFingerprint(bundledIdentity)} trusted=$trusted",
                )
            }
            if (!trusted) {
                identityVerification.markChanged(userId, bundle.signalDeviceId, bundledIdentity)
                changed += bundle.signalDeviceId
            }
        }
        if (changed.isNotEmpty()) throw IdentityChangedException(userId, changed)
    }

    /** Short public-key fingerprint for DEBUG logs only - never logs private
     *  keys, ciphertext, or full identity material. */
    private fun identityFingerprint(identity: IdentityKey?): String {
        val bytes = identity?.publicKey?.serialize() ?: return "null"
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Encrypts [plaintext] for every active device of [userId] (optionally
     *  excluding [excludeDeviceId] - the sending device itself, when
     *  building the sender's own multi-device echo envelopes). Filters out
     *  [excludeDeviceId] BEFORE establishing any session, so a single-device
     *  account never wastes a bundle fetch/one-time-prekey/session on a
     *  pointless session with itself. */
    suspend fun buildEnvelopesForUser(
        userId: String,
        accessToken: String,
        plaintext: ByteArray,
        excludeDeviceId: String? = null,
    ): List<EncryptedEnvelope> = withAccountLock {
        // Этап L.33: block BEFORE any ciphertext is produced if a peer's
        // identity changed - no data loss (plaintext stays local), and the
        // failure is surfaced to the sender as a resolvable prompt.
        val bundles = sendingBundles(userId, accessToken, excludeDeviceId)
        identityVerification.ensureNoChangedForUser(userId, bundles.map { it.signalDeviceId }.toSet())
        bundles.map { bundle ->
            val address = SignalProtocolAddress(userId, bundle.signalDeviceId)
            if (!keyStore.containsSession(address)) {
                establishSession(userId, bundle)
            }
            val cipher = SessionCipher(keyStore, keyStore, keyStore, keyStore, keyStore, address)
            val ciphertextMessage = cipher.encrypt(plaintext)
            val wireType = if (ciphertextMessage is PreKeySignalMessage) MESSAGE_TYPE_PREKEY else MESSAGE_TYPE_WHISPER
            EncryptedEnvelope(bundle.deviceId, wireType, b64(ciphertextMessage.serialize()))
        }
    }

    /** Dedicated mailbox path: filter BEFORE trust, session establishment, or prekey consumption.
     * Normal chat fan-out and own-device echoes are deliberately not used here. */
    suspend fun buildEnvelopeForDevice(userId: String, deviceId: String, accessToken: String, plaintext: ByteArray): EncryptedEnvelope = withAccountLock {
        require(java.util.UUID.fromString(userId).toString() == userId && java.util.UUID.fromString(deviceId).toString() == deviceId)
        suspend fun checked(values: List<DeviceBundleDto>): DeviceBundleDto {
            val target = values.singleOrNull { it.deviceId == deviceId } ?: error("Recipient device unavailable")
            require(target.signalDeviceId > 0)
            verifyBundleIdentitiesOrThrow(userId, listOf(target))
            identityVerification.ensureNoChangedForUser(userId, setOf(target.signalDeviceId))
            keyStore.cacheDeviceMapping(userId, target.deviceId, target.signalDeviceId)
            return target
        }
        var target = checked(keysApi.getDeviceBundles(accessToken, userId).devices)
        if (!keyStore.containsSession(SignalProtocolAddress(userId, target.signalDeviceId))) {
            val exact = keysApi.getTargetBundle(accessToken, userId, deviceId).devices
            require(exact.size == 1 && exact[0].deviceId == deviceId)
            // One operation may not silently switch a first-contact identity between discovery and OTP claim.
            require(exact[0].signalDeviceId == target.signalDeviceId && MessageDigest.isEqual(unB64(exact[0].identityKey), unB64(target.identityKey)))
            target = checked(exact)
            if (!keyStore.containsSession(SignalProtocolAddress(userId, target.signalDeviceId))) establishSession(userId, target)
        }
        val cipher = SessionCipher(keyStore, keyStore, keyStore, keyStore, keyStore, SignalProtocolAddress(userId, target.signalDeviceId))
        val encrypted = cipher.encrypt(plaintext)
        EncryptedEnvelope(deviceId, if (encrypted is PreKeySignalMessage) MESSAGE_TYPE_PREKEY else MESSAGE_TYPE_WHISPER, b64(encrypted.serialize()))
    }

    /** Mailbox sender discovery stays nonconsuming and observes only the authenticated sender device. */
    suspend fun decryptFromDevice(userId: String, deviceId: String, accessToken: String, messageType: Int, ciphertext: String): ByteArray = withAccountLock {
        val target = keysApi.getDeviceBundles(accessToken, userId).devices.singleOrNull { it.deviceId == deviceId } ?: error("Sender device unavailable")
        verifyBundleIdentitiesOrThrow(userId, listOf(target))
        identityVerification.ensureNoChangedForUser(userId, setOf(target.signalDeviceId))
        keyStore.cacheDeviceMapping(userId, target.deviceId, target.signalDeviceId)
        decryptForAccount(userId, target.signalDeviceId, messageType, ciphertext)
    }

    /** Resolves the small int Signal device id for a (userId, deviceId)
     *  pair, fetching+caching a bundle if this device has never been seen
     *  before - purely to learn the mapping, this never establishes a
     *  session (decryption of a PreKeySignalMessage establishes its own
     *  session using our local store; it needs no data from the sender's
     *  bundle). */
    override suspend fun resolveSignalDeviceId(userId: String, deviceId: String, accessToken: String): Int = keyStore.withAccount {
        keyStore.cachedSignalDeviceId(userId, deviceId)?.let { return@withAccount it }
        discoverAndCacheDevices(userId, accessToken)
        return@withAccount keyStore.cachedSignalDeviceId(userId, deviceId)
            ?: error("Unknown device $deviceId for user $userId")
    }

    /** Throws (never silently drops) on any decrypt failure - a tampered or
     *  undecryptable envelope must be visible as a failure, not corrupted
     *  or fabricated plaintext. An inbound identity change (a PreKey message
     *  signed by a new identity key) marks the peer CHANGED and rethrows, so
     *  the message is never decrypted under an unaccepted key. */
    // Native store callbacks may outlive token clearing. Pin their namespace
    // without adding a mutex-to-Room lock-order dependency.
    override suspend fun decrypt(userId: String, signalDeviceId: Int, messageType: Int, ciphertextBase64: String): ByteArray = identityVerification.withOperation {
        keyStore.withAccount { decryptForAccount(userId, signalDeviceId, messageType, ciphertextBase64) }
    }

    private suspend fun decryptForAccount(userId: String, signalDeviceId: Int, messageType: Int, ciphertextBase64: String): ByteArray {
        val address = SignalProtocolAddress(userId, signalDeviceId)
        val cipher = SessionCipher(keyStore, keyStore, keyStore, keyStore, keyStore, address)
        val bytes = Base64.getDecoder().decode(ciphertextBase64)
        return try {
            when (messageType) {
                MESSAGE_TYPE_PREKEY -> cipher.decrypt(PreKeySignalMessage(bytes))
                MESSAGE_TYPE_WHISPER -> cipher.decrypt(SignalMessage(bytes))
                else -> throw IllegalArgumentException("Unknown message_type $messageType")
            }.also {
                try { identityVerification.recordFirstContact(userId, signalDeviceId) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { android.util.Log.w("Signal", "Could not update local verification list") }
            }
        } catch (e: UntrustedIdentityException) {
            // libsignal 0.86.5 raises UntrustedIdentityException(name) with a
            // NULL identity key. Kotlin sees the Java getter getUntrustedIdentity()
            // as @NonNull, so reading `e.untrustedIdentity` itself throws
            // NPE ("getUntrustedIdentity(...) must not be null") BEFORE the ?:
            // can run - that was the original crash. Never touch that property.
            // The authoritative source for a session-establishing PreKey message
            // is the message itself (it carries the sender's new identity key).
            // For a whisper message (identity change can't be signalled there),
            // read the raw field reflectively as a best-effort fallback.
            val changedKey: IdentityKey? = if (messageType == MESSAGE_TYPE_PREKEY) {
                PreKeySignalMessage(bytes).identityKey
            } else {
                runCatching {
                    e.javaClass.getMethod("getUntrustedIdentity").invoke(e) as? IdentityKey
                }.getOrNull()
            }
            if (changedKey != null) {
                identityVerification.markChanged(userId, signalDeviceId, changedKey)
            }
            throw IdentityChangedException(userId, listOf(signalDeviceId))
        }
    }

    private suspend fun discoverAndCacheDevices(userId: String, accessToken: String): List<DeviceBundleDto> = keyStore.withAccount {
        val bundles = try {
            keysApi.getDeviceBundles(accessToken, userId).devices
        } catch (error: com.messenger.app.data.api.AuthApiException.Api) {
            // Older servers have no discovery route. Authorization, rate-limit
            // and server errors must never fall back to a consuming request.
            if (error.httpStatus != 404) throw error
            keysApi.getBundles(accessToken, userId).devices
        }
        bundles.forEach { keyStore.cacheDeviceMapping(userId, it.deviceId, it.signalDeviceId) }
        return@withAccount bundles
    }

    /** No successful bundle cache: every send sees current revocations and
     * identity changes. Claim an OTP only when a session actually is missing.
     * After the second await, use and verify the entire newer response rather
     * than combining it with the earlier discovery list. */
    private suspend fun sendingBundles(userId: String, token: String, excludeDeviceId: String? = null): List<DeviceBundleDto> {
        suspend fun checked(bundles: List<DeviceBundleDto>): List<DeviceBundleDto> {
            val recipients = bundles.filter { it.deviceId != excludeDeviceId }
            verifyBundleIdentitiesOrThrow(userId, recipients)
            identityVerification.ensureNoChangedForUser(userId, recipients.map { it.signalDeviceId }.toSet())
            return recipients
        }
        val discovered = checked(discoverAndCacheDevices(userId, token))
        if (discovered.none { !keyStore.containsSession(SignalProtocolAddress(userId, it.signalDeviceId)) }) return discovered
        val establishment = keysApi.getBundles(token, userId).devices
        establishment.forEach { keyStore.cacheDeviceMapping(userId, it.deviceId, it.signalDeviceId) }
        return checked(establishment)
    }

    private fun toPreKeyBundle(bundle: DeviceBundleDto): PreKeyBundle = PreKeyBundle(
        bundle.registrationId,
        bundle.signalDeviceId,
        bundle.oneTimePrekeyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
        bundle.oneTimePrekeyPublic?.let { ECPublicKey(unB64(it)) },
        bundle.signedPrekeyId,
        ECPublicKey(unB64(bundle.signedPrekeyPublic)),
        unB64(bundle.signedPrekeySignature),
        IdentityKey(unB64(bundle.identityKey)),
        bundle.kyberPrekeyId,
        KEMPublicKey(unB64(bundle.kyberPrekeyPublic)),
        unB64(bundle.kyberPrekeySignature),
    )

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unB64(value: String): ByteArray = Base64.getDecoder().decode(value)
}
