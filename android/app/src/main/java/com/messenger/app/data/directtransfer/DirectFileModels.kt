package com.messenger.app.data.directtransfer

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class DirectFileOffer(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = 1,
    @SerialName("transfer_id") val transferId: String,
    @SerialName("offer_client_message_id") val offerClientMessageId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("sender_device_id") val senderDeviceId: String,
    @SerialName("recipient_user_id") val recipientUserId: String,
    @SerialName("offer_nonce") val offerNonce: String,
    val filename: String,
    @SerialName("mime_type") val mimeType: String,
    val size: Long,
    val sha256: String,
    @SerialName("expires_at") val expiresAt: Long,
) {
    fun validated(now: Long = System.currentTimeMillis() / 1000): DirectFileOffer = apply {
        require(version == 1 && listOf(transferId, offerClientMessageId, senderUserId, senderDeviceId, recipientUserId).all(::canonicalUuid))
        require(senderUserId != recipientUserId && size in 1..DirectFileWire.MAX_FILE_BYTES && validHash(sha256))
        DirectOfferNonce.bytes(offerNonce)
        require(filename.isNotBlank() && filename !in setOf(".", "..") && filename.toByteArray(Charsets.UTF_8).size <= 255 && filename.none(::unsafeNameChar))
        require(mimeType.length in 1..127 && DIRECT_MIME.matches(mimeType))
        require(expiresAt > 0 && expiresAt <= now + 7 * 24 * 60 * 60 + 300)
    }
    fun json(): JsonElement = codec.encodeToJsonElement(this)
    fun stored(): String = codec.encodeToString(serializer(), this)

    companion object {
        // Wire fallback is identical on every platform. Localize UI separately.
        const val FALLBACK = "Direct file transfer"
        private const val LEGACY_FALLBACK = "Передача файла напрямую"
        fun caption(text: String?): String? {
            if (text == null) return null
            for (prefix in listOf(FALLBACK, LEGACY_FALLBACK)) {
                if (text.startsWith(prefix + "\n\n")) return text.drop(prefix.length + 2).takeIf { it.isNotBlank() }
            }
            return null
        }
        val codec = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fun decode(value: JsonElement?): DirectFileOffer? = value?.let { runCatching { codec.decodeFromJsonElement(serializer(), it).validated() }.getOrNull() }
        fun decodeStored(value: String?): DirectFileOffer? = value?.let { runCatching { codec.decodeFromString(serializer(), it).validated() }.getOrNull() }
        internal fun unsafeNameChar(value: Char): Boolean = value.isISOControl() || value == '/' || value == '\\' || value in '\u202a'..'\u202e' || value in '\u2066'..'\u2069' || value in "\u200e\u200f"
    }
}

