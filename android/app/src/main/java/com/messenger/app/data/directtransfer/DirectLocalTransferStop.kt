package com.messenger.app.data.directtransfer

/** Account-scoped display metadata only. Never authority, consent, an attempt or a lease. */
data class DirectLocalTransferStop(
    val transferId: String,
    val requestedPaused: Boolean,
    val pending: Boolean,
    val saveFailed: Boolean,
)

internal val DIRECT_LOCAL_STOP_REASONS = setOf(
    "manual_pause_saving", "manual_continue_saving",
    "manual_pause_save_failed", "manual_continue_save_failed",
)

/** A temporary display copy; the durable record and its hard error remain unchanged. */
fun DirectFilePresentation.withLocalStop(stop: DirectLocalTransferStop?): DirectFilePresentation {
    if (stop == null || stop.transferId != offer.transferId || !stop.pending && !stop.saveFailed ||
        !manualControlsAvailable ||
        completed || state in setOf("completed", "cancelled", "rejected", "expired", "other_device", "verifying") ||
        role !in setOf("sender", "receiver") || reason == "history_only" || offset >= offer.size) return this
    val code = if (stop.pending) {
        if (stop.requestedPaused) "manual_pause_saving" else "manual_continue_saving"
    } else {
        if (stop.requestedPaused) "manual_pause_save_failed" else "manual_continue_save_failed"
    }
    return copy(
        state = "paused",
        reason = code,
        speedBytesPerSecond = 0,
        // A display layer must never manufacture unavailable authorization.
        manualControlsAvailable = manualControlsAvailable && !stop.pending,
    )
}
