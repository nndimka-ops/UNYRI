package com.messenger.app.push

/** Choose once at arrival, before any suspend. Background socket copies own no alert. */
class MessageAlertOwnership(private val capacity: Int = 1024) {
    private var session: NotificationBatchSession? = null
    private val claimed = LinkedHashSet<String>()
    @Synchronized fun claim(id: String, current: NotificationBatchSession): Boolean {
        if (session != current) { claimed.clear(); session = current }
        if (!claimed.add(id)) return false
        while (claimed.size > capacity) claimed.remove(claimed.first())
        return true
    }
}
