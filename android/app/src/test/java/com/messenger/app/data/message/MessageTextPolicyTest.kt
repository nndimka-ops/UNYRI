package com.messenger.app.data.message

import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.attachment.MessageContentDto
import org.junit.Assert.*
import org.junit.Test

/** Client resource and literal-text contracts. No Android UI, transport or Unicode-table replica. */
class MessageTextPolicyTest {
    private fun rejected(block: () -> Unit) =
        assertThrows(MessageTextPolicyException::class.java) { block() }

    private fun authenticatedReaction(payload: ByteArray): MessageContentDto = MessageContentCodec.decode(
        MessageContentCodec.authenticate(payload, "synthetic-chat", "synthetic-event", "reaction",
            "synthetic-target-server", "synthetic-target-client"))

    @Test fun ordinaryAdmissionTrimsOnlyEdgesAndFrozenRetryPreservesOriginalText() {
        val expected = "Привет  мир\r\n\t👩🏽‍💻 — שלום دا\u200Cخل ❤️"
        val raw = "\u200B \u00A0\r\n" + expected + "\t\u2002 \u2060"
        assertEquals(expected, MessageTextPolicy.outgoing(raw))
        assertEquals(expected, MessageTextPolicy.caption(raw))
        val frozen = " \r\n" + expected + "\t "
        MessageTextPolicy.retry(frozen)
        assertEquals(frozen, MessageContentCodec.decode(MessageContentCodec.encodeText(frozen)).text)
    }

    @Test fun invisibleOrMalformedNewTextIsRejectedWithoutMakingAnOptionalCaptionMandatory() {
        for (raw in listOf("", " \t\r\n\u00A0 ", "\u200B\u2060", "A\u0000B", "\uD800x", "x\uDC00")) {
            rejected { MessageTextPolicy.outgoing(raw) }
        }
        for (raw in listOf("A\u0000B", "\uD800x", "x\uDC00")) {
            rejected { MessageTextPolicy.received(raw) }
            rejected { MessageTextPolicy.caption(raw) }
        }
        assertNull(MessageTextPolicy.caption(null))
        assertNull(MessageTextPolicy.caption(" \t\r\n\u00A0 "))
        MessageTextPolicy.retry(null, optional = true)
        rejected { MessageTextPolicy.retry(null) }
    }

    @Test fun admissionBudgetsRejectExcessUtf8AndCaptionScalars() {
        val inside = "界".repeat(21845) // 65,535 UTF-8 bytes, below 64 KiB.
        assertEquals(inside, MessageTextPolicy.outgoing(inside))
        rejected { MessageTextPolicy.outgoing(inside + "界") }
        assertEquals("a".repeat(4096), MessageTextPolicy.caption("a".repeat(4096)))
        rejected { MessageTextPolicy.caption("a".repeat(4097)) }
    }

