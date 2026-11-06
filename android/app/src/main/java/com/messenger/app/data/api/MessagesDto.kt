package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One Signal Protocol ciphertext blob addressed to a single recipient
 *  device - see SignalSessionManager for how these are produced. */
@Serializable
data class EnvelopeDto(
    @SerialName("recipient_device_id") val recipientDeviceId: String,
    @SerialName("message_type") val messageType: Int,
    val ciphertext: String,
)

@Serializable
data class SendMessageRequestDto(
    @SerialName("client_message_id") val clientMessageId: String,
    val envelopes: List<EnvelopeDto>,
    @SerialName("attachment_ids") val attachmentIds: List<String> = emptyList(),
    /** Этап I: "content" (default) | "edit" | "delete" | "reaction" - a
     *  server-visible routing/validation discriminator only. The actual
     *  edited text/reaction emoji/reply quote never appears here, only
     *  inside the encrypted envelopes above. */
    val kind: String = "content",
    /** Dual-purpose by [kind]: reply-to reference for "content", required
     *  lifecycle target for "edit"/"delete"/"reaction". */
    @SerialName("target_message_id") val targetMessageId: String? = null,
)

/** Response to a successful send - never echoes ciphertext back, the
 *  sender already has the plaintext it just composed. */
@Serializable
data class SendMessageResponseDto(
    val id: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("client_message_id") val clientMessageId: String,
    @SerialName("sender_id") val senderId: String,
    val sequence: Long,
    @SerialName("created_at") val createdAt: String,
    val status: String,
)

/** A received/historical envelope - opaque ciphertext addressed to *this*
 *  device, decrypted locally via SignalSessionManager before ever reaching
 *  Room/UI. `senderDeviceId` picks the right Signal session (sessions are
 *  per sender-device, not per sender-user). */
@Serializable
data class MessageDto(
    val id: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("client_message_id") val clientMessageId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("sender_device_id") val senderDeviceId: String,
    val sequence: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("message_type") val messageType: Int,
    val ciphertext: String,
    /** Этап I: "content" | "edit" | "delete" | "reaction" (server-visible). */
    val kind: String = "content",
    @SerialName("target_message_id") val targetMessageId: String? = null,
)

@Serializable
data class MessageListResponseDto(
    val messages: List<MessageDto>,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("next_before_sequence") val nextBeforeSequence: Long? = null,
)

@Serializable
data class ReadReceiptRequestDto(val sequence: Long)

@Serializable
data class ReadReceiptResponseDto(val sequence: Long)
