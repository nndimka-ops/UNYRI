package com.messenger.app.conference

import com.messenger.app.R
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ConferenceRoomsState(val capabilities: ConferenceCapabilities? = null, val mine: Boolean = false, val query: String = "",
    val rooms: List<DirectoryRoom> = emptyList(), val owned: List<ConferenceDto> = emptyList(), val cursor: String? = null,
    val loading: Boolean = false, val error: Int? = null, val waitingRoom: String? = null, val approvalWaiting: Boolean = false, val joinRetryAt: Long? = null)

/** Screen-scoped work. Leaving Rooms cancels discovery and any not-yet-admitted key request. */
class ConferenceRoomsController(private val api: ConferenceApi, private val mailbox: ConferenceKeyMailbox, private val auth: AuthRepository,
    private val conference: ConferenceController, private val scope: CoroutineScope, private val origin: String = ServerEndpoint.current()) {
    private val epoch = auth.currentSessionEpoch()
    private val mutable = MutableStateFlow(ConferenceRoomsState())
    val state: StateFlow<ConferenceRoomsState> = mutable
    private var load: Job? = null; private var join: Job? = null; private var revision = 0L
    private val cancellationCleanup = ConferenceRequestCleanup(scope)
    private var disposed = false
    private var readyAdmission: ConferenceAdmission.Catalog? = null
    private fun current() = !disposed && epoch == auth.currentSessionEpoch() && origin == ServerEndpoint.current()
    fun isCurrentScreen() = current()
    fun dismissError() { mutable.value = mutable.value.copy(error = null) }
    fun start() { refresh() }
    fun select(mine: Boolean) { if (mutable.value.mine != mine) { mutable.value = mutable.value.copy(mine = mine, rooms = emptyList(), owned = emptyList(), cursor = null); refresh() } }
    fun search(query: String) { mutable.value = mutable.value.copy(query = query.take(100)); if (!mutable.value.mine) refresh(debounce = true) }
    fun refresh(debounce: Boolean = false, next: Boolean = false) {
        if (next && (mutable.value.loading || mutable.value.cursor == null || mutable.value.mine)) return
        load?.cancel(); val ticket = ++revision; val before = mutable.value; val cursor = if (next) before.cursor else null
        mutable.value = before.copy(loading = true, error = null)
        load = scope.launch {
            try {
                if (debounce) delay(300)
                val caps = before.capabilities ?: try { withTimeout(4_000) { api.capabilities(origin, epoch) } } catch (error: AuthApiException.Api) { if (error.httpStatus == 404) ConferenceCapabilities() else throw error }
                if (!current() || ticket != revision) return@launch
                val mine = before.mine || !caps.roomDirectory
                if (mine) {
                    val owned = if (caps.ownerRooms) withTimeout(5_000) { api.owned(origin, epoch) }.filter { it.state == "active" } else emptyList()
                    if (current() && ticket == revision) mutable.value = mutable.value.copy(capabilities = caps, mine = true, owned = owned, rooms = emptyList(), cursor = null, loading = false)
                } else {
                    val result = withTimeout(5_000) { api.directory(before.query, cursor, origin, epoch) }
                    if (current() && ticket == revision && mutable.value.query == before.query && !mutable.value.mine) mutable.value = mutable.value.copy(capabilities = caps,
                        rooms = (if (next) mutable.value.rooms + result.items else result.items).distinctBy { it.id }, cursor = result.nextCursor, loading = false)
                }
            } catch (cancelled: CancellationException) { if (cancelled is TimeoutCancellationException && current() && ticket == revision) mutable.value = mutable.value.copy(loading = false, error = R.string.conference_directory_error) else throw cancelled }
              catch (_: Exception) { if (current() && ticket == revision) mutable.value = mutable.value.copy(loading = false, error = R.string.conference_directory_error) }
        }
    }
    fun requestJoin(room: DirectoryRoom, password: String?, onReady: (ConferenceAdmission.Catalog) -> Unit) {
        if (join?.isActive == true || !current() || mutable.value.capabilities?.roomKeyHandoff != true || room.admissionLocked || ConferenceAccess.parse(room.accessMode)?.invitation != false) return
        mutable.value = mutable.value.copy(waitingRoom = room.id, error = null, approvalWaiting = false, joinRetryAt = null)
        join = scope.launch {
            var handoff: ConferenceAdmission.Catalog? = null
            try {
                cancellationCleanup.awaitPrevious()
                ensureActive(); if (!current() || mutable.value.waitingRoom != room.id) return@launch
                val admission = mailbox.receive(room.id, origin, epoch, password,
                    waiting = { if (current() && mutable.value.waitingRoom == room.id) mutable.value = mutable.value.copy(approvalWaiting = false) },
                    approvalWaiting = { if (current() && mutable.value.waitingRoom == room.id) mutable.value = mutable.value.copy(approvalWaiting = true) })
                handoff = admission
                ensureActive(); if (!current() || mutable.value.waitingRoom != room.id) { mailbox.cancel(admission, epoch); return@launch }
                readyAdmission = admission
                handoff = null
                onReady(admission)
            } catch (_: TimeoutCancellationException) { if (current()) mutable.value = mutable.value.copy(waitingRoom = null, error = R.string.conference_key_wait_timeout) }
              catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { if (current()) mutable.value = mutable.value.copy(waitingRoom = null, approvalWaiting = false, error = errorMessage(error), joinRetryAt = (error as? ConferenceJoinApprovalFailure)?.retryAt) }
            finally { handoff?.let { withContext(NonCancellable) { mailbox.cancel(it, epoch) } } }
        }
    }
    private fun cancelReady(value: ConferenceAdmission.Catalog) { cancellationCleanup.enqueue { mailbox.cancel(value, epoch) } }
    fun cancelJoin() {
        val previous = join; previous?.cancel(); join = null
        val ready = readyAdmission; readyAdmission = null
        cancellationCleanup.enqueue(previous) { ready?.let { mailbox.cancel(it, epoch) } }
        mutable.value = mutable.value.copy(waitingRoom = null, approvalWaiting = false)
    }
    fun admit(value: ConferenceAdmission.Catalog) {
        val allowed = current() && readyAdmission === value
        readyAdmission = null; mutable.value = mutable.value.copy(waitingRoom = null, approvalWaiting = false)
        if (!allowed || !conference.joinCatalog(value)) { if (current()) mutable.value = mutable.value.copy(error = R.string.conference_error_busy); cancelReady(value) }
    }
    fun end(room: ConferenceDto) {
        scope.launch { try { withTimeout(5_000) { conference.endOwnedRoom(room.id, origin, epoch) }; if (current()) refresh() }
            catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled else if (current()) mutable.value = mutable.value.copy(error = R.string.conference_error_end) }
            catch (_: Exception) { if (current()) mutable.value = mutable.value.copy(error = R.string.conference_error_end) } }
    }
    fun pause() { revision++; load?.cancel(); load = null; cancelJoin(); mutable.value = mutable.value.copy(loading = false) }
    fun dispose() { pause(); disposed = true }
    private fun errorMessage(error: Exception): Int {
        if (error is ConferenceJoinApprovalFailure) return ConferenceErrorPresentation.message(error, false)
        val api = error as? AuthApiException.Api
        return when {
            api?.httpStatus == 403 && api.message == "Conference password is required." -> R.string.conference_password_required
            api?.httpStatus == 403 && api.message == "Conference password is incorrect." -> R.string.conference_password_incorrect
            api?.httpStatus == 409 && api.code == "settings_changed" -> R.string.conference_settings_changed
            api?.httpStatus == 404 || api?.httpStatus == 410 -> ConferenceErrorPresentation.message(error, false)
            else -> R.string.conference_key_wait_error
        }
    }
}

/** Cancel is immediate for the screen; a fresh request queues behind exact-ID cleanup.
 * No media lease, UI mutex or sixty-second wait is acquired by this queue. */
internal class ConferenceRequestCleanup(private val scope: CoroutineScope) {
    private var previous: Job? = null
    fun enqueue(request: Job? = null, cleanup: suspend () -> Unit = {}) {
        val before = previous
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { before?.join(); request?.join(); cleanup() }
        }
        previous = job
        job.invokeOnCompletion { if (previous === job) previous = null }
    }
    suspend fun awaitPrevious() { previous?.join() }
}
