package com.messenger.app.push

internal data class NotificationSessionKey(val epoch: Int, val userId: String?)

/** Shared high-water marks for displayed, foreground and deliberately suppressed events. */
internal class NotificationSequenceLedger {
    private var session: NotificationSessionKey? = null
    private val sequences = mutableMapOf<String, Long>()

    /** A stale awaiting gate must not consume anything in the next account/session. */
    @Synchronized
    fun record(event: PushEvent.NewMessage, expected: NotificationSessionKey, current: NotificationSessionKey): Boolean {
        if (expected != current || !belongsTo(event, current)) return false
        prepare(current)
        event.sequence.toLongOrNull()?.let { sequence ->
            sequences[event.chatId] = maxOf(sequence, sequences[event.chatId] ?: 0L)
        }
        return true
    }

    @Synchronized
    fun shouldPresent(event: PushEvent.NewMessage, current: NotificationSessionKey): Boolean {
        if (!belongsTo(event, current)) return false
        prepare(current)
        val sequence = event.sequence.toLongOrNull() ?: return true
        return sequences[event.chatId]?.let { sequence > it } ?: true
    }

    @Synchronized
    fun highest(chatId: String, current: NotificationSessionKey): Long? =
        if (session == current) sequences[chatId] else null

    @Synchronized
    fun clear() { session = null; sequences.clear() }

    private fun belongsTo(event: PushEvent.NewMessage, current: NotificationSessionKey): Boolean =
        current.userId != null && (event.userId == null || event.userId == current.userId)

    private fun prepare(current: NotificationSessionKey) {
        if (session != current) { sequences.clear(); session = current }
    }
}
