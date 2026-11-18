package com.messenger.app.data.message

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSnapshotFlowTest {
    data class Row(val id: Int, val text: String, val status: String = "sent")
    @Test fun duplicateRoomInvalidationsDoNotRemapButEditsReceiptsDeletesAndReactionsDo() = runTest {
        val messages = MutableSharedFlow<List<Row>>(replay = 1)
        val reactions = MutableSharedFlow<List<String>>(replay = 1)
        val baseline = (1..10_000).map { Row(it, "text-$it") }
        var projections = 0
        val output = mutableListOf<Pair<List<Row>, List<String>>>()
        val job = launch { projectChatSnapshots(messages, reactions) { m, r -> projections++; m to r }.collect { output.add(it) } }
        messages.emit(baseline); reactions.emit(emptyList()); runCurrent()
        repeat(100) { messages.emit(baseline.map { it.copy() }); reactions.emit(emptyList()); runCurrent() }
        assertEquals(1, projections)
        val edited = baseline.toMutableList().also { it[0] = it[0].copy(text = "edited") }
        messages.emit(edited); runCurrent()
        val read = edited.map { it.copy(status = "read") }
        messages.emit(read); runCurrent()
        reactions.emit(listOf("reaction")); runCurrent()
        messages.emit(read.drop(1)); runCurrent()
        assertEquals(5, projections)
        assertEquals(9999, output.last().first.size)
        assertEquals("read", output.last().first.first().status)
        assertEquals(listOf("reaction"), output.last().second)
        job.cancel()
        // A new collector/account starts fresh even for an equal first snapshot.
        val next = launch { projectChatSnapshots(messages, reactions) { m, r -> projections++; m to r }.collect {} }
        runCurrent(); assertEquals(6, projections); next.cancel()
    }
}
