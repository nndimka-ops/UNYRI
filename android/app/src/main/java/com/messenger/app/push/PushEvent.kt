package com.messenger.app.push

/** Parsed, validated FCM data payload (Этап K). The parser never trusts the
 *  payload - malformed UUIDs, unknown events and missing fields all collapse
 *  to [Unknown] so downstream handlers can safely no-op. */
sealed interface PushEvent {
    /** [userId] is the RECIPIENT account id (injected by the backend at send
     *  time) - the physical FCM token is shared across accounts, so handlers
     *  must reject a delayed push whose [userId] is not the current account. */
    val userId: String?

    data class IncomingCall(
        val callId: String,
        val mediaKind: String,
        override val userId: String? = null,
    ) : PushEvent

    data class CallEnded(
        val callId: String,
        val reason: String,
        override val userId: String? = null,
    ) : PushEvent

    data class NewMessage(
        val chatId: String,
        val messageId: String,
        val sequence: String,
        override val userId: String? = null,
        val deviceId: String? = null,
        /** Authenticated socket metadata only; the FCM parser never populates it. */
        val notificationBatchAlert: Boolean? = null,
        /** Null for FCM: only the authenticated socket knows an operation before ingest. */
        val operationKind: String? = null,
    ) : PushEvent {
        val mayPresent: Boolean get() = operationKind == null || operationKind == "content"
        companion object {
            fun fromSocket(message: com.messenger.app.data.api.MessageDto, owner: String) = NewMessage(
                message.chatId, message.id, message.sequence.toString(), owner,
                notificationBatchAlert = message.notificationBatchAlert, operationKind = message.kind)
        }
    }

    data class AdminNotice(
        val noticeId: String,
        val title: String,
        val body: String,
        val severity: String,
        override val userId: String? = null,
    ) : PushEvent

    data class Unknown(val event: String, val data: Map<String, String>) : PushEvent {
        override val userId: String? = null
    }
}
