package com.messenger.app.data.message

import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.local.message.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The single authoritative processor for the durable text-message outbox
 *  (N.2R Bug 4). Sending is eventual: a tap inserts a PENDING Room row and
 *  triggers a flush; transient failures stay PENDING with exponential backoff;
 *  only terminal auth failures (logout) or genuinely unrecoverable errors mark
 *  a message FAILED. Exactly-once is guaranteed by the server's
 *  (sender_device_id, client_message_id) idempotency plus the same-clientMessageId
 *  retry rule.
 *
 *  One processor per app (single-flight via [flushMutex]); account-scoped by
 *  [currentUserId] so Alice's queued messages are never sent after Bob logs in.
 */
class MessageOutboxProcessor(
    private val dao: MessageDao,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val currentUserId: () -> String?,
    private val currentSessionEpoch: () -> Int = { 0 },
    /** P0 hardening: runs a write only if the session epoch is still current,
     *  atomically under the same mutex the logout Room-clear uses. The default
     *  is a pass-through (no gating) so plain unit tests need no wiring. */
    private val withSessionWrite: suspend (Int, suspend () -> Unit) -> Unit? = { _, block -> block(); Unit },
    /** Sends one message (build envelopes fresh + POST + reconcile on success).
     *  MUST throw on any failure - classification happens here. */
    /** Auth refresh/chat lookup can themselves clear/write Room; never run under the write gate. */
    private val prepareAttempt: suspend (MessageEntity) -> Unit = {},
    private val mediaReadiness: suspend (MessageEntity) -> OutboxMediaReadiness = { OutboxMediaReadiness.NONE },
    private val prepareMedia: suspend (MessageEntity) -> Unit = {},
    private val attemptSend: suspend (MessageEntity) -> Unit,
) {
    private val flushMutex = Mutex()
    private var scheduledWake: Job? = null
    private var scheduledTime: Long? = null
    private class QueuedMessageCancellation : CancellationException("Queued message cancelled")
    private val cancelledMessages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeAttempt: Pair<String, Job>? = null
    private val deliveryPauses = java.util.concurrent.atomic.AtomicInteger(0)
    private val mediaPreparations = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private data class MediaFailure(val epoch: Int, val error: Exception)
    private val mediaFailures = java.util.concurrent.ConcurrentHashMap<String, MediaFailure>()

    /** Freeze delivery across the short local clear transaction, including nested/concurrent clears. */
    suspend fun <T> withDeliveryPaused(block: suspend () -> T): T {
        deliveryPauses.incrementAndGet()
        try {
            cancelActiveAttempt()
            return block()
        } finally {
            if (deliveryPauses.decrementAndGet() == 0) triggerFlush()
        }
    }

    /** Stop preparation/network work before waiting for the session write gate.
     * A request already accepted by the server cannot be recalled locally. */
    suspend fun cancelAndHide(clientMessageId: String, epochAtStart: Int = currentSessionEpoch()) {
        val epoch = epochAtStart
        cancelledMessages.add(clientMessageId)
        try {
            mediaPreparations[clientMessageId]?.let { it.cancel(QueuedMessageCancellation()); it.join() }
            mediaFailures.remove(clientMessageId)
            // Another queued message may own the gate while offline. Interrupt
            // that attempt too; its durable row remains pending and is retried
            // with the same id, while only the selected row is hidden.
            activeAttempt?.second?.let { it.cancel(QueuedMessageCancellation()); it.join() }
            withSessionWrite(epoch) { dao.hideLocally(clientMessageId) }
        } finally {
            cancelledMessages.remove(clientMessageId)
        }
        triggerFlush()
    }

    /** Stop existing workers once, then conditionally hide only IDs captured at tap.
     * A POST accepted remotely before cancellation cannot be recalled locally. */
    suspend fun cancelUnsentAndHide(chatId: String, clientMessageIds: List<String>, epochAtStart: Int = currentSessionEpoch()): List<String> {
        val epoch = epochAtStart
        val owner = currentUserId() ?: return emptyList()
        val captured = clientMessageIds.filter { it.isNotBlank() }.distinct().take(10)
        if (captured.isEmpty()) return emptyList()
        return withDeliveryPaused {
            val cancelled = mutableListOf<String>()
            withSessionWrite(epoch) {
                if (currentUserId() == owner) {
                    for (id in captured) {
                        if (dao.hideUnsent(id, chatId, owner) > 0) {
                            mediaFailures.remove(id)
                            cancelled += id
                        }
                    }
                }
            }
            cancelled
        }
    }

    suspend fun cancelActiveAttempt() {
        val media = mediaPreparations.values.toList()
        media.forEach { it.cancel(QueuedMessageCancellation()) }
        media.forEach { it.join() }
        activeAttempt?.second?.let { it.cancel(QueuedMessageCancellation()); it.join() }
    }

    fun triggerFlush() {
        scope.launch { flush() }
    }

    /** Connectivity-restored flush: clears every stale backoff window for the
     *  current account first, then flushes the whole queue in one pass. Used
     *  on login and socket (re)connect, where "retry later" no longer makes
     *  sense - the server is reachable NOW. */
    fun forceFlush() {
        scope.launch {
            flushMutex.withLock {
                val epoch = currentSessionEpoch()
                currentUserId()?.let { uid ->
                    withSessionWrite(epoch) { dao.resetPendingRetryWindows(uid) }
                }
                flushLocked()
            }
        }
    }

    suspend fun flush() = flushMutex.withLock { flushLocked() }

    private suspend fun flushLocked() {
        if (deliveryPauses.get() > 0) return
        val userId = currentUserId() ?: return // not logged in: nothing to send
        val epoch = currentSessionEpoch()
        val due = dao.nextScheduledAt(userId, nowEpochMs())
        if (due != scheduledTime) {
            scheduledWake?.cancel(); scheduledTime = due
            scheduledWake = due?.let { time -> scope.launch { delay((time - nowEpochMs()).coerceAtLeast(1)); scheduledTime = null; flush() } }
        }
        val eligible = dao.pendingEligible(userId, nowEpochMs())
        val blockedChats = mutableSetOf<Pair<String, String?>>()
        val blockedMediaChats = mutableSetOf<String>()
        for (entity in eligible) {
            if (deliveryPauses.get() > 0) return
            if (deliveryLane(entity) in blockedChats) continue
            if (currentUserId() != userId) return // account switched mid-flush
            if (currentSessionEpoch() != epoch) return
            val live = dao.findByClientMessageId(entity.clientMessageId) ?: continue
            if (live.hiddenLocally || live.status != MessageOutboxStatus.PENDING) continue
            val readiness = mediaReadiness(live)
            if (readiness != OutboxMediaReadiness.NONE) {
                if (live.chatId in blockedMediaChats) continue
                if (readiness == OutboxMediaReadiness.WAITING && deferMediaPreparation(live, userId, epoch)) {
                    blockedMediaChats.add(live.chatId)
                    continue
                }
            }
            if (!attemptOne(live, epoch)) return
            if (dao.findByClientMessageId(entity.clientMessageId)?.status == MessageOutboxStatus.PENDING) {
                if (readiness == OutboxMediaReadiness.NONE) blockedChats.add(deliveryLane(entity)) else blockedMediaChats.add(entity.chatId)
            }
        }
    }

    /** A reaction retry must not hold text hostage. Reactions on one target stay ordered. */
    private fun deliveryLane(entity: MessageEntity): Pair<String, String?> =
        entity.chatId to if (entity.kind == "reaction") entity.targetMessageId.orEmpty() else null

    /** One bounded conversion/upload lane; delivery and Signal stay serialized. */
    private fun deferMediaPreparation(entity: MessageEntity, user: String, epoch: Int): Boolean {
        val id = entity.clientMessageId
        mediaFailures[id]?.let { if (it.epoch == epoch) return false else mediaFailures.remove(id) }
        if (mediaPreparations.isNotEmpty()) return true
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (currentSessionEpoch() != epoch || currentUserId() != user || id in cancelledMessages) return@launch
                // Readiness may have suspended before a cancel paused and
                // snapshotted workers. This newly registered worker must use
                // live guarded state rather than that earlier unhidden row.
                var live: MessageEntity? = null
                withSessionWrite(epoch) {
                    val current = dao.findByClientMessageId(id)
                    if (deliveryPauses.get() == 0 && currentUserId() == user && current != null &&
                        current.senderId == user && current.status == MessageOutboxStatus.PENDING && !current.hiddenLocally) live = current
                }
                prepareMedia(live ?: return@launch)
            } catch (_: CancellationException) {
                // Cancellation is handled by the owner; never turn it into FAILED.
            } catch (error: Exception) {
                if (currentSessionEpoch() == epoch && currentUserId() == user) mediaFailures[id] = MediaFailure(epoch, error)
            } finally {
                mediaPreparations.remove(id, job)
                if (coroutineContext[Job]?.isActive == true && currentSessionEpoch() == epoch && currentUserId() == user && deliveryPauses.get() == 0) triggerFlush()
            }
        }
        mediaPreparations[id] = job
        // A cancelled dispatcher may skip the body and its finally block.
        job.invokeOnCompletion { mediaPreparations.remove(id, job) }
        job.start()
        return true
    }

    private suspend fun attemptOne(entity: MessageEntity, epoch: Int): Boolean = coroutineScope {
        val attempt = async(start = CoroutineStart.LAZY) { performAttempt(entity, epoch) }
        activeAttempt = entity.clientMessageId to attempt
        try {
            if (entity.clientMessageId in cancelledMessages) attempt.cancel(QueuedMessageCancellation())
            attempt.await()
        } catch (cancelled: CancellationException) {
            coroutineContext.ensureActive() // Preserve logout/parent cancellation.
            if (cancelled !is QueuedMessageCancellation) throw cancelled
            currentSessionEpoch() == epoch
        } finally {
            activeAttempt = null
        }
    }

    private suspend fun performAttempt(entity: MessageEntity, epoch: Int): Boolean {
        try {
            if (deliveryPauses.get() > 0) return true
            mediaFailures.remove(entity.clientMessageId)?.takeIf { it.epoch == epoch }?.let { throw it.error }
            prepareAttempt(entity)
            var ready: MessageEntity? = null
            withSessionWrite(epoch) {
                val live = dao.findByClientMessageId(entity.clientMessageId)
                if (deliveryPauses.get() == 0 && live != null && !live.hiddenLocally && live.status == MessageOutboxStatus.PENDING) ready = live
            }
            // The gate protects only the local snapshot. Delivery owns its own
            // captured-session checks and short reconciliation transaction.
            coroutineContext.ensureActive()
            if (currentSessionEpoch() != epoch) return false
            ready?.let { if (deliveryPauses.get() == 0) attemptSend(it) }
            // Success: attemptSend already reconciled the row to SENT (sequence set).
        } catch (e: CancellationException) {
            // A cancelled/session-stale attempt is not a send failure. In particular,
            // do not schedule old-account work or change its pending row to FAILED.
            throw e
        } catch (e: Exception) {
            if (currentSessionEpoch() != epoch) return false
            when (OutboxPolicy.classify(e)) {
                OutboxFailureClass.AUTH_TERMINAL -> {
                    // Session is dead; the auth layer logs out and a later flush
                    // for the next account would be scoped away. Stop now.
                    return false
                }
                OutboxFailureClass.PERMANENT -> {
                    withSessionWrite(epoch) {
                        val live = dao.findByClientMessageId(entity.clientMessageId)
                        if (live != null && !live.hiddenLocally && live.status == MessageOutboxStatus.PENDING) {
                            dao.upsert(live.copy(status = MessageOutboxStatus.FAILED,
                                lastErrorClass = OutboxPolicy.diagnosticCode(e)))
                        }
                    }
                }
                OutboxFailureClass.TRANSIENT -> {
                    val nextRetryCount = entity.retryCount + 1
                    val delayMs = OutboxPolicy.retryDelayMs(e, nextRetryCount)
                    withSessionWrite(epoch) {
                        val live = dao.findByClientMessageId(entity.clientMessageId)
                        if (live == null || live.hiddenLocally || live.status != MessageOutboxStatus.PENDING) return@withSessionWrite
                        dao.scheduleRetry(
                            clientMessageId = entity.clientMessageId,
                            retryCount = nextRetryCount,
                            nextRetryAtEpochMs = nowEpochMs() + delayMs,
                            lastErrorClass = OutboxPolicy.diagnosticCode(e),
                        )
                        scheduleDelayedFlush(delayMs)
                    }
                }
            }
        }
        return currentSessionEpoch() == epoch
    }

    private fun scheduleDelayedFlush(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            flush()
        }
    }
}

enum class OutboxMediaReadiness { NONE, WAITING, READY }

/** Status strings written into [MessageEntity.status] by the outbox. PENDING/
 *  SENT/DELIVERED/READ mirror the existing [com.messenger.app.domain.model.MessageStatus]. */
object MessageOutboxStatus {
    const val PENDING = "pending"
    const val FAILED = "failed"
}
