package com.messenger.app.data.directtransfer

import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.attachment.MessageContentDto
import java.io.File
import java.nio.ByteBuffer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DirectFileProtocolTest {
    private val json = DirectFileOffer.codec
    private fun fixture(name: String): JsonObject = json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/$name")).bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
    private fun hex(raw: String): ByteArray = ByteArray(raw.length / 2) { raw.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun independentWireKnownAnswersAndHostileFrames() {
        val fixture = fixture("direct-file-wire-v1.json")
        checkWireFixture(fixture)
    }

    @Test fun independentSampledAcknowledgmentWireVectors() = checkWireFixture(fixture("direct-file-wire-speed-v1.json"))

    private fun checkWireFixture(fixture: JsonObject) {
        for (element in fixture.getValue("valid").jsonArray) {
            val item = element.jsonObject
            val expected = item.getValue("expected").jsonObject
            val offset = expected.getValue("offset").jsonPrimitive.long
            val length = expected.getValue("length").jsonPrimitive.int
            val type = expected.getValue("type").jsonPrimitive.int
            val bytes = hex(item.getValue("wire_hex").jsonPrimitive.content)
            if (offset > DirectFileWire.MAX_FILE_BYTES) {
                assertTrue("Context must reject signed-max offset", runCatching { DirectFileWire.decode(bytes, DirectFileWire.MAX_FILE_BYTES) }.isFailure)
                continue
            }
            val fileSize = if (type in 3..4) offset else if (type == 2) maxOf(1L, offset) else maxOf(1L, offset + length)
            val frame = DirectFileWire.decode(bytes, fileSize, item.getValue("binary").jsonPrimitive.boolean)
            assertEquals(item.getValue("id").jsonPrimitive.content, offset, frame.offset)
            assertEquals(type, frame.type); assertEquals(length, frame.payload.size)
            assertArrayEquals(bytes, DirectFileWire.encode(frame, fileSize))
            assertEquals(expected.getValue("body_sha256").jsonPrimitive.content, DirectFileStorage.digest(frame.payload))
        }
        for (element in fixture.getValue("invalid").jsonArray) {
            val item = element.jsonObject
            val bytes = hex(item.getValue("wire_hex").jsonPrimitive.content)
            val fileSize = if (bytes.size >= 14) {
                val type = bytes[1].toInt() and 255
                val offset = ByteBuffer.wrap(bytes, 2, 8).long
                if (type in 3..4 && offset in 1..DirectFileWire.MAX_FILE_BYTES) offset else DirectFileWire.MAX_FILE_BYTES
            } else DirectFileWire.MAX_FILE_BYTES
            assertTrue(item.getValue("id").jsonPrimitive.content, runCatching {
                DirectFileWire.decode(bytes, fileSize, item.getValue("binary").jsonPrimitive.boolean)
            }.isFailure)
        }
    }

    @Test fun actualOfferAndControlSerializersKeepVersionInNestedChatEnvelope() {
        val fixture = fixture("direct-file-json-nonce-v1.json")
        val now = fixture.getValue("fixed_now_unix").jsonPrimitive.long
        val offer = json.decodeFromJsonElement(DirectFileOffer.serializer(), fixture.getValue("offer")).validated(now)
        val nested = json.decodeFromJsonElement(MessageContentDto.serializer(), fixture.getValue("nested_message_content"))
        val originalVersion = nested.directFile?.jsonObject?.get("version")?.jsonPrimitive?.int
        assertEquals(1, originalVersion)
        val chat = requireNotNull(nested.authentication).chatId
        val bytes = MessageContentCodec.authenticate(MessageContentCodec.encodeText(DirectFileOffer.FALLBACK, directFile = offer), chat, offer.offerClientMessageId, "content", null)
        val actualNested = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals(1, actualNested.getValue("direct_file").jsonObject.getValue("version").jsonPrimitive.int)
        val decoded = MessageContentCodec.decode(bytes)
        assertEquals(offer, DirectFileOffer.decode(decoded.directFile))
        assertTrue(MessageContentCodec.authenticates(decoded, chat, offer.offerClientMessageId, "content", null))
        val controls = fixture.getValue("controls").jsonArray.map { item ->
            val value = item.jsonObject
            val control = json.decodeFromJsonElement(DirectFileControl.serializer(), value.getValue("payload"))
            val actual = json.encodeToJsonElement(DirectFileControl.serializer(), control).jsonObject
            assertEquals(1, actual.getValue("version").jsonPrimitive.int)
            assertTrue(control.reason == null || control.reason in DIRECT_CONTROL_REASONS)
            buildJsonObject { put("id", value.getValue("id")); put("payload", actual) }
        }
        // Preserve the pre-R3 independent fixture; queued is the explicit R3 extension.
        assertEquals(fixture.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }.toSet() + "queued", DIRECT_CONTROL_REASONS)
        val export = buildJsonObject {
            put("offer", json.encodeToJsonElement(DirectFileOffer.serializer(), offer))
            put("nested_message_content", actualNested)
            put("controls", JsonArray(controls))
        }
        System.getProperty("coco.direct.export")?.let { File(it).writeText(export.toString(), Charsets.UTF_8) }
    }

    @Test fun r3QueuedCapacityAndBusyPreparationRemainDistinctValidatedReasons() {
        val fixture = fixture("direct-file-json-nonce-v1.json")
        val offer = json.decodeFromJsonElement(DirectFileOffer.serializer(), fixture.getValue("offer"))
        val senderPause = fixture.getValue("controls").jsonArray.map {
            json.decodeFromJsonElement(DirectFileControl.serializer(), it.jsonObject.getValue("payload"))
        }.first { it.type == "pause" && it.senderUserId == offer.senderUserId }
        val record = DirectTransferRecord(offer, senderPause.chatId, "receiver", registered = true,
            consent = true, peerDeviceId = senderPause.senderDeviceId)
        val envelope = DirectSignalEnvelope(clientSignalId = senderPause.clientSignalId,
            senderUserId = senderPause.senderUserId, senderDeviceId = senderPause.senderDeviceId,
            recipientDeviceId = senderPause.recipientDeviceId, messageType = 1, ciphertext = "test-boundary")
        for (reason in listOf("busy", "queued")) {
            val control = senderPause.copy(reason = reason)
            val decoded = json.decodeFromString(DirectFileControl.serializer(), json.encodeToString(DirectFileControl.serializer(), control))
            assertEquals(control, decoded)
            DirectControlPolicy.validate(record, decoded, envelope, control.recipientUserId, control.recipientDeviceId)
        }
        assertTrue(runCatching {
            val invalid = senderPause.copy(reason = "invented_queue_reason")
            DirectControlPolicy.validate(record, invalid, envelope, invalid.recipientUserId, invalid.recipientDeviceId)
        }.isFailure)
    }

    @Test fun foreignLegacyAndForwardedOffersNeverActivateTransfer() {
        val fixture = fixture("direct-file-json-nonce-v1.json")
        val content = json.decodeFromJsonElement(MessageContentDto.serializer(), fixture.getValue("nested_message_content"))
        val binding = requireNotNull(content.authentication)
        assertFalse(MessageContentCodec.authenticates(content.copy(authentication = null), binding.chatId, binding.clientMessageId, "content", null))
        assertFalse(MessageContentCodec.authenticates(content.copy(forwarded = true), binding.chatId, binding.clientMessageId, "content", null))
        assertFalse(MessageContentCodec.authenticates(content, UUID_OTHER, binding.clientMessageId, "content", null))
        assertFalse(MessageContentCodec.authenticates(content, binding.chatId, UUID_OTHER, "content", null))
        assertFalse(MessageContentCodec.authenticates(content, binding.chatId, binding.clientMessageId, "edit", null))
        assertTrue(MessageContentCodec.authenticates(MessageContentDto(kind = "text", text = "legacy"), binding.chatId, UUID_OTHER, "content", null))
    }

    @Test fun invalidNamesSizesBindingsAndVersionsRejected() {
        val fixture = fixture("direct-file-json-nonce-v1.json")
        val now = fixture.getValue("fixed_now_unix").jsonPrimitive.long
        for (entry in fixture.getValue("negative_offers").jsonArray) {
            val item = entry.jsonObject
            assertTrue(item.getValue("id").jsonPrimitive.content, runCatching {
                json.decodeFromJsonElement(DirectFileOffer.serializer(), item.getValue("payload")).validated(now)
            }.isFailure)
        }
        val valid = json.decodeFromJsonElement(DirectFileOffer.serializer(), fixture.getValue("offer"))
        for (mime in listOf("video/mp4;evil", "video//mp4", "video/*", "video/mp4 evil", "video/мп4")) assertTrue(runCatching { valid.copy(mimeType = mime).validated(now) }.isFailure)
        for (name in listOf(".", "..")) assertTrue(runCatching { valid.copy(filename = name).validated(now) }.isFailure)
    }

    @Test fun actualSwiftProducerDecodesWithoutChangingLargeOffsetsOrUnicode() {
        val fixture = fixture("direct-file-swift-nonce-v1.json")
        val offer = json.decodeFromJsonElement(DirectFileOffer.serializer(), fixture.getValue("offer"))
        assertEquals(5368709120L, offer.size)
        assertEquals("COCO original — café.mp4", offer.filename)
        for (entry in fixture.getValue("controls").jsonArray) {
            val payload = entry.jsonObject.getValue("payload")
            val control = json.decodeFromJsonElement(DirectFileControl.serializer(), payload)
            assertEquals(1, control.version)
            if (control.offset != null) assertTrue(control.offset >= 4294967296L)
        }
    }

    @Test fun possessionNonceRejectsMalformedAndWrongProofBeforeControlEffects() {
        val fixture = fixture("direct-file-json-nonce-v1.json")
        val now = fixture.getValue("fixed_now_unix").jsonPrimitive.long
        val offer = json.decodeFromJsonElement(DirectFileOffer.serializer(), fixture.getValue("offer")).validated(now)
        for (entry in fixture.getValue("negative_nonce_shapes").jsonArray) {
            val item = entry.jsonObject
            assertTrue(item.getValue("id").jsonPrimitive.content, runCatching {
                json.decodeFromJsonElement(DirectFileOffer.serializer(), item.getValue("offer")).validated(now)
            }.isFailure)
            assertTrue(item.getValue("id").jsonPrimitive.content, runCatching {
                val value = json.decodeFromJsonElement(DirectFileControl.serializer(), item.getValue("control"))
                DirectOfferNonce.bytes(value.offerNonce)
            }.isFailure)
        }
        for (entry in fixture.getValue("control_nonce_bindings").jsonArray) {
            val item = entry.jsonObject
            val value = json.decodeFromJsonElement(DirectFileControl.serializer(), item.getValue("payload"))
            val role = if (value.recipientUserId == offer.senderUserId) "sender" else "receiver"
            val record = DirectTransferRecord(offer, value.chatId, role, state = "transferring", consent = true, attemptId = value.attemptId)
            val envelope = DirectSignalEnvelope(sequence = 1, clientSignalId = value.clientSignalId, senderUserId = value.senderUserId,
                senderDeviceId = value.senderDeviceId, recipientDeviceId = value.recipientDeviceId, messageType = 1, ciphertext = "AA==")
            val result = runCatching { DirectControlPolicy.validate(record, value, envelope, value.recipientUserId, value.recipientDeviceId) }
            if (item.getValue("expected").jsonPrimitive.content.startsWith("reject")) assertTrue(item.getValue("id").jsonPrimitive.content, result.isFailure)
            else assertTrue(item.getValue("id").jsonPrimitive.content, result.isSuccess)
            // Validation receives immutable values and cannot mutate the current active state.
            assertEquals("transferring", record.state); assertTrue(record.consent); assertEquals(value.attemptId, record.attemptId)
        }
        val generated = DirectOfferNonce.create()
        assertEquals(32, DirectOfferNonce.bytes(generated).size)
        assertTrue(DirectOfferNonce.matches(generated, generated))
    }

    companion object { private const val UUID_OTHER = "01234567-89ab-4cde-8f01-23456789abcd" }
}
