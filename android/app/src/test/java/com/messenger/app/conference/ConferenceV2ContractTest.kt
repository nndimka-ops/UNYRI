package com.messenger.app.conference

import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.message.composeTextMessagePayload
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class ConferenceV2ContractTest {
    private val vectors by lazy { Json.parseToJsonElement(listOf(File("../shared/conference-v2-vectors.json"), File("../../shared/conference-v2-vectors.json")).first { it.isFile }.readText()).jsonObject }
    @Test fun `shared compact and legacy vectors use actual native parser`() {
        for (group in listOf("valid", "legacyValid")) for (item in vectors.getValue(group).jsonArray) {
            val obj = item.jsonObject; val expected = obj.getValue("expected").jsonObject
            val actual = ConferenceInvite.parseForServer(obj.getValue("link").jsonPrimitive.content, obj.getValue("currentServer").jsonPrimitive.content)
            assertEquals(expected.getValue("server").jsonPrimitive.content, actual.server, obj.getValue("id").toString())
            assertEquals(expected.getValue("roomId").jsonPrimitive.content, actual.roomId)
            assertEquals(expected.getValue("secret").jsonPrimitive.content, actual.secret)
            assertEquals(expected.getValue("mediaKey").jsonPrimitive.content, actual.mediaKey)
            assertEquals(actual.roomId, ConferenceInvite.parse(actual.link()).roomId)
        }
        for (item in vectors.getValue("invalid").jsonArray) {
            val obj = item.jsonObject
            assertFails(obj.getValue("id").toString()) { ConferenceInvite.parseForServer(obj.getValue("link").jsonPrimitive.content, obj.getValue("currentServer").jsonPrimitive.content) }
        }
    }
    @Test fun `all shared metadata vectors preserve privacy fallback and authentication`() {
        for (valid in listOf(true, false)) for (item in vectors.getValue(if (valid) "metadataValid" else "metadataInvalid").jsonArray) {
            val obj = item.jsonObject; val metadata = obj.getValue("metadata")
            val parsed = ConferenceInvitation.decode(metadata)
            if (valid) assertNotNull(parsed, obj.getValue("id").toString()) else assertNull(parsed, obj.getValue("id").toString())
            val content = buildJsonObject {
                put("version", 1); put("kind", "text"); put("text", "DO_NOT_DISPLAY-coco://c#synthetic-capability")
                put("conference_invite", metadata)
                put("authentication", buildJsonObject { put("version", 1); put("chat_id", "chat"); put("client_message_id", "message"); put("operation", "content") })
            }
            val decoded = MessageContentCodec.decode(content.toString().toByteArray())
            assertEquals(ConferenceInvitation.FALLBACK, decoded.text, obj.getValue("id").toString())
            if (valid) assertNotNull(ConferenceInvitation.decode(decoded.conferenceInvite)) else assertNull(decoded.conferenceInvite)
            assertTrue(MessageContentCodec.authenticates(decoded, "chat", "message", "content", null))
        }
    }
    @Test fun `actual durable outbox composer preserves invitation on retry forward and ordinary text`() {
        val card = assertNotNull(ConferenceInvitation.decode(vectors.getValue("metadataValid").jsonArray.first().jsonObject.getValue("metadata")))
        val queued = MessageEntity(card.id, null, "chat", "sender", ConferenceInvitation.FALLBACK, null, "2026-10-29T00:00:00Z", "pending", conferenceInviteJson = card.stored())
        val first = composeTextMessagePayload(queued, null)
        val retry = composeTextMessagePayload(queued.copy(payloadText = queued.text, sendAttempted = true, retryCount = 4), null)
        assertContentEquals(first, retry)
        val forward = MessageContentCodec.decode(composeTextMessagePayload(queued.copy(clientMessageId = "forward", chatId = "other", forwarded = true), null))
        assertEquals(card, ConferenceInvitation.decode(forward.conferenceInvite)); assertTrue(forward.forwarded)
        assertEquals(ConferenceInvitation.FALLBACK, forward.text)
        val authenticated = MessageContentCodec.authenticate(first, "chat", card.id, "content", null)
        assertEquals(card, ConferenceInvitation.decode(MessageContentCodec.decode(authenticated).conferenceInvite))
        val ordinary = MessageContentCodec.decode(composeTextMessagePayload(queued.copy(text = "new edited", payloadText = "original frozen", conferenceInviteJson = null), null))
        assertEquals("original frozen", ordinary.text); assertNull(ordinary.conferenceInvite)
        assertFalse(card.toString().contains(card.invite))
    }
}
