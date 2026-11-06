package com.messenger.app.data.attachment

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** withContext can discard a successful result when cancellation wins the return
 * dispatch. Retain ownership until the caller actually receives the resource. */
internal suspend fun <T : Any> transferPreparedResource(
    dispatcher: CoroutineDispatcher,
    create: suspend () -> T,
    discard: suspend (T) -> Unit,
): T {
    var prepared: T? = null
    try {
        return withContext(dispatcher) { create().also { prepared = it } }
    } catch (failure: Throwable) {
        prepared?.let { resource ->
            withContext(NonCancellable + dispatcher) {
                try { discard(resource) }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            }
        }
        throw failure
    }
}
