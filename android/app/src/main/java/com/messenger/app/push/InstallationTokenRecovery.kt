package com.messenger.app.push

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Finish the SDK reset and its durable acknowledgement together even if a registration
 * attempt is superseded by foreground/reconnect. A failed reset remains retryable. */
internal class InstallationTokenRecovery(
    private val inspected: () -> Boolean,
    private val requiresReset: () -> Boolean,
    private val reset: suspend () -> Unit,
    private val complete: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val prepareReset: suspend () -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun beforeToken(): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            if (inspected()) return@withLock false
            withContext(NonCancellable) {
                val repair = requiresReset()
                if (repair) { prepareReset(); reset() }
                // Record unaffected inspections too; directory scans are one-time work.
                complete()
                repair
            }
        }
    }
}
