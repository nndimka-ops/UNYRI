package com.messenger.app.ui.chats

/** An existing calls-only layout can precede the Room message snapshot by one or more frames. */
internal object NotificationTargetLayout {
    fun current(keys: List<String>, measuredCount: Int, visible: List<Pair<Int, Any>>, viewportHeight: Int): Boolean =
        viewportHeight > 0 && measuredCount == keys.size && visible.isNotEmpty() &&
            visible.all { (index, key) -> keys.getOrNull(index) == key }
}
