package com.messenger.app.data.directtransfer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Three fixed bulk lanes. A next-source copy can never occupy the current OUT prefix lane. */
internal class DirectFileWorkLanes(
    private val staging: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val outbound: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val inbound: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val stagingGate = Mutex()
    // limitedParallelism alone permits another logical copy to start whenever a journal hook suspends.
    suspend fun <T> runStaging(work: suspend () -> T): T = stagingGate.withLock { withContext(staging) { work() } }
    suspend fun <T> runOutboundHash(work: suspend () -> T): T = withContext(outbound) { work() }
    suspend fun <T> runInboundHash(work: suspend () -> T): T = withContext(inbound) { work() }
}
