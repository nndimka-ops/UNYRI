package com.messenger.app.ui.chats

import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.data.api.CallDto
import com.messenger.app.data.call.callTimestamp
import java.time.OffsetDateTime

/** One timestamp parse and one order shared by rendering and the motion decision. */
internal class ChatListPresentation {
    private val dates = HashMap<String, Long>()
    fun timestamp(value: String?): Long {
        if (value == null) return Long.MIN_VALUE
        return dates.getOrPut(value) {
            if (dates.size >= 2048) dates.clear()
            runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
        }
    }
    data class Order(val rows: List<ChatSummary>, val activity: Map<String, Long>)
    fun ordered(chats: List<ChatSummary>, pinned: Set<String>, archived: Set<String>, showingArchive: Boolean,
        unreadOnly: Boolean, calls: Map<String, CallDto>, leaving: Set<String>): Order {
        val activity = chats.associate { chat ->
            val call = calls[chat.otherUser?.id]?.takeIf {
                com.messenger.app.data.call.CallActivityPolicy.belongsToChat(it, chat.chatId, chat.otherUser?.id)
            }
            chat.chatId to maxOf(timestamp(chat.lastMessageAt), call?.let(::callTimestamp) ?: Long.MIN_VALUE)
        }
        val rows = chats.filter {
            it.chatId !in leaving && ChatArchiveVisibility.includes(it.chatId, it.type, archived, showingArchive) &&
                (!unreadOnly || it.unreadCount > 0)
        }.sortedWith(compareByDescending<ChatSummary> { it.chatId in pinned }
            .thenByDescending { activity[it.chatId] }.thenByDescending { it.chatId })
        return Order(rows, activity)
    }
}

/** Non-observable timing book: no idle timer and no clock-driven recomposition. */
internal class ChatListMotionClock {
    var liveSince = Long.MAX_VALUE
    var returnedAt = Long.MIN_VALUE
    var lastRows = emptyList<ChatListLiftPolicy.Row>()
    var lastFilter = ""
    var lastChats = emptyList<ChatSummary>()
    var lastPinned = emptySet<String>()
    private val sweeps = HashMap<String, Long>()
    private val consumed = HashSet<Long>()
    fun stamp(live: Boolean, now: Long) { liveSince = if (live) now else Long.MAX_VALUE }
    fun live(now: Long): Boolean = liveSince != Long.MAX_VALUE && now - liveSince >= 1000 &&
        (returnedAt == Long.MIN_VALUE || now - returnedAt >= 550)
    fun allowSweep(id: String, now: Long): Boolean {
        if (sweeps[id]?.let { now - it < 2500 } == true) return false
        sweeps[id] = now
        if (sweeps.size > 64) sweeps.entries.removeAll { now - it.value >= 2500 }
        return true
    }
    fun consume(token: Long): Boolean {
        if (consumed.size > 256) consumed.clear()
        return consumed.add(token)
    }
}

internal data class ChatRowMotionSignal(val token: Long, val chatId: String, val lift: Boolean = false,
    val sweep: Boolean = false, val glow: Boolean = false)
