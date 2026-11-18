package com.messenger.app.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PushPayloadParserTest {

    @Test fun `invalid sequence is rejected before notification dedup`() {
        for (sequence in listOf("0", "-1", "abc", "9223372036854775808")) {
            assertIs<PushEvent.Unknown>(PushPayloadParser.parse(mapOf(
                "event" to "new_message", "chat_id" to "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                "message_id" to "3f333df6-90a4-4fda-8dd3-9485d27cee36", "sequence" to sequence)))
        }
    }

    private val callId = "6f9619ff-8b86-d011-b42d-00c04fc964ff"

    @Test
    fun `incoming call parses`() {
        val event = PushPayloadParser.parse(
            mapOf("event" to "incoming_call", "call_id" to callId, "media_kind" to "video"),
        )
        assertIs<PushEvent.IncomingCall>(event)
        assertEquals(callId, event.callId)
        assertEquals("video", event.mediaKind)
    }

    @Test
    fun `incoming call defaults to audio when media kind missing`() {
        val event = PushPayloadParser.parse(mapOf("event" to "incoming_call", "call_id" to callId))
        assertIs<PushEvent.IncomingCall>(event)
        assertEquals("audio", event.mediaKind)
    }

    @Test
    fun `call ended parses reason`() {
        val event = PushPayloadParser.parse(mapOf("event" to "call_ended", "call_id" to callId, "reason" to "cancelled"))
        assertIs<PushEvent.CallEnded>(event)
        assertEquals("cancelled", event.reason)
    }

    @Test
    fun `new message parses routing ids`() {
        val chatId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        val messageId = "3f333df6-90a4-4fda-8dd3-9485d27cee36"
        val event = PushPayloadParser.parse(
            mapOf("event" to "new_message", "chat_id" to chatId, "message_id" to messageId, "sequence" to "42"),
        )
        assertIs<PushEvent.NewMessage>(event)
        assertEquals(chatId, event.chatId)
        assertEquals(messageId, event.messageId)
        assertEquals("42", event.sequence)
    }

    @Test
    fun `admin notice parses visible content and recipient`() {
        val noticeId = "95d55983-b260-4f5f-87a9-b0d0bc46cc4f"
        val event = PushPayloadParser.parse(
            mapOf(
                "event" to "admin_notice",
                "notice_id" to noticeId,
                "title" to "Security review",
                "body" to "Please review your active devices.",
                "severity" to "warning",
                "user_id" to "recipient-id",
            ),
        )
        val notice = assertIs<PushEvent.AdminNotice>(event)
        assertEquals(noticeId, notice.noticeId)
        assertEquals("Security review", notice.title)
        assertEquals("Please review your active devices.", notice.body)
        assertEquals("warning", notice.severity)
        assertEquals("recipient-id", notice.userId)
    }

    @Test
    fun `malformed admin notice is safe`() {
        for (payload in listOf(
            mapOf("event" to "admin_notice", "notice_id" to "bad", "title" to "Title", "body" to "Body"),
            mapOf("event" to "admin_notice", "notice_id" to callId, "title" to "", "body" to "Body"),
            mapOf("event" to "admin_notice", "notice_id" to callId, "title" to "Title", "body" to "Body", "severity" to "urgent"),
        )) {
            assertIs<PushEvent.Unknown>(PushPayloadParser.parse(payload))
        }
    }

    @Test
    fun `unknown event is safe`() {
        val event = PushPayloadParser.parse(mapOf("event" to "some_future_event", "x" to "y"))
        assertIs<PushEvent.Unknown>(event)
    }

    @Test
    fun `missing event is safe`() {
        assertIs<PushEvent.Unknown>(PushPayloadParser.parse(emptyMap()))
    }

    @Test
    fun `malformed call uuid is safe`() {
        val event = PushPayloadParser.parse(mapOf("event" to "incoming_call", "call_id" to "not-a-uuid"))
        assertIs<PushEvent.Unknown>(event)
    }

    @Test
    fun `malformed message uuid is safe`() {
        val event = PushPayloadParser.parse(
            mapOf("event" to "new_message", "chat_id" to "nope", "message_id" to "also-nope", "sequence" to "1"),
        )
        assertIs<PushEvent.Unknown>(event)
    }

    @Test
    fun `missing call id is safe`() {
        val event = PushPayloadParser.parse(mapOf("event" to "incoming_call"))
        assertIs<PushEvent.Unknown>(event)
    }

    @Test
    fun `payload never carries plaintext fields`() {
        // A hostile payload trying to smuggle plaintext through extra keys:
        val event = PushPayloadParser.parse(
            mapOf(
                "event" to "new_message",
                "chat_id" to "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                "message_id" to "3f333df6-90a4-4fda-8dd3-9485d27cee36",
                "sequence" to "1",
                "text" to "TOP-SECRET",
            ),
        )
        val newMessage = assertIs<PushEvent.NewMessage>(event)
        // The parsed event model has no field for smuggled plaintext.
        assertTrue(newMessage.toString().contains("TOP-SECRET").not())
    }
}
