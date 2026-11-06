package com.messenger.app.data.attachment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class EncryptionInfoDto(
    val version: Int = 1,
    /** v1 only - base64 raw AES-256 key, never sent to the backend, only
     *  inside this Signal-encrypted blob. Absent on version 2. */
    val key: String? = null,
    /** v1 only - base64 GCM nonce. Absent on version 2 (Tink's Streaming
     *  AEAD manages its own per-segment nonce derivation internally, see
     *  [AttachmentCryptoV2]). */
    val nonce: String? = null,
    /** v2 only (Этап H.2) - base64 cleartext Tink keyset bytes for this
     *  attachment's Streaming AEAD (AES256_GCM_HKDF_1MB). A fresh keyset
     *  per attachment, exactly like v1's fresh key/nonce pair. */
    val keyset: String? = null,
    /** base64 SHA-256 of the CIPHERTEXT (tag included) - both versions.
     *  See Этап H п.11 and Этап H.2 п.12: this is an early transport-
     *  corruption check, NOT a substitute for AEAD authentication, which
     *  remains the actual cryptographic authenticity guarantee for both
     *  v1 (GCM tag) and v2 (per-segment Tink authentication). */
    val sha256: String,
) {
    companion object {
        const val VERSION_V1_AES_GCM = 1
        const val VERSION_V2_STREAMING_AEAD = 2
    }
}

@Serializable
data class ImageInfoDto(val width: Int, val height: Int)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class VoiceInfoDto(@JsonNames("duration_ms") val durationMs: Long)

@Serializable data class VideoInfoDto(@SerialName("duration_ms") val durationMs: Long)

/**
 * A small, self-contained preview of the message being replied to - kept
 * inside the SAME Signal-encrypted content as the reply itself (Этап I
 * п.4), so rendering a reply bubble never depends on the original message
 * still being present/decryptable locally. `sequenceHint` is the target's
 * server sequence at reply-time (server-visible/safe - a bare integer, no
 * content) purely as a hint for jumping the local history pagination
 * window straight to it if it isn't cached yet (see tap-to-scroll).
 */
@Serializable
data class ReplySnapshotDto(
    @SerialName("sender_id") val senderId: String,
    @SerialName("content_type") val contentType: String,
    val preview: String,
    @SerialName("sequence_hint") val sequenceHint: Long? = null,
) {
    companion object {
        const val CONTENT_TYPE_TEXT = "text"
        const val CONTENT_TYPE_IMAGE = "image"
        const val CONTENT_TYPE_VOICE = "voice"
        const val CONTENT_TYPE_FILE = "file"
    }
}

/**
 * Everything the recipient needs to download/verify/decrypt/display an
 * attachment - travels entirely inside the Signal-encrypted message
 * content (see [MessageContentDto]). The backend only ever sees
 * `attachment_id` (as a storage-authorization reference in
 * `SendMessageRequest.attachmentIds`) - never this object.
 */
@Serializable
data class AttachmentMetadataDto(
    @SerialName("attachment_id") val attachmentId: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("object_key") val objectKey: String,
    val size: Long,
    @SerialName("encrypted_size") val encryptedSize: Long,
    @SerialName("mime_type") val mimeType: String,
    val encryption: EncryptionInfoDto,
    val filename: String? = null,
    val image: ImageInfoDto? = null,
    val voice: VoiceInfoDto? = null,
    /** Small inline low-res JPEG thumbnail, base64 (Этап H п.22, option A -
     *  kept inside Signal-encrypted metadata rather than a second
     *  attachment, since it's tiny). Images and video files. */
    val thumbnail: String? = null,
    val video: VideoInfoDto? = null,
) {
    companion object {
        const val MEDIA_TYPE_IMAGE = "image"
        const val MEDIA_TYPE_FILE = "file"
        const val MEDIA_TYPE_VOICE = "voice"
    }
}

/** Context protected by the Signal ciphertext, independent of server routing metadata. */
@Serializable
data class MessageAuthentication(
    val version: Int = 1,
    @SerialName("chat_id") val chatId: String,
    @SerialName("client_message_id") val clientMessageId: String,
    val operation: String,
    @SerialName("target_message_id") val targetMessageId: String? = null,
    @SerialName("target_client_message_id") val targetClientMessageId: String? = null,
)

/**
 * Versioned plaintext protected by Signal. Legacy text/media remain readable.
 * New outbound content binds its chat, client id, operation and lifecycle target
 * inside authentication; external routing fields alone never authorize an effect.
 */
