package com.messenger.app.data.directtransfer

internal data class DirectPollPlan(
    val cadenceMillis: Long?, val priority: DirectHttpPriority,
    val urgentWrite: Boolean = false, val retryOnly: Boolean = false,
)

/** Runtime cadence only. It grants no consent, READY, role lease or byte eligibility. */
internal object DirectPollPolicy {
    fun plan(record: DirectTransferRecord, ownsRole: Boolean, preparing: Boolean, outboundCapacityAvailable: Boolean = false): DirectPollPlan {
        val terminalRepair = record.terminal && (!record.mailboxClosed || record.pending.isNotEmpty())
        val mayPublishManual = DirectManualRuntime.mayPublishState(record)
        val canFreezeOptional = record.signalBudget?.mayFreezeOptional(record.pending.size) == true
        val genuine = mayPublishManual && (record.pending.any { it.logicalKey?.startsWith("pause_state:intent:") == true } ||
            canFreezeOptional && DirectManualRuntime.state(record).let { it.localRevision > it.publishedLocalRevision })
        if (terminalRepair) return DirectPollPlan(2_000, DirectHttpPriority.CONTROL, true, retryOnly = true)
        if (genuine) return DirectPollPlan(2_000, DirectHttpPriority.QUEUED, true)
        if (ownsRole || preparing || record.requestId != null)
            return DirectPollPlan(2_000, DirectHttpPriority.LIVE)
        if (record.pending.isNotEmpty()) return DirectPollPlan(2_000, DirectHttpPriority.QUEUED)
        val manual = DirectManualRuntime.state(record)
        if (mayPublishManual && canFreezeOptional && manual.syncNeeded && manual.syncProbeCount < DirectManualTiming.MAX_PROBES)
            return DirectPollPlan(2_000, DirectHttpPriority.QUEUED)
        if (outboundCapacityAvailable && record.role == "sender" && record.reason == "queued" &&
            record.queue?.capacityHintCount?.let { it in 1..5 } == true && manual.manualByteEligible && canFreezeOptional)
            return DirectPollPlan(2_000, DirectHttpPriority.QUEUED)
        // Waiting for the offer's durable send/registration is retry work, not a live stream.
        if (record.role == "sender" && !record.registered)
            return DirectPollPlan(2_000, DirectHttpPriority.QUEUED, retryOnly = true)
        if (record.role == "receiver" && DirectManualRuntime.requestEligible(record) &&
            record.connectionFailures in 1..4 && record.requestRetryCount < DirectTransferTiming.MAX_REQUESTS)
            return DirectPollPlan(2_000, DirectHttpPriority.QUEUED, retryOnly = true)
        // Held, unclaimed and queued mailboxes wake on foreground/reconnect or a peer nudge.
        // No safety timer: a healthy socket already tells us when the mailbox changes.
        return DirectPollPlan(null, DirectHttpPriority.PASSIVE)
    }
}
