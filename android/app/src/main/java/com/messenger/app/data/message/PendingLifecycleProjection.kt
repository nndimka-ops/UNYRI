package com.messenger.app.data.message

import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.attachment.MessageContentDto
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.local.message.ReactionEntity

/** Local intent overlays never mutate the confirmed sequence or backup data. */
internal fun projectPendingLifecycle(
    rows: List<MessageEntity>, reactions: List<ReactionEntity>,
): Pair<List<MessageEntity>, List<ReactionEntity>> {
    val pending = rows.filter { it.kind != "content" && it.status == "pending" && !it.hiddenLocally }
        .sortedBy { it.createdAt }
    if (pending.isEmpty()) return rows.filter { it.kind == "content" } to reactions
    val messages = rows.filter { it.kind == "content" }.associateBy { it.clientMessageId }.toMutableMap()
    val clients = messages.values.mapNotNull { row -> row.serverId?.let { it to row.clientMessageId } }.toMap()
    val projectedReactions = reactions.associateBy { it.targetMessageId to it.userId }.toMutableMap()
    for (event in pending) {
        val id = event.targetMessageId ?: continue
        val client = clients[id] ?: continue
        val target = messages[client] ?: continue
        if (event.chatId != target.chatId || (event.kind != "reaction" && event.senderId != target.senderId)) continue
        val content = MessageContentCodec.decode(event.text.toByteArray(Charsets.UTF_8))
        when (event.kind) {
            "edit" -> messages[client] = target.copy(text = content.text.orEmpty(), isEdited = true, conferenceInviteJson = null)
            "delete" -> { messages.remove(client); projectedReactions.keys.removeAll { it.first == id } }
            "reaction" -> {
                val key = id to event.senderId
                if (content.reactionAction == MessageContentDto.REACTION_ACTION_REMOVE) projectedReactions.remove(key)
                else content.reaction?.let { projectedReactions[key] = ReactionEntity(id, event.senderId, it, 0) }
            }
        }
    }
    return messages.values.toList() to projectedReactions.values.toList()
}
