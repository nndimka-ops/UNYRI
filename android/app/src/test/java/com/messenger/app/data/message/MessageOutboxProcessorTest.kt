package com.messenger.app.data.message

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.sync.InMemoryMessageDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N.2R Bug 4: the outbox processor's classification/retry semantics. */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageOutboxProcessorTest {
    @Test fun missingRecipientKeysKeepIntentAndDoNotBlockAnotherChat() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("waiting")); dao.upsert(entity("other").copy(chatId = "chat-2"))
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(dao,backgroundScope,{1000L},{"me"},attemptSend = {
            if(it.clientMessageId=="waiting") throw RecipientDevicesUnavailableException()
            sent += it.clientMessageId;dao.updateStatus(it.clientMessageId,"sent")
        })
        processor.flush()
        val pending = dao.findByClientMessageId("waiting")!!
        assertEquals("pending",pending.status)
        assertEquals("recipient_devices_unavailable",pending.lastErrorClass)
        assertEquals(16_000L,pending.nextRetryAtEpochMs)
        assertEquals(listOf("other"),sent)
    }
    @Test fun reactionFailureDoesNotDelayTextOrAnotherTargetButKeepsSameTargetOrder() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("r-old").copy(kind = "reaction", targetMessageId = "target-a"))
        dao.upsert(entity("r-new").copy(kind = "reaction", targetMessageId = "target-a"))
        dao.upsert(entity("r-other").copy(kind = "reaction", targetMessageId = "target-b"))
        dao.upsert(entity("text"))
        val attempts = mutableListOf<String>()
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" }, attemptSend = {
            attempts += it.clientMessageId
            if (it.clientMessageId == "r-old") throw AuthApiException.Network("offline")
            dao.updateStatus(it.clientMessageId, "sent")
        })
        processor.flush()
        assertEquals(listOf("r-old", "r-other", "text"), attempts)
        assertEquals("pending", dao.findByClientMessageId("r-new")!!.status)
        assertTrue(dao.findByClientMessageId("r-old")!!.nextRetryAtEpochMs > 1000L)
        assertEquals("sent", dao.findByClientMessageId("text")!!.status)
        attempts.clear()
        processor.flush()
        assertTrue(attempts.isEmpty())
    }

    @Test fun lateMediaCompletionCannotDeliverAfterAccountReplacement() = runTest {
        val dao = InMemoryMessageDao(); dao.upsert(entity("video"))
        var epoch = 1; var user = "me"
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        var ready = false
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { user }, currentSessionEpoch = { epoch },
            mediaReadiness = { if (ready) OutboxMediaReadiness.READY else OutboxMediaReadiness.WAITING },
            prepareMedia = { started.complete(Unit); release.await(); ready = true },
            attemptSend = { sent += it.clientMessageId })
        processor.flush(); runCurrent(); started.await()
        epoch++; user = "other"; release.complete(Unit); runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals("pending", dao.findByClientMessageId("video")!!.status)
        assertEquals(0, dao.findByClientMessageId("video")!!.retryCount)
    }
    @Test fun textBypassesSuspendedVideoWithoutRacingSignalOrDuplicatingMedia() = runTest {
        val dao = InMemoryMessageDao()
        listOf("video", "photo", "text", "other").forEach { dao.upsert(entity(it).copy(chatId = if (it == "other") "chat-2" else "chat-1")) }
        val ready = mutableSetOf<String>(); val prepared = mutableListOf<String>(); val sent = mutableListOf<String>()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            mediaReadiness = { if (it.clientMessageId !in setOf("video", "photo")) OutboxMediaReadiness.NONE else if (it.clientMessageId in ready) OutboxMediaReadiness.READY else OutboxMediaReadiness.WAITING },
            prepareMedia = {
                prepared += it.clientMessageId
                if (it.clientMessageId == "video") { started.complete(Unit); release.await() }
                ready += it.clientMessageId
            }, attemptSend = { sent += it.clientMessageId; dao.updateStatus(it.clientMessageId, "sent") })
        processor.flush(); runCurrent(); started.await()
        assertEquals(listOf("text", "other"), sent)
        assertEquals(listOf("video"), prepared)
        processor.flush(); runCurrent()
        assertEquals(listOf("video"), prepared)
        release.complete(Unit); runCurrent()
        assertEquals(listOf("text", "other", "video", "photo"), sent)
        assertEquals(listOf("video", "photo"), prepared)
    }

    @Test fun cancellingSuspendedMediaCannotRecreateItAndDoesNotBlockText() = runTest {
        val dao = InMemoryMessageDao(); dao.upsert(entity("video")); dao.upsert(entity("text"))
        val started = CompletableDeferred<Unit>(); val never = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            mediaReadiness = { if (it.clientMessageId == "video") OutboxMediaReadiness.WAITING else OutboxMediaReadiness.NONE },
            prepareMedia = { started.complete(Unit); never.await() },
            attemptSend = { sent += it.clientMessageId; dao.updateStatus(it.clientMessageId, "sent") })
        processor.flush(); runCurrent(); started.await()
        processor.cancelAndHide("video"); runCurrent()
        assertTrue(dao.findByClientMessageId("video")!!.hiddenLocally)
        assertEquals(0, dao.findByClientMessageId("video")!!.retryCount)
        assertEquals(listOf("text"), sent)
    }

    @Test fun groupCancelRechecksAckAndKeepsDeliveredForeignAndLaterRows() = runTest {
        val dao = InMemoryMessageDao()
        val ids = listOf("pending", "failed", "acked", "foreign", "hidden", "later")
        for (id in ids) dao.upsert(entity(id))
        dao.upsert(entity("failed", status = "failed"))
        dao.upsert(entity("foreign").copy(senderId = "peer"))
        dao.upsert(entity("hidden").copy(hiddenLocally = true))
        val captured = ids.filter { it != "later" } + "pending"
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            withSessionWrite = { _, block ->
                // ACK wins after the UI tap but before the actual hide statement.
                dao.upsert(entity("acked", status = "delivered").copy(serverId = "server-acked"))
                block(); Unit
            }, attemptSend = {})
        assertEquals(listOf("pending", "failed"), processor.cancelUnsentAndHide("chat-1", captured))
        assertTrue(dao.findByClientMessageId("pending")!!.hiddenLocally)
        assertTrue(dao.findByClientMessageId("failed")!!.hiddenLocally)
        for (id in listOf("acked", "foreign", "later")) assertTrue(!dao.findByClientMessageId(id)!!.hiddenLocally)
        assertTrue(processor.cancelUnsentAndHide("chat-1", captured).isEmpty())
    }

    @Test fun groupCancelJoinsActivePreparationAndDeliveryBeforeMutation() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("upload")); dao.upsert(entity("sending")); dao.upsert(entity("unrelated").copy(chatId = "chat-2"))
        val preparing = CompletableDeferred<Unit>(); val delivering = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var preparationRetired = false; var deliveryRetired = false
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            mediaReadiness = { if (it.clientMessageId == "upload") OutboxMediaReadiness.WAITING else OutboxMediaReadiness.NONE },
            prepareMedia = { try { preparing.complete(Unit); never.await() } finally { preparationRetired = true } },
            attemptSend = {
                if (it.clientMessageId == "sending") {
                    try { delivering.complete(Unit); never.await() } finally { deliveryRetired = true }
                } else { sent += it.clientMessageId; dao.updateStatus(it.clientMessageId, "sent") }
            })
        val flush = backgroundScope.async { processor.flush() }
        runCurrent(); preparing.await(); delivering.await()
        assertEquals(listOf("upload", "sending"), processor.cancelUnsentAndHide("chat-1", listOf("upload", "sending")))
        assertTrue(preparationRetired); assertTrue(deliveryRetired)
        flush.await(); runCurrent()
        assertTrue(dao.findByClientMessageId("upload")!!.hiddenLocally)
        assertTrue(dao.findByClientMessageId("sending")!!.hiddenLocally)
        processor.flush(); runCurrent()
        assertTrue("unrelated" in sent)
        assertEquals(0, dao.findByClientMessageId("upload")!!.retryCount)
    }

    @Test fun cancelledReadinessSnapshotCannotStartANewPreparationAfterCancelReturns() = runTest {
        val dao = InMemoryMessageDao(); dao.upsert(entity("video"))
        val observed = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val prepared = mutableListOf<String>()
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            mediaReadiness = { observed.complete(Unit); resume.await(); OutboxMediaReadiness.WAITING },
            prepareMedia = { prepared += it.clientMessageId }, attemptSend = {})
        val flush = backgroundScope.async { processor.flush() }
        runCurrent(); observed.await()
        assertEquals(listOf("video"), processor.cancelUnsentAndHide("chat-1", listOf("video")))
        // The flush passed its pause check before cancellation and retained an
        // old unhidden snapshot, but had not registered any worker to join.
        resume.complete(Unit); flush.await(); runCurrent()
        assertTrue("A stale readiness snapshot may not upload or recreate cancelled sources", prepared.isEmpty())
        assertTrue(dao.findByClientMessageId("video")!!.hiddenLocally)
    }

    @Test fun mediaBackoffBlocksOnlyOtherMediaInItsChat() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("video", nextRetryAtEpochMs = 9000))
        dao.upsert(entity("photo")); dao.upsert(entity("text"))
        dao.saveMediaJob("chat-1", "upload:video", "{}"); dao.saveMediaJob("chat-1", "upload:photo", "{}")
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            mediaReadiness = { if (it.clientMessageId == "text") OutboxMediaReadiness.NONE else OutboxMediaReadiness.WAITING },
            attemptSend = { sent += it.clientMessageId; dao.updateStatus(it.clientMessageId, "sent") })
        processor.flush(); runCurrent()
        assertEquals(listOf("text"), sent)
    }

    @Test
    fun deletedReactionTargetKeepsSafeFailureReasonAndDoesNotRetry() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("deleted-reaction").copy(kind = "reaction", targetMessageId = "deleted-target"))
        var attempts = 0
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            attemptSend = { attempts++; throw AuthApiException.Api("validation_error", "Cannot react to a deleted message.") })
        processor.flush(); processor.flush()
        val failed = dao.findByClientMessageId("deleted-reaction")!!
        assertEquals("failed", failed.status)
        assertEquals("reaction_target_deleted", failed.lastErrorClass)
        assertEquals(1, attempts)
        assertEquals(0, failed.retryCount)
        assertEquals("deleted-target", failed.targetMessageId)
        assertTrue(!failed.hiddenLocally)
    }

    @Test
    fun stalledDeliveryDoesNotBlockLocalMessagesOrReactions() = runTest {
        val dao = InMemoryMessageDao(); dao.upsert(entity("first"))
        val gate = com.messenger.app.data.auth.SessionWriteGate { 1 }
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            currentSessionEpoch = { 1 }, withSessionWrite = { epoch, block -> gate.write(epoch, block) },
            attemptSend = { started.complete(Unit); release.await(); dao.updateStatus(it.clientMessageId, "sent") })
        val sending = async { processor.flush() }; runCurrent(); started.await()
        kotlinx.coroutines.withTimeout(1000) {
            gate.write(1) {
                dao.upsert(entity("second"))
                dao.upsert(entity("reaction").copy(kind = "reaction", targetMessageId = "target"))
            }
        }
        assertTrue(!sending.isCompleted)
        assertEquals("pending", dao.findByClientMessageId("second")?.status)
        assertEquals("reaction", dao.findByClientMessageId("reaction")?.kind)
        release.complete(Unit); sending.await()
    }

    @Test
    fun logoutClearDoesNotWaitForStalledNetworkAndRejectsLateCommit() = runTest {
        val dao = InMemoryMessageDao(); dao.upsert(entity("old"))
        var epoch = 1
        val gate = com.messenger.app.data.auth.SessionWriteGate { epoch }
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var committed = false
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            currentSessionEpoch = { epoch }, withSessionWrite = { owner, block -> gate.write(owner, block) },
            attemptSend = {
                val owner = epoch
                started.complete(Unit); release.await()
                gate.write(owner) { committed = true }
            })
        val sending = async { processor.flush() }; runCurrent(); started.await()
        kotlinx.coroutines.withTimeout(1000) { gate.clear { epoch++ } }
        release.complete(Unit); sending.await()
        assertTrue(!committed)
        assertEquals(0, dao.findByClientMessageId("old")!!.retryCount)
    }

    @Test
    fun pausedClearStopsNextCapturedOldRowAndResumesOnlyFutureWork() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("old-first")); dao.upsert(entity("old-second"))
        val firstPreparing = CompletableDeferred<Unit>()
        val inTransaction = CompletableDeferred<Unit>()
        val finishTransaction = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            prepareAttempt = {
                if (it.clientMessageId == "old-first") { firstPreparing.complete(Unit); CompletableDeferred<Unit>().await() }
            }, attemptSend = { sent += it.clientMessageId; dao.updateStatus(it.clientMessageId, "sent") })
        val flushing = async { processor.flush() }; runCurrent(); firstPreparing.await()
        val clear = async {
            processor.withDeliveryPaused {
                inTransaction.complete(Unit)
                finishTransaction.await()
                dao.hideLocally("old-first"); dao.hideLocally("old-second")
                processor.withDeliveryPaused { assertTrue(sent.isEmpty()) } // Nested clear cannot resume early.
                dao.upsert(entity("future"))
                processor.flush()
                assertTrue(sent.isEmpty())
            }
        }
        runCurrent(); inTransaction.await()
        flushing.await() // Cancelled first preparation must not continue into old-second.
        assertTrue(sent.isEmpty())
        finishTransaction.complete(Unit); clear.await(); runCurrent()
        processor.flush()
        assertEquals(listOf("future"), sent)
    }

    @Test
    fun failedNestedPauseRestoresDelivery() = runTest {
        val dao = InMemoryMessageDao(); dao.upsert(entity("retained"))
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" }) {
            sent += it.clientMessageId; dao.updateStatus(it.clientMessageId, "sent")
        }
        try { processor.withDeliveryPaused { processor.withDeliveryPaused { error("disk failure") } } }
        catch (_: IllegalStateException) { }
        processor.flush()
        assertEquals(listOf("retained"), sent)
    }

    @Test
    fun chatClearDuringCapturedMediaPreparationSkipsOldSendButAllowsLaterMessage() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("old-upload"))
        val prepared = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(
            dao, backgroundScope, { 1000L }, { "me" },
            prepareAttempt = {
                if (it.clientMessageId == "old-upload") {
                    prepared.complete(Unit)
                    releaseUpload.await() // Worker already captured the old media row.
                }
            },
            attemptSend = { sent += it.clientMessageId; dao.updateStatus(it.clientMessageId, "sent") },
        )
        val flushing = async { processor.flush() }
        runCurrent(); prepared.await()
        dao.hideLocally("old-upload") // Same durable flag set by atomic bounded chat clear.
        dao.upsert(entity("future-message"))
        releaseUpload.complete(Unit); flushing.await()
        processor.flush()
        assertEquals(listOf("future-message"), sent)
        assertTrue(dao.findByClientMessageId("old-upload")!!.hiddenLocally)
        assertEquals("sent", dao.findByClientMessageId("future-message")!!.status)
    }

    @Test
    fun lateFailureCannotReplaceAReceiptReceivedDuringPreparation() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("delivered"))
        val processor = MessageOutboxProcessor(
            dao, backgroundScope, { 1000L }, { "me" },
            prepareAttempt = {
                dao.updateStatus(it.clientMessageId, "read")
                throw AuthApiException.Api("validation_error", "late failure")
            }, attemptSend = { error("must not send") },
        )
        processor.flush()
        assertEquals("read", dao.findByClientMessageId("delivered")?.status)
    }
    @Test
    fun changedIdentityStopsRetryingButPreservesMessageAndOtherChatDelivery() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("changed"))
        dao.upsert(entity("other").copy(chatId = "other-chat"))
        var blockedAttempts = 0
        var keyAccepted = false
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" }) { row ->
            if (row.clientMessageId == "changed" && !keyAccepted) {
                blockedAttempts++
                throw com.messenger.app.data.signal.IdentityChangedException("peer", listOf(1))
            }
            dao.updateStatus(row.clientMessageId, "sent")
        }
        processor.flush()
        processor.flush()
        val failed = dao.findByClientMessageId("changed")!!
        assertEquals("failed", failed.status)
        assertEquals(entity("changed").text, failed.text)
        assertEquals(0, failed.retryCount)
        assertEquals(1, blockedAttempts)
        assertEquals("sent", dao.findByClientMessageId("other")?.status)
        keyAccepted = true
        dao.resetToPending("changed") // Existing explicit retry keeps the same logical ID.
        processor.flush()
        assertEquals("sent", dao.findByClientMessageId("changed")?.status)
        assertEquals(failed.text, dao.findByClientMessageId("changed")?.text)
    }

    @Test
    fun preparationMayClearSessionWithoutDeadlockOrStaleSend() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("pending"))
        var epoch = 1
        val gate = com.messenger.app.data.auth.SessionWriteGate { epoch }
        var sends = 0
        val processor = MessageOutboxProcessor(
            dao = dao, scope = backgroundScope, nowEpochMs = { 1000L }, currentUserId = { "me" },
            currentSessionEpoch = { epoch },
            withSessionWrite = { owner, block -> gate.write(owner, block) },
            prepareAttempt = { gate.clear { epoch++ } },
            attemptSend = { sends++ },
        )
        kotlinx.coroutines.withTimeout(1000) { processor.flush() }
        assertEquals(0, sends)
        assertEquals("pending", dao.findByClientMessageId("pending")?.status)
    }

    @Test
    fun cancelledAttemptDoesNotScheduleRetryOrMutatePendingRow() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("cancelled"))
        val cancellation = kotlinx.coroutines.CancellationException("account changed")
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1_000L }, { "me" }) { throw cancellation }
        try {
            processor.flush()
            org.junit.Assert.fail("expected cancellation")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals(cancellation.message, e.message) // async may recover/copy the stack trace
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it === cancellation })
        }
        val row = dao.findByClientMessageId("cancelled")!!
        assertEquals("pending", row.status)
        assertEquals(0, row.retryCount)
        assertEquals(0L, row.nextRetryAtEpochMs)
    }

    @Test
    fun revokedSessionStopsRemainingMessagesInBatch() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("first"))
        dao.upsert(entity("second"))
        var attempts = 0
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" }) {
            attempts++
            throw AuthApiException.Api("session_revoked", "revoked")
        }
        processor.flush()
        assertEquals(1, attempts)
        assertEquals("pending", dao.findByClientMessageId("second")?.status)
    }

    @Test
    fun offlineRetryKeepsChatOrderAndCancellationRemovesQueuedMessage() = runTest {
        val dao = InMemoryMessageDao()
        for (id in listOf("1", "2", "3", "4")) dao.upsert(entity(id))
        var online = false
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" }) { row ->
            if (!online) throw AuthApiException.Network("offline")
            sent.add(row.clientMessageId)
            dao.updateStatus(row.clientMessageId, "sent")
        }
        processor.flush()
        assertEquals(1, dao.findByClientMessageId("1")!!.retryCount)
        assertEquals(0, dao.findByClientMessageId("2")!!.retryCount)
        online = true
        processor.flush()
        assertTrue(sent.isEmpty()) // first message is still in backoff
        dao.hideLocally("2")
        dao.resetPendingRetryWindows("me")
        processor.flush()
        assertEquals(listOf("1", "3", "4"), sent)
    }

    @Test
    fun cancellationDuringPreparationPreventsSendingSnapshot() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("cancel"))
        var sends = 0
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            prepareAttempt = { dao.hideLocally(it.clientMessageId) }, attemptSend = { sends++ })
        processor.flush()
        assertEquals(0, sends)
    }

    @Test
    fun userCancellationReleasesNetworkGateAndRemainingQueue() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("cancel"))
        dao.upsert(entity("next"))
        val gate = com.messenger.app.data.auth.SessionWriteGate { 1 }
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(
            dao, backgroundScope, { 1000L }, { "me" }, currentSessionEpoch = { 1 },
            withSessionWrite = { owner, block -> gate.write(owner, block) },
            attemptSend = { row ->
                if (row.clientMessageId == "cancel") {
                    started.complete(Unit)
                    kotlinx.coroutines.awaitCancellation() // Offline request holds gate.
                }
                sent.add(row.clientMessageId)
                dao.updateStatus(row.clientMessageId, "sent")
            },
        )
        processor.triggerFlush()
        started.await()
        kotlinx.coroutines.withTimeout(1000) { processor.cancelAndHide("cancel") }
        processor.flush()
        assertTrue(dao.findByClientMessageId("cancel")!!.hiddenLocally)
        assertEquals(listOf("next"), sent)
        // A new processor (app restart) must not retry the cancelled row.
        val restarted = MessageOutboxProcessor(dao, backgroundScope, { 999999L }, { "me" }) {
            sent.add(it.clientMessageId)
        }
        restarted.flush()
        assertEquals(listOf("next"), sent)
    }

    @Test
    fun canCancelLaterMessageWhileEarlierMessageHoldsGateOffline() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("head"))
        dao.upsert(entity("cancel"))
        val gate = com.messenger.app.data.auth.SessionWriteGate { 1 }
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        var online = false
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(
            dao, backgroundScope, { 1000L }, { "me" }, currentSessionEpoch = { 1 },
            withSessionWrite = { owner, block -> gate.write(owner, block) },
            attemptSend = { row ->
                if (!online) { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                sent.add(row.clientMessageId)
                dao.updateStatus(row.clientMessageId, "sent")
            },
        )
        processor.triggerFlush()
        started.await()
        kotlinx.coroutines.withTimeout(1000) { processor.cancelAndHide("cancel") }
        assertEquals("pending", dao.findByClientMessageId("head")!!.status)
        assertTrue(dao.findByClientMessageId("cancel")!!.hiddenLocally)
        online = true
        processor.flush()
        assertEquals(listOf("head"), sent)
    }

    private fun entity(clientMessageId: String, status: String = "pending", nextRetryAtEpochMs: Long = 0) =
        MessageEntity(
            clientMessageId = clientMessageId,
            serverId = null,
            chatId = "chat-1",
            senderId = "me",
            text = "hello",
            sequence = null,
            createdAt = "2026-09-07T05:00:00Z",
            status = status,
            retryCount = 0,
            nextRetryAtEpochMs = nextRetryAtEpochMs,
            lastErrorClass = null,
        )

    @Test
    fun transientFailureStaysPendingAndSchedulesRetry() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("m1"))
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(
            dao = dao,
            scope = this,
            nowEpochMs = { 1_000L },
            currentUserId = { "me" },
            attemptSend = { throw AuthApiException.Network("down") },
        )
        processor.flush()
        val row = dao.findByClientMessageId("m1")!!
        assertEquals("pending", row.status)
        assertEquals(1, row.retryCount)
        assertTrue(row.nextRetryAtEpochMs > 1_000L)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun permanentFailureMarksFailed() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("m1"))
        val processor = MessageOutboxProcessor(
            dao = dao,
            scope = this,
            nowEpochMs = { 1_000L },
            currentUserId = { "me" },
            attemptSend = { throw AuthApiException.Api("not_found", "chat gone") },
        )
        processor.flush()
        assertEquals("failed", dao.findByClientMessageId("m1")!!.status)
    }

    @Test
    fun authTerminalLeavesPendingWithoutRetrySchedule() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("m1"))
        val processor = MessageOutboxProcessor(
            dao = dao,
            scope = this,
            nowEpochMs = { 1_000L },
            currentUserId = { "me" },
            attemptSend = { throw AuthApiException.Api("session_revoked", "dead") },
        )
        processor.flush()
        val row = dao.findByClientMessageId("m1")!!
        assertEquals("pending", row.status) // auth layer owns logout; outbox leaves it
        assertEquals(0, row.retryCount)
    }

    @Test
    fun successIsReconciledByAttemptSendAndNotDoubleSent() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("m1"))
        var attempts = 0
        val processor = MessageOutboxProcessor(
            dao = dao,
            scope = this,
            nowEpochMs = { 1_000L },
            currentUserId = { "me" },
            attemptSend = { e ->
                attempts++
                dao.updateStatus(e.clientMessageId, "sent")
            },
        )
        processor.flush()
        processor.flush() // second flush must not re-send already-sent rows
        assertEquals(1, attempts)
        assertEquals("sent", dao.findByClientMessageId("m1")!!.status)
    }

    @Test
    fun forceFlushResetsStaleBackoffWindowBeforeFlushing() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("m1", nextRetryAtEpochMs = 9_999_999L))
        var attempts = 0
        val processor = MessageOutboxProcessor(
            dao = dao,
            scope = this,
            nowEpochMs = { 1_000L },
            currentUserId = { "me" },
            attemptSend = { e ->
                attempts++
                dao.updateStatus(e.clientMessageId, "sent")
            },
        )
        // Without a window reset the far-future nextRetryAt would make this a no-op.
        processor.forceFlush()
        advanceUntilIdle()
        assertEquals(1, attempts)
        assertEquals("sent", dao.findByClientMessageId("m1")!!.status)
    }

    @Test
    fun accountSwitchMidFlushStopsSendingOtherUsersMessages() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(entity("m1"))
        dao.upsert(entity("m2"))
        var current = "me"
        var attempts = 0
        val processor = MessageOutboxProcessor(
            dao = dao,
            scope = this,
            nowEpochMs = { 1_000L },
            currentUserId = { current },
            attemptSend = { e ->
                attempts++
                dao.updateStatus(e.clientMessageId, "sent")
                current = "someone-else" // logout mid-send
            },
        )
        processor.flush()
        // Only the first message was attempted; the account switch stopped
        // the flush before touching m2 (which belongs to "me", not the new
        // session's account).
        assertEquals(1, attempts)
        assertEquals("sent", dao.findByClientMessageId("m1")!!.status)
        assertEquals("pending", dao.findByClientMessageId("m2")!!.status)
    }
}
