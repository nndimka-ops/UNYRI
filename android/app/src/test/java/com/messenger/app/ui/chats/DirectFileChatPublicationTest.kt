package com.messenger.app.ui.chats

import com.messenger.app.data.directtransfer.DirectFileOffer
import com.messenger.app.data.directtransfer.DirectFilePresentation
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.ui.calls.ChatTimelineCache
import com.messenger.app.ui.transfers.DirectTransferUiPolicy
import kotlin.test.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DirectFileChatPublicationTest {
    private val chat = "10000000-0000-4000-8000-000000000010"
    private val offer = DirectFileOffer(
        transferId = "10000000-0000-4000-8000-000000000001",
        offerClientMessageId = "10000000-0000-4000-8000-000000000002",
        senderUserId = "10000000-0000-4000-8000-000000000003",
        senderDeviceId = "10000000-0000-4000-8000-000000000004",
        recipientUserId = "10000000-0000-4000-8000-000000000005",
        offerNonce = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        filename = "original.bin", mimeType = "application/octet-stream", size = 1024,
        sha256 = "a".repeat(64), expiresAt = 999999,
    )
    private val value = DirectFilePresentation(offer, "transferring", "receiver",
        offset = 128, ackSampleId = 1, speedBytesPerSecond = 64, manualControlsAvailable = true)
    private fun message(file: DirectFilePresentation = value) = Message(
        clientMessageId = offer.offerClientMessageId, serverId = "server", chatId = chat,
        senderId = offer.senderUserId, text = DirectFileOffer.FALLBACK, sequence = 1,
        createdAt = "2026-11-06T00:00:00Z", status = MessageStatus.DELIVERED, directFile = file,
    )

    @Test fun progressAdvancesExactRowWithoutRepublishingTimelineMessageInputs() = runBlocking {
        val original = message()
        val ack = message(value.copy(offset = 256, ackSampleId = 2, speedBytesPerSecond = 128))
        val paused = message(ack.directFile!!.copy(state = "paused", reason = "user_paused", localManualPaused = true))
        val snapshots = listOf<List<Message>?>(null, listOf(original), listOf(ack), listOf(paused))
        val structural = snapshots.asFlow().structuralDirectFileMessages().toList()
        // Exercise the actual screen-owned timeline cache on both publication
        // paths: ACK changes its raw row, but never reaches the structural path.
        val rawCache = ChatTimelineCache()
        val rawTimelineUpdates = snapshots.filterNotNull().map { rawCache.update(it, emptyList()) }
        val structuralCache = ChatTimelineCache()
        val structuralTimelineUpdates = structural.filterNotNull().map { structuralCache.update(it, emptyList()) }
        assertEquals(4, snapshots.size)
        assertEquals(3, structural.size)
        assertEquals(3, rawTimelineUpdates.size)
        assertEquals(2, structuralTimelineUpdates.size)
        assertEquals(128L, rawTimelineUpdates[0].single().message!!.directFile!!.offset)
        assertEquals(256L, rawTimelineUpdates[1].single().message!!.directFile!!.offset)
        assertEquals("paused", structuralTimelineUpdates[1].single().message!!.directFile!!.state)
        assertEquals(directFileMessageStructure(listOf(original)), structural[1])
        assertEquals(directFileMessageStructure(listOf(paused)), structural[2])
        val expected = structural[1]!!.single()
        val rows = snapshots.map { visibleDirectFilePresentation(it, expected, chat, true) }
        assertEquals(128L, rows[1]!!.offset)
        assertEquals(256L, rows[2]!!.offset)
        assertEquals(2L, rows[2]!!.ackSampleId)
        assertEquals(128L, rows[2]!!.speedBytesPerSecond)
        assertEquals("paused", rows[3]!!.state)
        assertTrue(rows[3]!!.localManualPaused)
    }

    @Test fun phaseAndSafetyChangesAreStructuralAndNoProgressFieldsAreRecomputed() = runBlocking {
        val changes = listOf(value, value.copy(peerManualPaused = true), value.copy(manualSyncPending = true),
            value.copy(reason = "background_interrupted", state = "paused"), value.copy(mailboxClosed = true),
            value.copy(state = "completed", completed = true, offset = offer.size))
        val events = changes.map { listOf(message(it)) }.asFlow().structuralDirectFileMessages().toList()
        assertEquals(changes.size, events.size)
        changes.zip(events).forEach { (full, structural) ->
            assertEquals(full.copy(offset = 0, ackSampleId = 0, speedBytesPerSecond = 0), structural!!.single().directFile)
            assertSame(full, visibleDirectFilePresentation(listOf(message(full)), structural.single(), chat, true))
        }
    }

    @Test fun selectorFailsClosedOnAccountChatOfferAuthorAndInertMessageMismatch() {
        val expected = message()
        assertNull(visibleDirectFilePresentation(listOf(expected), expected, chat, false))
        assertNull(visibleDirectFilePresentation(listOf(expected.copy(chatId = "another")), expected, chat, true))
        assertNull(visibleDirectFilePresentation(listOf(expected.copy(senderId = "another")), expected, chat, true))
        assertNull(visibleDirectFilePresentation(listOf(message(value.copy(offer = offer.copy(sha256 = "b".repeat(64))))), expected, chat, true))
        for (changed in listOf(expected.copy(forwarded = true), expected.copy(isDeletedForEveryone = true),
            expected.copy(status = MessageStatus.DECRYPT_FAILED), expected.copy(status = MessageStatus.IDENTITY_CHANGED),
            expected.copy(directFile = null))) {
            assertNull(visibleDirectFilePresentation(listOf(changed), expected, chat, true))
        }
        assertNull(visibleDirectFilePresentation(emptyList(), expected, chat, true))
        assertNull(visibleDirectFilePresentation(listOf(expected), expected.copy(forwarded = true), chat, true))
    }

    @Test fun authScopeChangeClearsObservedRowEvenWithoutAnotherProgressTick() = runBlocking {
        val expected = message()
        val snapshots = MutableStateFlow<List<Message>?>(listOf(expected))
        val current = MutableStateFlow(true)
        val received = Channel<DirectFilePresentation?>(Channel.UNLIMITED)
        val job = launch { snapshots.visibleDirectFilePresentation(expected, chat, current).collect { received.send(it) } }
        try {
            assertEquals(value, withTimeout(5000) { received.receive() })
            current.value = false
            assertNull(withTimeout(5000) { received.receive() })
        } finally { job.cancelAndJoin(); received.close() }
    }

    @Test fun historyProjectionStaysReadonlyWithoutPromotingItToALiveRecord() {
        val history = DirectFilePresentation(offer, "other_device", "other", reason = "history_only")
        val expected = message(history)
        val selected = visibleDirectFilePresentation(listOf(expected), expected, chat, true)
        assertSame(history, selected)
        assertEquals("other", selected!!.role)
        assertEquals("history_only", selected.reason)
        assertNull(DirectTransferUiPolicy.manualAction(selected))
        assertFalse(DirectTransferUiPolicy.canOfferActions(selected, DirectTransferUiPolicy.cardPermissions(selected)))
        assertFalse(DirectTransferUiPolicy.canRetrySynchronization(selected))
    }
}
