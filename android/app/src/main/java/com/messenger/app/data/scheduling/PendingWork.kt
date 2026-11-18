package com.messenger.app.data.scheduling

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Room invalidation starts/stops retries; an empty durable queue owns no timer. */
internal suspend fun retryPendingWork(pending: Flow<Boolean>, retryMillis: Long = 15_000, action: suspend () -> Unit) {
    pending.distinctUntilChanged().collectLatest { hasWork ->
        if (hasWork) while (true) { action(); delay(retryMillis) }
    }
}
