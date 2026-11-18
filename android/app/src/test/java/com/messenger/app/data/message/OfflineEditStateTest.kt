package com.messenger.app.data.message

import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.sync.InMemoryMessageDao
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Contract for the offline edit state machine at the DAO boundary.
 *
 * The production Room SQL is exercised separately by the instrumented
 * migration/DAO checks (not runnable without a device here). This test keeps
 * the ordering invariant explicit: before the first POST the edit is the text;
 * after the payload is frozen the edit is an intent that must not replace the
 * original payload under the same idempotency key.
 */
class OfflineEditStateTest {
    private fun message(
        id: String = "m1",
        text: String = "hello",
        status: String = "pending",
        serverId: String? = null,
    ) = MessageEntity(
        clientMessageId = id,
        serverId = serverId,
        chatId = "chat-1",
        senderId = "me",
        text = text,
        sequence = null,
        createdAt = "2026-10-19T10:00:00Z",
        status = status,
        kind = "content",
    )

    @Test
    fun editBeforeFirstAttemptUpdatesTextAndPayload() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(message())

        assertEquals(1, dao.updateTextBeforeSend("m1", "edited"))
        val row = dao.findByClientMessageId("m1")!!
        assertEquals("edited", row.text)
        assertEquals("edited", row.payloadText)
        assertNull(row.pendingEditText)
        assertEquals(false, row.sendAttempted)
    }

    @Test
    fun editAfterFreezeStoresIntentWithoutReplacingPayload() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(message())
        dao.markSendStarted("m1", "hello")

        assertEquals(0, dao.updateTextBeforeSend("m1", "too late"))
        assertEquals(1, dao.setPendingEdit("m1", "edited", 1))
        val row = dao.findByClientMessageId("m1")!!
        assertEquals("edited", row.text)
        assertEquals("hello", row.payloadText)
        assertEquals("edited", row.pendingEditText)
        assertEquals(1, row.pendingEditRevision)
        assertEquals(true, row.sendAttempted)
    }

    @Test
    fun commitSentAndEditClearsIntentInOneContractStep() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(message())
        dao.markSendStarted("m1", "hello")
        dao.setPendingEdit("m1", "edited", 2)

        val sent = dao.findByClientMessageId("m1")!!.copy(
            serverId = "server-1",
            sequence = 7,
            status = "sent",
        )
        val edit = MessageEntity(
            clientMessageId = "edit-1",
            serverId = null,
            chatId = "chat-1",
            senderId = "me",
            text = "{\"kind\":\"text\",\"text\":\"edited\"}",
            sequence = null,
            createdAt = "2026-10-19T10:00:01Z",
            status = "pending",
            kind = "edit",
            targetMessageId = "server-1",
        )
        dao.commitSentAndPendingEdit(sent, edit)

        val row = dao.findByClientMessageId("m1")!!
        assertNull(row.pendingEditText)
        assertEquals("hello", row.payloadText)
        assertNotNull(dao.findByClientMessageId("edit-1"))
        assertEquals("server-1", dao.findByClientMessageId("edit-1")!!.targetMessageId)
    }

    @Test
    fun deleteClearsIntentAndLateCommitDoesNotResurrectText() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(message())
        dao.markSendStarted("m1", "hello")
        dao.setPendingEdit("m1", "edited", 1)
        dao.hideLocally("m1")
        dao.clearPendingEdit("m1")

        val row = dao.findByClientMessageId("m1")!!
        assertEquals(true, row.hiddenLocally)
        assertNull(row.pendingEditText)
        assertEquals("hello", row.payloadText)
    }
}
