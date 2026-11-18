package com.messenger.app.data.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Backward-compatibility is the whole point of this codec (Этап H п.12) -
 *  these tests exist specifically to prove old Этап E/F/G plaintext
 *  messages remain readable forever, never just the new format. */
class MessageContentCodecTest {
    @Test fun `iOS attachment captions survive decoding without changing literal text`() {
        val original = "Подпись **literal** [Photo] https://example.org"
        val payload = """{"version":1,"kind":"attachment","text":"$original"}""".toByteArray()
        assertEquals(original, MessageContentCodec.decode(payload).text)
    }

    @Test
    fun `iOS voice duration aliases decode with both crypto versions`() {
        for (version in listOf(1, 2)) for (durationKey in listOf("durationMs", "duration_ms")) {
            val raw = """{"kind":"attachment","attachment":{"attachment_id":"voice-test","media_type":"voice","object_key":"attachments/test","size":12,"encrypted_size":28,"mime_type":"audio/mp4","encryption":{"version":$version,"sha256":"test"},"voice":{"$durationKey":3000}}}"""
            val content = MessageContentCodec.decode(raw.toByteArray())
            assertEquals(3000L, content.attachment?.voice?.durationMs)
            assertEquals(version, content.attachment?.encryption?.version)
        }
    }

    @Test
    fun `malformed attachment never becomes raw key material in UI`() {
        val raw = """{"kind":"attachment","attachment":{"encryption":{"key":"SECRET"}}}"""
        val content = MessageContentCodec.decode(raw.toByteArray())
        assertEquals(false, content.text.orEmpty().contains("SECRET"))
    }

    @Test
    fun `legacy raw plaintext decodes as kind text`() {
        val legacy = "Hello from before Этап H".toByteArray(Charsets.UTF_8)
        val content = MessageContentCodec.decode(legacy)
        assertEquals(MessageContentDto.KIND_TEXT, content.kind)
        assertEquals("Hello from before Этап H", content.text)
    }

    @Test
    fun `legacy plaintext that happens to look like unrelated json still falls back to text`() {
        val legacy = """{"hello":"world"}""".toByteArray(Charsets.UTF_8)
        val content = MessageContentCodec.decode(legacy)
        assertEquals(MessageContentDto.KIND_TEXT, content.kind)
        assertEquals("""{"hello":"world"}""", content.text)
    }

    @Test
    fun `versioned text round trips`() {
        val encoded = MessageContentCodec.encodeText("hi there")
        val decoded = MessageContentCodec.decode(encoded)
        assertEquals(MessageContentDto.KIND_TEXT, decoded.kind)
        assertEquals("hi there", decoded.text)
    }

    @Test
    fun `versioned attachment round trips with every field preserved`() {
        val attachment = AttachmentMetadataDto(
            attachmentId = "attachment-1",
            mediaType = AttachmentMetadataDto.MEDIA_TYPE_IMAGE,
            objectKey = "attachments/abc",
            size = 12345,
            encryptedSize = 12361,
            mimeType = "image/jpeg",
            encryption = EncryptionInfoDto(key = "a2V5", nonce = "bm9uY2U=", sha256 = "aGFzaA=="),
            filename = null,
            image = ImageInfoDto(width = 800, height = 600),
            voice = null,
            thumbnail = "dGh1bWI=",
        )
        val encoded = MessageContentCodec.encodeAttachment(attachment)
        val decoded = MessageContentCodec.decode(encoded)

        assertEquals(MessageContentDto.KIND_ATTACHMENT, decoded.kind)
        assertNull(decoded.text)
        assertEquals(attachment, decoded.attachment)
    }

    @Test
    fun `voice attachment metadata round trips`() {
        val attachment = AttachmentMetadataDto(
            attachmentId = "attachment-2",
            mediaType = AttachmentMetadataDto.MEDIA_TYPE_VOICE,
            objectKey = "attachments/def",
            size = 50_000,
            encryptedSize = 50_016,
            mimeType = "audio/mp4",
            encryption = EncryptionInfoDto(key = "a2V5Mg==", nonce = "bm9uY2Uy", sha256 = "aGFzaDI="),
            voice = VoiceInfoDto(durationMs = 15_000),
        )
        val decoded = MessageContentCodec.decode(MessageContentCodec.encodeAttachment(attachment))
        assertEquals(15_000L, decoded.attachment?.voice?.durationMs)
    }

