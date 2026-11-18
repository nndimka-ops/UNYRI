package com.messenger.app.call

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main-thread-owned per-call cache. Provider I/O runs exclusively on [dispatcher].
 * A missing/pending/failed sound is silent until proven otherwise; presentation
 * need not wait. Cancelled or invalidated lookups cannot publish into another call. */
internal class IncomingCallSoundResolver<K : Any, S : Any>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val resolve: suspend (S) -> S?,
    private val onReady: () -> Unit,
) {
    private var key: K? = null
    private var selected: S? = null
    private var value: S? = null
    private var generation = 0L
    private var job: Job? = null

    fun soundFor(request: K, sound: S?): S? {
        if (key == request && selected == sound) return value
        invalidate()
        key = request
        selected = sound
        if (sound == null) return null
        val ticket = generation
        job = scope.launch {
            val result = try { withContext(dispatcher) { resolve(sound) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (ticket != generation || key != request || selected != sound) return@launch
            value = result
            onReady()
        }
        return null
    }

    fun invalidate() {
        generation++
        job?.cancel(); job = null
        key = null; selected = null; value = null
    }
}
