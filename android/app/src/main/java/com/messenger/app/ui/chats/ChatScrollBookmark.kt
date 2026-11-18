package com.messenger.app.ui.chats

/** A stable item identity survives messages/call entries being inserted above it. */
data class ChatScrollBookmark(val key: String?, val offset: Int, val followLatest: Boolean) {
    fun resolve(keys: List<String>): Pair<Int, Int>? {
        if (keys.isEmpty()) return null
        if (followLatest) return keys.lastIndex to 0
        val index = keys.indexOf(key)
        return if (index >= 0) index to offset else keys.lastIndex to 0
    }
}
