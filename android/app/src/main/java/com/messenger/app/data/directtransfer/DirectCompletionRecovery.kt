package com.messenger.app.data.directtransfer

import java.util.UUID

internal data class DirectCompletionProbe(val attempt: String, val trigger: String, val nextCount: Int, val nextAt: Long)

/** A receipt probe may recover a lost Signal journal; it never authorizes bytes or new RTC. */
internal fun DirectTransferRecord.completionProbe(nowMillis: Long): DirectCompletionProbe? {
    if (role != "sender" || !completed || completionReceived || !consent || peerDeviceId?.let(::canonicalUuid) != true ||
        attemptId?.let(::canonicalUuid) != true || pending.isNotEmpty() || mailboxClosed || offer.expiresAt <= nowMillis / 1000 ||
        completionRecoveryCount !in 0 until DirectTransferTiming.MAX_REQUESTS || nowMillis < completionRecoveryAt) return null
    val attempt = requireNotNull(attemptId)
    val trigger = UUID.nameUUIDFromBytes("${offer.transferId}|$attempt|completed-receipt|$completionRecoveryCount".toByteArray(Charsets.UTF_8)).toString()
    val delay = minOf(1_800_000L, 90_000L shl completionRecoveryCount)
    return DirectCompletionProbe(attempt, trigger, completionRecoveryCount + 1, nowMillis + delay)
}
