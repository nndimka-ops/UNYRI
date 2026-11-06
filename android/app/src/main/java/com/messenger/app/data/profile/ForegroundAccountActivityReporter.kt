package com.messenger.app.data.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** UI RESUMED is the only source of activity. Pausing cancels even an in-flight HTTP call. */
class ForegroundAccountActivityReporter(
    private val repository: AccountAutoDeleteRepository,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = 60_000L,
) {
    private val resumed = mutableSetOf<Any>()
    private var loop: Job? = null
    private var generation = 0L
    private var reportingOwner: AccountAutoDeleteOwner? = null
    private val mutableResumed = MutableStateFlow(false)
    val isResumed: StateFlow<Boolean> = mutableResumed

    @Synchronized fun resumed(activity: Any) { resumed.add(activity); reconcile() }
    @Synchronized fun paused(activity: Any) { resumed.remove(activity); reconcile() }
    @Synchronized fun sessionChanged() { reconcile() }

    private fun reconcile() {
        mutableResumed.value = resumed.isNotEmpty()
        val owner = repository.ownerKey()
        if (resumed.isNotEmpty() && owner != null && owner == reportingOwner && loop?.isActive == true) return
        generation++
        loop?.cancel(); loop = null; reportingOwner = null
        if (resumed.isEmpty() || owner == null) return
        reportingOwner = owner
        val ticket = generation
        val foreground = { synchronized(this) { resumed.isNotEmpty() && generation == ticket && owner == repository.ownerKey() } }
        loop = scope.launch {
            while (isActive && foreground()) {
                try { repository.recordActivity(owner, foreground) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Offline contact does not advance the server's clock. */ }
                delay(intervalMillis)
            }
        }
    }
}
