package com.messenger.app.domain.model

import org.junit.Test

/**
 * Reproducible micro-benchmark for the chat render-path collapse step.
 * It deliberately uses a synthetic 10,000-message list (not a user history)
 * and only prints timings; it makes no fragile timing assertion.
 */
class HistoryPresentationBenchmarkTest {
    private fun rows(count: Int): List<Message> = (0 until count).map { index ->
        Message(
            clientMessageId = "client-$index",
            serverId = "server-$index",
            chatId = "benchmark-chat",
            senderId = if (index % 2 == 0) "a" else "b",
            text = "benchmark message $index",
            sequence = index.toLong(),
            createdAt = "2026-10-20T00:00:00Z",
            status = when {
                index % 997 == 0 -> MessageStatus.DECRYPT_FAILED
                index % 991 == 0 -> MessageStatus.READ
                else -> MessageStatus.SENT
            },
        )
    }

    /** Previous implementation, kept only in this benchmark for a same-run A/B. */
    private fun oldCollapse(messages: List<Message>, cutoff: Long, collapseRecentFailures: Boolean): List<Message> {
        val recentMarker = messages.filter {
            it.status == MessageStatus.DECRYPT_FAILED && !it.isUnavailableHistory(cutoff)
        }.maxByOrNull { it.sequence ?: 0 }
        return messages.filter {
            if (it.isUnavailableHistory(cutoff)) false
            else !collapseRecentFailures || it.status != MessageStatus.DECRYPT_FAILED ||
                it.clientMessageId == recentMarker?.clientMessageId
        }
    }

    private fun measure(block: () -> List<Message>): String {
        repeat(20) { block() }
        val samples = LongArray(50)
        repeat(samples.size) {
            val started = System.nanoTime()
            block()
            samples[it] = System.nanoTime() - started
        }
        samples.sort()
        return "median=${samples[samples.size / 2] / 1_000.0} " +
            "p95=${samples[(samples.size * 95) / 100] / 1_000.0} " +
            "min=${samples.first() / 1_000.0} max=${samples.last() / 1_000.0}"
    }

    @Test
    fun `collapse timing on 10k messages`() {
        val messages = rows(10_000)
        val old = measure { oldCollapse(messages, 3_000, collapseRecentFailures = true) }
        val current = measure { collapseUnavailableHistory(messages, 3_000, collapseRecentFailures = true) }
        println("PERF collapse_10k_us old [$old]")
        println("PERF collapse_10k_us new [$current]")
    }
}
