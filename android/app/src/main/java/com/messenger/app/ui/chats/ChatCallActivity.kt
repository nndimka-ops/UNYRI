package com.messenger.app.ui.chats

import com.messenger.app.data.api.CallDto
import com.messenger.app.data.call.callTimestamp
import com.messenger.app.domain.model.ChatSummary
import java.time.OffsetDateTime

internal object ChatCallActivity {
    fun latestByPeer(calls: List<CallDto>, owner: String?): Map<String, CallDto> {
        if (owner == null) return emptyMap()
        // Previously this sorted every call and used the last associateBy
        // winner. Keep exactly the same winner (highest timestamp, then id)
        // in one pass and avoid O(n log n) work on every call-list update.
        val latest = HashMap<String, CallDto>()
        for (call in calls) {
            if (call.chatVisible == false || (call.callerUserId != owner && call.calleeUserId != owner)) continue
            val peer = if (call.callerUserId == owner) call.calleeUserId else call.callerUserId
            val previous = latest[peer]
            val callAt = callTimestamp(call)
            val previousAt = previous?.let(::callTimestamp) ?: Long.MIN_VALUE
            if (previous == null || callAt > previousAt || (callAt == previousAt && call.id > previous.id)) {
                latest[peer] = call
            }
        }
        return latest
    }
    fun messageTime(chat: ChatSummary): Long = chat.lastMessageAt?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    } ?: Long.MIN_VALUE
    fun callIsLatest(chat: ChatSummary, call: CallDto?): Boolean = call != null && com.messenger.app.data.call.CallActivityPolicy.belongsToChat(call, chat.chatId, chat.otherUser?.id) && callTimestamp(call) > messageTime(chat)
    fun sorted(chats: List<ChatSummary>, calls: Map<String, CallDto>): List<ChatSummary> =
        chats.sortedWith(compareByDescending<ChatSummary> { maxOf(messageTime(it), calls[it.otherUser?.id]?.takeIf { call -> com.messenger.app.data.call.CallActivityPolicy.belongsToChat(call, it.chatId, it.otherUser?.id) }?.let(::callTimestamp) ?: Long.MIN_VALUE) }.thenByDescending { it.chatId })
}