    @Test
    fun `file attachment metadata preserves the filename`() {
        val attachment = AttachmentMetadataDto(
            attachmentId = "attachment-3",
            mediaType = AttachmentMetadataDto.MEDIA_TYPE_FILE,
            objectKey = "attachments/ghi",
            size = 999,
            encryptedSize = 1015,
            mimeType = "application/pdf",
            encryption = EncryptionInfoDto(key = "a2V5Mw==", nonce = "bm9uY2Uz", sha256 = "aGFzaDM="),
            filename = "Report.pdf",
        )
        val decoded = MessageContentCodec.decode(MessageContentCodec.encodeAttachment(attachment))
        assertEquals("Report.pdf", decoded.attachment?.filename)
    }

    // --- Этап I: reply/edit/reaction/delete/forward ------------------------

    @Test
    fun `reply snapshot round trips inside a text message`() {
        val snapshot = ReplySnapshotDto(senderId = "alice", contentType = ReplySnapshotDto.CONTENT_TYPE_TEXT, preview = "Hi there", sequenceHint = 42)
        val decoded = MessageContentCodec.decode(MessageContentCodec.encodeText("Reply text", replySnapshot = snapshot))
        assertEquals(MessageContentDto.KIND_TEXT, decoded.kind)
        assertEquals(snapshot, decoded.replySnapshot)
    }

    @Test
    fun `an edit's encrypted payload is shaped exactly like a plain text message`() {
        val decoded = MessageContentCodec.decode(MessageContentCodec.encodeEdit("New text"))
        assertEquals(MessageContentDto.KIND_TEXT, decoded.kind)
        assertEquals("New text", decoded.text)
    }

    @Test
    fun `reaction set round trips`() {
        val decoded = MessageContentCodec.decode(MessageContentCodec.encodeReaction("heart"))
        assertEquals(MessageContentDto.KIND_REACTION, decoded.kind)
        assertEquals("heart", decoded.reaction)
        assertEquals(MessageContentDto.REACTION_ACTION_SET, decoded.reactionAction)
    }

    @Test
    fun `reaction removal round trips with no emoji`() {
        val decoded = MessageContentCodec.decode(MessageContentCodec.encodeReactionRemoval())
        assertEquals(MessageContentDto.KIND_REACTION, decoded.kind)
        assertNull(decoded.reaction)
        assertEquals(MessageContentDto.REACTION_ACTION_REMOVE, decoded.reactionAction)
    }

    @Test
    fun `delete content round trips as its own recognized kind`() {
        val decoded = MessageContentCodec.decode(MessageContentCodec.encodeDelete())
        assertEquals(MessageContentDto.KIND_DELETE, decoded.kind)
    }

    @Test
    fun `forwarded flag round trips on text`() {
        val decoded = MessageContentCodec.decode(MessageContentCodec.encodeText("Fwd", forwarded = true))
        assertEquals(true, decoded.forwarded)
    }

    @Test
    fun `forwarded flag round trips on attachment`() {
        val attachment = AttachmentMetadataDto(
            attachmentId = "attachment-4",
            mediaType = AttachmentMetadataDto.MEDIA_TYPE_IMAGE,
            objectKey = "attachments/jkl",
            size = 100,
            encryptedSize = 116,
            mimeType = "image/jpeg",
            encryption = EncryptionInfoDto(key = "a2V5NA==", nonce = "bm9uY2U0", sha256 = "aGFzaDQ="),
            image = ImageInfoDto(10, 10),
        )
        val decoded = MessageContentCodec.decode(MessageContentCodec.encodeAttachment(attachment, forwarded = true))
        assertEquals(true, decoded.forwarded)
    }

    @Test
    fun `default forwarded is false for plain text`() {
        val decoded = MessageContentCodec.decode(MessageContentCodec.encodeText("plain"))
        assertEquals(false, decoded.forwarded)
    }
}
