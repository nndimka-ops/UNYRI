package com.messenger.app.data.directtransfer

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class DirectControlFreezeResult { FROZEN, DUPLICATE, BLOCKED, RETIRED }

/** The actual durable control publisher. Locks are per transfer, never per coordinator/account. */
internal class DirectControlOutbox(
    private val read: (String) -> DirectTransferRecord?,
    private val mutate: suspend (String, (DirectTransferRecord) -> DirectTransferRecord) -> Unit,
    private val checkCurrent: () -> Unit,
    private val mayCommit: (DirectTransferRecord, DirectFileControl) -> Boolean,
    private val encrypt: suspend (DirectFileControl, ByteArray) -> DirectSignalEnvelope,
    private val post: suspend (String, DirectSignalEnvelope) -> Unit,
    private val beforeFreezePublication: (DirectFileControl) -> Unit = {},
    private val retireBeforePost: (String, FrozenDirectSignal) -> Boolean = { _, _ -> false },
    private val onRemoved: (FrozenDirectSignal) -> Unit = {},
) {
    private val freezeLocks = ConcurrentHashMap<String, Mutex>()
    private val postLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun freeze(control: DirectFileControl, logicalKey: String, optional: Boolean,
                       onFrozen: (DirectTransferRecord, DirectFileControl) -> DirectTransferRecord = { record, _ -> record }): DirectControlFreezeResult {
        val id = control.transferId
        return freezeLocks.getOrPut(id) { Mutex() }.withLock {
            checkCurrent()
            val before = read(id) ?: return@withLock DirectControlFreezeResult.RETIRED
            if (!mayCommit(before, control)) return@withLock DirectControlFreezeResult.RETIRED
            if (before.pending.any { (it.logicalKey ?: "${it.control.type}:${it.control.attemptId}") == logicalKey } || logicalKey in before.sentControlKeys)
                return@withLock DirectControlFreezeResult.DUPLICATE
            val budget = before.signalBudget ?: DirectSignalBudget.unknown()
            if (if (optional) !budget.mayFreezeOptional(before.pending.size) else !budget.mayFreezeTerminal(before.pending.size))
                return@withLock DirectControlFreezeResult.BLOCKED
            val identity = DirectSignalIdentity(control.senderDeviceId, control.clientSignalId)
            // Debit BEFORE encryption/ambiguous network work. A failed/retired attempt never invents credits.
            mutate(id) { live -> live.copy(signalBudget = (live.signalBudget ?: DirectSignalBudget.unknown()).recording(identity)) }
            checkCurrent()
            if (read(id)?.let { mayCommit(it, control) } != true) return@withLock DirectControlFreezeResult.RETIRED
            val bytes = DirectFileOffer.codec.encodeToString(DirectFileControl.serializer(), control).toByteArray(Charsets.UTF_8)
            require(bytes.size <= 48 * 1024)
            val envelope = encrypt(control, bytes)
            checkCurrent()
            require(envelope.clientSignalId == control.clientSignalId && envelope.recipientDeviceId == control.recipientDeviceId &&
                envelope.messageType in 1..2 && envelope.ciphertext.length <= 90_000)
            var frozen = false
            mutate(id) { live ->
                if (!mayCommit(live, control) || live.pending.size >= DirectSignalBudget.MAX_PENDING) live
                else {
                    frozen = true
                    beforeFreezePublication(control)
                    onFrozen(live.copy(pending = live.pending + FrozenDirectSignal(envelope, control, logicalKey)), control)
                }
            }
            if (frozen) DirectControlFreezeResult.FROZEN else DirectControlFreezeResult.RETIRED
        }
    }

    suspend fun flush(id: String) = postLocks.getOrPut(id) { Mutex() }.withLock {
        val snapshot = read(id)?.pending.orEmpty()
        for (frozen in snapshot) {
            checkCurrent()
            if (read(id)?.pending?.none { it.envelope.clientSignalId == frozen.envelope.clientSignalId } != false) continue
            // Only an exact same-process never-attempted READY can be retired; unknown/attempted ciphertext retries unchanged.
            var retired = retireBeforePost(id, frozen)
            if (!retired) {
                try { post(id, frozen.envelope) }
                catch (error: DirectReadyRetired) {
                    retired = retireBeforePost(id, frozen)
                    if (!retired) throw error
                }
            }
            checkCurrent()
            mutate(id) { live -> live.copy(pending = live.pending.filterNot { it.envelope.clientSignalId == frozen.envelope.clientSignalId },
                sentControlKeys = if (retired) live.sentControlKeys else (live.sentControlKeys + (frozen.logicalKey ?: "${frozen.control.type}:${frozen.control.attemptId}")).distinct().takeLast(256),
                completionAcknowledged = live.completionAcknowledged || !retired && frozen.control.type == "complete_ack") }
            onRemoved(frozen)
        }
    }
}
