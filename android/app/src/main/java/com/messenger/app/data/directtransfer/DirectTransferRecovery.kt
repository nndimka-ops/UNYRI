package com.messenger.app.data.directtransfer

import kotlinx.coroutines.CancellationException

/** Recovery must remain safe even when a blocked peer can no longer receive Signal. */
internal suspend fun notifyDirectPauseBestEffort(notify: suspend () -> Unit, failed: (Exception) -> Unit) {
    try { notify() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { failed(failure) }
}

/** Restore durable intent, never a stale in-process RTC connection. */
internal fun DirectTransferRecord.afterProcessRestart(nowMillis: Long): DirectTransferRecord {
    if ((executionSession != null || executionInterrupted) && !terminal) return DirectExecutionPolicy.interrupted(this)
    if (terminal || !consent) return this
    if (role == "receiver" && state == "accepted") {
        // An acknowledged source hash can legitimately take longer than90s.
        // Preserve that preparation lease instead of repeatedly restarting it.
        return if (requestId != null) copy(requestRetryCount = 1, requestCreatedAt = nowMillis,
            requestAcknowledgedAt = if (requestAcknowledgedAt > 0) nowMillis else 0) else this
    }
    if (role != "sender" && state !in setOf("connecting", "transferring", "verifying", "paused")) return this
    val mustBeResolved = DirectManualRuntime.safetyBlocked(this)
    return copy(state = "paused", reason = if (mustBeResolved || role == "sender" && reason == "queued") reason else if (role == "sender") "reconnect_required" else reason ?: "connection_lost",
        requestId = null, requestRetryCount = 0, requestCreatedAt = 0, requestAcknowledgedAt = 0)
}

/** An authenticated offer can arrive before its sender has registered the mailbox. */
internal fun DirectTransferRecord.isAwaitingRegistration(status: Int, hasActiveConnection: Boolean): Boolean =
    status == 404 && role == "receiver" && !registered && !terminal && !hasActiveConnection

internal fun DirectTransferRecord.registrationWaiting(): DirectTransferRecord =
    if (terminal || DirectManualRuntime.safetyBlocked(this)) this else copy(state = if (consent) "accepted" else "offered", reason = "network_waiting")

/** Only actual verified durable byte progress ends a consecutive failure cycle. */
internal fun DirectTransferRecord.acknowledgeProgress(offset: Long, stats: DirectAckStats?): DirectTransferRecord {
    if (terminal) return this
    require(offset in durableOffset..offer.size)
    val advanced = offset > durableOffset
    return copy(durableOffset = offset, state = if (offset == offer.size) "verifying" else "transferring", reason = null,
        ackSampleId = stats?.sampleId ?: ackSampleId, speedBytesPerSecond = stats?.speedBytesPerSecond ?: speedBytesPerSecond,
        connectionFailures = if (advanced) 0 else connectionFailures, requestRetryCount = if (advanced) 0 else requestRetryCount)
}
