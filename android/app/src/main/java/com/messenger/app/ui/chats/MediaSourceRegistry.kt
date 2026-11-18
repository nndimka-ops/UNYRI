package com.messenger.app.ui.chats

/** Source attachment/geometry is independent of decode/work permission under a cover. */
internal class MediaSourceRegistry<T>(private val valid: (T) -> Boolean, private val capacity: Int = 128) {
    private data class Entry<T>(val owner: Any, val value: T)
    private val entries = LinkedHashMap<String, Entry<T>>()
    fun put(path: String, owner: Any, value: T) { entries[path] = Entry(owner, value); while (entries.size > capacity) entries.remove(entries.keys.first()) }
    fun remove(path: String, owner: Any) { if (entries[path]?.owner === owner) entries.remove(path) }
    fun get(path: String): T? = entries[path]?.value?.takeIf(valid)
}
