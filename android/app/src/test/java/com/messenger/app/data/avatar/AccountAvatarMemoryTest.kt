package com.messenger.app.data.avatar

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class AccountAvatarMemoryTest {
    @Test fun firstRenderIsMemoryOnlyAndNeverUsesAnotherOwnerOrSlot() {
        val memory = AccountAvatarMemory<String>()
        var decodes = 0
        val snapshot = AccountAvatarStore.Snapshot("server|alice", "1", byteArrayOf(1))
        assertEquals("photo", memory.load("legacy", snapshot.owner, { snapshot }) { decodes++; "photo" })
        assertEquals("photo", memory.peek("legacy", snapshot.owner))
        assertNull(memory.peek("legacy", "server|bob"))
        assertNull(memory.peek("other-slot", snapshot.owner))
        assertEquals("photo", memory.load("legacy", snapshot.owner, { error("Warm mount read disk") }) { error("Decoded again") })
        assertEquals(1, decodes)
        memory.clear()
        assertNull(memory.peek("legacy", snapshot.owner))
        assertNull(memory.load("legacy", "server|bob", { snapshot }) { error("Wrong owner decoded") })
        assertEquals("new-photo", memory.load("legacy", snapshot.owner, { snapshot.copy(revision = "2") }) { "new-photo" })
    }

    @Test fun purgeDuringDecodeCannotRepopulateTheCache() {
        val memory = AccountAvatarMemory<String>()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val decoding = executor.submit<String?> {
                memory.load("legacy", "server|alice", { AccountAvatarStore.Snapshot("server|alice", "1", byteArrayOf(1)) }) {
                    entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)); "obsolete"
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            memory.clear()
            assertNull(memory.peek("legacy", "server|alice"))
            resume.countDown()
            assertNull(decoding.get(5, TimeUnit.SECONDS))
            assertNull(memory.peek("legacy", "server|alice"))
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test fun storePurgeInvalidatesTheFirstRenderImageAndLateTicket() {
        val root = Files.createTempDirectory("avatar-memory").toFile()
        try {
            val store = AccountAvatarStore(root)
            val memory = AccountAvatarMemory<String>()
            store.onChange = { memory.clear() }
            val ticket = store.begin("legacy", "server|alice", "1")
            store.write(byteArrayOf(1), ticket)
            memory.load("legacy", ticket.owner, { store.read("legacy", ticket.owner) }) { "photo" }
            store.purge("legacy")
            assertNull(memory.peek("legacy", ticket.owner))
            assertFalse(store.write(byteArrayOf(2), ticket))
            assertNull(memory.load("legacy", ticket.owner, { store.read("legacy", ticket.owner) }) { "ghost" })
        } finally { root.deleteRecursively() }
    }
}
