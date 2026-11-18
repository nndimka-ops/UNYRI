package com.messenger.app.data.attachment

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Cancel before logout waits for the media gate, including a blocked native export. */
internal suspend fun <T> sessionMediaWork(
    changes: Flow<*>, isCurrent: () -> Boolean, block: suspend () -> T,
): T = coroutineScope {
    val work = async(start = CoroutineStart.LAZY) {
        if (!isCurrent()) throw CancellationException("Media account changed")
        block()
    }
    val observer = launch(start = CoroutineStart.UNDISPATCHED) {
        changes.first { !isCurrent() }
        work.cancel(CancellationException("Media account changed"))
    }
    try { work.await() } finally { observer.cancel() }
}
