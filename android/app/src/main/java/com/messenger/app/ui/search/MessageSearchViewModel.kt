package com.messenger.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.messenger.app.domain.model.MessageSearchResult
import com.messenger.app.domain.repository.MessageSearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

sealed interface MessageSearchUiState {
    data object Idle : MessageSearchUiState
    data object Loading : MessageSearchUiState
    data object Error : MessageSearchUiState
    data class Results(val results: List<MessageSearchResult>) : MessageSearchUiState
    data object NoResults : MessageSearchUiState
}

class MessageSearchViewModel(
    private val repository: MessageSearchRepository,
    private val chatId: String? = null,
) : ViewModel() {

    private data class Request(val text: String, val epoch: Long)
    private val request = MutableStateFlow(Request("", 0))
    private val query = MutableStateFlow("")
    val queryState: StateFlow<String> = query

    private val _uiState = MutableStateFlow<MessageSearchUiState>(MessageSearchUiState.Idle)
    val uiState: StateFlow<MessageSearchUiState> = _uiState

    init {
        viewModelScope.launch {
            request
                .debounce(200)
                .collectLatest { current -> if (current.text.isNotEmpty()) search(current) }
        }
    }

    fun onQueryChange(newQuery: String) {
        if (query.value == newQuery) return
        query.value = newQuery
        request.value = Request(newQuery.trim(), request.value.epoch + 1)
        _uiState.value = if (newQuery.isBlank()) MessageSearchUiState.Idle else MessageSearchUiState.Loading
    }

    fun retry() {
        if (query.value.isBlank()) return
        request.value = Request(query.value.trim(), request.value.epoch + 1)
        _uiState.value = MessageSearchUiState.Loading
    }

    override fun onCleared() {
        request.value = Request("", request.value.epoch + 1)
        query.value = ""
        _uiState.value = MessageSearchUiState.Idle
        super.onCleared()
    }

    private suspend fun search(current: Request) {
        val q = current.text
        try {
        val results = if (chatId != null) {
            repository.chatSearch(chatId, q)
        } else {
            repository.globalSearch(q)
        }
        if (request.value != current) return
        _uiState.value = if (results.isEmpty()) MessageSearchUiState.NoResults else MessageSearchUiState.Results(results)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (request.value == current) _uiState.value = MessageSearchUiState.Error }
    }
}

class MessageSearchViewModelFactory(
    private val repository: MessageSearchRepository,
    private val chatId: String? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MessageSearchViewModel(repository, chatId) as T
    }
}
