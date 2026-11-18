package com.messenger.app.ui.chats

import com.messenger.app.data.api.CallDto
import com.messenger.app.data.call.callTimestamp
import org.junit.Test

/**
 * Synthetic A/B for the chat-list activity map. It creates 1000 calls across
 * 100 peers; no user data is used. The old implementation sorted and then
 * associateBy()'d, the new one keeps the same (timestamp, id) winner in one pass.
 */
class LatestByPeerBenchmarkTest {
    private fun calls(): List<CallDto> = (0 until 1_000).map { index ->
        CallDto(
            id = "call-%04d".format(index),
            callerUserId = "me",
            calleeUserId = "peer-${index % 100}",
            mediaKind = "audio",
            state = "ended",
            createdAt = "2026-09-20T%02d:%02d:%02dZ".format((index / 3600) % 24, (index / 60) % 60, index % 60),
        )
    }

    private fun old(calls: List<CallDto>): Map<String, CallDto> =
        calls.filter { it.chatVisible != false && (it.callerUserId == "me" || it.calleeUserId == "me") }
            .sortedWith(compareBy<CallDto> { callTimestamp(it) }.thenBy { it.id })
            .associateBy { if (it.callerUserId == "me") it.calleeUserId else it.callerUserId }

    private fun measure(block: () -> Map<String, CallDto>): String {
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
    fun `latest call per peer timing on 1000 calls`() {
        val input = calls()
        val oldResult = old(input)
        val newResult = ChatCallActivity.latestByPeer(input, "me")
        check(oldResult.keys == newResult.keys)
        check(oldResult.all { (peer, call) -> newResult[peer]?.id == call.id })
        println("PERF latest_by_peer_1000_us old [${measure { old(input) }}]")
        println("PERF latest_by_peer_1000_us new [${measure { ChatCallActivity.latestByPeer(input, "me") }}]")
    }
}
