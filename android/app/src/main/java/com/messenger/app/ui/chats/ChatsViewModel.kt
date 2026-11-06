package com.messenger.app.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

sealed interface ChatsUiState {
    data object Idle : ChatsUiState
    data object Loading : ChatsUiState
    data class Error(val message: String) : ChatsUiState
}

class ChatsViewModel(private val repository: ChatRepository, connection: Flow<SocketConnectionState> = emptyFlow()) : ViewModel() {

    val chats: StateFlow<List<ChatSummary>> =
        repository.chats.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow<ChatsUiState>(ChatsUiState.Idle)
    val uiState: StateFlow<ChatsUiState> = _uiState

    private var refreshJob: Job? = null
    private var savedJob: Job? = null
    private var savedOpening = false
    private var savedEpoch = 0L

    private var connected = false
    private var connectionRevision = 0L
    init {
        refresh()
        viewModelScope.launch {
            connection.distinctUntilChanged().collect { state ->
                connected = state == SocketConnectionState.CONNECTED
                if (connected) {
                    connectionRevision++
                    if (_uiState.value is ChatsUiState.Error) refresh()
                }
            }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _uiState.value = ChatsUiState.Loading
            val startedRevision = connectionRevision
            val result = try { repository.refreshChats() }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { Result.failure(error) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _uiState.value = result.fold({ ChatsUiState.Idle }, { ChatsUiState.Error(errorMessage(it)) })
            refreshJob = null
            // The old offline request may finish AFTER the connected event.
            // Retry it once for that event, not on a recurring timer.
            if (result.isFailure && connected && connectionRevision != startedRevision) refresh()
        }
    }

    /** Idempotent get-or-create of Saved Messages, then hand the chat id
     *  back for navigation (M.35 - the server guarantees one saved chat). */
    fun openSavedMessages(onOpened: (String) -> Unit) {
        if (savedOpening) return
        savedOpening = true
        val epoch = ++savedEpoch
        savedJob = viewModelScope.launch {
            try {
                repository.getOrCreateSavedChat()
                    .onSuccess { if (epoch == savedEpoch) onOpened(it.chatId) }
                    .onFailure { if (epoch == savedEpoch) _uiState.value = ChatsUiState.Error(errorMessage(it)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (epoch == savedEpoch) _uiState.value = ChatsUiState.Error(errorMessage(error)) }
            finally { if (epoch == savedEpoch) savedOpening = false }
        }
    }

    fun cancelPendingOpen() {
        ++savedEpoch; savedJob?.cancel(); savedOpening = false
    }

    override fun onCleared() { cancelPendingOpen(); super.onCleared() }

    private fun errorMessage(t: Throwable): String = when (t) {
        is AuthApiException.Api -> t.message ?: "Something went wrong."
        is AuthApiException.Network -> "Network error. Please try again."
        else -> "Something went wrong."
    }
}

class ChatsViewModelFactory(private val repository: ChatRepository, private val connection: Flow<SocketConnectionState> = emptyFlow()) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ChatsViewModel(repository, connection) as T
    }
}
