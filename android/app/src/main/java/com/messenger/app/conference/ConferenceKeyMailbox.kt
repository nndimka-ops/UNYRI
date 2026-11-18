package com.messenger.app.conference

import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.signal.SignalSessionManager
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** No chat/outbox/history involvement. The only retained values are volatile ciphertext retries. */
internal interface ConferenceMailboxCrypto {
    suspend fun review(epoch: Int, user: String, device: String, token: String): com.messenger.app.data.signal.DeviceTrustSnapshot
    suspend fun approve(epoch: Int, snapshot: com.messenger.app.data.signal.DeviceTrustSnapshot)
    suspend fun encrypt(epoch: Int, user: String, device: String, token: String, plaintext: ByteArray): ConferenceKeyEnvelope
    suspend fun decrypt(epoch: Int, user: String, device: String, token: String, envelope: ConferenceKeyEnvelope): ByteArray
}
private class SignalConferenceMailboxCrypto(private val signal: SignalSessionManager) : ConferenceMailboxCrypto {
    override suspend fun review(epoch: Int, user: String, device: String, token: String) = signal.withSendingAccount(epoch) { signal.reviewDevice(user, device, token) }
    override suspend fun approve(epoch: Int, snapshot: com.messenger.app.data.signal.DeviceTrustSnapshot) = signal.approveDevice(snapshot, epoch)
    override suspend fun encrypt(epoch: Int, user: String, device: String, token: String, plaintext: ByteArray): ConferenceKeyEnvelope = withContext(Dispatchers.Default) {
        val snapshot = signal.withSendingAccount(epoch) { signal.reviewDevice(user, device, token) }; check(snapshot.trusted)
        val value = signal.withSendingAccount(epoch) { signal.buildEnvelopeForDevice(user, device, token, plaintext, snapshot.identity) }
        ConferenceKeyEnvelope(value.recipientDeviceId, value.messageType, value.ciphertext)
    }
    override suspend fun decrypt(epoch: Int, user: String, device: String, token: String, envelope: ConferenceKeyEnvelope): ByteArray = withContext(Dispatchers.Default) {
        val snapshot = signal.withSendingAccount(epoch) { signal.reviewDevice(user, device, token) }; check(snapshot.trusted)
        signal.withSendingAccount(epoch) { signal.decryptFromDevice(user, device, token, envelope.messageType, envelope.ciphertext, snapshot.identity) }
    }
}
class ConferenceKeyMailbox internal constructor(private val api: ConferenceApi, private val auth: AuthRepository, private val crypto: ConferenceMailboxCrypto,
    private val now: () -> Long = { Instant.now().epochSecond }, private val selectedOrigin: () -> String = ServerEndpoint::current) {
    constructor(api: ConferenceApi, auth: AuthRepository, signal: SignalSessionManager) : this(api, auth, SignalConferenceMailboxCrypto(signal))
    private class Cached(val expires: Long, val envelope: ConferenceKeyEnvelope, val identity: String)
    class TrustReview internal constructor(val snapshot: com.messenger.app.data.signal.DeviceTrustSnapshot, val releasing: Boolean) {
        internal val answer = CompletableDeferred<Boolean>()
    }
    private val _trustReview = kotlinx.coroutines.flow.MutableStateFlow<TrustReview?>(null)
    private val releaseApprovals = linkedMapOf<String,String>()
    private val declinedReleases = linkedSetOf<String>()
    val trustReview: kotlinx.coroutines.flow.StateFlow<TrustReview?> = _trustReview
    fun decideTrust(review: TrustReview, approve: Boolean) { if (_trustReview.value === review) review.answer.complete(approve) }
    private suspend fun authorize(user: String, device: String, origin: String, epoch: Int, releasing: Boolean, requestBinding: String? = null): String {
        if (releasing && requestBinding in declinedReleases) throw ConferenceKeyUnavailable()
        val snapshot = withTimeout(4_000) { crypto.review(epoch, user, device, token(origin, epoch)) }
        checkCurrent(origin, epoch)
        val approvedKey = snapshot.signalDeviceId.toString() + ":" + snapshot.identity
        if (!snapshot.trusted || (releasing && (requestBinding == null || releaseApprovals[requestBinding] != approvedKey))) {
            val review = TrustReview(snapshot, releasing)
            check(_trustReview.value == null)
            _trustReview.value = review
            try {
                if (!review.answer.await()) {
                    if (releasing) declinedReleases.add(requireNotNull(requestBinding))
                    throw ConferenceKeyUnavailable()
                }
                checkCurrent(origin, epoch); currentCoroutineContext().ensureActive()
                val fresh = withTimeout(4_000) { crypto.review(epoch, user, device, token(origin, epoch)) }
                checkCurrent(origin, epoch); currentCoroutineContext().ensureActive()
                check(fresh.identity == snapshot.identity && fresh.signalDeviceId == snapshot.signalDeviceId)
                if (!fresh.trusted) crypto.approve(epoch, fresh)
            } finally { if (_trustReview.value === review) _trustReview.value = null }
        }
        checkCurrent(origin, epoch)
        val current = withTimeout(4_000) { crypto.review(epoch, user, device, token(origin, epoch)) }
        checkCurrent(origin, epoch)
        check(current.trusted && current.identity == snapshot.identity && current.signalDeviceId == snapshot.signalDeviceId)
        if (releasing) releaseApprovals[requireNotNull(requestBinding)] = approvedKey
        return current.identity
    }
    private val providerResponses = linkedMapOf<String, Cached>()
    private fun current(origin: String, epoch: Int) = origin == selectedOrigin() && epoch == auth.currentSessionEpoch() && auth.authState.value is AuthSessionState.LoggedIn
    private fun checkCurrent(origin: String, epoch: Int) { if (!current(origin, epoch)) throw CancellationException("Room session changed") }
    private suspend fun token(origin: String, epoch: Int): String { checkCurrent(origin, epoch); val token = auth.currentAccessToken() ?: error("Token unavailable"); checkCurrent(origin, epoch); return token }
    fun clear() { providerResponses.clear(); releaseApprovals.clear(); declinedReleases.clear(); _trustReview.value?.answer?.cancel(); _trustReview.value = null }

    /** One consumer coroutine owns a request. Cancellation fences BOTH crypto completion and media admission. */
    suspend fun receive(roomId: String, origin: String, epoch: Int, password: String?, waiting: () -> Unit = {}, approvalWaiting: () -> Unit = waiting): ConferenceAdmission.Catalog {
        return try { receiveKey(roomId, origin, epoch, password, waiting) }
        catch (error: Exception) {
            if (!needsConferenceApproval(error)) throw error
            api.awaitJoinApproval(roomId, null, password, origin, epoch, approvalWaiting)
            currentCoroutineContext().ensureActive()
            receiveKey(roomId, origin, epoch, password, waiting)
        }
    }
    private suspend fun receiveKey(roomId: String, origin: String, epoch: Int, password: String?, waiting: () -> Unit): ConferenceAdmission.Catalog {
        val user = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: throw CancellationException("Signed out")
        val device = requireNotNull(auth.currentDeviceId()); ConferenceDiscoveryPolicy.uuid(roomId)
        val requestId = UUID.randomUUID().toString()
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        var accepted = false
        try {
            val result = withTimeout(60_000) {
                var request = createOrRead(roomId, requestId, nonce, password, origin, epoch)
                val requestExpiry = request.expiresAt
                fun checkRequest(value: ConferenceKeyRequest) {
                    checkCurrent(origin, epoch)
                    require(value.id == requestId && value.roomId == roomId && value.nonce == nonce && value.requesterUserId == user && value.requesterDeviceId == device && value.expiresAt == requestExpiry)
                }
                checkRequest(request); waiting()
                while (true) {
                    coroutineContext.ensureActive(); checkRequest(request)
                    when (request.status) {
                        "ready" -> {
                            val binding = ConferenceKeyBinding(origin, request, now()); binding.validate()
                            val envelope = requireNotNull(request.envelope); ConferenceDiscoveryPolicy.envelope(envelope, device)
                            val accessToken = token(origin, epoch)
                            authorize(requireNotNull(request.providerUserId), requireNotNull(request.providerDeviceId), origin, epoch, false)
                            binding.validate(); coroutineContext.ensureActive(); checkCurrent(origin, epoch)
                            // No retry after a decrypt attempt. A new request is required after any error/process loss.
                            val plaintext = crypto.decrypt(epoch, requireNotNull(request.providerUserId), requireNotNull(request.providerDeviceId), accessToken, envelope)
                            val decoded = try { coroutineContext.ensureActive(); checkCurrent(origin, epoch)
                                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString() } finally { plaintext.fill(0) }
                            val key = ConferenceKeyPayload.decode(decoded, ConferenceKeyBinding(origin, request, now()))
                            coroutineContext.ensureActive(); checkCurrent(origin, epoch)
                            return@withTimeout ConferenceAdmission.Catalog(origin, roomId, requestId, key)
                        }
                        "pending" -> { require(Instant.parse(request.expiresAt).epochSecond > now()); delay(2_000); request = readRetry(roomId, requestId, origin, epoch) }
                        else -> throw ConferenceKeyUnavailable()
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
            currentCoroutineContext().ensureActive(); checkCurrent(origin, epoch)
            accepted = true
            return result
        } finally {
            if ((!accepted || !currentCoroutineContext().isActive) && current(origin, epoch)) withContext(NonCancellable) { runCatching { withTimeout(2_000) { api.cancelKey(roomId, requestId, origin, epoch) } } }
        }
    }
    private fun transient(error: Exception) = error is java.io.IOException || error is TimeoutCancellationException || error is AuthApiException.Network ||
        (error as? AuthApiException.Api)?.httpStatus?.let { it == 429 || it in 500..599 } == true
    private suspend fun pause(error: Exception, attempt: Int) {
        currentCoroutineContext().ensureActive()
        delay(if ((error as? AuthApiException.Api)?.httpStatus == 429) 30_000 else (1_000L shl attempt.coerceAtMost(2)))
    }
    /** A lost POST acknowledgement must not make Retry create a conflicting second live request. */
    private suspend fun createOrRead(room: String, id: String, nonce: String, password: String?, origin: String, epoch: Int): ConferenceKeyRequest {
        var post = true; var attempt = 0
        while (true) {
            checkCurrent(origin, epoch); currentCoroutineContext().ensureActive()
            try { return withTimeout(4_000) { if (post) api.requestKey(room, id, nonce, password, origin, epoch) else api.readKey(room, id, origin, epoch) } }
            catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled; pause(cancelled, attempt++); post = false }
            catch (error: Exception) {
                if (!post && (error as? AuthApiException.Api)?.httpStatus == 404) { post = true; continue }
                if (!transient(error)) throw error
                pause(error, attempt++); post = false
            }
        }
    }
    private suspend fun readRetry(room: String, id: String, origin: String, epoch: Int): ConferenceKeyRequest {
        var attempt = 0
        while (true) {
            checkCurrent(origin, epoch); currentCoroutineContext().ensureActive()
            try { return withTimeout(4_000) { api.readKey(room, id, origin, epoch) } }
            catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled; pause(cancelled, attempt++) }
            catch (error: Exception) { if (!transient(error)) throw error; pause(error, attempt++) }
        }
    }
    suspend fun cancel(admission: ConferenceAdmission.Catalog, epoch: Int) {
        if (current(admission.server, epoch)) runCatching { withTimeout(2_000) { api.cancelKey(admission.roomId, admission.requestId, admission.server, epoch) } }
    }
    /** Called serially by a live E2EE-ready Room only. Empty heartbeat polls never publish UI state. */
    suspend fun provide(admission: ConferenceAdmission, identity: String, epoch: Int, stillCurrent: () -> Boolean) {
        checkCurrent(admission.server, epoch); if (!stillCurrent()) return
        val me = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: return
        val device = auth.currentDeviceId() ?: return
        val pending = withTimeout(4_000) { api.pendingKeys(admission.roomId, admission.server, epoch).items }
        checkCurrent(admission.server, epoch); coroutineContext.ensureActive(); if (!stillCurrent()) return
        val keyEpoch = java.security.MessageDigest.getInstance("SHA-256").digest(admission.mediaKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun cacheKey(r: ConferenceKeyRequest) = listOf(admission.server, admission.roomId, keyEpoch, epoch, r.id, r.assignmentRevision, r.accessRevision, r.nonce, r.requesterUserId, r.requesterDeviceId, identity).joinToString("|")
        val liveBindings = pending.filter { it.status == "pending" && it.providerUserId == me && it.providerDeviceId == device && it.providerIdentity == identity }.map(::cacheKey).toSet()
        providerResponses.entries.removeAll { it.value.expires <= now() || it.key !in liveBindings }
        releaseApprovals.keys.retainAll(liveBindings)
        declinedReleases.retainAll(liveBindings)
        for (r in pending.take(8)) {
            coroutineContext.ensureActive(); if (!stillCurrent()) return
            if (r.status != "pending" || r.roomId != admission.roomId || r.providerUserId != me || r.providerDeviceId != device || r.providerIdentity != identity) continue
            try {
                val binding = ConferenceKeyBinding(admission.server, r, now()); binding.validate()
                val cacheId = cacheKey(r)
                val approvedIdentity = withTimeout((Instant.parse(r.expiresAt).epochSecond - now()).coerceAtLeast(1) * 1000) {
                    authorize(r.requesterUserId, r.requesterDeviceId, admission.server, epoch, true, cacheId)
                }
                if (providerResponses[cacheId]?.identity?.let { it != approvedIdentity } == true) providerResponses.remove(cacheId)
                var envelope = providerResponses[cacheId]?.envelope
                if (envelope == null) {
                    if (providerResponses.size >= 16) continue
                    val accessToken = token(admission.server, epoch)
                    val plaintext = ConferenceKeyPayload.encode(binding, admission.mediaKey)
                    val encrypted = try { withTimeout(4_000) { crypto.encrypt(epoch, r.requesterUserId, r.requesterDeviceId, accessToken, plaintext) } } finally { plaintext.fill(0) }
                    coroutineContext.ensureActive(); checkCurrent(admission.server, epoch); if (!stillCurrent()) return
                    envelope = encrypted
                    providerResponses[cacheId] = Cached(Instant.parse(r.expiresAt).epochSecond, envelope, approvedIdentity)
                }
                checkCurrent(admission.server, epoch); if (!stillCurrent()) return
                val checked = withTimeout(4_000) { crypto.review(epoch, r.requesterUserId, r.requesterDeviceId, token(admission.server, epoch)) }
                check(checked.trusted && checked.identity == approvedIdentity)
                ConferenceKeyBinding(admission.server, r, now()).validate()
                withTimeout(4_000) { api.fulfillKey(admission.roomId, r.id, r.assignmentRevision, r.accessRevision, envelope, admission.server, epoch) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { if ((error as? AuthApiException.Api)?.httpStatus == 429) throw error /* Backoff belongs to the serial provider owner. */ }
        }
    }
}
class ConferenceKeyUnavailable : Exception("Room key unavailable")
