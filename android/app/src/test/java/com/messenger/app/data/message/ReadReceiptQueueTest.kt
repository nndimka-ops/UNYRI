package com.messenger.app.data.message

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadReceiptQueueTest {
    @Test fun `offline read retries after restart without opening chat`() = runBlocking {
        val durable = mapOf("closed-chat" to 42L)
        ReadReceiptQueue().flush(1, { true }, { durable }) { _, _ -> error("offline") }
        val sent = mutableListOf<Long>()
        val restarted = ReadReceiptQueue()
        restarted.flush(1, { true }, { durable }) { _, sequence -> sent += sequence }
        restarted.flush(1, { true }, { durable }) { _, sequence -> sent += sequence }
        assertEquals(listOf(42L), sent)
    }

    @Test fun `failed chat does not block other reads and retries stay forward only`() = runBlocking {
        val queue = ReadReceiptQueue()
        val sent = mutableListOf<String>()
        queue.flush(1, { true }, { linkedMapOf("a" to 3L, "b" to 7L) }) { chat, _ ->
            if (chat == "a") error("offline") else sent += chat
        }
        queue.flush(1, { true }, { linkedMapOf("a" to 3L, "b" to 6L) }) { chat, _ -> sent += chat }
        assertEquals(listOf("b", "a"), sent)
    }

    @Test fun `account switch interrupts batch and resets acknowledgement cache`() = runBlocking {
        val queue = ReadReceiptQueue()
        var epoch = 1
        val sent = mutableListOf<String>()
        queue.flush(1, { epoch == 1 }, { linkedMapOf("a" to 3L, "b" to 7L) }) { chat, _ ->
            sent += chat; epoch = 2
        }
        queue.flush(2, { epoch == 2 }, { mapOf("a" to 3L) }) { chat, _ -> sent += chat }
        assertEquals(listOf("a", "a"), sent)
    }
}
