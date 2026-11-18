package com.messenger.app.data.message

import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.local.message.ReactionEntity
import org.junit.Assert.*
import org.junit.Test

class PendingLifecycleProjectionTest {
    private val target = MessageEntity("client", "server", "chat", "me", "original", 1,
        "2026-10-04T00:00:00Z", "sent")
    private fun event(id: String, kind: String, payload: ByteArray) =
        MessageEntity(id, null, "chat", "me", payload.toString(Charsets.UTF_8), null,
            "2026-10-04T00:00:0${id}Z", "pending", kind, "server")

    @Test fun `pending edits overlay in order without changing confirmed history`() {
        val a = event("1", "edit", MessageContentCodec.encodeEdit("first"))
        val b = event("2", "edit", MessageContentCodec.encodeEdit("last"))
        val projected = projectPendingLifecycle(listOf(target, a, b), emptyList()).first.single()
        assertEquals("last", projected.text)
        assertEquals("original", target.text)
        assertEquals(0L, projected.lastEditSequence)
        assertEquals("original", projectPendingLifecycle(listOf(target, a.copy(status="failed")), emptyList()).first.single().text)
    }
    @Test fun `reaction remove overrides queued add and server echo`() {
        val add = event("1", "reaction", MessageContentCodec.encodeReaction("❤️"))
        val remove = event("2", "reaction", MessageContentCodec.encodeReactionRemoval())
        val server = listOf(ReactionEntity("server", "me", "👍", 9))
        assertEquals("❤️", projectPendingLifecycle(listOf(target, add), server).second.single().emoji)
        assertTrue(projectPendingLifecycle(listOf(target, add, remove), server).second.isEmpty())
        assertEquals("👍", server.single().emoji)
    }
    @Test fun `queued delete cannot be resurrected by later queued edits`() {
        val delete = event("1", "delete", MessageContentCodec.encodeDelete())
        val edit = event("2", "edit", MessageContentCodec.encodeEdit("late"))
        assertTrue(projectPendingLifecycle(listOf(target, delete, edit), emptyList()).first.isEmpty())
        assertFalse(projectPendingLifecycle(listOf(target, delete.copy(senderId="other")), emptyList()).first.isEmpty())
    }
}
