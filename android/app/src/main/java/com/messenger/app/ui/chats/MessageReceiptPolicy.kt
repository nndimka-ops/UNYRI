package com.messenger.app.ui.chats

import androidx.annotation.StringRes
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.messenger.app.R
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus

/** Status and identity are independent of which metadata layout hosts the receipt. */
internal object MessageReceiptPolicy {
    fun messageLiveId(message: Message): String = "message:${message.chatId}:${message.clientMessageId}"

    // A growing/reordered album must not remount its ink when the last member changes.
    fun albumLiveId(message: Message): String = message.albumId?.let {
        "album:${message.chatId}:${message.senderId}:$it"
    } ?: messageLiveId(message)

    fun albumStatus(messages: List<Message>): MessageStatus {
        require(messages.isNotEmpty())
        return messages.minBy { member -> when (member.status) {
            MessageStatus.FAILED -> -3
            MessageStatus.IDENTITY_CHANGED -> -2
            MessageStatus.DECRYPT_FAILED -> -1
            else -> requireNotNull(ReceiptInkPolicy.rank(member.status))
        } }.status
    }

    fun glyph(status: MessageStatus): String = when (status) {
        MessageStatus.PENDING -> "◷"
        MessageStatus.SENT -> "✓"
        MessageStatus.DELIVERED, MessageStatus.READ -> ReceiptInkPolicy.doubleTick
        MessageStatus.FAILED, MessageStatus.DECRYPT_FAILED, MessageStatus.IDENTITY_CHANGED -> "!"
    }

    @StringRes fun label(status: MessageStatus): Int = when (status) {
        MessageStatus.PENDING -> R.string.msg_status_sending
        MessageStatus.SENT -> R.string.msg_status_sent
        MessageStatus.DELIVERED -> R.string.msg_status_delivered
        MessageStatus.READ -> R.string.msg_status_read
        MessageStatus.FAILED -> R.string.msg_status_failed
        MessageStatus.DECRYPT_FAILED -> R.string.msg_status_decrypt_failed
        MessageStatus.IDENTITY_CHANGED -> R.string.msg_status_identity_changed
    }

    /** The invisible width reservation must not become a second spoken receipt. */
    fun inlineAccessibleText(text: AnnotatedString, receiptOffset: Int?): AnnotatedString =
        if (receiptOffset != null && receiptOffset >= 0 &&
            text.text.regionMatches(receiptOffset, ReceiptInkPolicy.doubleTick, 0, ReceiptInkPolicy.doubleTick.length)) {
            val exposed = buildAnnotatedString {
                append(text.subSequence(0, receiptOffset))
                append(text.subSequence(receiptOffset + ReceiptInkPolicy.doubleTick.length, text.length))
            }
            exposed.subSequence(0, exposed.text.trimEnd().length)
        } else text
}
