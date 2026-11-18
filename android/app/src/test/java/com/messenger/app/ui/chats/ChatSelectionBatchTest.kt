package com.messenger.app.ui.chats

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatSelectionBatchTest {
    @Test fun `partial failures preserve failed targets and continue once`() = runTest {
        val visited = mutableListOf<String>()
        val result = runChatSelectionBatch(linkedSetOf("a", "b", "c"), { true }) {
            visited += it
            if (it == "b") error("local journal unavailable")
        }
        assertEquals(listOf("a", "b", "c"), visited)
        assertEquals(setOf("a", "c"), result.succeeded)
        assertEquals(setOf("b"), result.failed)
    }

    @Test fun `changed owner never receives remaining commands`() = runTest {
        var owner = "first"
        val visited = mutableListOf<String>()
        try {
            runChatSelectionBatch(linkedSetOf("a", "b"), { owner == "first" }) {
                visited += it; owner = "second"
            }
            fail("Changed account must cancel")
        } catch (_: CancellationException) { }
        assertEquals(listOf("a"), visited)
    }

    @Test fun `stale batch is rejected before first write`() = runTest {
        var writes = 0
        try {
            runChatSelectionBatch(setOf("a"), { false }) { writes++ }
            fail("Stale batch must cancel")
        } catch (_: CancellationException) { }
        assertEquals(0, writes)
    }

    @Test fun `cancellation is never converted into partial success`() = runTest {
        val visited = mutableListOf<String>()
        try {
            runChatSelectionBatch(linkedSetOf("a", "b"), { true }) {
                visited += it; throw CancellationException("Screen closed")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf("a"), visited)
    }

    @Test fun `empty selection has no effects`() = runTest {
        val result = runChatSelectionBatch(emptySet(), { true }) { error("Unexpected write") }
        assertTrue(result.succeeded.isEmpty()); assertTrue(result.failed.isEmpty())
    }
}
