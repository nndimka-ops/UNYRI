package com.messenger.app.ui.chats

import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.ui.calls.ChatTimelineItem

/** Actions use the displayed batch, never a server lookup or an album-id-only search. */
internal fun chatMessageActionGroup(timeline: List<ChatTimelineItem>, anchor: Message): List<Message> {
    val album = anchor.albumId?.takeIf { it.isNotBlank() } ?: return listOf(anchor)
    if (anchor.isDeletedForEveryone || anchor.status == MessageStatus.DECRYPT_FAILED || anchor.status == MessageStatus.IDENTITY_CHANGED) return listOf(anchor)
    val row = timeline.firstOrNull { item -> item.messages.any { it.clientMessageId == anchor.clientMessageId } }
        ?: return listOf(anchor)
    return row.messages.filter { member ->
        member.chatId == anchor.chatId && member.senderId == anchor.senderId && member.albumId == album &&
            !member.isDeletedForEveryone && member.status != MessageStatus.DECRYPT_FAILED && member.status != MessageStatus.IDENTITY_CHANGED
    }.take(10).takeIf { members -> members.any { it.clientMessageId == anchor.clientMessageId } } ?: listOf(anchor)
}

/** Capture displayed pending/failed IDs at the tap; no album-wide future lookup. */
internal fun chatUnsentMessageActionIds(timeline: List<ChatTimelineItem>, anchor: Message, owner: String?): List<String> {
    if (owner == null || anchor.senderId != owner) return emptyList()
    return chatMessageActionGroup(timeline, anchor).filter {
        it.senderId == owner && it.serverId == null &&
            it.status in setOf(MessageStatus.PENDING, MessageStatus.FAILED) && !it.isDeletedForEveryone
    }.map { it.clientMessageId }.distinct()
}

internal fun toggleChatMessageGroup(selected: Set<String>, members: List<Message>): Set<String> {
    val ids = members.map { it.clientMessageId }.toSet()
    return if (ids.all { it in selected }) selected - ids else selected + ids
}

/** A forwarded batch is an independent new encrypted album in the destination. */
internal fun chatForwardingAlbums(
    timeline: List<ChatTimelineItem>, selected: Set<String>, makeId: () -> String = { java.util.UUID.randomUUID().toString() },
): Map<String, String> = buildMap {
    for (row in timeline) {
        val anchor = row.messages.firstOrNull() ?: continue
        val members = chatMessageActionGroup(listOf(row), anchor).filter { it.clientMessageId in selected }
        if (members.size < 2 || anchor.albumId.isNullOrBlank()) continue
        val fresh = makeId()
        for (member in members) put(member.clientMessageId, fresh)
    }
}
