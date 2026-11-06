package com.messenger.app.data.directtransfer

/** Production predicates shared by publisher, recovery and all RTC admission points. */
internal object DirectManualRuntime {
    private val hardReasons = setOf("prefix_mismatch", "integrity_failed", "integrity_mismatch", "verification_failed", "disk_full",
        "source_missing", "source_changed", "security_error", "storage_error", "unsupported", "access_unavailable", "transfer_unavailable", "permission_denied", "expired")
    fun safetyBlocked(record: DirectTransferRecord): Boolean = record.reason in hardReasons
    fun retiringNegotiation(record: DirectTransferRecord): DirectTransferRecord = record.copy(state = "paused",
        reason = if (safetyBlocked(record)) record.reason else null, requestId = null, acceptedRequest = null, speedBytesPerSecond = 0)
    fun state(record: DirectTransferRecord) = record.manualPause ?: DirectManualPauseState()
    fun mayChange(record: DirectTransferRecord): Boolean = !record.terminal && !record.mailboxClosed && record.state != "verifying" &&
        record.durableOffset < record.offer.size && (record.role == "sender" || record.role == "receiver" && record.consent)
    fun peerProofObserved(record: DirectTransferRecord): Boolean = if (record.role == "sender") record.consent && record.peerDeviceId != null
        else record.role == "receiver" && record.consent && record.registered && (record.queue?.identityProofObserved == true || record.requestAcknowledgedAt > 0)
    fun mayPublishState(record: DirectTransferRecord): Boolean = !record.terminal && !record.mailboxClosed && peerProofObserved(record)
    fun byteEligible(record: DirectTransferRecord): Boolean = !record.executionInterrupted && !record.terminal && !record.mailboxClosed && record.consent &&
        state(record).manualByteEligible && record.reason !in hardReasons
    fun requestEligible(record: DirectTransferRecord): Boolean {
        if (record.executionInterrupted || record.role != "receiver" || record.terminal || record.mailboxClosed || !record.consent || record.reason in hardReasons) return false
        val state = state(record)
        if (state.localPaused || state.peerPaused) return false
        return state.manualByteEligible || !peerProofObserved(record)
    }
    /** The packet itself must acknowledge our CURRENT intent, not an unrelated later packet. */
    fun packetMayArm(record: DirectTransferRecord, control: DirectFileControl): Boolean {
        if (!byteEligible(record)) return false
        val state = state(record)
        val snapshot = control.validatedManualSnapshot()
        return if (snapshot == null) state.localRevision == 0L && state.peerRevision == 0L
        else !snapshot.paused && snapshot.pauseRevision >= state.peerRevision && snapshot.ackPauseRevision == state.localRevision
    }
    fun changedLocal(record: DirectTransferRecord, paused: Boolean, now: Long): DirectTransferRecord {
        if (!mayChange(record)) return record
        val old = state(record)
        var next = old.changingLocal(paused)
        if (next == old) return record
        if (DirectManualTiming.canResetCycle(now, old.syncCycleStartedAt)) next = next.copy(syncProbeCount = 0, nextSyncAt = 0, syncCycleStartedAt = now)
        return retiringNegotiation(record).copy(manualPause = next)
    }
}

/** Expected retirement of a local async operation, never an auth collector cancellation. */
internal class DirectOperationRetired : Exception("Transfer operation was superseded")

/** Local journal reconciliation only; this grants no nonce, consent, claim or PC authority. */
internal object DirectManualJournalPolicy {
    private fun binding(before: DirectTransferRecord, after: DirectTransferRecord) {
        require(before.offer == after.offer && before.chatId == after.chatId && before.role == after.role) { "Transfer journal binding changed" }
    }
    private fun monotonic(before: DirectTransferRecord, after: DirectTransferRecord) {
        val old = DirectManualRuntime.state(before).validated()
        val next = DirectManualRuntime.state(after).validated()
        require(next.localRevision >= old.localRevision && (next.localRevision != old.localRevision || next.localPaused == old.localPaused)) { "Own manual intent regressed" }
        require(next.peerRevision >= old.peerRevision && (next.peerRevision != old.peerRevision || next.peerPaused == old.peerPaused)) { "Peer manual intent regressed" }
        require(next.acknowledgedLocalRevision >= old.acknowledgedLocalRevision && next.publishedLocalRevision >= old.publishedLocalRevision) { "Manual acknowledgement regressed" }
    }
    fun reconciled(memory: DirectTransferRecord, durable: DirectTransferRecord): DirectTransferRecord {
        binding(memory, durable); monotonic(memory, durable)
        require(!memory.terminal || memory.completed == durable.completed && memory.state == durable.state) { "Terminal journal regressed" }
        // A canonical read cannot silently undo a local/cold execution stop. Only the
        // explicit arming transaction writes executionInterrupted=false into memory.
        return if (memory.executionInterrupted && !durable.executionInterrupted)
            durable.copy(executionSession = null, executionInterrupted = true) else durable
    }
    fun transition(before: DirectTransferRecord, requested: DirectTransferRecord): DirectTransferRecord {
        binding(before, requested)
        // A stale initial read may not change a terminal domain decision or its cleanup authority.
        val after = if (before.terminal && (requested.completed != before.completed || requested.state != before.state)) {
            before.copy(cursor = maxOf(before.cursor, requested.cursor), processingSignal = requested.processingSignal,
                signalBudget = requested.signalBudget ?: before.signalBudget)
        } else requested
        monotonic(before, after)
        return after
    }
}
