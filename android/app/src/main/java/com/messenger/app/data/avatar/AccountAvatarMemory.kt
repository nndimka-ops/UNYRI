package com.messenger.app.data.avatar

/** Synchronous memory-only first render; disk/decode runs only on a worker miss. */
internal class AccountAvatarMemory<Image : Any> {
    private data class Key(val slot: String, val owner: String, val revision: String)
    private val lock = Any()
    private val loadLock = Any()
    private var generation = 0L
    private val images = LinkedHashMap<Key, Image>()

    fun peek(slot: String, owner: String): Image? = synchronized(lock) {
        images.entries.lastOrNull { it.key.slot == slot && it.key.owner == owner }?.value
    }

    fun clear() = synchronized(lock) { generation++; images.clear() }

    fun load(slot: String, owner: String, read: () -> AccountAvatarStore.Snapshot?, decode: (ByteArray) -> Image?): Image? {
        peek(slot, owner)?.let { return it }
        // This lock never participates in peek/clear or the store's I/O lock.
        return synchronized(loadLock) {
            peek(slot, owner)?.let { return it }
            val captured = synchronized(lock) { generation }
            val snapshot = read()?.takeIf { it.owner == owner } ?: return null
            val image = decode(snapshot.jpeg) ?: return null
            synchronized(lock) {
                if (captured != generation) return null
                images.keys.removeAll { it.slot == slot && it.owner == owner }
                images[Key(slot, owner, snapshot.revision)] = image
                if (images.size > 4) images.remove(images.keys.first())
                image
            }
        }
    }
}
