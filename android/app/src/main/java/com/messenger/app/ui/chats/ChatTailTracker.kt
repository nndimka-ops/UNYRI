package com.messenger.app.ui.chats

/** Screen-owned snapshot: revealing an existing tail after deletion is not an arrival. */
class ChatTailTracker(messageId: String?, itemKey: String?, knownIds: Set<String>) {
    private var tail = messageId to itemKey
    private var known = knownIds
    fun update(messageId: String?, itemKey: String?, ids: Set<String>): Boolean {
        val next = messageId to itemKey
        val arrived = next != tail && (
            (messageId != null && messageId != tail.first && messageId !in known) ||
            (itemKey != null && itemKey != tail.second && itemKey !in known))
        tail = next
        known = ids
        return arrived
    }
}
