package com.messenger.app.ui.transfers

import com.messenger.app.data.directtransfer.DirectFilePresentation
import com.messenger.app.data.directtransfer.DIRECT_LOCAL_STOP_REASONS
import com.messenger.app.data.directtransfer.DirectTransferManagerGroup
import com.messenger.app.data.directtransfer.DirectTransferManagerItem

internal enum class DirectTransferManualAction { PAUSE, CONTINUE }
internal enum class DirectTransferStatus {
    COMPLETED, CANCELLED, REJECTED, EXPIRED, HISTORY, OTHER_DEVICE, VERIFYING,
    PAUSE_SAVING, CONTINUE_SAVING, PAUSE_SAVE_FAILED, CONTINUE_SAVE_FAILED,
    HELD_BOTH, HELD_LOCAL, HELD_PEER, SYNC_LIMIT, CONFIRMING,
    TRANSFERRING, CONNECTING, QUEUED, SERVER_CAPACITY, NO_ROUTE, PREFIX_MISMATCH, DISK_FULL,
    SOURCE_MISSING, UNSUPPORTED, INTEGRITY_FAILED, SECURITY_ERROR, CALL_PRIORITY,
    UNAVAILABLE, BACKGROUND_INTERRUPTED, PAUSED, OFFERED, WAITING,
}

internal data class DirectTransferActionPermissions(
    val canCancel: Boolean = false,
    val canOpen: Boolean = false,
    val canRetry: Boolean = false,
    val canRestartFromZero: Boolean = false,
    val allowOfferActions: Boolean = false,
)

internal data class DirectTransferManagerIndex(val id: String, val chatId: String, val outgoing: Boolean,
    val ordinal: Long, val group: DirectTransferManagerGroup)

/** Pure display decisions. Every actual action is revalidated by the coordinator. */
internal object DirectTransferUiPolicy {
    private val terminalStates = setOf("cancelled", "rejected", "expired", "other_device", "completed")
    private val integrityReasons = setOf("prefix_mismatch", "integrity_failed", "integrity_mismatch", "verification_failed")
    private fun terminal(value: DirectFilePresentation) = value.completed || value.state in terminalStates
    private fun inert(value: DirectFilePresentation) = value.role !in setOf("sender", "receiver") || value.state == "other_device" || value.reason == "history_only"
    private fun localStop(value: DirectFilePresentation) = value.reason in DIRECT_LOCAL_STOP_REASONS

    fun managerOrder(values: List<DirectTransferManagerItem>): List<DirectTransferManagerIndex> = values
        .map { DirectTransferManagerIndex(it.id, it.chatId, it.outgoing, it.ordinal, it.group) }
        .sortedWith(compareBy<DirectTransferManagerIndex> { it.group.ordinal }.thenBy { it.ordinal }.thenBy { it.id })

    fun cardPermissions(value: DirectFilePresentation): DirectTransferActionPermissions {
        val retryable = value.state == "paused" && !value.mailboxClosed && !localStop(value) && value.reason !in setOf("transfer_unavailable", "access_unavailable", "source_missing", "source_changed")
        return DirectTransferActionPermissions(
            canCancel = !terminal(value) && !inert(value),
            canOpen = value.completed && value.role == "receiver" && !inert(value),
            canRetry = retryable && value.reason !in integrityReasons,
            canRestartFromZero = retryable && value.role == "receiver" && value.reason in integrityReasons,
            allowOfferActions = !value.mailboxClosed && !inert(value) && value.role == "receiver" && value.state == "offered",
        )
    }

