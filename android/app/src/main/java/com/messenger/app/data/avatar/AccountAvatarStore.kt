package com.messenger.app.data.avatar

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Device-owned, slot-keyed own-avatar bytes. Disk methods belong on an I/O worker. */
class AccountAvatarStore(private val root: File) {
    @Volatile var onChange: () -> Unit = {}
    data class Ticket internal constructor(val slot: String, val owner: String, val revision: String, internal val generation: UUID)
    data class Snapshot(val owner: String, val revision: String, val jpeg: ByteArray)
    private val tickets = mutableMapOf<String, Ticket>()
    private val snapshots = object : LinkedHashMap<String, Snapshot?>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot?>?) = size > 4
    }
    private val slotPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private fun file(slot: String): File {
        require(slot == "legacy" || slotPattern.matches(slot))
        return File(File(root, slot), "thumbnail.bin")
    }

    @Synchronized fun begin(slot: String, owner: String, revision: String): Ticket {
        file(slot)
        require(owner.contains('|') && owner.substringAfterLast('|').isNotBlank())
        return Ticket(slot, owner, revision, UUID.randomUUID()).also { tickets[slot] = it }
    }

    @Synchronized fun read(slot: String, owner: String): Snapshot? {
        file(slot)
        if (!snapshots.containsKey(slot)) snapshots[slot] = readFile(slot)
        return snapshots[slot]?.takeIf { it.owner == owner }
    }

    private fun readFile(slot: String): Snapshot? {
        val source = file(slot)
        if (!source.isFile || source.length() > MAX_BYTES + 8192) return null
        return runCatching {
            DataInputStream(source.inputStream().buffered()).use {
                check(it.readInt() == 1)
                val owner = it.readUTF()
                val revision = it.readUTF()
                val size = it.readInt()
                check(size in 1..MAX_BYTES)
                val bytes = ByteArray(size)
                it.readFully(bytes)
                check(it.read() == -1)
                Snapshot(owner, revision, bytes)
            }
        }.getOrNull()
    }

    /** Purge and newer revisions invalidate encoders that are still running. */
    @Synchronized fun write(jpeg: ByteArray?, ticket: Ticket): Boolean {
        if (tickets[ticket.slot] != ticket) return false
        val target = file(ticket.slot)
        if (jpeg == null) {
            tickets.remove(ticket.slot)
            snapshots.remove(ticket.slot)
            onChange()
            if (!target.parentFile.exists()) return false
            check(target.parentFile.deleteRecursively())
            return true
        }
        require(jpeg.size in 1..MAX_BYTES)
        val previous = readFile(ticket.slot)
        if (previous?.owner == ticket.owner && previous.revision == ticket.revision && previous.jpeg.contentEquals(jpeg)) return false
        check(target.parentFile.isDirectory || target.parentFile.mkdirs())
        val temporary = File.createTempFile("thumbnail-", ".tmp", target.parentFile)
        try {
            DataOutputStream(temporary.outputStream().buffered()).use {
                it.writeInt(1); it.writeUTF(ticket.owner); it.writeUTF(ticket.revision)
                it.writeInt(jpeg.size); it.write(jpeg)
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            snapshots.remove(ticket.slot)
            onChange()
        } finally { temporary.delete() }
        return true
    }

    @Synchronized fun purge(slot: String) {
        val folder = file(slot).parentFile
        tickets.remove(slot)
        snapshots.remove(slot)
        onChange()
        check(!folder.exists() || folder.deleteRecursively())
    }

    @Synchronized fun purgeAll() {
        tickets.clear()
        snapshots.clear()
        onChange()
        check(!root.exists() || root.deleteRecursively())
    }

    companion object {
        private const val MAX_BYTES = 128 * 1024
        fun owner(origin: String, user: String) = origin.trimEnd('/') + "|" + user.lowercase()
    }
}
