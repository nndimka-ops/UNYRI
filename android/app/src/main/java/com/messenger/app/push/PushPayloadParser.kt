package com.messenger.app.push

import java.util.UUID

/** Parses an FCM `data` map into a validated [PushEvent]. Pure JVM, no
 *  Android imports - unit-testable. A malformed payload is NEVER fatal: it
 *  becomes [PushEvent.Unknown] and is silently ignored (Этап K.13). */
object PushPayloadParser {

    fun parse(data: Map<String, String>): PushEvent {
        val event = data["event"] ?: return PushEvent.Unknown("", data)
        val userId = data[KEY_USER_ID]
        return when (event) {
            EVENT_INCOMING_CALL -> {
                val callId = data[KEY_CALL_ID] ?: return PushEvent.Unknown(event, data)
                if (!isUuid(callId)) return PushEvent.Unknown(event, data)
                PushEvent.IncomingCall(callId, data[KEY_MEDIA_KIND] ?: "audio", userId)
            }
            EVENT_CALL_ENDED -> {
                val callId = data[KEY_CALL_ID] ?: return PushEvent.Unknown(event, data)
                if (!isUuid(callId)) return PushEvent.Unknown(event, data)
                PushEvent.CallEnded(callId, data[KEY_REASON] ?: "unknown", userId)
            }
            EVENT_NEW_MESSAGE -> {
                val chatId = data[KEY_CHAT_ID] ?: return PushEvent.Unknown(event, data)
                val messageId = data[KEY_MESSAGE_ID] ?: return PushEvent.Unknown(event, data)
                val sequence = data[KEY_SEQUENCE] ?: return PushEvent.Unknown(event, data)
                if (!isUuid(chatId) || !isUuid(messageId) || (sequence.toLongOrNull() ?: 0L) <= 0L) return PushEvent.Unknown(event, data)
                PushEvent.NewMessage(chatId, messageId, sequence, userId)
            }
            EVENT_ADMIN_NOTICE -> {
                val noticeId = data[KEY_NOTICE_ID] ?: return PushEvent.Unknown(event, data)
                val title = data[KEY_TITLE]?.trim().orEmpty()
                val body = data[KEY_BODY]?.trim().orEmpty()
                val severity = data[KEY_SEVERITY] ?: "info"
                if (!isUuid(noticeId) || title.isEmpty() || title.length > 120 || body.isEmpty() || body.length > 2000 ||
                    severity !in setOf("info", "warning", "critical")) return PushEvent.Unknown(event, data)
                PushEvent.AdminNotice(noticeId, title, body, severity, userId)
            }
            else -> PushEvent.Unknown(event, data)
        }
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    const val EVENT_INCOMING_CALL = "incoming_call"
    const val EVENT_CALL_ENDED = "call_ended"
    const val EVENT_NEW_MESSAGE = "new_message"
    const val EVENT_ADMIN_NOTICE = "admin_notice"
    const val KEY_CALL_ID = "call_id"
    const val KEY_MEDIA_KIND = "media_kind"
    const val KEY_REASON = "reason"
    const val KEY_CHAT_ID = "chat_id"
    const val KEY_MESSAGE_ID = "message_id"
    const val KEY_SEQUENCE = "sequence"
    const val KEY_USER_ID = "user_id"
    const val KEY_NOTICE_ID = "notice_id"
    const val KEY_TITLE = "title"
    const val KEY_BODY = "body"
    const val KEY_SEVERITY = "severity"
}
