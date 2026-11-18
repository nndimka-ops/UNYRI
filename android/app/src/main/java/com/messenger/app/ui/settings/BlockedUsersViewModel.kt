package com.messenger.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.repository.BlockedUsersRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class BlockedUsersMode { BLOCKED, ADD }
data class BlockedUserAction(val user: PublicUser, val block: Boolean)
data class BlockedUsersUiState(
    val mode: BlockedUsersMode = BlockedUsersMode.BLOCKED,
    val query: String = "",
    val users: List<PublicUser> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val loadError: Boolean = false,
    val pageError: Boolean = false,
    val action: BlockedUserAction? = null,
    val actionBusy: Boolean = false,
    val actionError: Boolean = false,
    val changed: Boolean = false,
)

/** The screen owns this in-memory list; it never discovers or restores chats. */
class BlockedUsersViewModel(private val repository: BlockedUsersRepository) : ViewModel() {
    private val owner = repository.ownerKey()
    private val mutable = MutableStateFlow(BlockedUsersUiState())
    val state: StateFlow<BlockedUsersUiState> = mutable
    private var queryJob: Job? = null
    private var actionJob: Job? = null
    private var generation = 0L
    private var closed = false
    private var nextCursor: String? = null
    private var nextOffset: Long? = null
    private val knownBlockedIds = mutableSetOf<String>()

    init { refresh() }

    private fun current(): Boolean {
        if (closed) return false
        if (owner == null || owner != repository.ownerKey()) { close(); return false }
        return true
    }

    /** A settings screen can be reopened in the same Activity ViewModelStore. */
    fun open() {
        if (closed && owner != null && owner == repository.ownerKey()) {
            closed = false
            refresh()
        }
    }

    fun refresh() = request(reset = true)
    fun loadMore() {
        val current = mutable.value
        if (!current.loading && !current.loadingMore && current.canLoadMore) request(reset = false)
    }

    fun search(query: String) {
        val value = query.take(128)
        if (value == mutable.value.query || !current() || mutable.value.actionBusy) return
        mutable.value = mutable.value.copy(query = value, action = null, actionError = false)
        request(reset = true, debounce = true)
    }

    fun mode(value: BlockedUsersMode) {
        if (!current() || mutable.value.actionBusy || mutable.value.mode == value) return
        mutable.value = BlockedUsersUiState(mode = value)
        request(reset = true)
    }

    fun choose(user: PublicUser) {
        if (!current() || mutable.value.actionBusy || mutable.value.users.none { it.id == user.id }) return
        mutable.value = mutable.value.copy(action = BlockedUserAction(user, mutable.value.mode == BlockedUsersMode.ADD), actionError = false)
    }

    fun dismissAction() {
        if (!mutable.value.actionBusy) mutable.value = mutable.value.copy(action = null, actionError = false)
    }

    fun confirmAction() {
        val action = mutable.value.action ?: return
        if (!current() || mutable.value.actionBusy) return
        mutable.value = mutable.value.copy(actionBusy = true, actionError = false)
        actionJob = viewModelScope.launch {
            try {
                val result = repository.setBlocked(action.user.id, action.block)
                if (!current()) return@launch
                result.onSuccess {
                    // A stale pre-mutation page cannot put an unblocked row back.
                    ++generation
                    queryJob?.cancel()
                    if (action.block) {
                        knownBlockedIds += action.user.id
                        mutable.value = BlockedUsersUiState(changed = true)
                        request(reset = true)
                    } else {
                        knownBlockedIds -= action.user.id
                        mutable.value = mutable.value.copy(
                            users = mutable.value.users.filterNot { user -> user.id == action.user.id },
                            loading = false, loadingMore = false, action = null, actionBusy = false, actionError = false, changed = true,
                        )
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    mutable.value = mutable.value.copy(actionBusy = false, actionError = true)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (current()) mutable.value = mutable.value.copy(actionBusy = false, actionError = true)
            }
        }
    }

    private fun request(reset: Boolean, debounce: Boolean = false) {
        if (!current() || mutable.value.actionBusy) return
        queryJob?.cancel()
        val ticket = ++generation
        val snapshot = mutable.value
        if (reset) { nextCursor = null; nextOffset = null }
        mutable.value = snapshot.copy(
            users = if (reset) emptyList() else snapshot.users,
            loading = reset, loadingMore = !reset, loadError = false, pageError = false,
            canLoadMore = if (reset) false else snapshot.canLoadMore,
        )
        queryJob = viewModelScope.launch {
            try {
                if (debounce) delay(250)
                val result = if (snapshot.mode == BlockedUsersMode.BLOCKED) {
                    repository.list(snapshot.query.trim().removePrefix("@"), if (reset) null else nextCursor)
                        .map { page -> Page(page.items.map { it.user }, page.nextCursor, null) }
                } else {
                    repository.people(snapshot.query.trim().removePrefix("@"), if (reset) 0L else nextOffset ?: 0L)
                        .map { page -> Page(page.items, null, page.nextOffset) }
                }
                if (!current() || ticket != generation) return@launch
                result.onSuccess { page ->
                    if (snapshot.mode == BlockedUsersMode.BLOCKED) knownBlockedIds += page.users.map { it.id }
                    val visible = if (snapshot.mode == BlockedUsersMode.ADD) page.users.filterNot { it.id in knownBlockedIds } else page.users
                    nextCursor = page.cursor; nextOffset = page.offset
                    mutable.value = mutable.value.copy(
                        users = ((if (reset) emptyList() else mutable.value.users) + visible).distinctBy { it.id },
                        loading = false, loadingMore = false, loadError = false, pageError = false,
                        canLoadMore = page.cursor != null || page.offset != null,
                    )
                }.onFailure {
                    if (it is CancellationException) throw it
                    mutable.value = mutable.value.copy(loading = false, loadingMore = false, loadError = reset, pageError = !reset)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (current() && ticket == generation) mutable.value = mutable.value.copy(loading = false, loadingMore = false, loadError = reset, pageError = !reset)
            }
        }
    }

    /** Real screen departure/logout releases personal rows even if its VM store survives. */
    fun close() {
        closed = true; ++generation; queryJob?.cancel(); actionJob?.cancel()
        nextCursor = null; nextOffset = null
        knownBlockedIds.clear()
        mutable.value = BlockedUsersUiState()
    }

    private data class Page(val users: List<PublicUser>, val cursor: String?, val offset: Long?)

    class Factory(private val repository: BlockedUsersRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BlockedUsersViewModel(repository) as T
    }
}
