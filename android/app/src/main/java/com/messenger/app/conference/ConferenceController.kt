package com.messenger.app.conference

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.R
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.network.ReconnectPolicy
import io.livekit.android.room.network.ReconnectContext
import io.livekit.android.room.track.*
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import livekit.LivekitModels
import java.net.URI

/** SDK full reconnect republishes enabled tracks before replacing their cryptors.
 * The app's recovery loop performs fresh, disabled-track publication instead. */
object ConferenceReconnectPolicy : ReconnectPolicy {
    override fun getNextRetryDelay(context: ReconnectContext): kotlin.time.Duration? = null
}

private class ConferenceAttempt(val origin: String, val epoch: Int) {
    var createdId: String? = null
    var joinId: String? = null
    var connected = false
}

data class ConferenceTile(val id: String, val name: String, val video: VideoTrack?, val muted: Boolean, val speaking: Boolean, val cameraEnabled: Boolean = video != null,
    val audioMutedForMe: Boolean = false, val videoHiddenForMe: Boolean = false, val audioBlockedByHost: Boolean = false)
data class ConferenceState(
    val active: Boolean = false, val title: String = "", val host: Boolean = false,
    val approvalAvailable: Boolean = false, val approvalRequired: Boolean = false, val approvalWaiting: Boolean = false,
    val joinRequests: List<ConferenceJoinRequest> = emptyList(), val decidingJoinRequest: String? = null, val joinRetryAt: Long? = null,
    val connecting: Boolean = false, val reconnecting: Boolean = false, val minimized: Boolean = false,
    val microphone: Boolean = false, val camera: Boolean = false,
    val tiles: List<ConferenceTile> = emptyList(), val own: ConferenceTile? = null,
    val error: Int? = null, val controlsBusy: Boolean = false,
    val roomId: String = "", val expiresAt: Long = 0,
    val pageAnchor: String? = null, val pageIndex: Int = 0, val pinnedId: String? = null,
    val previewX: Float = 1f, val previewY: Float = 0f,
    val gridMode: ConferenceGridMode = ConferenceGridMode.PAGES, val visibleParticipantCount: Int = 0,
    val hostControlsAvailable: Boolean = false, val admissionLocked: Boolean = false, val settingsBusy: Boolean = false,
    val lastAppliedSettingsRevision: Long = 0, val roomAccessAvailable: Boolean = false, val description: String = "", val listed: Boolean = false, val accessMode: String = "invite", val settingsRevision: Long = 1, val accessRevision: Long = 1,
    val participantModerationAvailable: Boolean = false, val members: List<ConferenceMember> = emptyList(), val microphoneBlockedByHost: Boolean = false,
)

/** Local camera shares the grid, including when disabled, so toggling it never moves others. */
internal fun ConferenceState.presentationTiles(): List<ConferenceTile> = listOfNotNull(own?.copy(name = "Вы")) + tiles

/** App-scoped owner. Active SDK keys stay in RAM, never SavedState/logs/REST.
 * Owner re-entry capabilities are separately protected by the account/device/login
 * vault; SDK state and participant tokens remain RAM-only. */
