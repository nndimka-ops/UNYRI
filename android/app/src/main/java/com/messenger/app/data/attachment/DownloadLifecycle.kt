package com.messenger.app.data.attachment

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs inside the per-attachment writer gate. Cleanup completes before the next writer enters. */
internal suspend fun <T> downloadWithCleanup(cleanup: suspend (Throwable) -> Unit, block: suspend () -> T): T =
    try { block() } catch (failure: Throwable) {
        withContext(NonCancellable) {
            try { cleanup(failure) } catch (error: Throwable) { if (error !== failure) failure.addSuppressed(error) }
        }
        throw failure
    }

/** Once per account activation, before admitting any new transfer. Failure leaves recovery pending. */
internal class DownloadRecovery {
    private val mutex = Mutex()
    private var recovered: Int? = null
    suspend fun ensure(epoch: Int, recover: suspend () -> Unit) = mutex.withLock {
        if (recovered != epoch) { recover(); recovered = epoch }
    }
}
