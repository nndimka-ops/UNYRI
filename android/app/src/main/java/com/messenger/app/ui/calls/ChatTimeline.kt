package com.messenger.app.ui.calls

import com.messenger.app.data.api.CallDto
import com.messenger.app.data.call.callTimestamp
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import java.time.OffsetDateTime

data class ChatTimelineItem(val message: Message? = null, val call: CallDto? = null, val albumMembers: List<Message> = emptyList(), val albumGroupKey: String? = null) {
    val messages: List<Message> get() = albumMembers.ifEmpty { listOfNotNull(message) }
    val key: String get() = albumGroupKey ?: message?.clientMessageId ?: "call:${requireNotNull(call).id}"
}

/** A media reservation uses the device clock. Once a standalone send has a
 * complete server ACK, that clock must not put it before an earlier server
 * message. Keep the reservation itself for geometry, pending work and albums;
 * album placement/membership intentionally remains stable across partial ACKs. */
private fun Message.hasCanonicalStandalonePlacement(): Boolean =
    albumId.isNullOrBlank() && !isDeletedForEveryone && !serverId.isNullOrBlank() &&
        (sequence ?: 0L) > 0L && when (status) {
            MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ -> true
            else -> false
        }

private fun timelineEventDate(message: Message): String =
    if (message.hasCanonicalStandalonePlacement()) message.createdAt
    else message.localMediaPresentation?.createdAt ?: message.createdAt

private fun timelineOrderIndex(message: Message): Long =
    if (message.hasCanonicalStandalonePlacement()) requireNotNull(message.sequence)
    else message.localMediaPresentation?.index?.toLong() ?: message.sequence ?: Long.MAX_VALUE

/** Merge by event time, independently of restore/import order; stable IDs break ties. */
fun chatTimeline(messages: List<Message>, calls: List<CallDto>): List<ChatTimelineItem> = groupAlbumRows(buildList {
    val sortedCalls = calls.distinctBy { it.id }.sortedWith(compareBy<CallDto> { callTimestamp(it) }.thenBy { it.id })
    var index = 0
    val ordered = messages.map { it to runCatching { OffsetDateTime.parse(timelineEventDate(it)).toInstant().toEpochMilli() }.getOrDefault(Long.MAX_VALUE) }
        .sortedWith(compareBy<Pair<Message, Long>> { it.second }
            .thenBy { timelineOrderIndex(it.first) }.thenBy { it.first.clientMessageId })
    for ((message, time) in ordered) {
        while (index < sortedCalls.size && callTimestamp(sortedCalls[index]) <= time) add(ChatTimelineItem(call = sortedCalls[index++]))
        add(ChatTimelineItem(message = message))
    }
    while (index < sortedCalls.size) add(ChatTimelineItem(call = sortedCalls[index++]))
})

/** One send batch is one timeline item. Interleaved text/calls must not split
 * an uploading album into separate messages. Corrupt/deleted members stay out. */
private fun groupAlbumRows(source: List<ChatTimelineItem>): List<ChatTimelineItem> = buildList {
    val groups = mutableMapOf<Triple<String, String, String>, Int>()
    val parts = mutableMapOf<Triple<String, String, String>, Int>()
    for (item in source) {
        val current = item.message
        if (current == null) { add(item); continue }
        val key = current.albumId?.takeIf { it.isNotBlank() }?.let { Triple(current.chatId, current.senderId, it) }
        if (key == null) { add(item); continue }
        if (current.isDeletedForEveryone || current.status == com.messenger.app.domain.model.MessageStatus.DECRYPT_FAILED ||
            current.status == com.messenger.app.domain.model.MessageStatus.IDENTITY_CHANGED) {
            groups.remove(key); add(item); continue
        }
        val index = groups[key]
        if (index != null && get(index).messages.size < 10) {
            val previous = get(index)
            set(index, previous.copy(albumMembers = previous.messages + current))
        } else {
            val part = parts.getOrDefault(key,0);parts[key]=part+1;groups[key]=size
            add(item.copy(albumGroupKey="album:${key.first}:${key.second}:${key.third}:$part"))
        }
    }
}

/** Screen-owned cache: receipts, edits and reactions refresh values, not row placement. */
class ChatTimelineCache {
    private data class Placement(
        val id: String, val date: String, val sender: String, val album: String?, val orderIndex: Long,
        val deleted: Boolean, val unavailable: Boolean,
    )
    private var placement: List<Placement>? = null
    private var calls: List<CallDto> = emptyList()
    private var rows: List<ChatTimelineItem> = emptyList()

    fun update(messages: List<Message>, calls: List<CallDto>): List<ChatTimelineItem> {
        val next = messages.map { Placement(it.clientMessageId, timelineEventDate(it), it.senderId, it.albumId, timelineOrderIndex(it),
            it.isDeletedForEveryone, it.status == com.messenger.app.domain.model.MessageStatus.DECRYPT_FAILED ||
                it.status == com.messenger.app.domain.model.MessageStatus.IDENTITY_CHANGED) }
        if (placement != next || this.calls != calls) {
            rows = chatTimeline(messages, calls)
            placement = next
            this.calls = calls.toList()
        } else {
            val latest = messages.associateBy { it.clientMessageId }
            rows = rows.map { row ->
                val message = row.message?.let { latest.getValue(it.clientMessageId) }
                val members = row.albumMembers.map { latest.getValue(it.clientMessageId) }
                if (message == row.message && members == row.albumMembers) row
                else row.copy(message = message, albumMembers = members)
            }
        }
        return rows
    }
}
