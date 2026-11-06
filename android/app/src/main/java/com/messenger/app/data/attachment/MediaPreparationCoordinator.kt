package com.messenger.app.data.attachment

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-owned imports. UI departure must never cancel a user's selected files.
 * Call on Main; durable callbacks acquire the account workspace lease. */
class MediaPreparationCoordinator(
    private val scope: CoroutineScope,
    private val epoch: () -> Int,
    private val stage: suspend (Int, String) -> String,
    private val commit: suspend (Int, String, String, Boolean) -> Unit,
    private val marker: suspend (Int, String, Boolean) -> Unit,
    private val discard: suspend (Int, String) -> Unit,
    private val stageWithLimit: (suspend (Int, String, Long) -> String)? = null,
) {
    enum class Failure { SOURCE_LIMIT }
    data class State(val active: Boolean = false, val revision: Long = 0, val failed: Boolean = false, val failure: Failure? = null)
    private data class Operation(val token: Any, val job: Job)
    private val operations = mutableMapOf<String, Operation>()
    private var observedEpoch = epoch()
    private val mutableState = MutableStateFlow<Map<String, State>>(emptyMap())
    val state = mutableState.asStateFlow()
    private fun update(chat: String, active: Boolean, failed: Boolean = false, failure: Failure? = null) {
        val previous = mutableState.value[chat] ?: State()
        mutableState.value = mutableState.value + (chat to State(active, previous.revision + 1, failed, failure))
    }
    fun start(chat: String, sources: List<String>, asFile: Boolean = false, expectedEpoch: Int = epoch(), sourceLimitBytes: Long? = null, cleanup: () -> Unit = {}): Boolean {
        require(sourceLimitBytes == null || sourceLimitBytes > 0) { "Source limit must be positive" }
        require(sourceLimitBytes == null || stageWithLimit != null) { "Bounded source importer is unavailable" }
        if (expectedEpoch != epoch()) return false
        invalidateSession()
        if (sources.isEmpty() || operations.containsKey(chat)) return false
        val session = epoch()
        val token = Any()
        fun owns() = epoch() == session && operations[chat]?.token === token
        var entered = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            entered = true
            var failed = false
            var failure: Failure? = null
            try {
                marker(session, chat, true)
                for (source in sources.distinct()) {
                    ensureActive()
                    if (!owns()) throw CancellationException()
                    var staged: String? = null
                    try {
                        staged = if (sourceLimitBytes == null) stage(session, source) else requireNotNull(stageWithLimit)(session, source, sourceLimitBytes)
                        ensureActive()
                        if (!owns()) throw CancellationException()
                        commit(session, chat, staged, asFile)
                        if (owns()) update(chat, active = true, failed = failed, failure = failure)
                    } catch (error: AttachmentTooLargeException) {
                        if (sourceLimitBytes == null) throw error
                        failed = true
                        failure = Failure.SOURCE_LIMIT
                        if (owns()) update(chat, active = true, failed = true, failure = failure)
                    } finally {
                        staged?.let { path -> withContext(NonCancellable) { discard(session, path) } }
                    }
                }
            } catch (_: CancellationException) {
                // Keep items already committed, never resurrect a cancelled operation.
            } catch (error: Exception) {
                failed = true
                if (sourceLimitBytes != null && error is AttachmentTooLargeException) failure = Failure.SOURCE_LIMIT
            } finally {
                runCatching(cleanup).onFailure { failed = true }
                if (owns()) {
                    // Finish the marker write before releasing ownership to a new import.
                    withContext(NonCancellable) { runCatching { marker(session, chat, false) }.onFailure { failed = true } }
                    if (owns()) { operations.remove(chat); update(chat, active = false, failed = failed, failure = failure) }
                }
            }
        }
        operations[chat] = Operation(token, job)
        update(chat, active = true)
        // A cancelled dispatcher can skip the entire body, including finally.
        // No marker was written in that case, but release the UI and temp source.
        job.invokeOnCompletion {
            if (!entered) {
                runCatching(cleanup)
                if (owns()) { operations.remove(chat); update(chat, active = false) }
            }
        }
        job.start()
        return true
    }
    fun cancel(chat: String) { operations[chat]?.job?.cancel() }
    fun dismissError(chat: String) { update(chat, mutableState.value[chat]?.active == true) }
    fun invalidateSession() {
        val current = epoch()
        if (current == observedEpoch) return
        observedEpoch = current
        val old = operations.values.toList()
        operations.clear()
        mutableState.value = emptyMap()
        old.forEach { it.job.cancel() }
    }
}
