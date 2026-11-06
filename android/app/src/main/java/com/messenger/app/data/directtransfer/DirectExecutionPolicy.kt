package com.messenger.app.data.directtransfer

/** Local execution authority only. Never sent to a peer or restored as a live system lease. */
internal data class DirectExecutionSession(val id: String, val epoch: Int, val granted: Boolean = false, val revoked: Boolean = false)

internal object DirectExecutionPolicy {
    fun disarmed(record: DirectTransferRecord): DirectTransferRecord = record.copy(executionSession = null)
    fun settledHold(record: DirectTransferRecord): Boolean {
        if (record.pausedForLocalCall || record.closedUnavailable || DirectManualRuntime.safetyBlocked(record)) return true
        val manual = DirectManualRuntime.state(record)
        return (manual.localPaused || manual.peerPaused) && manual.acknowledgedLocalRevision == manual.localRevision && manual.pendingResponseTo == null
    }
    fun mayRun(marker: String?, interrupted: Boolean, foreground: Boolean, session: DirectExecutionSession?, epoch: Int): Boolean {
        if (interrupted) return false
        if (marker != null && (session?.id != marker || session.epoch != epoch)) return false
        // Revocation after a grant is also a foreground stop until explicitly continued.
        if (marker != null && session?.id == marker && session.revoked) return false
        return foreground || marker != null && session?.id == marker && session.epoch == epoch && session.granted && !session.revoked
    }

    fun interrupted(record: DirectTransferRecord): DirectTransferRecord = if (record.terminal) record
        // Failed local Accept admission leaves an unclaimed offer, never a newly paused accepted job.
        else if (record.role == "receiver" && !record.consent) record.copy(executionSession = null, executionInterrupted = false)
        else record.copy(
        executionSession = null, executionInterrupted = true, state = "paused",
        reason = if (DirectManualRuntime.safetyBlocked(record)) record.reason else "background_interrupted",
        requestId = null, acceptedRequest = null, requestCreatedAt = 0, requestAcknowledgedAt = 0,
    )
}

internal data class DirectExecutionProgress(
    val epoch: Int,
    val session: String,
    val entries: List<DirectExecutionProgressEntry>,
    val staging: Boolean,
) {
    val busy: Boolean get() = staging || entries.any { it.busy }
    val unfinished: Boolean get() = staging || entries.any { !it.finished && !it.held }
}

internal data class DirectExecutionProgressEntry(
    val id: String, val outgoing: Boolean, val filename: String, val offset: Long, val size: Long,
    val busy: Boolean, val finished: Boolean, val held: Boolean,
)
