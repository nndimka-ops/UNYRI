package com.messenger.app.data.call

import com.messenger.app.data.api.CallDto

internal object CallActivityPolicy {
    fun eligible(call: CallDto, owner: String): Boolean = call.chatVisible == true && !call.chatId.isNullOrBlank() &&
        (call.callerUserId == owner || call.calleeUserId == owner)
    fun belongsToChat(call: CallDto, chatId: String, peer: String?): Boolean = call.chatVisible != false &&
        if (call.chatId != null) call.chatId == chatId else call.chatVisible == null && peer != null && (call.callerUserId == peer || call.calleeUserId == peer)
    fun applyClears(calls: List<CallDto>, cutoffs: Map<String, Long>): List<CallDto> = calls.map { call ->
        val cutoff = cutoffs[call.chatId]
        if (cutoff != null && callTimestamp(call) <= cutoff) call.copy(chatVisible = false, unread = false) else call
    }
    fun suppressPending(calls: List<CallDto>, pending: Set<String>): List<CallDto> = calls.map { if (it.id in pending) it.copy(unread = false) else it }
}
