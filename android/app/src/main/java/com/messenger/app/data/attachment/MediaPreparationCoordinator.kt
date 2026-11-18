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
    private val retainOriginal: (suspend (Int, String) -> String)? = null,
    private val inspectOriginals: suspend (Int, String, List<String>) -> ComposerOriginals = { _, _, sources -> ComposerOriginals(sources.toSet()) },
    private val sourceAvailable: suspend (Int, String) -> Boolean = { _, _ -> true },
    private val removeDraftReferences: suspend (Int, String, Set<String>) -> Unit = { _, _, _ -> },
    private val saveCaption: suspend (Int, String, ComposerCaption) -> Unit = { _, _, _ -> },
    private val commitWithTransport: (suspend (Int, String, String, Boolean, Boolean) -> Unit)? = null,
) {
    enum class Failure { SOURCE_LIMIT, DIRECT_CAPACITY }
    data class ImportItem(val source: String, val staged: String? = null, val asFile: Boolean,
        val flies: Boolean, val order: Int, val arrival: Any = Any(), val directOriginal: Boolean = false, val info: ComposerSourceInfo? = null,
        val directSend: Boolean = false)
    data class State(val active: Boolean = false, val revision: Long = 0, val failed: Boolean = false,
        val failure: Failure? = null, val items: List<ImportItem> = emptyList(), val sending: Boolean = false,
        val forgotten: Set<String> = emptySet(), val caption: ComposerCaption? = null, val unavailable: Set<String> = emptySet())
    private data class Operation(val token: Any, val job: Job, var durable: Boolean = false)
    private val operations = mutableMapOf<String, MutableList<Operation>>()
    private var observedEpoch = epoch()
    private val mutableState = MutableStateFlow<Map<String, State>>(emptyMap())
    val state = mutableState.asStateFlow()
    // Session-owned source identities and arrivals outlive a navigation pop. Marking a
    // landing never publishes state, so it cannot recompose the composer at rest.
    private val landed = mutableSetOf<Any>()
    private val sendTokens = mutableMapOf<String, Any>()
    private val sendJobs = mutableMapOf<String, Job>()

    /** Queueing and ownership have the same application lifetime, including before dispatch. */
    fun submitSend(chat: String, expectedEpoch: Int = epoch(), onNotStarted: () -> Unit = {},
        onComplete: (Boolean) -> Unit = {}, caption: ComposerCaption? = null, queue: suspend () -> Boolean): Boolean {
        invalidateSession()
        if (expectedEpoch != epoch() || sendTokens.containsKey(chat) || mutableState.value[chat]?.active == true || !scope.isActive) {
            onNotStarted(); onComplete(false); return false
        }
        val token = newSendToken(chat)
        var entered = false
        var success = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            entered = true
            try {
                if (epoch() != expectedEpoch) throw CancellationException("Account changed")
                if (caption != null && mutableState.value[chat]?.caption == caption) saveCaption(expectedEpoch, chat, caption)
                success = queue()
                if (success && caption != null) consumeCaption(chat, caption, expectedEpoch)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the journal for retry. */ }
            finally { finishSend(chat, token) }
        }
        sendJobs[chat] = job
        job.invokeOnCompletion {
            try { if (!entered) onNotStarted() }
            finally {
                finishSend(chat, token)
                if (sendJobs[chat] === job) sendJobs.remove(chat)
                if (epoch() == expectedEpoch) {
                    if (!success && mutableState.value[chat]?.unavailable.isNullOrEmpty()) update(chat, mutableState.value[chat]?.active == true, failed = true)
                    onComplete(success)
                }
            }
        }
        job.start()
        return true
    }

    suspend fun importForSend(chat: String, sources: List<String>, asFile: Boolean, expectedEpoch: Int,
        sourceLimitBytes: Long?, originals: Boolean, cleanup: () -> Unit): List<String>? {
        val result = CompletableDeferred<List<String>?>()
        if (!start(chat, sources, asFile, expectedEpoch, sourceLimitBytes, cleanup,
                onComplete = { result.complete(it) }, onFailure = { result.complete(null) },
                append = true, originals = originals)) {
            return null
        }
        return result.await()
    }
    fun land(arrival: Any): Boolean = landed.add(arrival)
    fun hasLanded(arrival: Any) = arrival in landed
    fun restoreCaption(chat: String, text: String, revision: String? = null): ComposerCaption {
        invalidateSession()
        val previous = mutableState.value[chat] ?: State()
        previous.caption?.let { return it }
        val caption = ComposerCaption(text, revision ?: java.util.UUID.randomUUID().toString())
        mutableState.value = mutableState.value + (chat to previous.copy(caption = caption))
        return caption
    }
    fun editCaption(chat: String, text: String): ComposerCaption {
        invalidateSession()
        val previous = mutableState.value[chat] ?: State()
        previous.caption?.takeIf { it.text == text }?.let { return it }
        val caption = ComposerCaption(text)
        mutableState.value = mutableState.value + (chat to previous.copy(caption = caption))
        val session = epoch()
        scope.launch {
            try { if (epoch() == session && mutableState.value[chat]?.caption == caption) saveCaption(session, chat, caption) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch() == session) update(chat, mutableState.value[chat]?.active == true, failed = true) }
        }
        return caption
    }
    fun consumeCaption(chat: String, captured: ComposerCaption, expectedEpoch: Int = epoch()) {
        if (epoch() != expectedEpoch) return
        val previous = mutableState.value[chat] ?: return
        if (previous.caption == captured) mutableState.value = mutableState.value + (chat to previous.copy(caption = ComposerCaption("")))
    }
    /** Durable admission already removed the journal references. Never use the user busy guard here. */
    fun consumeCommitted(chat: String, uris: List<String>, expectedEpoch: Int = epoch()) {
        if (epoch() != expectedEpoch) return
        uris.forEach { forget(chat, it, invalidateSend = false) }
        update(chat, mutableState.value[chat]?.active == true)
    }
    fun markUnavailable(chat: String, uris: Set<String>, expectedEpoch: Int = epoch()) {
        if (expectedEpoch != epoch()) return
        val previous = mutableState.value[chat] ?: State()
        val identities = uris + previous.items.filter { it.source in uris || it.staged in uris }.flatMap { listOfNotNull(it.source, it.staged) }
        val missing = uris.filter { uri -> uri !in previous.forgotten && previous.items.none { it.source == uri || it.staged == uri } }
            .map { ImportItem(it, asFile = true, flies = false, order = 0) }
        mutableState.value = mutableState.value + (chat to previous.copy(items = previous.items + missing, unavailable = previous.unavailable + identities, revision = previous.revision + 1))
    }
    private suspend fun validateRetained(chat: String, session: Int) {
        val candidates = mutableState.value[chat]?.items.orEmpty().filter { it.directOriginal && it.staged != null }
        for (item in candidates) {
            val available = try { sourceAvailable(session, item.source) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            if (!available) markUnavailable(chat, setOf(item.source), session)
        }
    }
    fun newSendToken(chat: String): Any = Any().also {
        sendTokens[chat] = it
        val previous = mutableState.value[chat] ?: State()
        mutableState.value = mutableState.value + (chat to previous.copy(sending = true))
    }
    fun ownsSend(chat: String, token: Any) = sendTokens[chat] === token
    fun finishSend(chat: String, token: Any) {
        if (!ownsSend(chat, token)) return
        sendTokens.remove(chat)
        val previous = mutableState.value[chat] ?: return
        mutableState.value = mutableState.value + (chat to previous.copy(sending = false))
    }
    fun forget(chat: String, uri: String, invalidateSend: Boolean = true) {
        val previous = mutableState.value[chat] ?: State()
        val removed = previous.items.filter { it.source == uri || it.staged == uri }
        removed.forEach { landed.remove(it.arrival) }
        mutableState.value = mutableState.value + (chat to previous.copy(items = previous.items - removed.toSet(),
            forgotten = previous.forgotten + uri + removed.flatMap { listOfNotNull(it.source, it.staged) },
            unavailable = previous.unavailable - (removed.flatMap { listOfNotNull(it.source, it.staged) } + uri).toSet(), sending = previous.sending && !invalidateSend))
        if (invalidateSend) sendTokens.remove(chat)
    }
    fun remove(chat: String, uri: String, expectedEpoch: Int = epoch()) {
        if (expectedEpoch != epoch() || mutableState.value[chat]?.sending == true) return
        val before = mutableState.value[chat] ?: State()
        val removed = before.items.filter { it.source == uri || it.staged == uri }
        forget(chat, uri)
        val durable = if (removed.isEmpty()) setOf(uri) else removed.filterNot { it.directOriginal }.mapNotNull { it.staged }.toSet()
        if (durable.isEmpty()) return
        scope.launch {
            try { removeDraftReferences(expectedEpoch, chat, durable) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (expectedEpoch == epoch()) {
                    val current = mutableState.value[chat] ?: return@launch
                    mutableState.value = mutableState.value + (chat to current.copy(failed = true,
                        items = current.items + removed.filter { old -> current.items.none { it.source == old.source } },
                        forgotten = current.forgotten - (removed.flatMap { listOfNotNull(it.source, it.staged) } + uri).toSet()))
                }
            }
        }
    }
    private fun update(chat: String, active: Boolean, failed: Boolean = false, failure: Failure? = null) {
        val previous = mutableState.value[chat] ?: State()
        mutableState.value = mutableState.value + (chat to previous.copy(active = active, revision = previous.revision + 1, failed = failed, failure = failure))
    }
    fun start(chat: String, sources: List<String>, asFile: Boolean = false, expectedEpoch: Int = epoch(), sourceLimitBytes: Long? = null, cleanup: () -> Unit = {},
        flies: Boolean = false, onComplete: (List<String>) -> Unit = {}, onFailure: () -> Unit = {}, append: Boolean = false,
        maximumItems: Int? = null, originals: Boolean = false, direct: Boolean = false): Boolean {
        check(!direct || commitWithTransport != null) { "Atomic transport journal unavailable" }
        require(sourceLimitBytes == null || sourceLimitBytes > 0) { "Source limit must be positive" }
        require(sourceLimitBytes == null || stageWithLimit != null) { "Bounded source importer is unavailable" }
        fun reject(): Boolean { cleanup(); return false }
        if (expectedEpoch != epoch()) return reject()
        invalidateSession()
        if (sources.isEmpty() || (!append && operations.containsKey(chat))) return reject()
        val previous = mutableState.value[chat] ?: State()
        val existing = previous.items.flatMap { listOfNotNull(it.source, it.staged) }.toSet()
        val admitted = sources.distinct().filterNot { it in existing }
        if (admitted.isEmpty()) return reject()
        if (maximumItems != null && admitted.size + previous.items.size > maximumItems) return reject()
        val fileDraft = asFile || direct
        val slots = admitted.mapIndexed { order, source -> ImportItem(source, asFile = fileDraft, flies = flies, order = order, directSend = direct) }
        mutableState.value = mutableState.value + (chat to previous.copy(items = previous.items + slots))
        val session = epoch()
        val token = Any()
        fun owns() = epoch() == session && operations[chat]?.any { it.token === token } == true
        fun releaseOperation() {
            operations[chat]?.removeAll { it.token === token }
            if (operations[chat].isNullOrEmpty()) operations.remove(chat)
        }
        var entered = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            entered = true
            var failed = false
            var cancelled = false
            var failure: Failure? = null
            val completed = mutableListOf<String>()
            try {
                val originalSelection = if (originals) inspectOriginals(session, chat, admitted) else ComposerOriginals(emptySet())
                ensureActive()
                if (!owns()) throw CancellationException()
                if (originalSelection.unavailable.isNotEmpty()) markUnavailable(chat, originalSelection.unavailable, session)
                validateRetained(chat, session)
                val current = mutableState.value.getValue(chat)
                val selectedOriginals = originalSelection.selected.filter { source -> source !in current.unavailable && current.items.any { item ->
                    item.source == source && slots.any { it.arrival === item.arrival }
                } }.toSet()
                val retainedOriginals = current.items.filter { it.directOriginal && it.source !in current.unavailable }.map { it.source }.toSet() + selectedOriginals
                if (retainedOriginals.size > ComposerOriginals.MAX_DIRECT) {
                    failure = Failure.DIRECT_CAPACITY
                    error("At most eight direct originals fit in the tray")
                }
                mutableState.value = mutableState.value + (chat to current.copy(items = current.items.map {
                    if (slots.any { slot -> slot.arrival === it.arrival }) it.copy(directOriginal = it.source in selectedOriginals, info = originalSelection.metadata[it.source]) else it
                }))
                for (source in admitted) {
                    ensureActive()
                    if (!owns()) throw CancellationException()
                    val arrival = slots.first { it.source == source }.arrival
                    if (mutableState.value[chat]?.items?.none { it.arrival === arrival } != false) continue
                    if (source in mutableState.value[chat]?.unavailable.orEmpty()) continue
                    val sessionOnly = source in selectedOriginals
                    var staged: String? = null
                    try {
                        if (!sessionOnly) {
                            val operation = operations[chat]?.firstOrNull { it.token === token } ?: throw CancellationException()
                            if (!operation.durable) { operation.durable = true; marker(session, chat, true) }
                        }
                        staged = if (sessionOnly) requireNotNull(retainOriginal)(session, source)
                            else if (originals && stageWithLimit != null) stageWithLimit.invoke(session, source, com.messenger.app.data.directtransfer.DIRECT_NORMAL_SOURCE_LIMIT)
                            else if (sourceLimitBytes == null) stage(session, source) else requireNotNull(stageWithLimit)(session, source, sourceLimitBytes)
                        ensureActive()
                        if (!owns()) throw CancellationException()
                        if (mutableState.value[chat]?.items?.none { it.arrival === arrival } != false) continue
                        if (!sessionOnly) {
                            if (commitWithTransport != null) commitWithTransport.invoke(session, chat, staged, fileDraft, direct)
                            else commit(session, chat, staged, fileDraft)
                        }
                        // Removal may happen while the Room commit suspends. Retire only this
                        // reference after that commit, never replace the remaining journal.
                        if (owns() && mutableState.value[chat]?.items?.none { it.arrival === arrival } != false) {
                            if (!sessionOnly) removeDraftReferences(session, chat, setOf(staged))
                            continue
                        }
                        if (owns()) {
                            completed += staged
                            val current = mutableState.value.getValue(chat)
                            mutableState.value = mutableState.value + (chat to current.copy(items = current.items.map {
                                if (it.arrival === arrival) it.copy(staged = staged) else it
                            }))
                            update(chat, active = true, failed = failed, failure = failure)
                        }
                    } catch (error: AttachmentTooLargeException) {
                        if (sourceLimitBytes == null) throw error
                        failed = true
                        failure = Failure.SOURCE_LIMIT
                        if (owns()) update(chat, active = true, failed = true, failure = failure)
                    } catch (error: Exception) {
                        if (error is CancellationException || !isUnavailableComposerSource(error)) throw error
                        if (owns()) markUnavailable(chat, setOf(source), session)
                    } finally {
                        if (!sessionOnly) staged?.let { path -> withContext(NonCancellable) { discard(session, path) } }
                    }
                }
            } catch (_: CancellationException) {
                cancelled = true
                // Keep items already committed, never resurrect a cancelled operation.
            } catch (error: Exception) {
                failed = true
                if (sourceLimitBytes != null && error is AttachmentTooLargeException) failure = Failure.SOURCE_LIMIT
            } finally {
                runCatching(cleanup).onFailure { failed = true }
                if (owns()) {
                    // Finish the marker write before releasing ownership to a new import.
                    if (operations[chat]?.firstOrNull { it.token === token }?.durable == true && operations[chat]?.count { it.durable } == 1) withContext(NonCancellable) {
                        runCatching { marker(session, chat, false) }.onFailure { failed = true }
                        // A new explicit Add can arrive while the journal write suspends.
                        if (owns() && (operations[chat]?.count { it.durable } ?: 0) > 1) runCatching { marker(session, chat, true) }.onFailure { failed = true }
                    }
                    if (owns()) {
                        val current = mutableState.value.getValue(chat)
                        mutableState.value = mutableState.value + (chat to current.copy(items = current.items.filterNot { item -> slots.any { it.arrival === item.arrival } && item.staged == null && item.source !in current.unavailable }))
                        releaseOperation(); update(chat, active = operations.containsKey(chat), failed = failed, failure = failure)
                        if (!failed && !cancelled && completed.isNotEmpty()) onComplete(completed) else onFailure()
                    }
                }
            }
        }
        operations.getOrPut(chat) { mutableListOf() }.add(Operation(token, job))
        update(chat, active = true)
        // A cancelled dispatcher can skip the entire body, including finally.
        // No marker was written in that case, but release the UI and temp source.
        job.invokeOnCompletion {
            if (!entered) {
                runCatching(cleanup)
                if (owns()) {
                    val current = mutableState.value.getValue(chat)
                    mutableState.value = mutableState.value + (chat to current.copy(items = current.items - slots.toSet()))
                    releaseOperation(); update(chat, active = operations.containsKey(chat))
                    onFailure()
                }
            }
        }
        job.start()
        return true
    }
    fun cancel(chat: String) { operations[chat]?.toList()?.forEach { it.job.cancel() } }
    fun dismissError(chat: String) { update(chat, mutableState.value[chat]?.active == true) }
    fun invalidateSession() {
        val current = epoch()
        if (current == observedEpoch) return
        observedEpoch = current
        val old = operations.values.flatMap { it.toList() }
        val oldSends = sendJobs.values.toList()
        sendJobs.clear()
        operations.clear()
        landed.clear(); sendTokens.clear()
        mutableState.value = emptyMap()
        old.forEach { it.job.cancel() }
        oldSends.forEach { it.cancel() }
    }
}
