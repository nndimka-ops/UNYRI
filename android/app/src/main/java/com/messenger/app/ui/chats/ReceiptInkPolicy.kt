package com.messenger.app.ui.chats

import com.messenger.app.domain.model.MessageStatus

/** One policy for the canonical renderer, including its fixed placeholder geometry. */
internal object ReceiptInkPolicy {
    const val mountGateMillis = 800L
    const val resumeSettleMillis = 800L
    const val batchWindowMillis = 80L
    const val maxBatch = 3
    const val doubleTick = "✓✓"
    fun rank(status: MessageStatus): Int? = when (status) {
        MessageStatus.PENDING -> 0
        MessageStatus.SENT -> 1
        MessageStatus.DELIVERED -> 2
        MessageStatus.READ -> 3
        else -> null
    }
    fun animates(from: MessageStatus, to: MessageStatus, mountedForMillis: Long, appActive: Boolean,
        calm: Boolean, watchedSending: Boolean = false): Boolean {
        val old = rank(from) ?: return false
        val next = rank(to) ?: return false
        return !calm && appActive && next > old &&
            (from == MessageStatus.PENDING || watchedSending || mountedForMillis >= mountGateMillis)
    }
    fun batchAnimates(count: Int): Boolean = count in 1..maxBatch
    fun reveal(status: MessageStatus): Float = when (status) {
        MessageStatus.SENT -> .5f
        MessageStatus.DELIVERED, MessageStatus.READ -> 1f
        else -> 0f
    }
}