    fun canCancel(value: DirectFilePresentation, permissions: DirectTransferActionPermissions) =
        permissions.canCancel && !terminal(value) && !inert(value)
    fun canOpen(value: DirectFilePresentation, permissions: DirectTransferActionPermissions) =
        permissions.canOpen && value.completed && value.role == "receiver" && !inert(value)
    fun canOfferActions(value: DirectFilePresentation, permissions: DirectTransferActionPermissions) =
        permissions.allowOfferActions && !value.mailboxClosed && !localStop(value) && !terminal(value) && !inert(value) && value.role == "receiver" && value.state == "offered"
    fun canRetry(value: DirectFilePresentation, permissions: DirectTransferActionPermissions) =
        permissions.canRetry && !value.mailboxClosed && !localStop(value) && !terminal(value) && !inert(value) && value.state == "paused" &&
            !(value.reason == "background_interrupted" && value.manualControlsAvailable) &&
            value.reason !in integrityReasons && value.reason !in setOf("transfer_unavailable", "access_unavailable", "source_missing", "source_changed") &&
            !value.localManualPaused && !value.peerManualPaused && !value.manualSyncPending
    fun canRestart(value: DirectFilePresentation, permissions: DirectTransferActionPermissions) =
        permissions.canRestartFromZero && !value.mailboxClosed && !localStop(value) && !terminal(value) && !inert(value) && value.role == "receiver" &&
            value.state == "paused" && value.reason in integrityReasons &&
            !value.localManualPaused && !value.peerManualPaused && !value.manualSyncPending
    fun manualAction(value: DirectFilePresentation): DirectTransferManualAction? {
        if (!value.manualControlsAvailable || terminal(value) || inert(value) || value.state == "verifying" || value.offset >= value.offer.size) return null
        return when (value.reason) {
            "background_interrupted" -> DirectTransferManualAction.CONTINUE
            "manual_pause_saving", "manual_continue_saving" -> null
            "manual_pause_save_failed" -> DirectTransferManualAction.PAUSE
            // An ambiguous false revision may already be durable: retry the requested Continue.
            "manual_continue_save_failed" -> DirectTransferManualAction.CONTINUE
            else -> if (value.localManualPaused) DirectTransferManualAction.CONTINUE else DirectTransferManualAction.PAUSE
        }
    }
    fun canRetrySynchronization(value: DirectFilePresentation) = !localStop(value) && value.manualControlsAvailable && !terminal(value) && !inert(value) &&
        value.state != "verifying" && value.manualSyncPending && value.manualSyncLimitReached

    fun status(value: DirectFilePresentation): DirectTransferStatus = when {
        value.completed || value.state == "completed" -> DirectTransferStatus.COMPLETED
        value.state == "cancelled" -> DirectTransferStatus.CANCELLED
        value.state == "rejected" -> DirectTransferStatus.REJECTED
        value.state == "expired" -> DirectTransferStatus.EXPIRED
        value.state == "other_device" && value.reason == "history_only" -> DirectTransferStatus.HISTORY
        value.state == "other_device" -> DirectTransferStatus.OTHER_DEVICE
        value.mailboxClosed && value.reason == "transfer_unavailable" -> DirectTransferStatus.UNAVAILABLE
        value.state == "verifying" -> DirectTransferStatus.VERIFYING
        value.reason == "manual_pause_saving" -> DirectTransferStatus.PAUSE_SAVING
        value.reason == "manual_continue_saving" -> DirectTransferStatus.CONTINUE_SAVING
        value.reason == "manual_pause_save_failed" -> DirectTransferStatus.PAUSE_SAVE_FAILED
        value.reason == "manual_continue_save_failed" -> DirectTransferStatus.CONTINUE_SAVE_FAILED
        value.localManualPaused && value.peerManualPaused -> DirectTransferStatus.HELD_BOTH
        value.localManualPaused -> DirectTransferStatus.HELD_LOCAL
        value.peerManualPaused -> DirectTransferStatus.HELD_PEER
        value.manualSyncPending && value.manualSyncLimitReached -> DirectTransferStatus.SYNC_LIMIT
        value.manualSyncPending -> DirectTransferStatus.CONFIRMING
        value.reason == "background_interrupted" -> DirectTransferStatus.BACKGROUND_INTERRUPTED
        value.state == "transferring" -> DirectTransferStatus.TRANSFERRING
        value.state == "connecting" -> DirectTransferStatus.CONNECTING
        value.role == "receiver" && value.state == "accepted" -> DirectTransferStatus.QUEUED
        value.reason == "server_capacity" -> DirectTransferStatus.SERVER_CAPACITY
        value.state == "paused" && value.reason == "queued" -> DirectTransferStatus.QUEUED
        value.state == "paused" && value.reason == "direct_unavailable" -> DirectTransferStatus.NO_ROUTE
        value.state == "paused" && value.reason == "prefix_mismatch" -> DirectTransferStatus.PREFIX_MISMATCH
        value.state == "paused" && value.reason == "disk_full" -> DirectTransferStatus.DISK_FULL
        value.state == "paused" && value.reason in setOf("source_missing", "source_changed") -> DirectTransferStatus.SOURCE_MISSING
        value.state == "paused" && value.reason == "unsupported" -> DirectTransferStatus.UNSUPPORTED
        value.state == "paused" && value.reason in integrityReasons -> DirectTransferStatus.INTEGRITY_FAILED
        value.state == "paused" && value.reason == "security_error" -> DirectTransferStatus.SECURITY_ERROR
        value.state == "paused" && value.reason == "call_in_progress" -> DirectTransferStatus.CALL_PRIORITY
        value.state == "paused" && value.reason in setOf("transfer_unavailable", "access_unavailable") -> DirectTransferStatus.UNAVAILABLE
        value.state == "paused" -> DirectTransferStatus.PAUSED
        value.role == "receiver" && value.state == "offered" -> DirectTransferStatus.OFFERED
        else -> DirectTransferStatus.WAITING
    }
}
