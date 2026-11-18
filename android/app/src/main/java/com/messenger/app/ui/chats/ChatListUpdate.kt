package com.messenger.app.ui.chats

import com.messenger.app.domain.model.ChatSummary

internal data class ChatListSnapshot(val order: ChatListPresentation.Order, val pins: Set<String>,
    val filter: String, val source: List<ChatSummary>)

/** Pin state, displayed order and motion are installed in one Compose state write. */
internal data class ChatListFrame(val snapshot: ChatListSnapshot, val decision: ChatListLiftPolicy.Decision, val revision: Long) {
    fun signals(): List<ChatRowMotionSignal> = buildList {
        decision.lifted?.let { add(ChatRowMotionSignal(revision * 2, it, lift = true, sweep = decision.swept == it)) }
        decision.swept?.takeIf { it != decision.lifted }?.let { add(ChatRowMotionSignal(revision * 2 + 1, it, sweep = true)) }
    }
}

internal class ChatListUpdates(private val clock: ChatListMotionClock) {
    private var revision = 0L
    fun apply(snapshot: ChatListSnapshot, live: Boolean, now: Long): ChatListFrame {
        val order = snapshot.order
        val rows = order.rows.map { ChatListLiftPolicy.Row(it.chatId, order.activity[it.chatId] ?: Long.MIN_VALUE, it.unreadCount, it.isMuted) }
        val prior = if (clock.lastFilter == snapshot.filter) clock.lastRows else emptyList()
        val decision = if (clock.lastPinned != snapshot.pins && clock.lastRows.isNotEmpty()) ChatListLiftPolicy.Decision(animateOrder = true)
            else ChatListLiftPolicy.decide(prior, rows, live && clock.live(now) && snapshot.source != clock.lastChats) { clock.allowSweep(it, now) }
        clock.lastRows = rows; clock.lastFilter = snapshot.filter; clock.lastChats = snapshot.source; clock.lastPinned = snapshot.pins
        return ChatListFrame(snapshot, decision, ++revision)
    }
}