    @Test fun freshPlaintextRejectsMalformedUtf8RatherThanReplacingItsBytes() {
        val valid = "Literal 👩🏽‍💻\r\nשלום دا\u200Cخل".toByteArray(Charsets.UTF_8)
        val original = valid.copyOf()
        MessageTextPolicy.requireFreshPlaintext(valid)
        assertArrayEquals(original, valid)
        for (bytes in listOf(
            byteArrayOf(0xC3.toByte(), 0x28), // Invalid continuation.
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()), // Overlong ASCII.
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), // UTF-16 surrogate.
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // Above U+10FFFF.
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x92.toByte()), // Truncated scalar.
        )) rejected { MessageTextPolicy.requireFreshPlaintext(bytes) }
    }

    @Test fun plaintextPreflightCountsStructureOutsideStringsAndPastWhitespaceBom() {
        val literal = "Literal " + "[".repeat(100) + " \\\" { } \\\\ " + "]".repeat(100)
        val encoded = MessageContentCodec.encodeText(literal)
        MessageTextPolicy.requireFreshPlaintext(encoded)
        assertEquals(literal, MessageContentCodec.decode(encoded).text)
        val prefix = byteArrayOf(32, 9, 0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 13, 10)
        val depth64 = ("[".repeat(64) + "0" + "]".repeat(64)).toByteArray()
        val depth65 = ("[".repeat(65) + "0" + "]".repeat(65)).toByteArray()
        MessageTextPolicy.requireFreshPlaintext(prefix + depth64)
        rejected { MessageTextPolicy.requireFreshPlaintext(prefix + depth65) }
        // This is a byte/depth preflight, not a claim to parse arbitrary JSON syntax.
        assertTrue(MessageTextPolicy.allowsPlaintext(ByteArray(1_048_576) { 'a'.code.toByte() }))
        assertFalse(MessageTextPolicy.allowsPlaintext(ByteArray(1_048_577) { 'a'.code.toByte() }))
    }

    @Test fun aReactionCannotBorrowTheMuchLargerReceivedBodyBudget() {
        val huge = "x".repeat(131072) // The previously accepted authenticated reaction counterexample.
        MessageTextPolicy.received(huge)
        rejected { MessageTextPolicy.receivedReaction(huge) }
        assertFalse(MessageTextPolicy.isDisplayableReaction(huge))
        val payload = MessageContentCodec.encodeReaction(huge)
        val decoded = MessageContentCodec.decode(payload)
        assertEquals(huge, decoded.reaction)
        rejected { MessageContentCodec.validateReceivedText(decoded) }
        rejected { authenticatedReaction(payload) }
        assertEquals(huge, decoded.reaction) // Derived rejection must not rewrite retained raw data.
    }

    @Test fun compactUnicodeAndLegacyNamedReactionsAndNullRemovalRemainCompatible() {
        for (label in listOf("👨‍👩‍👧‍👦", "👍🏽", "e\u0301", "heart", "😀".repeat(64))) {
            MessageTextPolicy.receivedReaction(label)
            assertTrue(MessageTextPolicy.isDisplayableReaction(label))
            val authenticated = authenticatedReaction(MessageContentCodec.encodeReaction(label))
            MessageContentCodec.validateReceivedText(authenticated)
            assertEquals(label, authenticated.reaction)
            assertEquals(MessageContentDto.REACTION_ACTION_SET, authenticated.reactionAction)
        }
        rejected { MessageTextPolicy.receivedReaction("😀".repeat(65)) }
        rejected { MessageTextPolicy.receivedReaction("a\nb") }
        MessageTextPolicy.receivedReaction(null)
        assertFalse(MessageTextPolicy.isDisplayableReaction(null))
        val removal = authenticatedReaction(MessageContentCodec.encodeReactionRemoval())
        MessageContentCodec.validateReceivedText(removal)
        assertNull(removal.reaction)
        assertEquals(MessageContentDto.REACTION_ACTION_REMOVE, removal.reactionAction)
    }

    @Test fun boundedPresentationIsTypedAndCodecRetainsTheOriginal() {
        val raw = "Начало 👩🏽‍💻 שלום ".repeat(900) + "END"
        val body = MessageTextPolicy.presentationResult(raw)
        val inline = MessageTextPolicy.presentationResult(raw, inline = true)
        for (result in listOf(body, inline)) {
            assertEquals(MessageTextPolicy.PresentationKind.TEXT, result.kind)
            assertTrue(result.truncated)
            assertNotEquals(raw, result.text)
        }
        assertTrue(body.text.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
        assertTrue(body.text.codePointCount(0, body.text.length) <= 8192)
        assertTrue(inline.text.toByteArray(Charsets.UTF_8).size <= 2048)
        assertTrue(inline.text.codePointCount(0, inline.text.length) <= 512)
        assertEquals(raw, MessageContentCodec.decode(MessageContentCodec.encodeText(raw)).text)
        for (literal in listOf("[Empty message]", "[Unsupported message]")) {
            val result = MessageTextPolicy.presentationResult(literal)
            assertEquals(MessageTextPolicy.PresentationKind.TEXT, result.kind)
            assertEquals(literal, result.text)
            assertFalse(result.truncated)
        }
        assertEquals(MessageTextPolicy.PresentationKind.EMPTY, MessageTextPolicy.presentationResult(" \t\r\n").kind)
        assertEquals(MessageTextPolicy.PresentationKind.INVALID, MessageTextPolicy.presentationResult("A\u0000B").kind)
    }
}
