package com.messenger.app.ui.chats

import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.repository.ChatRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Repository : ChatRepository {
        var savedCalls = 0
        var refreshCalls = 0
        var refreshFails = false
        var savedFails = false
        override val chats = flowOf(emptyList<ChatSummary>())
        override fun observeChat(chatId: String) = flowOf<ChatSummary?>(null)
        override suspend fun refreshChats(): Result<Unit> { refreshCalls++; val fail = refreshFails; delay(500); return if (fail) Result.failure(java.io.IOException("offline")) else Result.success(Unit) }
        override suspend fun getOrCreateSavedChat(): Result<ChatSummary> {
            savedCalls++
            withContext(NonCancellable) { delay(500) }
            return if (savedFails) Result.failure(IllegalStateException()) else Result.success(ChatSummary("saved", "saved", null, "", null))
        }
        override suspend fun createOrOpenDirectChat(otherUserId: String): Result<ChatSummary> = error("unused")
        override suspend fun getChat(chatId: String): Result<ChatSummary> = error("unused")
        override suspend fun setMute(chatId: String, mutedUntil: String?): Result<Unit> = error("unused")
        override suspend fun clearMute(chatId: String): Result<Unit> = error("unused")
        override suspend fun blockUser(chatId: String, blockedUserId: String): Result<Unit> = error("unused")
        override suspend fun unblockUser(chatId: String): Result<Unit> = error("unused")
        override suspend fun reportChat(chatId: String, reason: String, reportedUserId: String?, description: String?): Result<Unit> = error("unused")
        override suspend fun markLocallyRead(chatId: String, sequence: Long) {}
    }
    @Test fun `reconnect clears stale refresh error only after successful reload`() = runTest(dispatcher) {
        val repo = Repository().apply { refreshFails = true }
        val connection = MutableStateFlow(com.messenger.app.domain.repository.SocketConnectionState.DISCONNECTED)
        val model = ChatsViewModel(repo, connection)
        advanceTimeBy(501)
        assertTrue(model.uiState.value is ChatsUiState.Error)
        repo.refreshFails = false
        connection.value = com.messenger.app.domain.repository.SocketConnectionState.CONNECTED
        advanceTimeBy(501)
        assertEquals(ChatsUiState.Idle, model.uiState.value)
        assertEquals(2, repo.refreshCalls)
        advanceTimeBy(60_000)
        assertEquals(2, repo.refreshCalls)
    }
    @Test fun `connected event during failing request is not lost`() = runTest(dispatcher) {
        val repo = Repository().apply { refreshFails = true }
        val connection = MutableStateFlow(com.messenger.app.domain.repository.SocketConnectionState.DISCONNECTED)
        val model = ChatsViewModel(repo, connection)
        advanceTimeBy(100)
        repo.refreshFails = false
        connection.value = com.messenger.app.domain.repository.SocketConnectionState.CONNECTED
        advanceTimeBy(1001)
        assertEquals(ChatsUiState.Idle, model.uiState.value)
        assertEquals(2, repo.refreshCalls)
    }
    @Test fun `rapid saved taps make one request and one navigation`() = runTest(dispatcher) {
        val repo = Repository(); val model = ChatsViewModel(repo); val opened = mutableListOf<String>()
        repeat(5) { model.openSavedMessages { opened += it } }
        advanceTimeBy(501)
        assertEquals(1, repo.savedCalls); assertEquals(listOf("saved"), opened)
    }
    @Test fun `search or navigation cancels a late saved result`() = runTest(dispatcher) {
        val repo = Repository(); val model = ChatsViewModel(repo); var opened = false
        model.openSavedMessages { opened = true }; advanceTimeBy(1)
        model.cancelPendingOpen(); advanceTimeBy(600)
        assertFalse(opened)
        model.openSavedMessages { opened = true }; advanceTimeBy(501)
        assertTrue(opened)
    }
    @Test fun `failed saved request can be retried`() = runTest(dispatcher) {
        val repo = Repository(); repo.savedFails = true
        val model = ChatsViewModel(repo); var opened = false
        model.openSavedMessages { opened = true }; advanceTimeBy(501)
        assertTrue(model.uiState.value is ChatsUiState.Error)
        repo.savedFails = false; model.openSavedMessages { opened = true }; advanceTimeBy(501)
        assertTrue(opened)
    }
    @Test fun `refresh coalesces requests already in progress`() = runTest(dispatcher) {
        val repo = Repository(); val model = ChatsViewModel(repo)
        repeat(5) { model.refresh() }; advanceTimeBy(501)
        assertEquals(1, repo.refreshCalls)
        model.refresh(); advanceTimeBy(501); assertEquals(2, repo.refreshCalls)
    }
}
