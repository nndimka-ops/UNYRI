package com.messenger.app.ui.search

import com.messenger.app.domain.model.MessageSearchResult
import com.messenger.app.domain.repository.MessageSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class MessageSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Repository : MessageSearchRepository {
        var fail = false
        override suspend fun globalSearch(query: String, limit: Int): List<MessageSearchResult> {
            withContext(NonCancellable) { delay(400) }
            if (fail) error("read failed")
            return listOf(MessageSearchResult(query, "chat", "peer", 1, "now", query))
        }
        override suspend fun chatSearch(chatId: String, query: String, limit: Int) = globalSearch(query, limit)
    }
    @Test fun `clearing query rejects an in flight result`() = runTest(dispatcher) {
        val model = MessageSearchViewModel(Repository())
        model.onQueryChange("old"); advanceTimeBy(250); runCurrent()
        model.onQueryChange(""); advanceTimeBy(600); runCurrent()
        assertEquals(MessageSearchUiState.Idle, model.uiState.value)
    }
    @Test fun `new query wins and repeated query keeps completed results`() = runTest(dispatcher) {
        val model = MessageSearchViewModel(Repository())
        model.onQueryChange("old"); advanceTimeBy(250); runCurrent()
        model.onQueryChange("new"); advanceTimeBy(1200); runCurrent()
        assertEquals("new", (model.uiState.value as MessageSearchUiState.Results).results.single().text)
        model.onQueryChange("new")
        assertTrue(model.uiState.value is MessageSearchUiState.Results)
    }
    @Test fun `read failure is recoverable by a new search`() = runTest(dispatcher) {
        val repo = Repository(); repo.fail = true
        val model = MessageSearchViewModel(repo)
        model.onQueryChange("fail"); advanceTimeBy(700); runCurrent()
        assertEquals(MessageSearchUiState.Error, model.uiState.value)
        repo.fail = false; model.onQueryChange("ok"); advanceTimeBy(700); runCurrent()
        assertTrue(model.uiState.value is MessageSearchUiState.Results)
    }
    @Test fun `same query after rapid clear does not stay loading`() = runTest(dispatcher) {
        val model = MessageSearchViewModel(Repository())
        model.onQueryChange("same"); advanceTimeBy(700); runCurrent()
        assertTrue(model.uiState.value is MessageSearchUiState.Results)
        model.onQueryChange(""); model.onQueryChange("same")
        advanceTimeBy(700); runCurrent()
        assertTrue(model.uiState.value is MessageSearchUiState.Results)
    }

    @Test fun `retry repeats the same failed local query`() = runTest(dispatcher) {
        val repo = Repository(); repo.fail = true
        val model = MessageSearchViewModel(repo)
        model.onQueryChange("same"); advanceTimeBy(700); runCurrent()
        assertEquals(MessageSearchUiState.Error, model.uiState.value)
        repo.fail = false; model.retry(); advanceTimeBy(700); runCurrent()
        assertEquals("same", (model.uiState.value as MessageSearchUiState.Results).results.single().text)
    }

}
