package com.messenger.app.ui.chats

import com.messenger.app.R
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.domain.model.MessageStatus.*
import kotlin.test.*

class MessageReceiptPolicyTest {
    private fun message(id: String, status: MessageStatus = SENT) =
        Message(id, null, "chat", "owner", "text", null, "", status, albumId = "album")

    @Test fun `message identity survives server acknowledgement and status changes`() {
        val pending = message("first", PENDING)
        assertEquals(MessageReceiptPolicy.messageLiveId(pending),
            MessageReceiptPolicy.messageLiveId(pending.copy(serverId = "server", sequence = 7, status = READ)))
        assertNotEquals(MessageReceiptPolicy.messageLiveId(pending), MessageReceiptPolicy.messageLiveId(message("second")))
        assertNotEquals(MessageReceiptPolicy.messageLiveId(pending), MessageReceiptPolicy.messageLiveId(pending.copy(chatId = "other")))
    }

    @Test fun `album identity survives growth reordering and removal of first member`() {
        val first = message("first"); val last = message("last", READ)
        val original = MessageReceiptPolicy.albumLiveId(first)
        for (members in listOf(listOf(first), listOf(first, last), listOf(last, first), listOf(last))) {
            assertEquals(original, MessageReceiptPolicy.albumLiveId(members.first()))
        }
        assertNotEquals(original, MessageReceiptPolicy.albumLiveId(first.copy(albumId = "other")))
        assertNotEquals(original, MessageReceiptPolicy.albumLiveId(first.copy(senderId = "other")))
        assertNotEquals(original, MessageReceiptPolicy.albumLiveId(first.copy(chatId = "other")))
        assertEquals(MessageReceiptPolicy.messageLiveId(first), MessageReceiptPolicy.albumLiveId(first.copy(albumId = null)))
    }

    @Test fun `album presents the least advanced member and never reads an error as success`() {
        for (status in MessageStatus.entries) {
            val members = listOf(message("read", READ), message("member", status))
            assertEquals(status, MessageReceiptPolicy.albumStatus(members))
            assertEquals(status, MessageReceiptPolicy.albumStatus(members.reversed()))
        }
        assertEquals(SENT, MessageReceiptPolicy.albumStatus(listOf(message("sent", SENT), message("delivered", DELIVERED))))
        assertEquals(PENDING, MessageReceiptPolicy.albumStatus(listOf(message("pending", PENDING), message("sent", SENT))))
        assertEquals(FAILED, MessageReceiptPolicy.albumStatus(listOf(message("pending", PENDING), message("failed", FAILED))))
        assertFailsWith<IllegalArgumentException> { MessageReceiptPolicy.albumStatus(emptyList()) }
    }

    @Test fun `all status semantics have their own localized TalkBack label`() {
        val labels = mapOf(PENDING to R.string.msg_status_sending, SENT to R.string.msg_status_sent,
            DELIVERED to R.string.msg_status_delivered, READ to R.string.msg_status_read,
            FAILED to R.string.msg_status_failed, DECRYPT_FAILED to R.string.msg_status_decrypt_failed,
            IDENTITY_CHANGED to R.string.msg_status_identity_changed)
        assertEquals(MessageStatus.entries.toSet(), labels.keys)
        labels.forEach { (status, label) -> assertEquals(label, MessageReceiptPolicy.label(status)) }
        assertEquals(labels.size, labels.values.toSet().size)
        assertEquals(listOf("◷", "✓", "✓✓", "✓✓", "!", "!", "!"), MessageStatus.entries.map(MessageReceiptPolicy::glyph))
    }

    @Test fun `inline semantics omit only the reserved receipt run`() {
        val body = "body ✓✓ 12:34  "
        assertEquals(AnnotatedString(body.trimEnd()), MessageReceiptPolicy.inlineAccessibleText(AnnotatedString(body + ReceiptInkPolicy.doubleTick), body.length))
        for (offset in listOf(null, -1, body.length, 0)) {
            assertEquals(AnnotatedString(body), MessageReceiptPolicy.inlineAccessibleText(AnnotatedString(body), offset))
        }
    }

    @Test fun `inline semantics preserve accessible links when removing the placeholder`() {
        val body = "https://example.com 12:34"
        val annotated = buildAnnotatedString {
            append(body); append("  "); append(ReceiptInkPolicy.doubleTick)
            addLink(LinkAnnotation.Url("https://example.com"), 0, 19)
        }
        assertEquals(annotated.subSequence(0, body.length),
            MessageReceiptPolicy.inlineAccessibleText(annotated, body.length + 2))
    }

    @Test fun `fixed ink reveal covers every state without treating errors as delivered`() {
        for (status in MessageStatus.entries) assertEquals(when (status) {
            SENT -> .5f; DELIVERED, READ -> 1f; else -> 0f
        }, ReceiptInkPolicy.reveal(status))
    }
}
