package com.messenger.app.data.directtransfer

/** Server closure/absence and local expiry retire undeliverable protocol work durably. */
internal fun DirectTransferRecord.withClosedMailbox(expired: Boolean = false): DirectTransferRecord = copy(
    state = if (terminal) state else if (expired) "expired" else "paused",
    reason = if (terminal) reason else if (expired) "expired" else "transfer_unavailable",
    mailboxClosed = true, closePending = false, pending = emptyList(), processingSignal = null,
    requestId = null, acceptedRequest = null, speedBytesPerSecond = 0,
)

/** Persist through the coordinator's normal journal before the scheduler can observe closure. */
internal suspend fun closeDirectMailbox(
    id: String, expired: Boolean,
    stop: suspend (String) -> Unit,
    mutate: suspend (String, (DirectTransferRecord) -> DirectTransferRecord) -> Unit,
) {
    stop(id)
    mutate(id) { it.withClosedMailbox(expired) }
}

/** Shared by polling and explicit actions, including failures from reads, control POSTs and close. */
internal suspend fun runDirectMailboxOperation(
    id: String,
    read: (String) -> DirectTransferRecord?,
    nowSeconds: () -> Long,
    hasActiveConnection: (String) -> Boolean,
    close: suspend (String, Boolean) -> Unit,
    operation: suspend () -> Unit,
) {
    val initial = read(id) ?: return
    if (initial.offer.expiresAt <= nowSeconds()) { close(id, true); return }
    if (initial.mailboxClosed) { close(id, false); return }
    try { operation() }
    catch (error: DirectTransferApiError) {
        val current = read(id) ?: throw error
        // A just-delivered offer can beat the sender's PUT. This one provisional 404 is
        // retried with backoff until registration or expiry; it is not a deleted mailbox.
        if (error.status == 410 || error.status == 404 && !current.isAwaitingRegistration(404, hasActiveConnection(id)))
            close(id, false)
        else throw error
    }
}
