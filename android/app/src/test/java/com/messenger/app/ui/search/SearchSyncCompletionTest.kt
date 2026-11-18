package com.messenger.app.ui.search

import androidx.lifecycle.ViewModelStore
import com.messenger.app.data.sync.SyncPhase
import com.messenger.app.domain.model.MessageSearchResult
import com.messenger.app.domain.repository.MessageSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/** Real one-shot search view model + a local index changed only when simulated catch-up finishes. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchSyncCompletionTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.clear(); Dispatchers.resetMain() }

    private class Index : MessageSearchRepository {
        var version = 0
        val queries = mutableListOf<String>()
        override suspend fun globalSearch(query: String, limit: Int): List<MessageSearchResult> {
            queries += query
            return if (version == 0) emptyList() else listOf(
                MessageSearchResult("hit", "chat", "peer", 1, "now", "$query v$version"))
        }
        override suspend fun chatSearch(chatId: String, query: String, limit: Int) = globalSearch(query, limit)
    }

    private fun newModel(index: Index) = MessageSearchViewModel(index).also { models.put("search", it) }

    private fun TestScope.watch(model: MessageSearchViewModel, phases: MutableStateFlow<SyncPhase>,
        query: MutableStateFlow<String?>, resumed: MutableStateFlow<Boolean>) = backgroundScope.launch {
        collectLocalSearchWithSync(phases, query, resumed, model::onQueryChange) { expected ->
            if (model.queryState.value == expected) model.retry()
        }
    }

    private fun text(model: MessageSearchViewModel) =
        (model.uiState.value as MessageSearchUiState.Results).results.single().text

    @Test fun slowCatchUpRefreshesActualLocalResultsAfterItCompletes() = runTest(dispatcher) {
        val index = Index(); val model = newModel(index)
        val phases = MutableStateFlow(SyncPhase.IDLE)
        watch(model, phases, MutableStateFlow("needle"), MutableStateFlow(true))
        runCurrent(); advanceTimeBy(201); runCurrent()
        assertEquals(MessageSearchUiState.NoResults, model.uiState.value)

        // Reproduce the old header behavior: the immediate retry finishes before a long sync.
        phases.value = SyncPhase.RUNNING
        model.retry()
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(MessageSearchUiState.NoResults, model.uiState.value)
        assertEquals(2, index.queries.size)

        index.version = 1
        phases.value = SyncPhase.IDLE
        runCurrent(); advanceTimeBy(199); runCurrent()
        assertEquals(2, index.queries.size)
        advanceTimeBy(202); runCurrent()
        assertEquals("needle v1", text(model))
        assertEquals(3, index.queries.size)
    }

    @Test fun queryChangeCancelsAnAlreadyDebouncingCompletionRefresh() = runTest(dispatcher) {
        val index = Index(); val model = newModel(index)
        val phases = MutableStateFlow(SyncPhase.IDLE)
        val query = MutableStateFlow<String?>("old")
        watch(model, phases, query, MutableStateFlow(true))
        runCurrent(); advanceTimeBy(201); runCurrent()
        phases.value = SyncPhase.RUNNING; runCurrent()
        index.version = 1
        phases.value = SyncPhase.IDLE; runCurrent(); advanceTimeBy(100)
        query.value = "new"; runCurrent(); advanceTimeBy(600); runCurrent()
        assertEquals(listOf("old", "new"), index.queries)
        assertEquals("new v1", text(model))
    }

    @Test fun queryChangedDuringRunningSyncRefreshesTheNewQueryOnCompletion() = runTest(dispatcher) {
        val index = Index(); val model = newModel(index)
        val phases = MutableStateFlow(SyncPhase.IDLE)
        val query = MutableStateFlow<String?>("old")
        watch(model, phases, query, MutableStateFlow(true))
        runCurrent(); advanceTimeBy(201); runCurrent()
        phases.value = SyncPhase.RUNNING; runCurrent()
        query.value = "new"; runCurrent(); advanceTimeBy(201); runCurrent()
        assertEquals(MessageSearchUiState.NoResults, model.uiState.value)
        index.version = 1
        phases.value = SyncPhase.IDLE; runCurrent(); advanceTimeBy(401); runCurrent()
        assertEquals(listOf("old", "new", "new"), index.queries)
        assertEquals("new v1", text(model))
    }

    @Test fun lifecycleStopCancelsPendingWorkAndResumeReadsTheFreshIndexOnce() = runTest(dispatcher) {
        val index = Index(); val model = newModel(index)
        val phases = MutableStateFlow(SyncPhase.IDLE)
        val resumed = MutableStateFlow(true)
        watch(model, phases, MutableStateFlow("needle"), resumed)
        runCurrent(); advanceTimeBy(201); runCurrent()
        phases.value = SyncPhase.RUNNING; runCurrent()
        index.version = 1
        phases.value = SyncPhase.IDLE; runCurrent(); advanceTimeBy(100)
        resumed.value = false; runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(1, index.queries.size)
        assertEquals(MessageSearchUiState.Idle, model.uiState.value)
        resumed.value = true; runCurrent(); advanceTimeBy(501); runCurrent()
        assertEquals(2, index.queries.size)
        assertEquals("needle v1", text(model))
    }

    @Test fun catchUpCompletedWhileHiddenIsReflectedOnResumeWithoutBackgroundSearch() = runTest(dispatcher) {
        val index = Index(); val model = newModel(index)
        val phases = MutableStateFlow(SyncPhase.IDLE)
        val resumed = MutableStateFlow(true)
        watch(model, phases, MutableStateFlow("needle"), resumed)
        runCurrent(); advanceTimeBy(201); runCurrent()
        phases.value = SyncPhase.RUNNING; runCurrent()
        resumed.value = false; runCurrent()
        index.version = 1
        phases.value = SyncPhase.IDLE; runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(1, index.queries.size)
        resumed.value = true; runCurrent(); advanceTimeBy(201); runCurrent()
        assertEquals("needle v1", text(model))
        assertEquals(2, index.queries.size)
    }

    @Test fun nonMessageScopesBlankQueriesAndFailedSyncDoNotStartRefreshLoops() = runTest(dispatcher) {
        val index = Index(); val model = newModel(index)
        val phases = MutableStateFlow(SyncPhase.IDLE)
        val query = MutableStateFlow<String?>(null) // Chats/People do not own a local message query.
        watch(model, phases, query, MutableStateFlow(true))
        runCurrent(); phases.value = SyncPhase.RUNNING; runCurrent()
        phases.value = SyncPhase.IDLE; runCurrent(); advanceTimeBy(1_000); runCurrent()
        query.value = "   "; runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(emptyList(), index.queries)
        query.value = "needle"; runCurrent(); advanceTimeBy(201); runCurrent()
        phases.value = SyncPhase.RUNNING; runCurrent()
        phases.value = SyncPhase.FAILED; runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("needle"), index.queries)
    }

    @Test fun completionBurstsCoalesceAndDisposingTheCollectorCancelsItsRefresh() = runTest(dispatcher) {
        val index = Index(); val model = newModel(index)
        val phases = MutableStateFlow(SyncPhase.IDLE)
        val job = watch(model, phases, MutableStateFlow("needle"), MutableStateFlow(true))
        runCurrent(); advanceTimeBy(201); runCurrent()
        phases.value = SyncPhase.RUNNING; runCurrent()
        index.version = 1
        phases.value = SyncPhase.IDLE; runCurrent(); advanceTimeBy(100)
        phases.value = SyncPhase.RUNNING; runCurrent()
        index.version = 2
        phases.value = SyncPhase.IDLE; runCurrent(); advanceTimeBy(401); runCurrent()
        assertEquals("needle v2", text(model))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, index.queries.size)
        phases.value = SyncPhase.RUNNING; runCurrent()
        index.version = 3
        phases.value = SyncPhase.IDLE; runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, index.queries.size)
        assertEquals(MessageSearchUiState.Idle, model.uiState.value)
    }
}
