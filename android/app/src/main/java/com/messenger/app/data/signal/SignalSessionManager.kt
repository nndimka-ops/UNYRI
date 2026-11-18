package com.messenger.app.data.signal

import android.util.Log
import com.messenger.app.BuildConfig
import com.messenger.app.data.api.DeviceBundleDto
import com.messenger.app.data.api.validateWireIntegers
import com.messenger.app.data.api.KeysApi
import com.messenger.app.data.api.KyberPreKeyUploadDto
import com.messenger.app.data.api.OneTimePreKeyUploadDto
import com.messenger.app.data.api.SignedPreKeyUploadDto
import com.messenger.app.data.api.UploadKeysRequestDto
import java.security.MessageDigest
import java.util.Base64
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
data class DeviceFanout(val envelopes: List<EncryptedEnvelope>, val withheldDeviceIds: List<String>)
data class DeviceTrustSnapshot(val userId: String, val deviceId: String, val signalDeviceId: Int, val identity: String, val trusted: Boolean, val safetyNumber: String?)

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
    private val localSecurity: com.messenger.app.data.secure.LocalSecurityDecisions? = null,
) : MessageDecryptor {
    fun isLocallyRevoked(user: String, device: String): Boolean = localSecurity?.isRevoked(user, device) == true
    suspend fun revokeOwnDevice(user: String, device: String, token: String) = withAccountLock {
        check(identityVerification.isOwnUser(user))
        val signal = keyStore.cachedSignalDeviceId(user, device)
        val identity = signal?.let { keyStore.getIdentity(SignalProtocolAddress(user, it))?.serialize() }
        // Persist the routing tombstone BEFORE discovery or the revoke HTTP call.
        localSecurity?.revoke(com.messenger.app.data.secure.LocalSecurityDecisions.RevokedDevice(user, device, signal,
            identity?.let(com.messenger.app.data.secure.LocalSecurityDecisions::fingerprint)))
        val bundle = if (signal == null || identity == null) {
            try { keysApi.getDeviceBundles(token, user).devices.singleOrNull { it.deviceId == device } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null } // A withheld discovery response cannot cancel local removal.
        } else null
        bundle?.let {
            it.validateWireIntegers()
            localSecurity?.revoke(com.messenger.app.data.secure.LocalSecurityDecisions.RevokedDevice(user, device, it.signalDeviceId,
                com.messenger.app.data.secure.LocalSecurityDecisions.fingerprint(unB64(it.identityKey))))
        }
    }
    private fun revoked(user: String, bundle: DeviceBundleDto): Boolean {
        val security = localSecurity ?: return false
        val identity = unB64(bundle.identityKey)
        if (security.isRevoked(user, bundle.deviceId)) security.revoke(
            com.messenger.app.data.secure.LocalSecurityDecisions.RevokedDevice(user, bundle.deviceId, bundle.signalDeviceId,
                com.messenger.app.data.secure.LocalSecurityDecisions.fingerprint(identity)))
        return security.isRevoked(user, bundle.deviceId, bundle.signalDeviceId, identity)
    }
    suspend fun reviewDevice(user: String, device: String, token: String): DeviceTrustSnapshot = withAccountLock {
        val target = keysApi.getDeviceBundles(token, user).devices.singleOrNull { it.deviceId == device } ?: error("Device unavailable")
        target.validateWireIntegers()
        check(!revoked(user, target)) { "Device locally revoked" }
        val address = SignalProtocolAddress(user, target.signalDeviceId)
        val key = IdentityKey(unB64(target.identityKey))
        val trusted = keyStore.durableIdentityMatches(address, key) && keyStore.getIdentity(address)?.serialize()?.contentEquals(key.serialize()) == true && !identityVerification.isPending(user, target.signalDeviceId)
        if (!trusted) identityVerification.markChanged(user, target.signalDeviceId, key)
        DeviceTrustSnapshot(user, device, target.signalDeviceId, b64(key.serialize()), trusted, identityVerification.safetyNumberFor(user, target.signalDeviceId))
    }
    suspend fun approveDevice(snapshot: DeviceTrustSnapshot, epoch: Int) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        withSendingAccount(epoch) {
            check(localSecurity?.isRevoked(snapshot.userId, snapshot.deviceId, snapshot.signalDeviceId, unB64(snapshot.identity)) != true) { "Device locally revoked" }
            identityVerification.acceptNewKey(snapshot.userId, snapshot.signalDeviceId, snapshot.identity, epoch)
        }
    }
    /** Pin crypto and trust observations to the outbox's original session. */
    suspend fun <T> withSendingAccount(epoch: Int, block: suspend () -> T): T =
        keyStore.withAccount(epoch) { identityVerification.withOperation(epoch) { block() } }

    private suspend fun <T> withAccountLock(block: suspend () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { identityVerification.withOperation {
        keyStore.withAccount { block() }
    } }

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
        val bundles = sendingBundles(userId, accessToken).first
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
            keyStore.nativeOperation {
                if (!keyStore.containsSession(address)) SessionBuilder(keyStore, keyStore, keyStore, keyStore, address).process(toPreKeyBundle(bundle))
            }
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

    /** Observe the complete snapshot before encryption. Only a first peer snapshot
     *  is TOFU; additional addresses and own devices require explicit approval. */
    internal suspend fun verifyBundleIdentitiesOrThrow(userId: String, bundles: List<DeviceBundleDto>) {
        if (bundles.map { it.deviceId }.distinct().size != bundles.size || bundles.map { it.signalDeviceId }.distinct().size != bundles.size) throw com.messenger.app.data.api.HostileWireException()
        val changed = mutableListOf<Int>()
        val firstContact = !keyStore.hasPeerIdentity(userId) && !identityVerification.isOwnUser(userId)
        bundles.forEach { bundle ->
            bundle.validateWireIntegers()
            val address = SignalProtocolAddress(userId, bundle.signalDeviceId)
            val bundledIdentity = IdentityKey(unB64(bundle.identityKey))
            val trusted = !revoked(userId, bundle) && keyStore.durableIdentityMatches(address, bundledIdentity) && !identityVerification.isPending(userId, bundle.signalDeviceId) &&
                (keyStore.getIdentity(address)?.serialize()?.contentEquals(bundledIdentity.serialize())
                    ?: firstContact)
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
    ): List<EncryptedEnvelope> = buildFanout(userId, accessToken, plaintext, excludeDeviceId).envelopes

    suspend fun buildFanout(userId: String, accessToken: String, plaintext: ByteArray, excludeDeviceId: String? = null): DeviceFanout = withAccountLock {
        // Pending addresses receive nothing; independent known devices keep working.
        val (bundles, withheld) = sendingBundles(userId, accessToken, excludeDeviceId)
        if (bundles.isEmpty() && !identityVerification.isOwnUser(userId)) identityVerification.ensureNoChangedForUser(userId)
        val envelopes = bundles.map { bundle ->
            val address = SignalProtocolAddress(userId, bundle.signalDeviceId)
            if (!keyStore.containsSession(address)) {
                establishSession(userId, bundle)
            }
            val cipher = SessionCipher(keyStore, keyStore, keyStore, keyStore, keyStore, address)
            val ciphertextMessage = keyStore.nativeOperation {
                check(!revoked(userId, bundle)) { "Device locally revoked" }
                cipher.encrypt(plaintext)
            }
            val wireType = if (ciphertextMessage is PreKeySignalMessage) MESSAGE_TYPE_PREKEY else MESSAGE_TYPE_WHISPER
            EncryptedEnvelope(bundle.deviceId, wireType, b64(ciphertextMessage.serialize()))
        }
        DeviceFanout(envelopes, withheld)
    }

    /** Dedicated mailbox path: filter BEFORE trust, session establishment, or prekey consumption.
     * Normal chat fan-out and own-device echoes are deliberately not used here. */
    suspend fun buildEnvelopeForDevice(userId: String, deviceId: String, accessToken: String, plaintext: ByteArray, expectedIdentity: String? = null): EncryptedEnvelope = withAccountLock {
        require(java.util.UUID.fromString(userId).toString() == userId && java.util.UUID.fromString(deviceId).toString() == deviceId)
        suspend fun checked(values: List<DeviceBundleDto>): DeviceBundleDto {
            val target = values.singleOrNull { it.deviceId == deviceId } ?: error("Recipient device unavailable")
            target.validateWireIntegers()
            check(!revoked(userId, target)) { "Device locally revoked" }
            if (expectedIdentity != null) require(target.identityKey == expectedIdentity && keyStore.getIdentity(SignalProtocolAddress(userId, target.signalDeviceId))?.serialize()?.contentEquals(unB64(expectedIdentity)) == true)
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
        val encrypted = keyStore.nativeOperation {
            check(!revoked(userId, target)) { "Device locally revoked" }
            cipher.encrypt(plaintext)
        }
        EncryptedEnvelope(deviceId, if (encrypted is PreKeySignalMessage) MESSAGE_TYPE_PREKEY else MESSAGE_TYPE_WHISPER, b64(encrypted.serialize()))
    }

    /** Mailbox sender discovery stays nonconsuming and observes only the authenticated sender device. */
    suspend fun decryptFromDevice(userId: String, deviceId: String, accessToken: String, messageType: Int, ciphertext: String, expectedIdentity: String? = null): ByteArray = withAccountLock {
        val target = keysApi.getDeviceBundles(accessToken, userId).devices.singleOrNull { it.deviceId == deviceId } ?: error("Sender device unavailable")
        target.validateWireIntegers()
        check(!revoked(userId, target)) { "Device locally revoked" }
        if (expectedIdentity != null) require(target.identityKey == expectedIdentity && keyStore.getIdentity(SignalProtocolAddress(userId, target.signalDeviceId))?.serialize()?.contentEquals(unB64(expectedIdentity)) == true)
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
        check(localSecurity?.isRevoked(userId, deviceId) != true) { "Device locally revoked" }
        keyStore.cachedSignalDeviceId(userId, deviceId)?.let { return@withAccount com.messenger.app.data.api.HostileWire.device(it) }
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
    override suspend fun decrypt(userId: String, signalDeviceId: Int, messageType: Int, ciphertextBase64: String): ByteArray = withAccountLock {
        decryptForAccount(userId, signalDeviceId, messageType, ciphertextBase64)
    }

    private fun receiveKey(dto: com.messenger.app.data.api.MessageDto): String = receiveChatTag(dto.chatId) + "_" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(kotlinx.serialization.json.Json.encodeToString(com.messenger.app.data.api.MessageDto.serializer(), dto.copy(notificationBatchAlert = null)).toByteArray())
        .joinToString("") { "%02x".format(it) }
    override suspend fun decryptRetained(dto: com.messenger.app.data.api.MessageDto, signalDeviceId: Int): ByteArray = withAccountLock {
        val routing = kotlinx.serialization.json.Json.encodeToString(com.messenger.app.data.api.MessageDto.serializer(),dto.copy(notificationBatchAlert = null)).toByteArray(Charsets.UTF_8)
        decryptForAccount(dto.senderId, signalDeviceId, dto.messageType, dto.ciphertext, receiveKey(dto),routing)
    }
    override suspend fun isLocallyExcluded(dto: com.messenger.app.data.api.MessageDto): Boolean = withAccountLock {
        val signal = keyStore.cachedSignalDeviceId(dto.senderId, dto.senderDeviceId)
        val identity = signal?.let { keyStore.getIdentity(SignalProtocolAddress(dto.senderId, it))?.serialize() }
            ?: if (dto.messageType == MESSAGE_TYPE_PREKEY) runCatching { PreKeySignalMessage(unB64(dto.ciphertext)).identityKey.serialize() }.getOrNull() else null
        localSecurity?.isRevoked(dto.senderId, dto.senderDeviceId, signal, identity) == true
    }
    override suspend fun retainedPlaintext(dto: com.messenger.app.data.api.MessageDto): ByteArray? = withAccountLock {
        check(!isLocallyExcluded(dto)) { "Device locally revoked" }
        keyStore.receivePlaintext(receiveKey(dto))
    }
    override suspend fun retainedMessages(): List<com.messenger.app.data.api.MessageDto> = withAccountLock {
        keyStore.retainedReceiveRouting().map { kotlinx.serialization.json.Json.decodeFromString(com.messenger.app.data.api.MessageDto.serializer(),it.toString(Charsets.UTF_8)) }
    }
    override suspend fun acknowledge(dto: com.messenger.app.data.api.MessageDto) = withAccountLock { keyStore.acknowledgeReceive(receiveKey(dto)) }
    suspend fun discardReceiveChat(chatId: String) = withAccountLock { keyStore.discardReceiveChat(chatId) }

    private suspend fun decryptForAccount(userId: String, signalDeviceId: Int, messageType: Int, ciphertextBase64: String, journal: String? = null, routing: ByteArray? = null): ByteArray {
        check(localSecurity?.isRevoked(userId, signal = signalDeviceId) != true) { "Device locally revoked" }
        com.messenger.app.data.api.HostileWire.device(signalDeviceId)
        val address = SignalProtocolAddress(userId, signalDeviceId)
        val cipher = SessionCipher(keyStore, keyStore, keyStore, keyStore, keyStore, address)
        val bytes = try { Base64.getDecoder().decode(ciphertextBase64) }
            catch (error: IllegalArgumentException) { throw MalformedSignalCiphertextException(error) }
        return try {
            if (identityVerification.isPending(userId, signalDeviceId)) throw UntrustedIdentityException(userId)
            val nativeDecrypt = {
            val incomingIdentity = if (messageType == MESSAGE_TYPE_PREKEY) PreKeySignalMessage(bytes).identityKey.serialize() else keyStore.getIdentity(address)?.serialize()
            if (incomingIdentity != null && !keyStore.durableIdentityMatches(address, IdentityKey(incomingIdentity))) throw UntrustedIdentityException(userId)
            check(localSecurity?.isRevoked(userId, signal = signalDeviceId, identity = incomingIdentity) != true) { "Device locally revoked" }
            if (messageType == MESSAGE_TYPE_PREKEY && identityVerification.isOwnUser(userId) && keyStore.getIdentity(address) == null) {
                throw UntrustedIdentityException(userId)
            }
            when (messageType) {
                MESSAGE_TYPE_PREKEY -> cipher.decrypt(PreKeySignalMessage(bytes))
                MESSAGE_TYPE_WHISPER -> cipher.decrypt(SignalMessage(bytes))
                else -> throw UnsupportedSignalMessageTypeException()
            } }
            (if (journal == null) keyStore.nativeOperation(nativeDecrypt) else keyStore.receiveJournaled(journal, routing, nativeDecrypt)).also {
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
        bundles.forEach { it.validateWireIntegers(); keyStore.cacheDeviceMapping(userId, it.deviceId, it.signalDeviceId) }
        return@withAccount bundles
    }

    /** No successful bundle cache: every send sees current revocations and
     * identity changes. Claim an OTP only when a session actually is missing.
     * After the second await, use and verify the entire newer response rather
     * than combining it with the earlier discovery list. */
    private suspend fun sendingBundles(userId: String, token: String, excludeDeviceId: String? = null): Pair<List<DeviceBundleDto>, List<String>> {
        suspend fun checked(bundles: List<DeviceBundleDto>, admitInitial: Boolean = false): Pair<List<DeviceBundleDto>, List<String>> {
            val recipients = bundles.filter { it.deviceId != excludeDeviceId }
            try { verifyBundleIdentitiesOrThrow(userId, recipients) } catch (_: IdentityChangedException) { /* Known devices keep working. */ }
            // A verified contact changing an active device blocks the contact send,
            // even if another unchanged device could still receive. Inactive devices
            // and own-device F1 withholding retain their existing behavior.
            if (!identityVerification.isOwnUser(userId)) {
                val verifiedChanges = recipients.filter { !revoked(userId, it) && keyStore.verifiedIdentityPending(userId, it.signalDeviceId) }
                if (verifiedChanges.isNotEmpty()) throw IdentityChangedException(userId, verifiedChanges.map { it.signalDeviceId })
            }
            if (admitInitial && !identityVerification.isOwnUser(userId)) keyStore.nativeOperation {
                if (!keyStore.hasPeerIdentity(userId)) recipients.forEach {
                    if (keyStore.loadPeerVerification(userId, it.signalDeviceId)?.status != IDENTITY_CHANGED)
                        keyStore.saveIdentity(SignalProtocolAddress(userId,it.signalDeviceId), IdentityKey(unB64(it.identityKey)))
                }
            }
            val approved = recipients.filterNot { revoked(userId, it) || identityVerification.isPending(userId, it.signalDeviceId) }
            val withheld = if (identityVerification.isOwnUser(userId)) recipients.filter { revoked(userId, it) || identityVerification.isPending(userId, it.signalDeviceId) }.map { it.deviceId } else emptyList()
            return approved to withheld
        }
        val initial = discoverAndCacheDevices(userId, token)
        val discovered = checked(initial)
        if (discovered.first.none { !keyStore.containsSession(SignalProtocolAddress(userId, it.signalDeviceId)) }) return checked(initial, true)
        val establishment = keysApi.getBundles(token, userId).devices
        establishment.forEach { it.validateWireIntegers(); keyStore.cacheDeviceMapping(userId, it.deviceId, it.signalDeviceId) }
        establishment.filter { it.deviceId != excludeDeviceId }.forEach { bundle ->
            val earlier = initial.singleOrNull { it.deviceId == bundle.deviceId }
            if (earlier == null || earlier.signalDeviceId != bundle.signalDeviceId || earlier.identityKey != bundle.identityKey)
                identityVerification.markChanged(userId,bundle.signalDeviceId,IdentityKey(unB64(bundle.identityKey)))
        }
        return checked(establishment, true)
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
