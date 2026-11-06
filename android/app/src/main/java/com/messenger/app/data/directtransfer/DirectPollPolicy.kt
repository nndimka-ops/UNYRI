package com.messenger.app.data.directtransfer

internal data class DirectPollPlan(val cadenceMillis: Long, val priority: DirectHttpPriority, val urgentWrite: Boolean = false, val held: Boolean = false)

/** Runtime cadence only. It grants no consent, READY, role lease or byte eligibility. */
internal object DirectPollPolicy {
    fun plan(record: DirectTransferRecord, ownsRole: Boolean, preparing: Boolean, outboundCapacityAvailable: Boolean = false, heldCadenceMillis: Long = 30_000): DirectPollPlan {
        val terminalRepair = record.terminal && (!record.mailboxClosed || record.pending.isNotEmpty())
        val mayPublishManual = DirectManualRuntime.mayPublishState(record)
        val genuine = mayPublishManual && (record.pending.any { it.logicalKey?.startsWith("pause_state:intent:") == true } ||
            DirectManualRuntime.state(record).let { it.localRevision > it.publishedLocalRevision })
        if (terminalRepair) return DirectPollPlan(2_000, DirectHttpPriority.CONTROL, true)
        if (genuine) return DirectPollPlan(2_000, DirectHttpPriority.QUEUED, true)
        if (ownsRole || preparing || record.requestId != null)
            return DirectPollPlan(2_000, DirectHttpPriority.LIVE)
        if (record.pending.isNotEmpty()) return DirectPollPlan(2_000, DirectHttpPriority.QUEUED)
        val manual = DirectManualRuntime.state(record)
        if (mayPublishManual && manual.syncNeeded && manual.syncProbeCount < DirectManualTiming.MAX_PROBES)
            return DirectPollPlan(2_000, DirectHttpPriority.QUEUED)
        if (manual.localPaused || manual.peerPaused || !manual.manualByteEligible)
            return DirectPollPlan(heldCadenceMillis.coerceIn(2_000, 60_000), DirectHttpPriority.QUEUED, held = true)
        if (outboundCapacityAvailable && record.role == "sender" && record.reason == "queued" &&
            record.queue?.capacityHintCount?.let { it in 1..5 } == true)
            return DirectPollPlan(2_000, DirectHttpPriority.QUEUED)
        if (record.role == "receiver" && !record.consent)
            return DirectPollPlan(10_000, DirectHttpPriority.PASSIVE)
        return DirectPollPlan(20_000, DirectHttpPriority.QUEUED)
    }

    /** Account-wide estimate; the actual API rolling gate remains the hard authority. */
    fun heldCadence(records: Collection<DirectTransferRecord>, ownsRole: (DirectTransferRecord) -> Boolean,
        preparing: (DirectTransferRecord) -> Boolean, outboundCapacityAvailable: Boolean, coldReadReserve: Int): Long {
        var held = 0L; var live = 0L; var normalDemand = 0L
        for (record in records) {
            val base = plan(record, ownsRole(record), preparing(record), outboundCapacityAvailable)
            when {
                base.held -> held++
                base.priority == DirectHttpPriority.LIVE || base.priority == DirectHttpPriority.CONTROL -> live += 30
                else -> normalDemand += 60_000L / base.cadenceMillis
            }
        }
        if (held == 0L) return 2_000
        // Leave rolling headroom and the two real streams'60 reads. Startup reserves ONE extra
        // metadata read per selected nonlive row plus capability; a first tick may use TWO GETs.
        val normalAllowance = minOf(84L, (120L - live).coerceAtLeast(0))
        val available = (normalAllowance - normalDemand - coldReadReserve.coerceIn(0, 33)).coerceAtLeast(1)
        val millis = (60_000L * held + available - 1) / available
        return ((millis + 999) / 1000 * 1000).coerceIn(2_000, 60_000)
    }

}
