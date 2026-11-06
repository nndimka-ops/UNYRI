package com.messenger.app.data.directtransfer

import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal enum class DirectHttpPriority { CONTROL, LIVE, QUEUED, PASSIVE }

/** Scheduling metadata only; never peer, consent, attempt or journal authority. */
internal class DirectHttpContext(val priority: DirectHttpPriority, val urgent: Boolean = false) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DirectHttpContext>
}

/** The client has synchronously enqueued; response I/O is awaited outside the gate lock. */
internal class DirectHttpStarted<T>(val awaitResult: suspend () -> T, val cancel: () -> Unit)

/** One process/device direct-API gate. Admission notifications are not rate credits. */
internal class DirectHttpGate(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val budget: DirectRequestBudget = DirectRequestBudget(),
) {
    private data class Waiter(val id: String, val key: String?, val read: Boolean,
        val context: DirectHttpContext, val ownerJob: Job?, var ready: CompletableDeferred<Unit>) {
        // Published after preparation returns, before monitor contention. No authority/credit.
        @Volatile var prepared = false
        // Set once at initial insertion; never reset by a later requeue.
        var enqueueOrder = 0L
    }
    // All sections are bounded bookkeeping, synchronous guards and OkHttp enqueue only.
    // A monitor also lets a response callback install 429 cooldown synchronously,
    // independently of a cancelled or not-yet-resumed request coroutine.
    private val mutex = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val waiters = mutableListOf<Waiter>()
    private var lastEnqueueOrder = 0L
    private val active = mutableSetOf<String>()
    private val started = mutableSetOf<String>()
    private val preparing = mutableMapOf<String, Waiter>()
    private val backgroundActive = mutableSetOf<String>()
    private val promoted = mutableMapOf<String, String>()
    private var wakeJob: Job? = null
    private var wakeGeneration = 0L

    /**
     * prepare may suspend and is repeated after a lost final admission. The final
     * guard and start MUST NOT suspend or do journal/network I/O. The client start
     * boundary is its synchronous enqueue/callStart, not OS or wire scheduling.
     */
    suspend fun <P, T> withPermit(key: String?, read: Boolean, prepare: suspend () -> P,
        beforeStart: () -> Unit = {}, start: (P) -> DirectHttpStarted<T>): T {
        val ownerContext = currentCoroutineContext()
        ownerContext.ensureActive()
        val waiter = Waiter(UUID.randomUUID().toString(), key, read,
            ownerContext[DirectHttpContext] ?: DirectHttpContext(DirectHttpPriority.QUEUED),
            ownerContext[Job], CompletableDeferred())
        var request: DirectHttpStarted<T>? = null
        var succeeded = false
        try {
            synchronized(mutex) {
                // Preparing requests are still unstarted waiters. Keeping their
                // places prevents a final-rate requeue from exceeding this bound.
                require(waiters.size + active.count { it !in started } < MAX_WAITERS) {
                    "Direct API request queue full"
                }
                check(lastEnqueueOrder < Long.MAX_VALUE) { "Direct API queue order exhausted" }
                waiter.enqueueOrder = ++lastEnqueueOrder
                waiters += waiter
                drainLocked()
            }
            while (request == null) {
                waiter.ready.await()
                ownerContext.ensureActive()
                val prepared = prepare() // token refresh/request preparation: no gate lock
                ownerContext.ensureActive()
                waiter.prepared = true
                request = synchronized(mutex) {
                    ownerContext.ensureActive()
                    check(waiter.id in active)
                    val now = clock()
                    val requestClass = kind(waiter)
                    val isBackground = background(waiter)
                    val backgroundBusy = isBackground && backgroundActive.any { it != waiter.id }
                    if (backgroundBusy || requestDelayMillis(waiter, now) != 0L || higherPreparedRead(waiter, now)) {
                        // Preparation, ready priority or continuation delivery changed
                        // eligibility. Neither rate nor background-capacity waits
                        // retain an HTTP permit or publish a final attempt marker.
                        active.remove(waiter.id)
                        preparing.remove(waiter.id)
                        waiter.prepared = false
                        backgroundActive.remove(waiter.id)
                        waiter.ready = CompletableDeferred()
                        waiters += waiter
                        drainLocked()
                        null
                    } else {
                        // Capacity notifications precede suspending preparation;
                        // latch the actual enqueue classification, not that old one.
                        if (isBackground) backgroundActive += waiter.id else backgroundActive.remove(waiter.id)
                        // The monitor reserves this eligible start while the
                        // bounded final guard runs. No other caller can consume
                        // or install cooldown here, and elapsed time only makes
                        // eligibility better. Timestamp beside enqueue, rather
                        // than before a guard whose thread might resume later.
                        var debitAttempted = false
                        try {
                            ownerContext.ensureActive()
                            beforeStart()
                            ownerContext.ensureActive()
                            debitAttempted = true
                            budget.consume(clock(), requestClass)
                            start(prepared).also {
                                started += waiter.id
                                preparing.remove(waiter.id)
                                waiter.prepared = false
                                // Gained urgency may have released a conservative
                                // background mark while another permit is free.
                                drainLocked()
                            }
                        } catch (failure: Throwable) {
                            // Preserve conservative credit semantics even when a
                            // final guard throws/cancels without enqueuing HTTP.
                            if (!debitAttempted) budget.consume(clock(), requestClass)
                            throw failure
                        }
                    }
                }
            }
            val result = request.awaitResult()
            succeeded = true
            return result
        } finally {
            if (!succeeded) runCatching { request?.cancel?.invoke() }
            // Non-suspending cleanup also covers cancellation after notification
            // but before await resumed, and after enqueue but before response await.
            synchronized(mutex) {
                waiters.removeAll { it.id == waiter.id }
                active.remove(waiter.id)
                preparing.remove(waiter.id)
                waiter.prepared = false
                started.remove(waiter.id)
                backgroundActive.remove(waiter.id)
                drainLocked()
            }
        }
    }

    suspend fun <T> promoting(key: String, operation: suspend () -> T): T {
        val lease = UUID.randomUUID().toString()
        synchronized(mutex) {
            require(key in promoted || promoted.size < MAX_WAITERS)
            promoted[key] = lease
            drainLocked()
        }
        try { return operation() }
        finally {
            synchronized(mutex) {
                if (promoted[key] == lease) promoted.remove(key)
                drainLocked()
            }
        }
    }

    /** Called directly by the response callback BEFORE it completes the response. */
    fun backOff(retryAfterMillis: Long) = synchronized(mutex) {
        budget.deferAfter429(clock(), retryAfterMillis)
        drainLocked()
    }

    private fun urgent(w: Waiter) = w.context.urgent || w.key in promoted
    private fun rank(w: Waiter) = if (urgent(w)) -1 else w.context.priority.ordinal
    private fun background(w: Waiter) = w.context.priority >= DirectHttpPriority.QUEUED && !urgent(w)
    private fun kind(w: Waiter): DirectRequestClass = if (w.read) {
        if (urgent(w) || w.context.priority <= DirectHttpPriority.LIVE) DirectRequestClass.LIVE_READ else DirectRequestClass.NORMAL_READ
    } else if (urgent(w)) DirectRequestClass.PRIORITY_WRITE else DirectRequestClass.NORMAL_WRITE

    /** At most one shared read credit stays available for a higher pending read. */
    private fun requestDelayMillis(w: Waiter, now: Long): Long {
        val ordinary = budget.delayMillis(now, kind(w))
        if (!w.read) return ordinary
        val higher = waiters.any { higherPendingRead(it, w, now) } ||
            preparing.values.any { higherPendingRead(it, w, now) }
        return if (higher) maxOf(ordinary, budget.readHeadroomDelayMillis(now)) else ordinary
    }

    private fun higherPendingRead(other: Waiter, w: Waiter, now: Long): Boolean =
        other.id != w.id && other.id !in started && other.read && other.ownerJob?.isActive != false &&
            rank(other) < rank(w) && kind(other) == DirectRequestClass.LIVE_READ &&
            (!background(other) || backgroundActive.none { it != other.id }) &&
            budget.delayMillis(now, kind(other)) == 0L

    /** Current prepared/eligible reads compete for the same credit, not monitor arrival. */
    private fun higherPreparedRead(w: Waiter, now: Long): Boolean = w.read && preparing.values.any { other ->
        other.id != w.id && other.read && other.prepared && other.ownerJob?.isActive != false &&
            rank(other) < rank(w) &&
            (!background(other) || backgroundActive.none { it != other.id }) &&
            budget.delayMillis(now, kind(other)) == 0L
    }

    /** Monitor held; no token/producer preparation, response read, await or delay. */
    private fun drainLocked() {
        wakeJob?.cancel()
        wakeJob = null
        val generation = ++wakeGeneration
        while (active.size < MAX_ACTIVE) {
            val now = clock()
            val next = waiters.withIndex().filter {
                (!background(it.value) || backgroundActive.isEmpty()) && !higherPreparedRead(it.value, now) && requestDelayMillis(it.value, now) == 0L
            }.minWithOrNull(compareBy<IndexedValue<Waiter>> { rank(it.value) }.thenBy { it.value.enqueueOrder }) ?: break
            val waiter = waiters.removeAt(next.index)
            active += waiter.id
            waiter.prepared = false
            preparing[waiter.id] = waiter
            if (background(waiter)) backgroundActive += waiter.id
            waiter.ready.complete(Unit) // capacity notification only; no credit/debit
        }
        if (active.size >= MAX_ACTIVE || waiters.isEmpty()) return
        val now = clock()
        val candidates = waiters.filter { (!background(it) || backgroundActive.isEmpty()) && !higherPreparedRead(it, now) }
        if (candidates.isEmpty()) return
        val wait = candidates.minOf { requestDelayMillis(it, now) }.coerceIn(1, 3_600_000)
        wakeJob = scope.launch {
            delay(wait)
            synchronized(mutex) { if (wakeGeneration == generation) drainLocked() }
        }
    }

    companion object { const val MAX_ACTIVE = 2; const val MAX_WAITERS = 64 }
}