@Serializable
data class MessageContentDto(
    val version: Int = 1,
    val kind: String,
    val text: String? = null,
    val attachment: AttachmentMetadataDto? = null,
    /** Only meaningful when `kind` is "text"/"attachment" and this message
     *  is a reply - null otherwise. */
    @SerialName("reply_snapshot") val replySnapshot: ReplySnapshotDto? = null,
    /** Only meaningful when `kind = "reaction"` - the emoji being set (null
     *  when [reactionAction] is "remove"). */
    val reaction: String? = null,
    @SerialName("reaction_action") val reactionAction: String? = null,
    /** Only meaningful when `kind` is "text"/"attachment" - never set by the
     *  backend, purely a UI hint the sender chooses to include. */
    val forwarded: Boolean = false,
    @SerialName("album_id") val albumId: String? = null,
    val authentication: MessageAuthentication? = null,
    @SerialName("conference_invite") val conferenceInvite: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("direct_file") val directFile: kotlinx.serialization.json.JsonElement? = null,
) {
    companion object {
        const val KIND_TEXT = "text"
        const val KIND_ATTACHMENT = "attachment"
        const val KIND_REACTION = "reaction"
        const val KIND_DELETE = "delete"
        const val REACTION_ACTION_SET = "set"
        const val REACTION_ACTION_REMOVE = "remove"
    }
}

/**
 * Encodes/decodes the bytes that actually get Signal-encrypted.
 *
 * Backward compatibility strategy (Этап H п.12): Этап E/F/G messages are
 * raw UTF-8 text with NO JSON envelope at all. Rather than a schema/version
 * flag inside old messages (impossible - they were never written with one),
 * [decode] treats ANY plaintext that isn't valid [MessageContentDto] JSON
 * (with a recognized `kind`) as legacy plain text. New messages are always
 * written in the versioned format, so this is a one-way migration that
 * never breaks reading old history.
 */
object MessageContentCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun authenticate(plaintext: ByteArray, chatId: String, clientMessageId: String,
                     operation: String, targetMessageId: String?, targetClientMessageId: String? = null): ByteArray {
        if (!com.messenger.app.data.message.MessageTextPolicy.allowsPlaintext(plaintext)) throw com.messenger.app.data.message.MessageTextPolicyException()
        val decoded = decode(plaintext)
        validateSendingText(decoded)
        val content = decoded.copy(authentication = MessageAuthentication(
            chatId = chatId, clientMessageId = clientMessageId, operation = operation,
            targetMessageId = targetMessageId, targetClientMessageId = targetClientMessageId))
        require(authenticates(content, chatId, clientMessageId, operation, targetMessageId))
        return json.encodeToString(MessageContentDto.serializer(), content).toByteArray(Charsets.UTF_8)
    }

    /** Legacy text/media remain readable, but cannot authorize a lifecycle action. */
    fun authenticates(content: MessageContentDto, chatId: String, clientMessageId: String,
                      operation: String, targetMessageId: String?): Boolean {
        val expectedKind = when (operation) {
            "content" -> content.kind == MessageContentDto.KIND_TEXT || content.kind == MessageContentDto.KIND_ATTACHMENT
            "edit" -> content.kind == MessageContentDto.KIND_TEXT
            "delete" -> content.kind == MessageContentDto.KIND_DELETE
            "reaction" -> content.kind == MessageContentDto.KIND_REACTION
            else -> false
        }
        if (!expectedKind) return false
        val binding = content.authentication ?: return operation == "content" && content.directFile == null
        if (content.directFile != null && (content.forwarded || operation != "content" || content.kind != MessageContentDto.KIND_TEXT)) return false
        return binding.version == 1 && binding.chatId == chatId && binding.clientMessageId == clientMessageId &&
            binding.operation == operation && binding.targetMessageId == targetMessageId &&
            (operation == "content" || (!targetMessageId.isNullOrBlank() && !binding.targetClientMessageId.isNullOrBlank()))
    }

    fun encodeText(text: String, replySnapshot: ReplySnapshotDto? = null, forwarded: Boolean = false,
                   conferenceInvite: com.messenger.app.conference.ConferenceInvitation? = null,
                   directFile: com.messenger.app.data.directtransfer.DirectFileOffer? = null): ByteArray {
        require(directFile == null || conferenceInvite == null && !forwarded)
        if (conferenceInvite == null) com.messenger.app.data.message.MessageTextPolicy.retry(text)
        com.messenger.app.data.message.MessageTextPolicy.received(replySnapshot?.preview)
        return json.encodeToString(MessageContentDto.serializer(),
            MessageContentDto(kind = MessageContentDto.KIND_TEXT, text = if (conferenceInvite == null) text else com.messenger.app.conference.ConferenceInvitation.FALLBACK,
                replySnapshot = replySnapshot, forwarded = forwarded, conferenceInvite = conferenceInvite?.json(), directFile = directFile?.validated()?.json())).toByteArray(Charsets.UTF_8)
    }

    fun encodeAttachment(attachment: AttachmentMetadataDto, replySnapshot: ReplySnapshotDto? = null, forwarded: Boolean = false, caption: String? = null, albumId: String? = null): ByteArray {
        com.messenger.app.data.message.MessageTextPolicy.retry(caption, optional = true)
        com.messenger.app.data.message.MessageTextPolicy.received(replySnapshot?.preview)
        return json.encodeToString(MessageContentDto.serializer(),
            MessageContentDto(kind = MessageContentDto.KIND_ATTACHMENT, text = caption, albumId = albumId, attachment = attachment, replySnapshot = replySnapshot, forwarded = forwarded)).toByteArray(Charsets.UTF_8)
    }

    /** Call only AFTER the existing authentication binding check on receive. */
    fun validateReceivedText(content: MessageContentDto) {
        com.messenger.app.data.message.MessageTextPolicy.received(content.text)
        com.messenger.app.data.message.MessageTextPolicy.received(content.replySnapshot?.preview)
        com.messenger.app.data.message.MessageTextPolicy.receivedReaction(content.reaction)
        com.messenger.app.data.message.MessageTextPolicy.received(content.attachment?.filename)
    }

    private fun validateSendingText(content: MessageContentDto) {
        if (content.kind == MessageContentDto.KIND_TEXT && content.conferenceInvite == null) com.messenger.app.data.message.MessageTextPolicy.retry(content.text)
        else if (content.kind == MessageContentDto.KIND_ATTACHMENT) com.messenger.app.data.message.MessageTextPolicy.retry(content.text, optional = true)
        validateReceivedText(content)
    }

    /** An edit event's encrypted payload is shaped exactly like a plain
     *  text message - see [MessageContentDto]'s class doc. */
    fun encodeEdit(newText: String): ByteArray = encodeText(newText)

    fun encodeReaction(emoji: String): ByteArray =
        json.encodeToString(
            MessageContentDto.serializer(),
            MessageContentDto(kind = MessageContentDto.KIND_REACTION, reaction = emoji, reactionAction = MessageContentDto.REACTION_ACTION_SET),
        ).toByteArray(Charsets.UTF_8)

    fun encodeReactionRemoval(): ByteArray =
        json.encodeToString(
            MessageContentDto.serializer(),
            MessageContentDto(kind = MessageContentDto.KIND_REACTION, reactionAction = MessageContentDto.REACTION_ACTION_REMOVE),
        ).toByteArray(Charsets.UTF_8)

    fun encodeDelete(): ByteArray =
        json.encodeToString(MessageContentDto.serializer(), MessageContentDto(kind = MessageContentDto.KIND_DELETE))
            .toByteArray(Charsets.UTF_8)

    fun decode(plaintext: ByteArray): MessageContentDto {
        if (!com.messenger.app.data.message.MessageTextPolicy.allowsPlaintext(plaintext)) return MessageContentDto(kind = "invalid_authenticated_content")
        val raw = String(plaintext, Charsets.UTF_8)
        val parsed = runCatching { json.decodeFromString(MessageContentDto.serializer(), raw) }.getOrNull()
        val recognizedKind = parsed?.kind in setOf(
            MessageContentDto.KIND_TEXT,
            MessageContentDto.KIND_ATTACHMENT,
            MessageContentDto.KIND_REACTION,
            MessageContentDto.KIND_DELETE,
        )
        val objectValue = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        // A damaged authenticated payload must never fall back to legacy text.
        if (objectValue?.containsKey("authentication") == true && (parsed?.authentication == null || !recognizedKind)) {
            return MessageContentDto(kind = "invalid_authenticated_content")
        }
        if (parsed != null && recognizedKind) {
            if (objectValue?.containsKey("direct_file") == true && parsed.directFile != null) {
                val offer = if (parsed.kind == MessageContentDto.KIND_TEXT && !parsed.forwarded && parsed.conferenceInvite == null)
                    com.messenger.app.data.directtransfer.DirectFileOffer.decode(parsed.directFile) else null
                return if (offer == null) MessageContentDto(kind = "invalid_authenticated_content")
                    else parsed.copy(text = parsed.text ?: com.messenger.app.data.directtransfer.DirectFileOffer.FALLBACK, directFile = offer.json())
            }
            if (objectValue?.containsKey("conference_invite") == true) {
                val invitation = if (parsed.kind == MessageContentDto.KIND_TEXT) com.messenger.app.conference.ConferenceInvitation.decode(parsed.conferenceInvite) else null
                return parsed.copy(text = com.messenger.app.conference.ConferenceInvitation.FALLBACK, conferenceInvite = invitation?.json())
            }
            return parsed
        }
        if (objectValue?.containsKey("conference_invite") == true) return MessageContentDto(kind = MessageContentDto.KIND_TEXT, text = com.messenger.app.conference.ConferenceInvitation.FALLBACK)
        if ((objectValue?.get("kind") as? JsonPrimitive)?.content == MessageContentDto.KIND_ATTACHMENT) {
            return MessageContentDto(kind = MessageContentDto.KIND_ATTACHMENT, text = "Unable to read attachment. Please ask the sender to resend it.")
        }
        return MessageContentDto(kind = MessageContentDto.KIND_TEXT, text = raw)
    }
}
