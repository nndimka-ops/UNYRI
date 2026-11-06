package com.messenger.app.data.message

import com.messenger.app.data.api.AuthApiException

/** How a failed send attempt should be handled by the outbox. */
enum class OutboxFailureClass {
    /** Keep PENDING and schedule a retry (network/5xx/recoverable). */
    TRANSIENT,

    /** Retrying can't help without a state/content change - mark FAILED. */
    PERMANENT,

    /** The session is dead - the auth layer's logout policy takes over. */
    AUTH_TERMINAL,
}

/** Pure decision boundary for the durable outbox (N.2R Bug 4). Never treats a
 *  transient network/server failure as permanent - a message stays PENDING
 *  and retries until the backend accepts it or the session is authoritatively
 *  dead. */
object OutboxPolicy {
    /** Persist only a safe diagnostic code, never server text or message content. */
    fun diagnosticCode(e: Exception): String = when {
        e is MessageTextPolicyException -> "text_policy_invalid"
        e is RecipientDevicesUnavailableException -> "recipient_devices_unavailable"
        e is AuthApiException.Api && e.code == "validation_error" &&
            e.message == "Cannot react to a deleted message." -> "reaction_target_deleted"
        e is AuthApiException.Api && e.code == "validation_error" &&
            e.message == "At least one recipient envelope is required." -> "recipient_devices_unavailable"
        e is AuthApiException.Api && e.code == "validation_error" &&
            e.message == "One or more recipient devices are not members of this chat." -> "recipient_devices_changed"
        e is AuthApiException.Api && e.code == "validation_error" &&
            e.message == "One or more attachments are invalid, not yet uploaded, or already attached to another message." -> "attachment_association_invalid"
        e is AuthApiException.Api -> e.code
        else -> e::class.simpleName ?: "unknown"
    }

    fun classify(e: Exception): OutboxFailureClass = when (e) {
        is MessageTextPolicyException -> OutboxFailureClass.PERMANENT
        is RecipientDevicesUnavailableException -> OutboxFailureClass.TRANSIENT
        // A changed identity needs an explicit safety-number decision, not a timer.
        is com.messenger.app.data.signal.IdentityChangedException -> OutboxFailureClass.PERMANENT
        is com.messenger.app.data.attachment.MediaPreparationException, is com.messenger.app.data.attachment.AttachmentTooLargeException, is java.io.FileNotFoundException -> OutboxFailureClass.PERMANENT
        is AuthApiException.Network -> OutboxFailureClass.TRANSIENT
        is AuthApiException.Api -> when (e.code) {
            // Recoverable server-side conditions.
            "rate_limited", "internal_error" -> OutboxFailureClass.TRANSIENT
            // Authoritative terminal auth: the session must not keep sending.
            "invalid_refresh_token", "session_revoked", "unauthorized" -> OutboxFailureClass.AUTH_TERMINAL
            // Strict own-device coverage (Bug 1): rebuilding envelopes on the
            // next attempt refreshes bundles, so this is recoverable.
            "validation_error" ->
                if (e.message?.contains("Every other active device") == true ||
                    e.message == "At least one recipient envelope is required." ||
                    e.message == "One or more recipient devices are not members of this chat.") {
                    OutboxFailureClass.TRANSIENT
                } else {
                    OutboxFailureClass.PERMANENT
                }
            // 404 (chat gone), conflict (id reuse across chats), etc.
            else -> OutboxFailureClass.PERMANENT
        }
        // Unknown/network-ish exceptions (IOException, timeout wrappers, ...)
        // are transient by default - never lose a composed message over them.
        else -> OutboxFailureClass.TRANSIENT
    }

    /** Deterministic exponential backoff capped at 60s (1s, 2s, 4s, 8s, 15s,
     *  30s, 60s). A connectivity-restored signal triggers an immediate flush
     *  that skips this wait rather than hammering the server. */
    fun baseBackoffMs(retryCount: Int): Long = when (retryCount.coerceAtLeast(0)) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 4_000L
        3 -> 8_000L
        4 -> 15_000L
        5 -> 30_000L
        else -> 60_000L
    }

    /** Server key/message buckets use a 60-second fixed window. Rapid local
     * flushes must not repeatedly hit that same exhausted window. */
    fun retryDelayMs(error: Exception, retryCount: Int): Long = when {
        error is RecipientDevicesUnavailableException -> 15_000L
        diagnosticCode(error) == "recipient_devices_unavailable" -> 15_000L
        error is AuthApiException.Api && (error.code == "rate_limited" || error.httpStatus == 429) -> 60_000L
        error is AuthApiException.Network && error.httpStatus == 429 -> 60_000L
        else -> baseBackoffMs(retryCount)
    }
}

/** A direct message cannot be encrypted until the recipient publishes keys.
 * Keep the durable intent, even when the sender has another own device. */
internal class RecipientDevicesUnavailableException : Exception("Recipient encryption devices are not ready")
