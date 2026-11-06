package com.messenger.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.repository.ChatRepository
import com.messenger.app.domain.repository.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Loaded(val items: List<PublicUser>, val canLoadMore: Boolean, val loadingMore: Boolean = false, val pageError: Boolean = false, val cached: Boolean = false) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

class SearchViewModel(
    private val repository: ProfileRepository,
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState

    /** One-shot navigation signal: chat_id to open, or null when consumed. */
    private val _openChatId = MutableStateFlow<String?>(null)
    val openChatId: StateFlow<String?> = _openChatId

    private var searchJob: Job? = null
    private var currentQuery = ""
    private var directoryMode = false
    private var searchEpoch = 0L
    private var pageBusy = false
    private var nextOffset: Long? = 0L
    private val accumulated = mutableListOf<PublicUser>()
    private var openingJob: Job? = null
    private var openingEpoch = 0L
    private val _openingUserId = MutableStateFlow<String?>(null)
    val openingUserId: StateFlow<String?> = _openingUserId
    private val _openError = MutableStateFlow(false)
    val openError: StateFlow<Boolean> = _openError

    private val _recentQueries = MutableStateFlow<List<String>>(emptyList())
    val recentQueries: StateFlow<List<String>> = _recentQueries
    fun rememberQuery(query: String) {
        val term = query.trim().take(256)
        if (term.isNotEmpty()) _recentQueries.value = (listOf(term) + _recentQueries.value.filter { it != term }).take(5)
    }
    fun clearRecentQueries() { _recentQueries.value = emptyList() }
    fun dismissOpenError() { _openError.value = false }
    private fun cancelOpening() {
        ++openingEpoch; openingJob?.cancel(); _openingUserId.value = null; _openChatId.value = null; _openError.value = false
    }
    fun pause() {
        ++searchEpoch; searchJob?.cancel(); pageBusy = false; cancelOpening()
        _uiState.value = when(val state = _uiState.value) {
            is SearchUiState.Loaded -> state.copy(loadingMore = false)
            SearchUiState.Loading -> SearchUiState.Idle
            else -> state
        }
    }

    /** Closing search invalidates even a non-cooperative late transport callback. */
    fun close() {
        pause()
        pageBusy = false; _openingUserId.value = null; _openChatId.value = null
        _openError.value = false; _uiState.value = SearchUiState.Idle
        accumulated.clear(); nextOffset = null
    }

    /** Debounced search (Phase 6 §4): the previous job is cancelled so the
     *  latest query always wins; a stale response can never overwrite a newer
     *  query (the [loadPage] guard checks a monotonically increasing epoch). */
    fun search(query: String, directory: Boolean = false) {
        val trimmed = query.trim().removePrefix("@")
        if (trimmed == currentQuery && directoryMode == directory && (_uiState.value as? SearchUiState.Loaded)?.cached == false) return
        if (trimmed != currentQuery) cancelOpening()
        searchJob?.cancel()
        val epoch = ++searchEpoch
        pageBusy = false
        currentQuery = trimmed
        directoryMode = directory
        if (!directory && trimmed.length < MIN_QUERY_LENGTH) {
            accumulated.clear()
            nextOffset = 0L
            _uiState.value = SearchUiState.Idle
            return
        }
        _uiState.value = SearchUiState.Loading
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            accumulated.clear()
            nextOffset = 0L
            loadPage(trimmed, epoch, 0L)
        }
    }

    fun loadMore() {
        val offset = nextOffset ?: return
        if (pageBusy || (!directoryMode && currentQuery.length < MIN_QUERY_LENGTH) || _uiState.value !is SearchUiState.Loaded) return
        val query = currentQuery
        val epoch = searchEpoch
        // Reserve before launching: two taps in one frame must not fetch twice.
        pageBusy = true
        _uiState.value = SearchUiState.Loaded(accumulated.toList(), true, loadingMore = true, cached = (_uiState.value as? SearchUiState.Loaded)?.cached == true)
        searchJob = viewModelScope.launch { loadPage(query, epoch, offset) }
    }

    /** Creates (or opens the existing) direct chat with [user], then signals
     *  navigation via [openChatId]. A repeated-tap guard keeps a double tap
     *  from launching duplicate requests/navigation. */
    fun message(user: PublicUser) {
        if (_openingUserId.value != null || _openChatId.value != null) return
        _openingUserId.value = user.id
        _openError.value = false
        val epoch = ++openingEpoch
        openingJob = viewModelScope.launch {
            try {
                chatRepository.createOrOpenDirectChat(user.id)
                    .onSuccess { if (epoch == openingEpoch) _openChatId.value = it.chatId }
                    .onFailure { if (epoch == openingEpoch) _openError.value = true }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == openingEpoch) _openError.value = true }
            finally { if (epoch == openingEpoch) _openingUserId.value = null }
        }
    }

    fun consumeOpenChatId() {
        _openChatId.value = null
    }

    private suspend fun loadPage(query: String, epoch: Long, offset: Long) {
        if (epoch != searchEpoch) return
        pageBusy = true
        try {
            (if (directoryMode) repository.directory(query, offset = offset) else repository.searchUsers(query, offset = offset))
                .onSuccess { result ->
                    if (epoch != searchEpoch) return
                    val unique = (accumulated + result.items).distinctBy { it.id }
                    accumulated.clear()
                    accumulated.addAll(unique)
                    nextOffset = result.nextOffset?.takeIf { it > offset }
                    _uiState.value = SearchUiState.Loaded(accumulated.toList(), nextOffset != null, cached = result.cached)
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    if (epoch != searchEpoch) return
                    _uiState.value = if (accumulated.isNotEmpty())
                        SearchUiState.Loaded(accumulated.toList(), nextOffset != null, pageError = true, cached = (_uiState.value as? SearchUiState.Loaded)?.cached == true)
                    else SearchUiState.Error(errorMessage(it))
                }
        } finally {
            if (epoch == searchEpoch) pageBusy = false
        }
    }

    override fun onCleared() { close(); super.onCleared() }

    private fun errorMessage(t: Throwable): String = when (t) {
        is AuthApiException.Api -> t.message ?: "Something went wrong."
        is AuthApiException.Network -> "Network error. Please try again."
        else -> "Something went wrong."
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 2
        const val DEBOUNCE_MS = 300L
    }
}

class SearchViewModelFactory(
    private val repository: ProfileRepository,
    private val chatRepository: ChatRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SearchViewModel(repository, chatRepository) as T
    }
}
