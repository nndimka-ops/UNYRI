package com.messenger.app.data.message

import com.messenger.app.data.api.AuthApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ReceiptParityTest {
    @Test fun `ingestion wake and UI delivery flushes share one dedup lane`() = runTest {
        val receipts = SessionReceipts()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var cursor = 7L; var saved = 0L
        val sent = mutableListOf<Long>()
        suspend fun flush() = receipts.flush(1, true, { true }, { mapOf("chat" to cursor) },
            { saved }, { _, sequence -> saved = sequence }, { _, sequence ->
                sent += sequence
                if (sequence == 7L) { started.complete(Unit); release.await() }
                sequence
            })
        val ingestion = launch { flush() }
        started.await()
        cursor = 8
        val wake = launch { flush() }; val ui = launch { flush() }
        release.complete(Unit)
        ingestion.join(); wake.join(); ui.join()
        assertEquals(listOf(7L, 8L), sent)
        assertEquals(8L, saved)
    }

    @Test fun `cancelled transport cannot strand a waiting flush`() = runTest {
        val queue = ReadReceiptQueue()
        val started = CompletableDeferred<Unit>()
        val sent = mutableListOf<Long>()
        val first = launch { queue.flush(1, { true }, { mapOf("chat" to 1L) }) { _, _ ->
            started.complete(Unit); awaitCancellation()
        } }
        started.await()
        val next = launch { queue.flush(1, { true }, { mapOf("chat" to 2L) }) { _, seq -> sent += seq } }
        first.cancelAndJoin(); next.join()
        assertEquals(listOf(2L), sent)
    }
    @Test fun `flush requested during transport resnapshots durable cursor`() = runTest {
        val queue = ReadReceiptQueue()
        var cursor = 1L
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val sent = mutableListOf<Long>()
        val worker = launch { queue.flush(1, { true }, { mapOf("chat" to cursor) }) { _, sequence ->
            sent += sequence
            if (sequence == 1L) { started.complete(Unit); finish.await() }
        } }
        started.await(); cursor = 2
        val second = launch { queue.flush(1, { true }, { mapOf("chat" to cursor) }) { _, sequence -> sent += sequence } }
        finish.complete(Unit); worker.join(); second.join()
        assertEquals(listOf(1L, 2L), sent)
    }
    @Test fun `own saved server cursor skips cold launch and open chat is first`() = runTest {
        val receipts = SessionReceipts()
        val sent = mutableListOf<String>()
        val saved = mutableMapOf("old" to 9L)
        suspend fun flush() = receipts.flush(1, false, { true },
            { linkedMapOf("old" to 9L, "closed" to 3L, "open" to 5L) },
            { saved[it] ?: 0 }, { chat, seq -> saved[chat] = seq },
            { chat, seq -> sent += chat; seq }, priority = "open")
        flush(); flush()
        assertEquals(listOf("open", "closed"), sent)
        val restarted = SessionReceipts()
        restarted.flush(1, false, { true }, { saved }, { saved[it] ?: 0 }, { _, _ -> fail() }, { _, _ -> fail() })
    }
    @Test fun `409 pauses both receipt directions for five minutes and activity ends pause`() = runTest {
        var now = 100L
        val receipts = SessionReceipts { now }
        var attempts = 0
        suspend fun flush(delivered: Boolean) = receipts.flush(1, delivered, { true }, { mapOf("chat" to 8L) },
            { 0 }, { _, _ -> }, { _, _ -> attempts++; throw AuthApiException.Api("conflict", "blocked", 409) })
        flush(false); flush(true)
        assertEquals(1, attempts)
        now += 299_999; flush(false); assertEquals(1, attempts)
        now++; flush(true); assertEquals(2, attempts)
        receipts.allow(1, "chat"); flush(false); assertEquals(3, attempts)
        val ticket = receipts.ticket(1); receipts.allow(1, "chat")
        receipts.refuse(1, "chat", ticket)
        assertFalse(receipts.isRefused(1, "chat"))
        receipts.refuse(1, "chat", receipts.ticket(1))
        assertFalse(receipts.isRefused(2, "chat"))
    }
    @Test fun `background or account change stops receipt transport`() = runTest {
        SessionReceipts().flush(1, false, { false }, { fail() }, { fail() }, { _, _ -> fail() }, { _, _ -> fail() })
    }
}