class ConferenceController(
    private val context: Context, private val api: ConferenceApi, private val auth: AuthRepository,
    private val scope: CoroutineScope, private val mailbox: ConferenceKeyMailbox? = null,
    private val ownedRoomStore: ConferenceOwnedRoomStore? = null, private val canStart: () -> Boolean,
) {
    private val _state = MutableStateFlow(ConferenceState())
    val state: StateFlow<ConferenceState> = _state
    private val captureReserved = MutableStateFlow(false)
    val captureReservation: StateFlow<Boolean> = captureReserved
    private val _pendingInvite = MutableStateFlow<ConferenceInvite?>(null)
    val pendingInvite: StateFlow<ConferenceInvite?> = _pendingInvite
    var room: Room? = null; private set
    private var invite: ConferenceInvite? = null
    private var admission: ConferenceAdmission? = null
    private var providerJob: Job? = null
    private val _invitePasswordRequired = MutableStateFlow(false)
    val invitePasswordRequired: StateFlow<Boolean> = _invitePasswordRequired
    private var operation: Job? = null
    private var events: Job? = null
    private var mediaControl: Job? = null
    private var cleanup: Job? = null
    private var settingsJob: Job? = null
    private var approvalRevision = 0L
    private var approvalJob: Job? = null
    private var approvalDecisionJob: Job? = null
    private var metadataJob: Job? = null
    private var metadataRevision = 0L
    private var activeAttempt: ConferenceAttempt? = null
    private var ownerEpoch = -1
    private var ownerOrigin = ""
    private var generation = 0L
    private var serviceAdmission: CompletableDeferred<Unit>? = null
    private val ordering = linkedMapOf<String, Int>()
    private var visible = emptySet<String>()
    // Room-scoped playback preferences survive SDK reconnect but never logout/another room.
    private val mutedForMe = mutableSetOf<String>()
    private val hiddenForMe = mutableSetOf<String>()
    private var screenVisible = true
    private var screenVisibilityOwner = 0L
    private var keyProvider: BaseKeyProvider? = null
    private var loss: CompletableDeferred<ConferenceLoss>? = null
    private var desiredMicrophone = false
    private var microphoneConsentRevision = 0L
    private var presentationOwner = 0L
    private var desiredCamera = false
    private var cameraOptions = LocalVideoTrackOptions(captureParams = VideoCaptureParameter(640, 360, 20))
    private var selectedAudioDevice: AudioDevice? = null
    private var audioListener: AudioDeviceChangeListener? = null
    val busy: Boolean get() = _state.value.active
    fun belongsToCurrentSession(): Boolean = busy && ownerEpoch == auth.currentSessionEpoch() && ownerOrigin == ServerEndpoint.current() && auth.authState.value is com.messenger.app.domain.model.AuthSessionState.LoggedIn
    val reservesCallSlot: Boolean get() = captureReserved.value
    val serviceGeneration: Long? get() = generation.takeIf { busy }

    fun offerInvite(raw: String) {
        _invitePasswordRequired.value = false
        _pendingInvite.value = runCatching { ConferenceInvite.parse(raw) }.getOrNull()
        if (_pendingInvite.value == null) _state.value = _state.value.copy(error = R.string.conference_error_invite)
    }
    fun dismissInvite() { _pendingInvite.value = null; _invitePasswordRequired.value = false }
    fun dismissError() { _state.value = _state.value.copy(error = null) }
    suspend fun ownedRooms(origin: String, epoch: Int): List<ConferenceDto> {
        if (!api.capabilities(origin, epoch).ownerRooms) throw UnsupportedOperationException()
        val me = (auth.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id
        return api.owned(origin, epoch).filter { it.ownerUserId == me && it.state == "active" }.take(3)
    }
    suspend fun endOwnedRoom(id: String, origin: String, epoch: Int) {
        api.end(id, origin, epoch)
        withContext(Dispatchers.IO) { ownedRoomStore?.remove(id) }
        if (belongsToCurrentSession() && _state.value.roomId == id && epoch == ownerEpoch && origin == ownerOrigin) stopLocal()
    }
    fun savedOwnerInvite(room: ConferenceDto): ConferenceInvite? {
        val me = (auth.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id ?: return null
        if (room.ownerUserId != me) return null
        return runCatching { ownedRoomStore?.invitation(room) }.getOrNull()
    }
    fun rejoinOwnedRoom(room: ConferenceDto): Boolean {
        if (reservesCallSlot || !canStart()) { reportMediaBusy(); return false }
        val saved = savedOwnerInvite(room) ?: return false
        if (ConferenceAccess.parse(room.accessMode)?.password == true) {
            _pendingInvite.value = saved; _invitePasswordRequired.value = true
        } else join(saved)
        return true
    }
    val canShareInvitation: Boolean get() = invite != null && belongsToCurrentSession()
    fun inviteLink(): String? = invite?.link()
    fun invitationCard(): ConferenceInvitation? = invite?.let { current ->
        runCatching { ConferenceInvitation(id = java.util.UUID.randomUUID().toString(), title = _state.value.title,
            server = current.server, roomId = current.roomId, invite = current.link(), expiresAt = _state.value.expiresAt).validated() }.getOrNull()
    }
    fun returnToRoom(value: ConferenceInvite): Boolean {
        if (!belongsToCurrentSession() || invite?.sameCapability(value) != true) return false
        minimize(false); return true
    }
    fun page(index: Int, size: Int) {
        val tiles = _state.value.presentationTiles()
        val count = maxOf(1, (tiles.size + size - 1) / size)
        val page = index.coerceIn(0, count - 1)
        _state.value = _state.value.copy(pageIndex = page, pageAnchor = tiles.getOrNull(page * size)?.id)
    }
    fun pin(id: String?) { _state.value = _state.value.copy(pinnedId = id?.takeIf { candidate -> _state.value.presentationTiles().any { it.id == candidate } }) }
    fun display(mode: ConferenceGridMode, count: Int = _state.value.visibleParticipantCount) {
        _state.value = _state.value.copy(gridMode = mode, visibleParticipantCount = count.coerceIn(0, 30))
    }
    fun updateSettings(title: String, locked: Boolean) {
        val before = _state.value
        if (!belongsToCurrentSession() || !before.host || !before.hostControlsAvailable || before.settingsBusy || before.connecting || before.reconnecting) return
        if (!ConferenceInvitation.validTitle(title.trim())) return
        val ticket = generation; val origin = ownerOrigin; val epoch = ownerEpoch; val id = before.roomId
        metadataRevision++
        _state.value = before.copy(settingsBusy = true)
        settingsJob = scope.launch {
            try {
                val updated = withTimeout(5_000) { api.settings(id, title, locked, origin, epoch) }
                checkCurrent(ticket)
                check(updated.id == id && updated.state == "active" && updated.ownerUserId == (auth.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id)
                metadataRevision++
                _state.value = _state.value.copy(title = updated.title, admissionLocked = updated.admissionLocked, settingsBusy = false)
            } catch (_: TimeoutCancellationException) { if (ticket == generation && belongsToCurrentSession()) { metadataRevision++; _state.value = _state.value.copy(settingsBusy = false, error = R.string.conference_error_settings) } }
              catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (ticket == generation && belongsToCurrentSession()) { metadataRevision++; _state.value = _state.value.copy(settingsBusy = false, error = R.string.conference_error_settings) } }
        }
    }
    fun updateSettings(edit: ConferenceSettingsEdit) {
        val before = _state.value
        if (!belongsToCurrentSession() || !before.host || !before.roomAccessAvailable || before.settingsBusy || before.connecting || before.reconnecting) return
        if (runCatching { edit.validate() }.isFailure) return
        val ticket = generation; val origin = ownerOrigin; val epoch = ownerEpoch
        metadataRevision++; _state.value = before.copy(settingsBusy = true)
        settingsJob = scope.launch {
            try {
                val updated = withTimeout(8_000) { api.settings(before.roomId, edit, origin, epoch) }
                checkCurrent(ticket); require(updated.id == before.roomId && updated.state == "active")
                metadataRevision++; _state.value = _state.value.copy(settingsBusy = false, lastAppliedSettingsRevision = updated.settingsRevision); applyRoomMetadata(updated)
            } catch (cancelled: CancellationException) { if (cancelled is TimeoutCancellationException && ticket == generation) _state.value = _state.value.copy(settingsBusy = false, error = R.string.conference_error_settings) else throw cancelled }
              catch (error: Exception) {
                if (ticket != generation || !belongsToCurrentSession()) return@launch
                metadataRevision++; _state.value = _state.value.copy(settingsBusy = false, error = R.string.conference_error_settings)
                if ((error as? com.messenger.app.data.api.AuthApiException.Api)?.httpStatus == 409) {
                    runCatching { api.get(before.roomId, origin, epoch) }.getOrNull()?.let { if (ticket == generation && belongsToCurrentSession()) applyRoomMetadata(it) }
                    if (ticket == generation) _state.value = _state.value.copy(error = R.string.conference_settings_changed)
                }
            }
        }
    }
    fun restrictParticipantAudio(identity: String, blocked: Boolean) = moderateParticipant(identity) { member, origin, epoch, roomId ->
        api.audioRestriction(roomId, member.userId, member.participantIdentity, blocked, origin, epoch)
    }
    fun removeParticipant(identity: String) = moderateParticipant(identity) { member, origin, epoch, roomId ->
        api.removeParticipant(roomId, member.userId, member.participantIdentity, origin, epoch)
    }
    private fun moderateParticipant(identity: String, action: suspend (ConferenceMember, String, Int, String) -> ConferenceDto) {
        val before = _state.value
        if (!belongsToCurrentSession() || !before.host || !before.participantModerationAvailable || before.settingsBusy || before.controlsBusy || before.connecting || before.reconnecting) return
        val member = before.members.firstOrNull { it.participantIdentity == identity } ?: return
        val me = (auth.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id
        if (member.userId == me || before.tiles.none { it.id == identity }) return
        val ticket = generation; val origin = ownerOrigin; val epoch = ownerEpoch; val id = before.roomId
        metadataRevision++
        _state.value = before.copy(settingsBusy = true)
        settingsJob = scope.launch {
            try {
                val updated = withTimeout(5_000) { action(member, origin, epoch, id) }
                checkCurrent(ticket)
                check(updated.id == id && updated.state == "active" && updated.ownerUserId == me)
                metadataRevision++
                _state.value = _state.value.copy(settingsBusy = false)
                applyRoomMetadata(updated)
            } catch (_: TimeoutCancellationException) {
                if (ticket == generation && belongsToCurrentSession()) { metadataRevision++; _state.value = _state.value.copy(settingsBusy = false, error = R.string.conference_error_moderation) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                if (ticket == generation && belongsToCurrentSession()) { metadataRevision++; _state.value = _state.value.copy(settingsBusy = false, error = R.string.conference_error_moderation) }
            }
        }
    }
    private fun applyRoomMetadata(dto: ConferenceDto) {
        _state.value = _state.value.copy(title = dto.title, approvalRequired = dto.approvalRequired == true, admissionLocked = dto.admissionLocked, members = dto.participants, description = dto.description, listed = dto.listed, accessMode = dto.accessMode, settingsRevision = dto.settingsRevision, accessRevision = dto.accessRevision)
        val media = room ?: return
        val ownId = media.localParticipant.identity?.value ?: media.localParticipant.sid.value
        val blocked = dto.participants.firstOrNull { it.participantIdentity == ownId }?.audioPublishBlocked == true || microphonePermissionBlocked(media)
        if (blocked) stopMicrophoneByHost(media)
        else _state.value = _state.value.copy(microphoneBlockedByHost = false)
        refreshParticipants()
    }
    private fun microphonePermissionBlocked(media: Room): Boolean = microphonePermissionBlocked(media.localParticipant)
    private fun microphonePermissionBlocked(participant: Participant): Boolean = participant.permissions?.let {
        ConferencePresentationPolicy.microphonePermissionBlocked(it.canPublish, it.canPublishSources.isNotEmpty(), Track.Source.MICROPHONE in it.canPublishSources)
    } ?: false
    private fun audioBlockedByHost(participant: Participant, identity: String): Boolean =
        _state.value.members.any { it.participantIdentity == identity && it.audioPublishBlocked } || microphonePermissionBlocked(participant)
    private fun stopMicrophoneByHost(media: Room) {
        microphoneConsentRevision++
        desiredMicrophone = false
        _state.value = _state.value.copy(microphone = false, microphoneBlockedByHost = true)
        silenceLocalMicrophone(media)
    }
    private fun silenceLocalMicrophone(media: Room) {
        media.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.let { publication ->
            publication.track?.stop()
            publication.muted = true
        }
    }
    private fun ownsPresentation(owner: Long): Boolean = ConferencePresentationPolicy.acceptsPresentation(owner, presentationOwner, busy, belongsToCurrentSession(), _state.value.minimized)
    fun anchor(owner: Long, id: String?) { if (ownsPresentation(owner) && id != null && id != _state.value.pageAnchor && _state.value.presentationTiles().any { it.id == id }) _state.value = _state.value.copy(pageAnchor = id) }
    fun preview(x: Float, y: Float) { _state.value = _state.value.copy(previewX = x.coerceIn(0f, 1f), previewY = y.coerceIn(0f, 1f)) }
    fun preview(owner: Long, x: Float, y: Float) { if (owner == presentationOwner && busy) preview(x, y) }
    fun beginPresentation(): Long = (++presentationOwner)
    fun present(owner: Long, ids: Set<String>) { if (ownsPresentation(owner)) setVisible(ids) }
    fun endPresentation(owner: Long) { if (owner == presentationOwner) { ++presentationOwner; setVisible(emptySet()) } }
    fun suspendPresentation() { ++presentationOwner; setVisible(emptySet()) }
    fun minimize(value: Boolean) { _state.value = _state.value.copy(minimized = value) }
    fun reportMediaBusy() {
        _state.value = _state.value.copy(error = when {
            _state.value.active -> R.string.media_busy_room
            reservesCallSlot -> R.string.media_busy_cleanup
            else -> R.string.media_busy_call_or_capture
        })
    }
    fun create(title: String, max: Int) = create(ConferenceCreateOptions(title, max))
    fun create(options: ConferenceCreateOptions) = begin { attempt, caps ->
        options.validate()
        val generated = ConferenceInvite.fresh(attempt.origin)
        val dto = withContext(NonCancellable) {
            api.create(options, ConferenceInvite.secretHash(generated.secret), attempt.origin, attempt.epoch, caps.roomAccessV2, caps.roomJoinApproval)
                .also { attempt.createdId = it.id }
        }
        ConferenceAdmission.Invitation(ConferenceInvite(attempt.origin, dto.id, generated.secret, generated.mediaKey), options.password)
    }
    fun join(value: ConferenceInvite, password: String? = null) = begin { attempt, _ -> ConferenceAdmission.Invitation(value.forServer(attempt.origin), password) }
    fun joinCatalog(value: ConferenceAdmission.Catalog): Boolean {
        if (reservesCallSlot || !canStart()) { reportMediaBusy(); return false }
        if (value.server != ServerEndpoint.current()) return false
        begin { attempt, _ -> require(mailbox != null && value.server == attempt.origin); value }
        return true
    }

    private fun begin(resolve: suspend (ConferenceAttempt, ConferenceCapabilities) -> ConferenceAdmission) {
        if (reservesCallSlot || !canStart()) { reportMediaBusy(); return }
        ownerOrigin = ServerEndpoint.current()
        if (runCatching { ConferenceInvite.origin(ownerOrigin) }.getOrNull() != ownerOrigin) {
            _state.value = _state.value.copy(error = R.string.conference_error_https); return
        }
        ownerEpoch = auth.currentSessionEpoch(); generation++
        presentationOwner++
        val ticket = generation
        val attempt = ConferenceAttempt(ownerOrigin, ownerEpoch)
        activeAttempt = attempt
        captureReserved.value = true
        desiredMicrophone = false; desiredCamera = false
        cameraOptions = LocalVideoTrackOptions(captureParams = VideoCaptureParameter(640, 360, 20))
        selectedAudioDevice = null
        _pendingInvite.value = null
        _state.value = ConferenceState(active = true, connecting = true)
        var pendingResolution: (suspend (ConferenceAttempt, ConferenceCapabilities) -> ConferenceAdmission)? = resolve
        operation = scope.launch {
            try {
                val vaultOwner = withContext(Dispatchers.IO) { runCatching { ownedRoomStore?.currentOwner() }.getOrNull() }
                checkCurrent(ticket)
                val capabilities = try { withTimeout(3_000) { api.capabilities(attempt.origin, attempt.epoch) } } catch (cancelled: CancellationException) { if (cancelled is TimeoutCancellationException) ConferenceCapabilities() else throw cancelled } catch (_: Exception) { ConferenceCapabilities() }
                checkCurrent(ticket)
                val current = requireNotNull(pendingResolution).invoke(attempt, capabilities)
                pendingResolution = null
                checkCurrent(ticket)
                admission = current
                invite = (current as? ConferenceAdmission.Invitation)?.value
                attempt.joinId = current.roomId
                serviceAdmission = CompletableDeferred()
                ContextCompat.startForegroundService(context, Intent(context, ConferenceService::class.java).putExtra("generation", generation))
                withTimeout(8_000) { serviceAdmission!!.await() }
                checkCurrent(ticket)
                ConferenceRecoveryLoop().run(
                    connect = {
                        checkCurrent(ticket)
                        // Cleanup waits for every admission outcome; never /leave during recovery.
                        val grant = try {
                            withContext(NonCancellable) { api.join(current, attempt.epoch, (capabilities.roomKeyHandoff || current is ConferenceAdmission.Catalog) && mailbox != null) }
                        } catch (error: Exception) {
                            if (!needsConferenceApproval(error) || current !is ConferenceAdmission.Invitation || attempt.connected) throw error
                            try {
                                api.awaitJoinApproval(current.roomId, current.value.secret, current.password, current.server, attempt.epoch) {
                                    if (ticket != generation || !belongsToCurrentSession()) throw CancellationException()
                                    _state.value = _state.value.copy(approvalWaiting = true)
                                }
                                checkCurrent(ticket)
                            } finally { if (ticket == generation) _state.value = _state.value.copy(approvalWaiting = false) }
                            withContext(NonCancellable) { api.join(current, attempt.epoch, capabilities.roomKeyHandoff && mailbox != null) }
                        }
                        (current as? ConferenceAdmission.Invitation)?.password = null
                        checkCurrent(ticket)
                        connectMedia(current, grant, ticket)
                        attempt.connected = true
                        val currentUser = (auth.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id
                        if (ownedRoomStore != null && current is ConferenceAdmission.Invitation && grant.conference.ownerUserId == currentUser) {
                            val saved = vaultOwner != null && withContext(Dispatchers.IO) {
                                runCatching { ownedRoomStore.remember(vaultOwner, current.value, grant.conference.expiresAt, grant.conference.ownerUserId) }.getOrDefault(false)
                            }
                            checkCurrent(ticket)
                            if (!saved) _state.value = _state.value.copy(error = R.string.conference_owner_access_not_saved)
                        }
                        startMetadataMonitor(current, attempt.epoch, ticket)
                        startJoinApprovalMonitor(current, attempt.epoch, ticket)
                        if ((capabilities.roomKeyHandoff || current is ConferenceAdmission.Catalog) && mailbox != null) startKeyProvider(current, grant.participantIdentity, attempt.epoch, ticket)
                    },
                    awaitLoss = { requireNotNull(loss).await() },
                    dispose = {
                        mediaControl?.cancelAndJoin(); mediaControl = null
                        releaseMedia(requireComplete = true)
                    },
                    recovering = {
                        _state.value = _state.value.copy(connecting = false, reconnecting = true, controlsBusy = false, tiles = emptyList(), own = null)
                    },
                )
            } catch (cancelled: CancellationException) { if (ticket == generation && busy) stopLocal(); throw cancelled }
              catch (error: Exception) {
                if (ticket == generation) {
                    val retry = (admission as? ConferenceAdmission.Invitation)?.value
                    val apiError = error as? com.messenger.app.data.api.AuthApiException.Api
                    val passwordRequired = apiError?.httpStatus == 403 && apiError.message in setOf("Conference password is required.", "Conference password is incorrect.")
                    stopLocal(if (passwordRequired) null else ConferenceErrorPresentation.message(error, attempt.connected),
                        retryAt = (error as? ConferenceJoinApprovalFailure)?.retryAt)
                    if (passwordRequired && retry != null && attempt.epoch == auth.currentSessionEpoch() && attempt.origin == ServerEndpoint.current()) {
                        _pendingInvite.value = retry; _invitePasswordRequired.value = true
                    }
                }
              } finally { pendingResolution = null }
        }
    }

    /** One bounded foreground watcher; no persisted capability/key and no work after teardown. */
    private fun startJoinApprovalMonitor(current: ConferenceAdmission, epoch: Int, ticket: Long) {
        approvalJob?.cancel()
        val media = room ?: return
        fun currentRoom() = ticket == generation && room === media && belongsToCurrentSession() && _state.value.roomId == current.roomId
        approvalJob = scope.launch {
            var nextDelay = 3_000L
            while (currentRoom()) {
                if (screenVisible && !_state.value.connecting && !_state.value.reconnecting && _state.value.host && _state.value.approvalAvailable && _state.value.approvalRequired) {
                    try {
                        val revision = approvalRevision
                        val value = withTimeout(4_000) { api.pendingJoinApprovals(current.roomId, current.server, epoch) }
                        if (!currentRoom()) return@launch
                        if (revision != approvalRevision || _state.value.decidingJoinRequest != null) { delay(nextDelay); continue }
                        require(value.items.size <= 64 && value.items.all { it.conferenceId == current.roomId && it.status == "pending" })
                        if (_state.value.joinRequests.map { listOf(it.requestId, it.status, it.displayName, it.username) } != value.items.map { listOf(it.requestId, it.status, it.displayName, it.username) })
                            _state.value = _state.value.copy(joinRequests = value.items)
                        nextDelay = 3_000L
                    } catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled }
                      catch (error: Exception) {
                          val status = when (error) {
                              is com.messenger.app.data.api.AuthApiException.Api -> error.httpStatus
                              is com.messenger.app.data.api.AuthApiException.Network -> error.httpStatus
                              else -> null
                          }
                          nextDelay = if (status == 429) 61_000L else 6_000L
                      }
                } else if (currentRoom() && _state.value.joinRequests.isNotEmpty()) _state.value = _state.value.copy(joinRequests = emptyList())
                delay(nextDelay)
            }
        }
    }
    fun decideJoinRequest(id: String, approve: Boolean) {
        val before = _state.value
        if (!belongsToCurrentSession() || !before.host || before.connecting || before.reconnecting || before.decidingJoinRequest != null || before.joinRequests.none { it.requestId == id }) return
        val ticket = generation; val origin = ownerOrigin; val epoch = ownerEpoch
        approvalRevision++
        _state.value = before.copy(decidingJoinRequest = id)
        approvalDecisionJob = scope.launch {
            try {
                val value = withTimeout(6_000) { api.decideJoinApproval(before.roomId, id, approve, origin, epoch) }
                checkCurrent(ticket); require(value.requestId == id && value.conferenceId == before.roomId)
                approvalRevision++
                _state.value = _state.value.copy(decidingJoinRequest = null, joinRequests = _state.value.joinRequests.filterNot { it.requestId == id })
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                if (ticket == generation) _state.value = _state.value.copy(decidingJoinRequest = null, error = R.string.conference_error_settings)
            } catch (_: Exception) {
                if (ticket == generation && belongsToCurrentSession()) _state.value = _state.value.copy(decidingJoinRequest = null, error = R.string.conference_error_settings)
            }
        }
    }

    private fun startMetadataMonitor(current: ConferenceAdmission, epoch: Int, ticket: Long) {
        metadataJob?.cancel()
        val media = room ?: return
        fun currentRoom() = ticket == generation && room === media && belongsToCurrentSession() && _state.value.roomId == current.roomId
        metadataJob = scope.launch {
            val capabilities = try { withTimeout(3_000) { api.capabilities(current.server, epoch) } }
                catch (_: TimeoutCancellationException) { null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (currentRoom()) _state.value = _state.value.copy(hostControlsAvailable = capabilities?.hostControls == true,
                participantModerationAvailable = capabilities?.participantModeration == true, roomAccessAvailable = capabilities?.roomAccessV2 == true,
                approvalAvailable = capabilities?.roomJoinApproval == true || _state.value.approvalAvailable)
            var nextDelay = 10_000L
            while (currentRoom()) {
                delay(nextDelay); nextDelay = 10_000L
                if (!screenVisible || _state.value.connecting || _state.value.reconnecting) continue
                val revision = metadataRevision
                try {
                    val dto = withTimeout(3_000) { api.get(current.roomId, current.server, epoch) }
                    if (!currentRoom()) return@launch
                    if (dto.id != current.roomId || dto.state != "active") { stopLocal(R.string.conference_error_closed); return@launch }
                    if (ConferencePresentationPolicy.acceptsMetadata(revision, metadataRevision, _state.value.settingsBusy) && ConferenceInvitation.validTitle(dto.title)) {
                        applyRoomMetadata(dto)
                    }
                } catch (_: TimeoutCancellationException) { /* transient, bounded */ }
                  catch (cancelled: CancellationException) { throw cancelled }
                  catch (error: Exception) {
                    val status = (error as? com.messenger.app.data.api.AuthApiException.Api)?.httpStatus
                    if (status == 429) nextDelay = 30_000L
                    if (currentRoom() && status in listOf(401, 403, 404, 410)) { stopLocal(ConferenceErrorPresentation.message(error, connected = true)); return@launch }
                }
            }
        }
    }

    private fun startKeyProvider(current: ConferenceAdmission, identity: String, epoch: Int, ticket: Long) {
        providerJob?.cancel()
        val media = room ?: return
        fun ready() = ticket == generation && room === media && belongsToCurrentSession() && !state.value.connecting && !state.value.reconnecting
        providerJob = scope.launch {
            var backoff = false
            while (ready()) {
                try { withTimeout(4_000) { mailbox?.provide(current, identity, epoch, ::ready) }; backoff = false }
                catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled }
                catch (error: Exception) { backoff = (error as? com.messenger.app.data.api.AuthApiException.Api)?.httpStatus == 429 }
                delay(if (backoff) 30_000 else if (screenVisible) 2_000 else 5_000)
            }
        }
    }

    private suspend fun connectMedia(current: ConferenceAdmission, grant: ConferenceGrant, ticket: Long) {
        if (!grant.e2eeRequired) throw ConferenceEncryptionFailure()
        if (grant.conference.state != "active" || grant.conference.id != current.roomId) throw ConferenceClosed()
        val endpoint = URI(grant.livekitUrl)
        require(endpoint.scheme == "wss" && !endpoint.host.isNullOrBlank() && endpoint.userInfo == null && endpoint.fragment == null)
        _state.value = _state.value.copy(title = grant.conference.title, roomId = grant.conference.id,
            approvalAvailable = grant.conference.approvalRequired != null, approvalRequired = grant.conference.approvalRequired == true,
            admissionLocked = grant.conference.admissionLocked, members = grant.conference.participants, description = grant.conference.description, listed = grant.conference.listed, accessMode = grant.conference.accessMode, settingsRevision = grant.conference.settingsRevision, accessRevision = grant.conference.accessRevision,
            microphoneBlockedByHost = grant.conference.participants.firstOrNull { it.participantIdentity == grant.participantIdentity }?.audioPublishBlocked == true,
            expiresAt = runCatching { java.time.Instant.parse(grant.conference.expiresAt).epochSecond }.getOrDefault(0),
            host = grant.conference.ownerUserId == (auth.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id)
        // LiveKit.create initializes the prefixed native library before the key provider.
        val media = LiveKit.create(context)
        room = media
        val disconnected = CompletableDeferred<ConferenceLoss>()
        loss = disconnected
        val provider = BaseKeyProvider(uncryptedMagicBytes = "", discardFrameWhenCryptorNotReady = true)
        keyProvider = provider
        if (!provider.setSharedKey(current.mediaKey)) throw ConferenceEncryptionFailure()
        media.setRoomOptions(RoomOptions(adaptiveStream = true, dynacast = true, reconnectPolicy = ConferenceReconnectPolicy,
            e2eeOptions = E2EEOptions(keyProvider = provider), videoTrackCaptureDefaults = cameraOptions,
            videoTrackPublishDefaults = VideoTrackPublishDefaults(simulcast = true)))
        (media.audioHandler as? AudioSwitchHandler)?.let { handler ->
            handler.loggingEnabled = false
            val listener = object : AudioDeviceChangeListener {
                override fun invoke(devices: List<AudioDevice>, selected: AudioDevice?) {
                    // The SDK dispatches on its audio thread; marshal generation/Room checks.
                    scope.launch {
                        val wanted = selectedAudioDevice ?: return@launch
                        if (room === media && ticket == generation && selected != wanted) {
                            devices.firstOrNull { it == wanted }?.let(handler::selectDevice)
                        }
                    }
                }
            }
            audioListener = listener
            handler.registerAudioDeviceChangeListener(listener)
        }
        events = scope.launch {
            media.events.events.collect { event ->
                if (room !== media || ticket != generation) return@collect
                when (event) {
                    is RoomEvent.Disconnected -> {
                        disconnected.complete(ConferenceRecoveryPolicy.loss(event.reason))
                        _state.value = _state.value.copy(reconnecting = true, controlsBusy = false)
                        return@collect
                    }
                    is RoomEvent.TrackE2EEStateEvent -> if (event.state in setOf(E2EEState.MISSING_KEY, E2EEState.ENCRYPTION_FAILED, E2EEState.DECRYPTION_FAILED, E2EEState.INTERNAL_ERROR)) {
                        // Encryption failure is terminal, including during a replacement join.
                        stopLocal(R.string.conference_error_encryption); return@collect
                    }
                    is RoomEvent.ParticipantPermissionsChanged -> if (event.participant === media.localParticipant) {
                        // Permission restoration only enables the button; never restore old capture intent.
                        metadataRevision++
                        if (microphonePermissionBlocked(media)) stopMicrophoneByHost(media)
                        else _state.value = _state.value.copy(microphoneBlockedByHost = false)
                    }
                    else -> Unit
                }
                refreshParticipants()
            }
        }
        try {
            withTimeout(25_000) { media.connect(grant.livekitUrl, grant.participantToken, ConnectOptions(autoSubscribe = false)) }
        } catch (error: Exception) { failConferenceConnect(error, disconnected) }
        checkMedia(media, ticket)
        if (_state.value.microphoneBlockedByHost || microphonePermissionBlocked(media)) stopMicrophoneByHost(media)
        else publishMicrophone(media, ticket)
        if (desiredCamera) publishCamera(media, ticket)
        checkMedia(media, ticket)
        _state.value = _state.value.copy(connecting = false, reconnecting = false, controlsBusy = false,
            microphone = desiredMicrophone, camera = desiredCamera)
        refreshParticipants()
    }

    private suspend fun checkMedia(media: Room, ticket: Long = generation) {
        checkCurrent(ticket)
        if (room !== media) throw CancellationException("Conference changed")
        completedLossFailure()?.let { throw it }
        if (media.state == Room.State.DISCONNECTED) throw ConferenceTransportFailure()
        if (media.e2eeManager?.enabled != true) throw ConferenceEncryptionFailure()
    }

    private suspend fun completedLossFailure(): Exception? = loss?.takeIf { it.isCompleted }?.await()?.failure()

    private suspend fun publishMicrophone(media: Room, ticket: Long) {
        try {
            publishConferenceMicrophoneIfAllowed(
                validate = { checkMedia(media, ticket) },
                blocked = { _state.value.microphoneBlockedByHost || microphonePermissionBlocked(media) },
                createDisabled = {
                    media.localParticipant.createAudioTrack().also { microphone ->
                        try { microphone.stop() } catch (error: Exception) { microphone.dispose(); throw error }
                    }
                },
                publish = { media.localParticipant.publishAudioTrack(it) },
                onPublished = { microphone ->
                    // A muted user stays muted throughout recovery, including its first frame.
                    if (desiredMicrophone) microphone.start()
                    else media.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.muted = true
                },
                silence = { stopMicrophoneByHost(media) },
                isOwned = { media.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track === it },
                dispose = { it.dispose() },
                requested = { desiredMicrophone },
            )
        } catch (error: Exception) {
            completedLossFailure()?.let { throw it }
            throw error
        }
    }

    private suspend fun publishCamera(media: Room, ticket: Long = generation) {
        val video = media.localParticipant.createVideoTrack(options = cameraOptions)
        video.stop()
        try {
            check(media.localParticipant.publishVideoTrack(video))
            checkMedia(media, ticket)
            video.start()
            video.startCapture()
        } catch (error: Exception) {
            if (media.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track !== video) video.dispose()
            completedLossFailure()?.let { throw it }
            throw error
        }
    }

    private suspend fun checkCurrent(ticket: Long = generation) {
        currentCoroutineContext().ensureActive()
        if (ticket != generation || !busy || auth.currentSessionEpoch() != ownerEpoch || ServerEndpoint.current() != ownerOrigin) throw CancellationException("Session changed")
    }
    fun serviceReady(ticket: Long, error: Throwable? = null) {
        if (ticket != generation || !busy) return
        if (error == null) serviceAdmission?.complete(Unit) else serviceAdmission?.completeExceptionally(error)
    }
    fun setVisible(ids: Set<String>) {
        // A local grid tile is a renderer only, never a remote subscription.
        visible = ids.intersect(_state.value.tiles.mapTo(hashSetOf()) { it.id })
        refreshParticipants()
    }
    fun muteForMe(id: String, muted: Boolean) = setLocalPreference(id, mutedForMe, muted)
    fun hideVideoForMe(id: String, hidden: Boolean) = setLocalPreference(id, hiddenForMe, hidden)
    private fun setLocalPreference(id: String, preferences: MutableSet<String>, enabled: Boolean) {
        if (!belongsToCurrentSession() || _state.value.tiles.none { it.id == id }) return
        if (enabled) preferences.add(id) else preferences.remove(id)
        // Retain reconnect preferences without letting a long-lived room grow unbounded.
        if (preferences.size > 128) preferences.retainAll(_state.value.tiles.mapTo(hashSetOf()) { it.id })
        // Publish immediately even without a track (e.g. the participant's camera is off).
        _state.value = _state.value.copy(tiles = _state.value.tiles.map { tile -> tile.copy(
            audioMutedForMe = tile.id in mutedForMe, videoHiddenForMe = tile.id in hiddenForMe,
            video = tile.video.takeUnless { tile.id in hiddenForMe }) })
        refreshParticipants()
    }
    fun setScreenVisible(value: Boolean) { screenVisible = value; refreshParticipants() }
    fun beginScreenVisibility(): Long = ++screenVisibilityOwner
    fun setScreenVisible(owner: Long, value: Boolean) { if (owner == screenVisibilityOwner) setScreenVisible(value) }
    fun endScreenVisibility(owner: Long) { if (owner == screenVisibilityOwner) { ++screenVisibilityOwner; setScreenVisible(false) } }
    private fun refreshParticipants() {
        val media = room ?: return
        val remote = media.remoteParticipants.values.toList()
        if (remote.any { participant -> participant.trackPublications.values.any { it.encryptionType != LivekitModels.Encryption.Type.GCM } }) {
            stopLocal(R.string.conference_error_unencrypted); return
        }
        remote.forEach { participant ->
            val id = participant.identity?.value ?: participant.sid.value
            ordering.getOrPut(id) { ordering.size }
            val hostBlocked = audioBlockedByHost(participant, id)
            participant.trackPublications.values.filterIsInstance<RemoteTrackPublication>().forEach { publication ->
                val audio = publication.kind == Track.Kind.AUDIO
                // Stop already decoded audio immediately, before the unsubscribe round trip.
                // Apply the authoritative participant restriction to EVERY AUDIO
                // publication, even if a modified sender claims CAMERA as its source.
                val desired = ConferencePresentationPolicy.subscribe(audio, screenVisible, id in visible, id in mutedForMe, id in hiddenForMe, hostBlocked)
                if (audio) publication.track?.let { track -> if (track.enabled != desired) track.enabled = desired }
                if (publication.isDesired != desired) publication.setSubscribed(desired)
            }
        }
        fun tile(p: Participant): ConferenceTile {
            val camera = p.getTrackPublication(Track.Source.CAMERA)
            val id = p.identity?.value ?: p.sid.value
            val blocked = audioBlockedByHost(p, id)
            return ConferenceTile(id, p.name?.takeIf { it.isNotBlank() } ?: "Participant", if (camera?.muted == false && id !in hiddenForMe) camera.track as? VideoTrack else null,
                p.getTrackPublication(Track.Source.MICROPHONE)?.muted != false || blocked, p.isSpeaking && id !in mutedForMe && !blocked, camera?.muted == false,
                id in mutedForMe, id in hiddenForMe, blocked)
        }
        val next = remote.sortedBy { ordering[it.identity?.value ?: it.sid.value] }.map(::tile)
        val previous = _state.value
        val own = tile(media.localParticipant)
        val selection = if (!previous.connecting && !previous.reconnecting) ConferencePresentationPolicy.rebase(
            previous.presentationTiles().map { it.id }, listOf(own.id) + next.map { it.id }, previous.pageAnchor, previous.pageIndex, previous.pinnedId)
            else ConferenceSelection(previous.pageAnchor, previous.pageIndex, previous.pinnedId)
        _state.value = previous.copy(tiles = next, own = own, pageAnchor = selection.anchor,
            pageIndex = selection.page, pinnedId = selection.pin)
    }
    fun toggleMicrophone() {
        // A blocked-button tap is information only. Do not request permission,
        // create a media Room or enter the capture-control queue for this notice.
        if (belongsToCurrentSession() && _state.value.microphoneBlockedByHost) {
            _state.value = _state.value.copy(error = R.string.conference_error_microphone_host)
            return
        }
        control { media ->
            if (_state.value.microphoneBlockedByHost || microphonePermissionBlocked(media)) {
                stopMicrophoneByHost(media)
                _state.value = _state.value.copy(error = R.string.conference_error_microphone_host)
                return@control
            }
            val previous = desiredMicrophone
            val consent = microphoneConsentRevision
            desiredMicrophone = !previous
            _state.value = _state.value.copy(microphone = desiredMicrophone)
            try {
                checkMedia(media)
                if (media.localParticipant.getTrackPublication(Track.Source.MICROPHONE) == null) publishMicrophone(media, generation)
                else check(media.localParticipant.setMicrophoneEnabled(desiredMicrophone))
                checkMedia(media)
                // A pre-block enable may complete after both block and allow events.
                // It still has no current user consent and must stay off.
                if (consent != microphoneConsentRevision || _state.value.microphoneBlockedByHost || microphonePermissionBlocked(media)) {
                    desiredMicrophone = false
                    _state.value = _state.value.copy(microphone = false)
                    silenceLocalMicrophone(media)
                }
            } catch (error: Exception) {
                if (room === media && loss?.isCompleted != true) {
                    val blocked = _state.value.microphoneBlockedByHost || microphonePermissionBlocked(media)
                    val restored = ConferencePresentationPolicy.microphoneAfterFailure(previous, blocked, consent, microphoneConsentRevision)
                    desiredMicrophone = restored
                    _state.value = _state.value.copy(microphone = restored, microphoneBlockedByHost = blocked)
                    if (!restored && (blocked || consent != microphoneConsentRevision)) silenceLocalMicrophone(media)
                }
                throw error
            }
        }
    }
    fun toggleCamera() = control { media ->
        val previous = desiredCamera
        desiredCamera = !previous
        _state.value = _state.value.copy(camera = desiredCamera)
        try {
            if (desiredCamera) {
                serviceAdmission = CompletableDeferred()
                ContextCompat.startForegroundService(context, Intent(context, ConferenceService::class.java).putExtra("generation", generation).putExtra("camera", true))
                withTimeout(8_000) { serviceAdmission!!.await() }
            }
            checkMedia(media)
            if (desiredCamera && media.localParticipant.getTrackPublication(Track.Source.CAMERA) == null) publishCamera(media)
            else check(media.localParticipant.setCameraEnabled(desiredCamera))
            checkMedia(media)
        } catch (error: Exception) {
            if (room === media && loss?.isCompleted != true) {
                desiredCamera = previous
                _state.value = _state.value.copy(camera = previous)
            }
            throw error
        }
    }
    fun switchCamera() = control { media -> (media.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack)?.switchCamera() }
    fun audioDevices(): List<AudioDevice> = (room?.audioHandler as? AudioSwitchHandler)?.availableAudioDevices.orEmpty()
    fun selectedAudioRoute(): String? = selectedAudioDevice?.name ?: (room?.audioHandler as? AudioSwitchHandler)?.selectedAudioDevice?.name
    fun selectAudioDevice(device: AudioDevice) {
        selectedAudioDevice = device
        (room?.audioHandler as? AudioSwitchHandler)?.selectDevice(device)
    }
    private fun control(action: suspend (Room) -> Unit) {
        val media = room ?: return
        if (_state.value.controlsBusy || _state.value.connecting || _state.value.reconnecting) return
        _state.value = _state.value.copy(controlsBusy = true)
        mediaControl = scope.launch {
            try { checkMedia(media); action(media); refreshParticipants() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (room === media) _state.value = _state.value.copy(error = R.string.conference_error_control) }
            finally { if (room === media) _state.value = _state.value.copy(controlsBusy = false) }
        }
    }
    fun leave(endForEveryone: Boolean = false) {
        val current = admission; val epoch = ownerEpoch; val origin = ownerOrigin
        val mayEnd = _state.value.host && endForEveryone
        if (mayEnd && current != null) {
            if (_state.value.controlsBusy) return
            _state.value = _state.value.copy(controlsBusy = true)
            val ticket = generation
            scope.launch {
                try {
                    api.end(current.roomId, origin, epoch)
                    withContext(Dispatchers.IO) { ownedRoomStore?.remove(current.roomId) }
                    if (ticket == generation) stopLocal()
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { if (ticket == generation) _state.value = _state.value.copy(controlsBusy = false, error = R.string.conference_error_end) }
            }
            return
        }
        stopLocal()
    }
    fun terminateForSessionChange() { _pendingInvite.value = null; ownedRoomStore?.forgetMemory(); stopLocal() }

    /** Stop capture before Room.cleanupRoom disposes the sender cryptors. A new
     * recovery Room is forbidden if any of these teardown operations fails. */
    private fun releaseMedia(requireComplete: Boolean) {
        metadataJob?.cancel(); metadataJob = null
        approvalRevision++
        approvalJob?.cancel(); approvalJob = null
        approvalDecisionJob?.cancel(); approvalDecisionJob = null
        providerJob?.cancel(); providerJob = null
        val media = room; room = null
        val provider = keyProvider; keyProvider = null
        loss = null
        events?.cancel(); events = null
        var failed = false
        fun release(block: () -> Unit) { if (runCatching(block).isFailure) failed = true }
        if (media != null) {
            (media.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack)?.let { cameraOptions = it.options }
            val handler = media.audioHandler as? AudioSwitchHandler
            audioListener?.let { listener -> handler?.unregisterAudioDeviceChangeListener(listener) }
            audioListener = null
            media.localParticipant.trackPublications.values.mapNotNull { it.track }.forEach { track -> release { track.stop() } }
            release { media.disconnect() }
            release { media.release() }
        }
        release { provider?.rtcKeyProvider?.dispose() }
        if (failed && requireComplete) throw IllegalStateException("Conference media teardown failed")
    }

    private fun stopLocal(error: Int? = null, retryAt: Long? = null) {
        generation++
        presentationOwner++
        val attempt = activeAttempt; activeAttempt = null
        val previousOperation = operation
        val previousControl = mediaControl
        invite = null; admission = null; mailbox?.clear(); _invitePasswordRequired.value = false; visible = emptySet(); ordering.clear(); mutedForMe.clear(); hiddenForMe.clear()
        operation?.cancel(); operation = null
        mediaControl?.cancel(); mediaControl = null
        settingsJob?.cancel(); settingsJob = null
        serviceAdmission?.cancel(); serviceAdmission = null
        releaseMedia(requireComplete = false)
        desiredMicrophone = false; desiredCamera = false; selectedAudioDevice = null
        context.stopService(Intent(context, ConferenceService::class.java))
        _state.value = ConferenceState(error = error, joinRetryAt = retryAt)
        if (attempt != null) {
            val priorCleanup = cleanup
            cleanup = scope.launch {
                try {
                    priorCleanup?.join()
                    previousOperation?.join()
                    previousControl?.join()
                    withContext(NonCancellable) {
                        runCatching {
                            if (!attempt.connected && attempt.createdId != null) api.end(attempt.createdId!!, attempt.origin, attempt.epoch)
                            else attempt.joinId?.let { api.leave(it, attempt.origin, attempt.epoch) }
                        }
                    }
                } finally { if (activeAttempt == null) captureReserved.value = false }
            }
        } else if (cleanup?.isActive != true) captureReserved.value = false
    }
}
