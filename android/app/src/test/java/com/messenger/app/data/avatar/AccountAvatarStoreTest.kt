package com.messenger.app.data.avatar

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AccountAvatarStoreTest {
    private val other = "12345678-1234-1234-1234-123456789abc"
    private val owner = AccountAvatarStore.owner("https://qa.invalid/", "USER-A")
    private val peer = AccountAvatarStore.owner("https://qa.invalid", "USER-B")

    private fun withStore(test: (AccountAvatarStore, File) -> Unit) {
        val root = Files.createTempDirectory("account-avatar-contract").toFile()
        try { test(AccountAvatarStore(File(root, "thumbnails")), root) }
        finally { root.deleteRecursively() }
    }

    @Test fun writeReplaceDeleteAndReopenAreSlotAndOwnerBound() = withStore { store, root ->
        val a = store.begin("legacy", owner, "1")
        val b = store.begin(other, peer, "1")
        assertTrue(store.write(byteArrayOf(1, 2), a))
        assertTrue(store.write(byteArrayOf(9), b))
        assertArrayEquals(byteArrayOf(1, 2), store.read("legacy", owner)?.jpeg)
        assertNull(store.read("legacy", peer))
        assertNull(store.read(other, owner))
        assertArrayEquals(byteArrayOf(9), store.read(other, peer)?.jpeg)
        val replacement = store.begin("legacy", owner, "2")
        assertFalse(store.write(byteArrayOf(8), a))
        assertTrue(store.write(byteArrayOf(3), replacement))
        val reopened = AccountAvatarStore(File(root, "thumbnails"))
        assertArrayEquals(byteArrayOf(3), reopened.read("legacy", owner)?.jpeg)
        assertArrayEquals(byteArrayOf(9), reopened.read(other, peer)?.jpeg)
        assertFalse(store.write(byteArrayOf(3), replacement))
        File(root, "thumbnails/legacy/interrupted.tmp").writeBytes(byteArrayOf(7))
        assertTrue(store.write(null, replacement))
        assertFalse(store.write(byteArrayOf(8), replacement))
        assertFalse(File(root, "thumbnails/legacy").exists())
        assertNull(store.read("legacy", owner))
        assertArrayEquals(byteArrayOf(9), store.read(other, peer)?.jpeg)
    }

    @Test fun purgeInvalidatesPendingWritesAndKeepsTheOtherSlot() = withStore { store, _ ->
        val a = store.begin("legacy", owner, "1")
        val b = store.begin(other, peer, "1")
        store.write(byteArrayOf(1), a); store.write(byteArrayOf(2), b)
        store.purge("legacy")
        assertNull(store.read("legacy", owner))
        assertFalse(store.write(byteArrayOf(3), a))
        assertArrayEquals(byteArrayOf(2), store.read(other, peer)?.jpeg)
        val reused = store.begin("legacy", peer, "1")
        store.write(byteArrayOf(4), reused)
        assertNull(store.read("legacy", owner))
        store.purgeAll()
        assertNull(store.read(other, peer))
        assertFalse(store.write(byteArrayOf(5), b))
        assertFalse(store.write(byteArrayOf(5), reused))
        store.purgeAll()
    }

    @Test fun invalidSlotsAndOversizedOrCorruptFilesDoNotLeak() = withStore { store, root ->
        for (invalidOwner in listOf("", "server|", "server| ", "missing-separator")) {
            assertThrows(IllegalArgumentException::class.java) { store.begin("legacy", invalidOwner, "1") }
        }
        for (slot in listOf("../legacy", "", "/tmp/avatar", "LEGACY", "$other/../legacy")) {
            assertThrows(IllegalArgumentException::class.java) { store.begin(slot, owner, "1") }
            assertThrows(IllegalArgumentException::class.java) { store.read(slot, owner) }
            assertThrows(IllegalArgumentException::class.java) { store.purge(slot) }
        }
        val ticket = store.begin("legacy", owner, "1")
        assertThrows(IllegalArgumentException::class.java) { store.write(ByteArray(128 * 1024 + 1), ticket) }
        File(root, "thumbnails/legacy").mkdirs()
        File(root, "thumbnails/legacy/thumbnail.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.read("legacy", owner))
    }
}
