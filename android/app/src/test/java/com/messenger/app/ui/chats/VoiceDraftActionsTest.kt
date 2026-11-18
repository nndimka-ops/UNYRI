package com.messenger.app.ui.chats

import java.io.File
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDraftActionsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `delete owns the draft before its coroutine starts and while persistence suspends`() = runTest {
        val actions = VoiceDraftActions()
        val file = temporary.newFile("draft.m4a").apply { writeText("audio") }
        val persisted = CompletableDeferred<Unit>()
        var queues = 0
        var deletes = 0
        assertTrue(actions.launch("draft", VoiceDraftActions.Action.Delete, this, { throw it }) { claim ->
            persisted.await()
            claim.commit()
            assertTrue(file.delete())
            deletes++
        })
        assertTrue(actions.busy)
        assertFalse(actions.launch("draft", VoiceDraftActions.Action.Send, this, { throw it }) { queues++ })
        runCurrent() // Delete is now suspended inside the storage write from the review.
        assertTrue(file.exists())
        assertFalse(actions.launch("draft", VoiceDraftActions.Action.Send, this, { throw it }) { queues++ })
        persisted.complete(Unit)
        runCurrent()
        assertFalse(actions.busy)
        assertEquals(1, deletes)
        assertEquals(0, queues)
        assertFalse(actions.launch("draft", VoiceDraftActions.Action.Send, this, { throw it }) { queues++ })
    }

    @Test fun `send owns the file across queueing and pointer clearing and rejects late delete`() = runTest {
        val actions = VoiceDraftActions()
        val file = temporary.newFile("queued.m4a").apply { writeText("audio") }
        val queued = mutableListOf<File>()
        val clearPointer = CompletableDeferred<Unit>()
        assertTrue(actions.launch("draft", VoiceDraftActions.Action.Send, this, { throw it }) { claim ->
            queued += file
            claim.commit()
            clearPointer.await()
        })
        assertFalse(actions.launch("draft", VoiceDraftActions.Action.Delete, this, { throw it }) { file.delete() })
        runCurrent()
        assertEquals(listOf(file), queued)
        assertFalse(actions.launch("draft", VoiceDraftActions.Action.Delete, this, { throw it }) { file.delete() })
        clearPointer.complete(Unit)
        runCurrent()
        assertFalse(actions.launch("draft", VoiceDraftActions.Action.Delete, this, { throw it }) { file.delete() })
        assertFalse(actions.launch("draft", VoiceDraftActions.Action.Send, this, { throw it }) { queued += file })
        assertEquals("audio", queued.single().readText())
    }

    @Test fun `a committed send stays retired when post-commit UI cleanup fails`() = runTest {
        val actions = VoiceDraftActions()
        val errors = mutableListOf<Exception>()
        val file = temporary.newFile("queued.m4a")
        actions.launch("draft", VoiceDraftActions.Action.Send, this, errors::add) { claim ->
            claim.commit()
            error("UI cleanup failed after the atomic storage handoff")
        }
        runCurrent()
        assertEquals(1, errors.size)
        assertFalse(actions.busy)
        assertFalse(actions.launch("draft", VoiceDraftActions.Action.Delete, this, { throw it }) { file.delete() })
        assertTrue(file.exists())
    }

    @Test fun `failed uncommitted actions release ownership for retry or deletion`() = runTest {
        val actions = VoiceDraftActions()
        var failures = 0
        actions.launch("draft", VoiceDraftActions.Action.Send, this, { failures++ }) { error("queue rejected") }
        runCurrent()
        assertEquals(1, failures)
        assertTrue(actions.launch("draft", VoiceDraftActions.Action.Delete, this, { throw it }) { it.commit() })
        runCurrent()
        assertFalse(actions.busy)
        assertTrue(actions.launch("next", VoiceDraftActions.Action.Send, this, { throw it }) { it.commit() })
        runCurrent()
    }
}
