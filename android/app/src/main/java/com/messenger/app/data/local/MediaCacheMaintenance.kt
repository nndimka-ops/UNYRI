package com.messenger.app.data.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One account's worker. Eligibility events are queued, never used to cancel an in-flight sweep. */
internal class MediaCacheMaintenance(
    private val policies: Flow<CachePolicy>,
    private val changes: Flow<Unit>,
    private val enforce: suspend (CachePolicy) -> MediaCacheManager.ClearResult,
    private val nextDeadline: suspend (CachePolicy) -> Long?,
) {
    suspend fun run() = policies.distinctUntilChanged().collectLatest { policy ->
        if (policy.maxBytes <= 0 && policy.retentionDays <= 0) return@collectLatest
        coroutineScope {
            val wake = Channel<Unit>(Channel.CONFLATED)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                changes.retryWhen { error, _ ->
                    if (error is CancellationException) throw error
                    delay(RETRY_MILLIS); true
                }.collect { wake.trySend(Unit) }
            }
            try {
                var wait: Long? = 0 // Reconcile on account/policy activation even if events preceded subscription.
                while (true) {
                    if (wait == null) wake.receive()
                    else if (wait > 0) withTimeoutOrNull(wait) { wake.receive() }
                    delay(500) // Coalesce a burst before reading the current protection state.
                    wake.tryReceive()
                    // Do not drain after this point: releases during enforcement/deadline calculation
                    // must survive even if size-only retention returns no next deadline.
                    wait = try {
                        var failed = false
                        do {
                            val result = enforce(policy)
                            failed = failed || result.failedFiles > 0
                            if (result.deletedFiles >= 100) delay(500)
                        } while (result.deletedFiles >= 100)
                        val next = nextDeadline(policy)
                        if (failed) minOf(next ?: RETRY_MILLIS, RETRY_MILLIS) else next
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { RETRY_MILLIS }
                }
            } finally { observer.cancel(); wake.close() }
        }
    }

    private companion object { const val RETRY_MILLIS = 3_600_000L }
}
