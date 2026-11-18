package com.messenger.app.ui.chats

/** Main-thread scroll ownership. A target also invalidates already queued tail follows. */
internal class NotificationScrollGate(initialTarget: String?, initialRequest: String? = null) {
    private var target = initialTarget
    private var request = initialRequest
    private var pending = initialTarget != null
    var generation = 0L
        private set

    fun updateTarget(next: String?, nextRequest: String? = null) {
        if (next == target && nextRequest == request) return
        target = next; request = nextRequest; pending = next != null; generation++
    }
    fun holds(owner: Long): Boolean = pending && generation == owner
    fun complete(owner: Long) {
        if (holds(owner)) { pending = false; generation++ }
    }
    fun followTicket(): Long? = generation.takeUnless { pending }
    fun mayFollow(ticket: Long): Boolean = !pending && generation == ticket
}

internal data class ResolvedNotificationTarget(val clientId: String, val scrollOwner: Long)