internal fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun validHash(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
internal val DIRECT_MIME = Regex("[A-Za-z0-9!#&^_.+\\x24\\-]+/[A-Za-z0-9!#&^_.+\\x24\\-]+")

/** Possession proof carried only by Signal content; this is not a file encryption key. */
internal object DirectOfferNonce {
    fun create(): String = java.util.Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
    fun bytes(value: String): ByteArray {
        require(value.length == 44) { "Invalid offer possession proof" }
        val raw = java.util.Base64.getDecoder().decode(value)
        require(raw.size == 32 && java.util.Base64.getEncoder().encodeToString(raw) == value) { "Invalid offer possession proof" }
        return raw
    }
    fun matches(first: String, second: String): Boolean = java.security.MessageDigest.isEqual(bytes(first), bytes(second))
}

@Serializable
internal data class DirectTransferMetadata(
    val id: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("offer_client_message_id") val offerClientMessageId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("sender_device_id") val senderDeviceId: String,
    @SerialName("recipient_user_id") val recipientUserId: String,
    @SerialName("recipient_device_id") val recipientDeviceId: String? = null,
    @SerialName("expires_at") val expiresAt: Long,
    val status: String,
)

@Serializable
internal data class DirectSignalEnvelope(
    val sequence: Long = 0,
    @SerialName("client_signal_id") val clientSignalId: String,
    @SerialName("sender_user_id") val senderUserId: String = "",
    @SerialName("sender_device_id") val senderDeviceId: String = "",
    @SerialName("recipient_device_id") val recipientDeviceId: String,
    @SerialName("message_type") val messageType: Int,
    val ciphertext: String,
)

@Serializable
internal data class DirectSignalsPage(val transfer: DirectTransferMetadata, val signals: List<DirectSignalEnvelope>, @SerialName("has_more") val hasMore: Boolean)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class DirectFileControl(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = 1,
    @SerialName("transfer_id") val transferId: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("offer_client_message_id") val offerClientMessageId: String,
    @SerialName("client_signal_id") val clientSignalId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("sender_device_id") val senderDeviceId: String,
    @SerialName("recipient_user_id") val recipientUserId: String,
    @SerialName("recipient_device_id") val recipientDeviceId: String,
    @SerialName("offer_nonce") val offerNonce: String,
    @SerialName("attempt_id") val attemptId: String,
    val type: String,
    val sdp: String? = null,
    val offset: Long? = null,
    @SerialName("prefix_sha256") val prefixSha256: String? = null,
    val reason: String? = null,
    @Serializable(with = DirectManualLongSerializer::class) @SerialName("pause_revision") val pauseRevision: Long? = null,
    @Serializable(with = DirectManualBooleanSerializer::class) val paused: Boolean? = null,
    @Serializable(with = DirectManualLongSerializer::class) @SerialName("ack_pause_revision") val ackPauseRevision: Long? = null,
    @Serializable(with = DirectManualBooleanSerializer::class) @SerialName("request_ack") val requestAck: Boolean? = null,
) {
    fun validatedManualSnapshot(): DirectManualSnapshot? {
        DirectManualWire.validate(type, pauseRevision, paused, ackPauseRevision, requestAck)
        return pauseRevision?.let { DirectManualSnapshot(it, requireNotNull(paused), requireNotNull(ackPauseRevision)) }
    }
}

@Serializable
internal data class FrozenDirectSignal(val envelope: DirectSignalEnvelope, val control: DirectFileControl, val logicalKey: String? = null)

@Serializable
internal data class DirectTransferRecord(
    val offer: DirectFileOffer,
    val chatId: String,
    val role: String,
    val state: String = "offered",
    val consent: Boolean = false,
    val registered: Boolean = false,
    val peerDeviceId: String? = null,
    val durableOffset: Long = 0,
    val attemptId: String? = null,
    val requestId: String? = null,
    val usedAttempts: List<String> = emptyList(),
    val cursor: Long = 0,
    val pending: List<FrozenDirectSignal> = emptyList(),
    val reason: String? = null,
    val completed: Boolean = false,
    val closePending: Boolean = false,
    val sendingOffset: Long = 0,
    val mailboxClosed: Boolean = false,
    val processingSignal: DirectSignalEnvelope? = null,
    val lastRequestAttempt: String? = null,
    val completionAcknowledged: Boolean = false,
    val connectionFailures: Int = 0,
    val nextAttemptAt: Long = 0,
    val sentControlKeys: List<String> = emptyList(),
    val ackSampleId: Long = 0,
    val speedBytesPerSecond: Long = 0,
    val completionReceived: Boolean = false,
    val acceptedRequest: DirectFileControl? = null,
    val requestCreatedAt: Long = 0,
    val requestAcknowledgedAt: Long = 0,
    val requestRetryCount: Int = 0,
    val pausedForLocalCall: Boolean = false,
    val completionRecoveryCount: Int = 0,
    val completionRecoveryAt: Long = 0,
    val manualPause: DirectManualPauseState? = null,
    val signalBudget: DirectSignalBudget? = null,
    val queue: DirectFileQueueState? = null,
    val executionSession: String? = null,
    val executionInterrupted: Boolean = false,
) {
    /** Confirmed closed mailbox with no remaining local protocol bookkeeping.
     * This retires admission only: it does not invent a peer cancel/expiry or delete bytes. */
    val closedUnavailable: Boolean get() = role in setOf("sender", "receiver") &&
        state == "paused" && reason == "transfer_unavailable" && mailboxClosed && !completed &&
        !completionReceived && !completionAcknowledged && !closePending && pending.isEmpty() && processingSignal == null
    val terminal: Boolean get() = completed || state in setOf("cancelled", "rejected", "expired", "other_device")
    fun presentation(): DirectFilePresentation {
        val manual = manualPause
        val unfinishedSync = manual != null &&
            (manual.acknowledgedLocalRevision < manual.localRevision || manual.pendingResponseTo != null)
        return DirectFilePresentation(
            offer, state, role, durableOffset, if (executionInterrupted && !terminal && !DirectManualRuntime.safetyBlocked(this)) "background_interrupted" else reason, completed, ackSampleId, speedBytesPerSecond,
            localManualPaused = manual?.localPaused == true,
            peerManualPaused = manual?.peerPaused == true,
            manualSyncPending = unfinishedSync,
            manualSyncLimitReached = unfinishedSync && (manual?.syncLimitReached == true || signalBudget?.let { budget ->
                !budget.known || budget.overflowed || budget.debits >= DirectSignalBudget.OPTIONAL_LIMIT
            } != false),
            mailboxClosed = mailboxClosed,
            manualControlsAvailable = !terminal && !mailboxClosed && state != "verifying" && durableOffset < offer.size &&
                (role == "sender" || role == "receiver" && consent),
        )
    }
}

data class DirectFilePresentation(
    val offer: DirectFileOffer,
    val state: String,
    val role: String,
    val offset: Long = 0,
    val reason: String? = null,
    val completed: Boolean = false,
    val ackSampleId: Long = 0,
    val speedBytesPerSecond: Long = 0,
    val localManualPaused: Boolean = false,
    val peerManualPaused: Boolean = false,
    val manualSyncPending: Boolean = false,
    val manualSyncLimitReached: Boolean = false,
    val manualControlsAvailable: Boolean = false,
    val mailboxClosed: Boolean = false,
)

internal val DIRECT_CONTROL_REASONS = setOf("network_waiting", "connection_lost", "direct_unavailable", "prefix_mismatch", "source_missing", "source_changed",
    "disk_full", "integrity_failed", "busy", "queued", "call_in_progress", "reconnect_required", "security_error", "storage_error", "user_cancelled", "user_declined",
    "background", "disconnected", "no_direct_route", "integrity_mismatch", "expired", "retry", "verification_failed", "unsupported", "account_changed", "permission_denied")
