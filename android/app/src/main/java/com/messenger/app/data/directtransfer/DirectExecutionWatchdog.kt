package com.messenger.app.data.directtransfer

internal object DirectExecutionWatchdog {
    fun delayMillis(session: DirectExecutionSession?, epoch: Int): Long? =
        1_000L.takeIf { session != null && !session.revoked && session.epoch == epoch }
}
