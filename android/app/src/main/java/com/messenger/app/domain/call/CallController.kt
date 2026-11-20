package com.messenger.app.domain.call

import android.util.Log
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.CallDto
import com.messenger.app.data.call.AudioRoute
import com.messenger.app.data.call.AudioRouteKind
import com.messenger.app.data.call.CallAudioRoutePolicy
import com.messenger.app.data.call.CallApi
import com.messenger.app.data.call.CallSignalingClient
import com.messenger.app.data.call.CallSignalingEvent
import com.messenger.app.data.call.CallRemoteSdpOrder
import com.messenger.app.data.call.CallSdpCapabilities
import com.messenger.app.data.call.PeerConnectionState
import com.messenger.app.data.call.WebRtcEngine
import com.messenger.app.data.call.WebRtcEvent
import com.messenger.app.domain.model.CallDirection
import com.messenger.app.domain.model.CallEndReason
import com.messenger.app.domain.model.CallInfo
import com.messenger.app.domain.model.CallMediaKind
import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CallController"
/** Strict upper bound for the process-dead FCM reconcile lookup: a stale or
 *  unanswerable network must never hang the incoming-call presentation past
 *  the caller's ringing window. */
private const val RECONCILE_TIMEOUT_MS = 3_000L

/** N.1A: an unanswered video-upgrade request becomes terminal after this
 *  window (audio stays connected, no hangup). */
private const val VIDEO_UPGRADE_TIMEOUT_MS = 25_000L

/** How long a transient [CallNotice] stays visible before auto-clearing. */
private const val NOTICE_CLEAR_DELAY_MS = 3_000L

/** P0.1: outcome of reconciling an incoming call against the authoritative
 *  backend. Only [RINGING] mutates state to IncomingRinging. */
private enum class IncomingReconcile { RINGING, TERMINAL, UNKNOWN }

/**
 * Single app-scoped controller owning the ENTIRE lifecycle of "the current
 * call" (Этап J п.28) - ChatScreen, a notification, an incoming-call
 * screen, and the foreground service all just observe [state]; none of them
 * touch [WebRtcEngine]/[CallApi]/[CallSignalingClient] directly. There is
 * only ever at most one non-Idle call at a time (matches the backend's
 * one-active-call-per-user invariant).
 *
 * ICE-before-SDP handling (Этап J п.16): candidates that arrive before the
 * remote description is set are queued in [pendingRemoteIce] and flushed,
 * in order, the moment `setRemoteDescription` completes. Duplicate
 * candidates (Этап J п.17) are deduped via [forwardedIce] before ever
 * reaching [WebRtcEngine].
 */
class CallController(
    private val callApi: CallApi,
    private val signalingClient: CallSignalingClient,
    private val webRtcEngine: WebRtcEngine,
    private val scope: CoroutineScope,
    private val displayNameResolver: suspend (String) -> String = { it },
    private val reconnectGracePeriodMs: Long = 30_000L,
    private val videoUpgradeTimeoutMs: Long = VIDEO_UPGRADE_TIMEOUT_MS,
    private val sessionOwner: () -> String? = { "standalone" },
    private val externalCallBusy: () -> Boolean = { false },
    private val currentUserId: () -> String? = { null },
    private val nonCallCaptureBusy: () -> Boolean = { false },
    private val drainNonCallCapture: suspend () -> Boolean = { true },
    private val onCaptureStillBusy: () -> Unit = {},
    private val onStartBlocked: (CallStartBlockedReason) -> Unit = {},
    private val onDiagnostics: (Map<String, Double>) -> Unit = {},
    private val diagnosticsRecording: () -> Boolean = { false },
    private val decorationDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val isLocallyBlocked: (String) -> Boolean = { false },
    private val isContact: (String) -> Boolean = { false },
) {
    private val _state = MutableStateFlow<CallUiState>(CallUiState.Idle)
    val state: StateFlow<CallUiState> = _state
    private val _startingOutgoing = MutableStateFlow<CallInfo?>(null)
    val startingOutgoing: StateFlow<CallInfo?> = _startingOutgoing
    private val _callStats = MutableStateFlow<com.messenger.app.data.call.WebRtcCallStats?>(null)
    val callStats: StateFlow<com.messenger.app.data.call.WebRtcCallStats?> = _callStats
    private val _audioRoutes = MutableStateFlow<List<AudioRoute>>(emptyList())
    val audioRoutes: StateFlow<List<AudioRoute>> = _audioRoutes
    fun remoteVoiceLevels() = webRtcEngine.remoteVoiceLevels()
    fun setRendererVisibility(local: Boolean, remote: Boolean) = webRtcEngine.setRendererVisibility(local, remote)
    private val _ringIndicator = MutableStateFlow<CallRingIndicator?>(null)
    val ringIndicator: StateFlow<CallRingIndicator?> = _ringIndicator
    var ringbackStartedAtElapsedRealtimeMs: Long? = null
        private set
    private var outgoingReads: CallOutgoingReadPolicy? = null
    private var outgoingRingingJob: Job? = null
    private var outgoingReadJob: Job? = null
    private var outgoingReadGeneration = 0L
    private val unboundPeerRinging = linkedSetOf<String>()
    private var incomingConfirmationJob: Job? = null
    private var confirmedIncomingCallId: String? = null

    private fun reduceRinging(event: CallRingingIndicatorPolicy.Event) {
        val before = _ringIndicator.value
        val next = CallRingingIndicatorPolicy.reduce(before, event).value
        if (next?.ringbackAllowed == true && before?.ringbackAllowed != true) ringbackStartedAtElapsedRealtimeMs = elapsedRealtimeMs()
        if (next?.ringbackAllowed != true) ringbackStartedAtElapsedRealtimeMs = null
        _ringIndicator.value = next
        // Confirmation/legacy keep the terminal backstop; answer/end cancel all ringing work.
        if (next?.phase?.isTerminal == true) {
            outgoingRingingJob?.cancel(); outgoingRingingJob = null
            outgoingReadGeneration++
            outgoingReadJob?.cancel(); outgoingReadJob = null
        }
    }

    /** Both push and socket paths reach this gate only after a successful platform post. */
    fun incomingNotificationPosted(callId: String, presented: Boolean) {
        if (com.messenger.app.BuildConfig.DEBUG) Log.d("CallRingAck", "posted presented=$presented current=${(_state.value as? CallUiState.IncomingRinging)?.call?.callId == callId} confirmed=${confirmedIncomingCallId == callId}")
        if (!presented || confirmedIncomingCallId == callId ||
            (_state.value as? CallUiState.IncomingRinging)?.call?.callId != callId) return
        val ticket = incomingGeneration to sessionOwner()
        confirmedIncomingCallId = callId
        incomingConfirmationJob = scope.launch {
            repeat(2) { attempt ->
                if (!ownsIncoming(ticket) || (_state.value as? CallUiState.IncomingRinging)?.call?.callId != callId) return@launch
                try {
                    if (withTimeoutOrNull(5_000L) {
                        callApi.ringingIfCurrent(callId) { ownsIncoming(ticket) && (_state.value as? CallUiState.IncomingRinging)?.call?.callId == callId }
                    } == null) throw AuthApiException.Network("Ringing confirmation timed out")
                    if (com.messenger.app.BuildConfig.DEBUG) Log.d("CallRingAck", "confirmation delivered")
                    return@launch
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (com.messenger.app.BuildConfig.DEBUG) Log.d("CallRingAck", "confirmation failed attempt=$attempt type=${e.javaClass.simpleName} status=${(e as? AuthApiException.Api)?.httpStatus}")
                    if (attempt != 0 || !CallRingConfirmationPolicy.retry(e)) return@launch
                    kotlinx.coroutines.delay(1_000L)
                }
            }
        }
    }

    /** Foreground refresh follows the same unthrottled path as a socket reconnect. */
    fun refreshOutgoingRinging() {
        val call = (_state.value as? CallUiState.OutgoingRinging)?.call ?: return
        reconcileOutgoingRinging(call.callId)
    }

    private fun startOutgoingRingingChecks(callId: String) {
        if (outgoingRingingJob != null) return
        outgoingRingingJob = scope.launch {
            while (isCurrentCall(callId) && _state.value is CallUiState.OutgoingRinging) {
                val due = outgoingReads?.next(_ringIndicator.value?.awaitsConfirmation == true) ?: break
                kotlinx.coroutines.delay((due.at - elapsedRealtimeMs()).coerceAtLeast(0))
                if (!isCurrentCall(callId) || _state.value !is CallUiState.OutgoingRinging) break
                // A refresh/confirmation may have changed the next due read during sleep.
                val current = outgoingReads?.next(_ringIndicator.value?.awaitsConfirmation == true) ?: break
                if (current.at <= elapsedRealtimeMs()) reconcileOutgoingRinging(callId, current.read)
            }
        }
    }

    /** Refreshes supersede stalled reads and do not consume or share the polling throttle.
     * Stamp the request, not its response: a slow early GET cannot declare not-online. */
    private fun reconcileOutgoingRinging(callId: String, read: CallOutgoingReadPolicy.Read = CallOutgoingReadPolicy.Read.REFRESH) {
        if (!isCurrentCall(callId) || _state.value !is CallUiState.OutgoingRinging) return
        val policy = outgoingReads ?: return
        val issuedAt = elapsedRealtimeMs()
        policy.issued(read, issuedAt)
        val generation = ++outgoingReadGeneration
        outgoingReadJob?.cancel()
        val ticket = incomingGeneration to sessionOwner()
        outgoingReadJob = scope.launch {
            try {
                val row = withTimeoutOrNull(5_000L) { callApi.getCall(callId) } ?: return@launch
                if (generation != outgoingReadGeneration || !ownsIncoming(ticket) || !isCurrentCall(callId) ||
                    _state.value !is CallUiState.OutgoingRinging || row.id != callId) return@launch
                val call = currentCallInfoOrNull() ?: return@launch
                if (row.calleeUserId != call.otherUserId || currentUserId()?.let { it != row.callerUserId } == true) return@launch
                reduceRinging(CallRingingIndicatorPolicy.Event.ServerRow(row.state, row.peerRinging,
                    row.peerRingingExpected, issuedAt >= policy.deadline))
                when (row.state) {
                    "accepted", "connected" -> scope.launch { handleSignalingEvent(CallSignalingEvent.Accepted(callId)) }
                    "ended" -> endLocally(call, mapEndReason(row.endReason ?: "failed"))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Own network failures never imply peer absence. */ }
        }
    }

    /** Includes an outgoing REST request whose ringing state has not arrived yet. */
    fun canBeginConference(): Boolean = !nonCallCaptureBusy() && canBeginNonCallCapture()
    fun canBeginNonCallCapture(): Boolean = !externalCallBusy() && creatingCall == null && when (_state.value) {
        CallUiState.Idle, is CallUiState.Ended, is CallUiState.Failed -> true
        else -> false
    }

    fun foregroundServiceTicket(): CallForegroundServiceTicket? {
        if (_state.value !is CallUiState.Connecting && _state.value !is CallUiState.Connected && _state.value !is CallUiState.Reconnecting) return null
        val call = currentCallInfoOrNull() ?: return null
        return CallForegroundServiceTicket(call.callId, incomingGeneration, sessionOwner() ?: return null)
    }

    /** Platform admission may finish after logout or a different call starts.
     * Never release that newer call's media or use its credentials for cleanup. */
    fun foregroundServiceUnavailable(ticket: CallForegroundServiceTicket): Boolean {
        if (foregroundServiceTicket() != ticket) return false
        val call = currentCallInfoOrNull() ?: return false
        endLocally(call, CallEndReason.FAILED, CallFailureReason.DEVICE_RESTRICTED)
        scope.launch {
            withTimeoutOrNull(RECONCILE_TIMEOUT_MS) {
                try {
                    callApi.markFailedIfCurrent(ticket.callId) {
                        ticket.generation == incomingGeneration && ticket.owner == sessionOwner()
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Local media is already released; server liveness bounds an unavailable API. */ }
            }
        }
        return true
    }

    /** P0.1: invoked when a terminal call push must clear the incoming-call
     *  notification even with NO local ringing state (process restart). The
     *  notification manager owns the actual cancel; this controller is
     *  framework-free and only signals it with the terminal [String] call_id
     *  (the manager applies the owner-scoped ownership check). */
    var onDismissIncomingPush: (String) -> Unit = {}
    var onIncomingPushReconciled: () -> Unit = {}
    private val terminalPushCalls = linkedSetOf<String>()
    private var terminalHintProbe: Pair<String, Job>? = null
    private var incomingGeneration = 0L
    private fun ownsIncoming(ticket: Pair<Long, String?>) = ticket.first == incomingGeneration && ticket.second != null && ticket.second == sessionOwner()

    private var activeCallId: String? = null
    private val audioRoutePolicy = CallAudioRoutePolicy()
    private var creatingCall: Long? = null
    private var outgoingCreationGeneration = 0L
    private var acceptingCallId: String? = null
    private var mediaReady = false
    private var initialCameraFailed = false
    private var localCameraHasFrame = false
    // User capture intent survives an internal camera teardown while answering glare.
    private var localCameraRequested = false
    private var remoteVideoSignaled: Boolean? = null
    private var remoteDescriptionSet = false
    private var pendingOffer: CallSignalingEvent.Offer? = null
    private var remoteSdpOrder = CallRemoteSdpOrder()
    private var pendingOfferJob: Job? = null
    private val pendingRemoteIce = mutableListOf<CallSignalingEvent.Ice>()
    private val sas = CallSasHandshake()
    private val _comparisonCode = kotlinx.coroutines.flow.MutableStateFlow<List<Int>?>(null)
    val comparisonCode: kotlinx.coroutines.flow.StateFlow<List<Int>?> = _comparisonCode
    private fun updateComparison(callId: String) {
        val fingerprints = webRtcEngine.dtlsFingerprints()
        _comparisonCode.value = sas.indices(callId, fingerprints?.first, fingerprints?.second)
    }
    private val forwardedIce = mutableSetOf<Triple<String, String?, Int?>>()
    private var reconnectJob: Job? = null
    private var signalingJob: Job? = null
    private var engineJob: Job? = null
    private var statsJob: Job? = null
    private var socketStateJob: Job? = null
    private var upgradeTimeoutJob: Job? = null
    private var noticeJob: Job? = null
    private var keepAliveJob: Job? = null

    /** Offer purpose determines how an applied answer updates presentation. */
    private enum class OfferPurpose { INITIAL, MEDIA, ICE_RESTART }
    private data class LocalOffer(
        val callId: String,
        val generation: Long,
        val purpose: OfferPurpose,
        val videoMode: VideoMode,
        val sdp: String? = null,
    )
    // A single owner covers every local SDP offer, including one still being
    // produced. Epochs never reset: rollback/end invalidate suspended work.
    @Volatile private var negotiationGeneration = 0L
    private var localOffer: LocalOffer? = null
    private var remoteOfferGeneration: Long? = null
    // Native local SDP can outlive its producer/ticket. A superseding remote
    // negotiation inherits this obligation until its own guarded rollback finishes.
    private var localRollbackPending = false
    private var queuedMediaMode: VideoMode? = null

    /** Tracks the signaling socket's previous connection state so we can
     *  distinguish an initial connect from a genuine reconnect (Этап
     *  J.1-6): only a DISCONNECTED/CONNECTING -> CONNECTED transition while
     *  a call is in progress triggers a state reconciliation. */
    private var lastSocketState: SocketConnectionState? = null

    /** Buffered so a remote track arriving during [CallUiState.Connecting]
     *  (which happens for video, where `onTrack` fires at
     *  setRemoteDescription time, before ICE reaches CONNECTED) is not lost. */
    private var remoteVideoEnabled = false

    private var recovery = com.messenger.app.data.call.CallRecoveryPolicy()
    private var recoveryWorking = false
    private var outageStartedAt: Long? = null

    init {
        signalingJob = scope.launch {
            signalingClient.events.collect { event ->
                val owner = activeCallId
                try { handleSignalingEvent(event) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    trace("signaling-failed event=${event.javaClass.simpleName} error=${e.javaClass.simpleName}")
                    // Established media survives a failed SDP/ICE operation.
                    // Its bounded recovery policy, not a catch-all, owns termination.
                    if (connectedSnapshot() == null) owner?.let(::failCurrentCall)
                }
            }
        }
        engineJob = scope.launch {
            webRtcEngine.events.collect { event -> handleEngineEvent(event) }
        }
        socketStateJob = scope.launch {
            signalingClient.connectionState.collect { state ->
                val previous = lastSocketState
                lastSocketState = state
                val reconnected = state == SocketConnectionState.CONNECTED &&
                    previous != null && previous != SocketConnectionState.CONNECTED
                if (reconnected) reconcileAfterReconnect()
            }
        }
        // N.2R: keep the server's call liveness fresh while this device is
        // actually in a call. Without it the backend (which is not a media
        // proxy) could never tell a live-but-quiet call from one abandoned
        // by every device, so it would either leak "busy" rows forever or
        // have to kill long calls. Sent on a fixed interval; each frame is a
        // fire-and-forget best effort (a missed frame is made up on the next
        // tick, well within the server's 40s grace).
        keepAliveJob = scope.launch {
            while (isActive) {
                val callId = when (val s = _state.value) {
                    is CallUiState.Connecting -> s.call.callId
                    is CallUiState.Connected -> s.call.callId
                    is CallUiState.Reconnecting -> s.call.callId
                    else -> null
                }
                if (callId != null) signalingClient.sendKeepAlive(callId)
                kotlinx.coroutines.delay(CALL_KEEP_ALIVE_INTERVAL_MS)
            }
        }
    }

    // -------------------------------------------------------------------
    // Outgoing
    // -------------------------------------------------------------------

    fun startCall(otherUserId: String, otherDisplayName: String, mediaKind: CallMediaKind) {
        val ticket = incomingGeneration to sessionOwner()
        if (!ownsIncoming(ticket)) return
        val blocked = when {
            externalCallBusy() -> CallStartBlockedReason.ROOM
            nonCallCaptureBusy() -> CallStartBlockedReason.CAPTURE
            _state.value !is CallUiState.Idle || creatingCall != null -> CallStartBlockedReason.CALL
            else -> null
        }
        if (blocked != null) { onStartBlocked(blocked); return }
        val creation = ++outgoingCreationGeneration
        creatingCall = creation
        _ringIndicator.value = null
        unboundPeerRinging.clear()
        ringbackStartedAtElapsedRealtimeMs = null
        _startingOutgoing.value = CallInfo("pending", otherUserId, com.messenger.app.domain.model.DisplayNameSanitizer.peer(otherDisplayName), mediaKind, CallDirection.OUTGOING)
        reduceRinging(CallRingingIndicatorPolicy.Event.Started)
        scope.launch {
            var createdCallInfo: CallInfo? = null
            fun releaseUnpublishedAudio(): String? {
                val info = createdCallInfo ?: return null
                if (!ownsIncoming(ticket) || activeCallId != info.callId || _state.value !is CallUiState.Idle) return null
                activeCallId = null
                resetSignalingBuffers()
                webRtcEngine.dispose()
                return info.callId
            }
            try {
                if (!ownsIncoming(ticket)) return@launch
                val call = callApi.createCall(otherUserId, mediaKind.wireValue())
                // A completed old-account request cannot restore its call or
                // prepare audio after logout/server/account replacement. Do
                // not cancel it using the new session's API credentials.
                if (!ownsIncoming(ticket)) return@launch
                // An incoming call may have arrived during the HTTP request.
                if (creatingCall != creation || _startingOutgoing.value == null) {
                    runCatching { callApi.cancel(call.id) }
                    return@launch
                }
                if (_state.value !is CallUiState.Idle) {
                    runCatching { callApi.cancel(call.id) }
                    return@launch
                }
                val info = CallInfo(call.id, otherUserId, com.messenger.app.domain.model.DisplayNameSanitizer.peer(otherDisplayName), mediaKind, CallDirection.OUTGOING)
                createdCallInfo = info
                activeCallId = call.id
                resetSignalingBuffers()
                // Ringback observes this state. Prepare communication mode
                // and the real output before its first audible publication.
                webRtcEngine.prepareCallAudio()
                if (!ownsIncoming(ticket) || activeCallId != call.id || _state.value !is CallUiState.Idle) return@launch
                outgoingReads = CallOutgoingReadPolicy(elapsedRealtimeMs())
                reduceRinging(CallRingingIndicatorPolicy.Event.Created(call.peerRingingExpected == true))
                _state.value = CallUiState.OutgoingRinging(info, audioRoute = webRtcEngine.currentAudioRoute().kind)
                _startingOutgoing.value = null
                if (call.id in unboundPeerRinging) reduceRinging(CallRingingIndicatorPolicy.Event.PeerRinging)
                unboundPeerRinging.clear()
                startOutgoingRingingChecks(call.id)
                reconcileOutgoingRinging(call.id, CallOutgoingReadPolicy.Read.CREATED)
            } catch (e: CancellationException) {
                releaseUnpublishedAudio()
                throw e
            }
            catch (e: Exception) {
                if (!ownsIncoming(ticket)) return@launch
                if (creatingCall != creation) return@launch
                if (createdCallInfo == null && _startingOutgoing.value == null) return@launch
                releaseUnpublishedAudio()?.let { callId ->
                    scope.launch { if (ownsIncoming(ticket)) runCatching { callApi.markFailed(callId) } }
                }
                val owner = activeCallId
                if (owner != null && _state.value is CallUiState.OutgoingRinging) failCurrentCall(owner)
                else if (_state.value is CallUiState.Idle) {
                    val reason = when {
                        e is AuthApiException.Api && e.httpStatus == 409 && e.code == "conflict" && e.message == "busy" -> CallFailureReason.BUSY
                        e is AuthApiException.Network -> CallFailureReason.NETWORK
                        e is AuthApiException.Api && e.httpStatus == 429 -> CallFailureReason.RATE_LIMITED
                        else -> CallFailureReason.GENERIC
                    }
                    _state.value = CallUiState.Failed(createdCallInfo, reason)
                    reduceRinging(CallRingingIndicatorPolicy.Event.CreateFailed(reason == CallFailureReason.BUSY))
                }
            } finally {
                // The old request may finish while a new account's create is
                // still pending. Only its own ticket may release that guard.
                if (creatingCall == creation) { creatingCall = null; _startingOutgoing.value = null }
            }
        }
    }

    fun cancelStartingOutgoing() {
        if (_startingOutgoing.value == null || _state.value !is CallUiState.Idle) return
        _startingOutgoing.value = null
        reduceRinging(CallRingingIndicatorPolicy.Event.CancelledHere)
    }

    fun cancelOutgoing() {
        val call = (_state.value as? CallUiState.OutgoingRinging)?.call ?: return
        reduceRinging(CallRingingIndicatorPolicy.Event.CancelledHere)
        endLocally(call, CallEndReason.CANCELLED)
        scope.launch { runCatching { callApi.cancel(call.callId) }.onFailure { runCatching { callApi.hangup(call.callId) } } }
    }

    // -------------------------------------------------------------------
    // Incoming
    // -------------------------------------------------------------------

    fun acceptIncoming() {
        if (externalCallBusy()) return
        val call = (_state.value as? CallUiState.IncomingRinging)?.call ?: return
        if (isLocallyBlocked(call.otherUserId)) { rejectIncoming(); return }
        if (acceptingCallId == call.callId) return
        val ticket = incomingGeneration to sessionOwner()
        acceptingCallId = call.callId
        scope.launch {
            try {
                // Incoming presentation remains independent of non-call capture.
                // Stop owned preview/voice and await actual release before Accept.
                if (!drainNonCallCapture()) {
                    if (ownsIncoming(ticket) && isCurrentCall(call.callId) && _state.value is CallUiState.IncomingRinging) onCaptureStillBusy()
                    return@launch
                }
                if (!ownsIncoming(ticket) || !isCurrentCall(call.callId) || _state.value !is CallUiState.IncomingRinging || externalCallBusy()) return@launch
                if (isLocallyBlocked(call.otherUserId)) { rejectIncoming(); return@launch }
                val updated = callApi.accept(call.callId)
                if (!ownsIncoming(ticket) || !isCurrentCall(call.callId)) return@launch
                if (isLocallyBlocked(call.otherUserId)) { rejectIncoming(); return@launch }
                _state.value = CallUiState.Connecting(call)
                if (!beginMediaSetup(updated)) return@launch
                pendingOffer?.let { offer ->
                    pendingOffer = null
                    handleRemoteOffer(offer)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (isCurrentCall(call.callId)) {
                    if (_state.value is CallUiState.IncomingRinging) resetToIdle()
                    else failCurrentCall(call.callId)
                    onDismissIncomingPush(call.callId)
                }
            } finally {
                if (acceptingCallId == call.callId) acceptingCallId = null
            }
        }
    }

    fun rejectIncoming(): Job? {
        val call = (_state.value as? CallUiState.IncomingRinging)?.call ?: return null
        endLocally(call, CallEndReason.REJECTED)
        return scope.launch { runCatching { callApi.reject(call.callId) } }
    }

    // -------------------------------------------------------------------
    // FCM wake path (Этап K.4/K.5)
    // -------------------------------------------------------------------

    /** Process-dead wake: the FCM payload is only a hint. Reconcile against
     *  the authoritative backend call before ever ringing locally - a stale
     *  push for an already ended/cancelled/answered-elsewhere call must
     *  never resurrect it. Never touches media here; that only happens after
     *  the user explicitly taps Accept (an Activity-visible path). */
    private fun canPresentIncoming(): Boolean = !externalCallBusy() && when (_state.value) {
        CallUiState.Idle, is CallUiState.Ended, is CallUiState.Failed -> true
        else -> false
    }

    fun showIncomingCallFromPush(callId: String, mediaKind: String): Job? {
        if (!canPresentIncoming() || callId in terminalPushCalls) {
            Log.i("IncomingWake", "ignored busy=${!canPresentIncoming()} terminal=${callId in terminalPushCalls}")
            return null
        }
        val ticket = incomingGeneration to sessionOwner()
        val t0 = elapsedRealtimeMs()
        if (com.messenger.app.BuildConfig.DEBUG) Log.d(TAG, "push reconcile begin callId=$callId")
        return scope.launch {
            val result = reconcileIncomingCall(callId, mediaKind, ticket)
            // A sparse, content-free release breadcrumb distinguishes delivery
            // from an authoritative lookup failure after process recreation.
            Log.i("IncomingWake", "lookup=${result.name} current=${ownsIncoming(ticket)} elapsed_ms=${elapsedRealtimeMs() - t0}")
            if (ownsIncoming(ticket) && result == IncomingReconcile.TERMINAL) dismissAuthoritativelyInvalidIncoming(callId)
            if (ownsIncoming(ticket)) onIncomingPushReconciled()
            if (com.messenger.app.BuildConfig.DEBUG) Log.d(TAG, "push reconcile finished callId=$callId elapsedMs=${elapsedRealtimeMs() - t0}")
        }
    }

    /** P0.1: on process restart, reconcile the persisted incoming-notification
     *  owner (from [com.messenger.app.call.IncomingNotificationOwnerStore]).
     *  Still RINGING -> restore [CallUiState.IncomingRinging] so the observer
     *  keeps/re-posts the notification. TERMINAL -> owner-scoped dismiss.
     *  UNKNOWN (network fail/timeout) -> leave the potentially valid ringing
     *  notification untouched (never blindly erase it). */
    fun reconcileIncomingNotificationOwner(callId: String) {
        if (!canPresentIncoming()) return
        val ticket = incomingGeneration to sessionOwner()
        scope.launch {
            val result = reconcileIncomingCall(callId, "audio", ticket)
            if (!ownsIncoming(ticket)) return@launch
            when (result) {
                IncomingReconcile.TERMINAL -> dismissAuthoritativelyInvalidIncoming(callId)
                IncomingReconcile.RINGING, IncomingReconcile.UNKNOWN -> Unit
            }
        }
    }

    /** Check a ringing call without restoring it or touching accepted media.
     * A lost terminal event must clear the state as well as the notification. */
    suspend fun reconcileRingingNotification(callId: String) {
        if ((_state.value as? CallUiState.IncomingRinging)?.call?.callId != callId) return
        val ticket = incomingGeneration to sessionOwner()
        val call = try {
            withTimeoutOrNull(RECONCILE_TIMEOUT_MS) { callApi.getCall(callId) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        if (!ownsIncoming(ticket) ||
            (_state.value as? CallUiState.IncomingRinging)?.call?.callId != callId) return
        // Network failure is not evidence of hangup. Retry on the next probe.
        val recipient = currentUserId()
        if (call != null && (call.id != callId || call.state != "ringing" ||
                    (recipient != null && (call.calleeUserId != recipient || call.callerUserId == recipient)))) {
            dismissIncomingCallFromPush(callId)
        }
    }

    /** Shared authoritative reconcile. Returns the outcome; only the RINGING
     *  path mutates state to [CallUiState.IncomingRinging]. */
    private suspend fun reconcileIncomingCall(callId: String, mediaKind: String, ticket: Pair<Long, String?>): IncomingReconcile {
        if (!ownsIncoming(ticket)) return IncomingReconcile.UNKNOWN
        val call = try {
            withTimeoutOrNull(RECONCILE_TIMEOUT_MS) { callApi.getCall(callId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: AuthApiException) {
            val outcome = when (e) {
                is AuthApiException.Api -> "api_${e.code.take(40)}_http_${e.httpStatus ?: 0}"
                is AuthApiException.Network -> "network_http_${e.httpStatus ?: 0}"
            }
            Log.i("IncomingWake", "lookup_failure=$outcome")
            if (com.messenger.app.BuildConfig.DEBUG) Log.w(TAG, "push reconcile auth failure callId=$callId: ${e.message}")
            return IncomingReconcile.UNKNOWN
        } catch (error: Exception) {
            // A truncated body or malformed JSON is not an authoritative
            // hangup. Do not let a background wake crash the whole process.
            val category = when (error) {
                is java.io.IOException -> "response_read"
                is kotlinx.serialization.SerializationException -> "response_decode"
                else -> "unexpected"
            }
            Log.i("IncomingWake", "lookup_failure=$category")
            return IncomingReconcile.UNKNOWN
        }
        if (!ownsIncoming(ticket)) return IncomingReconcile.UNKNOWN
        if (call == null) {
            if (com.messenger.app.BuildConfig.DEBUG) Log.w(TAG, "push reconcile timeout callId=$callId")
            return IncomingReconcile.UNKNOWN
        }
        // A wake hint/old PendingIntent is never proof of direction: GET /calls
        // also permits the caller. Only this account's actual incoming call may
        // become a ringing notification, even after a process/account restart.
        val recipient = currentUserId()?.takeIf { it.isNotBlank() } ?: return IncomingReconcile.UNKNOWN
        if (call.id != callId || call.calleeUserId != recipient || call.callerUserId == recipient || isLocallyBlocked(call.callerUserId) || call.state != "ringing") {
            if (com.messenger.app.BuildConfig.DEBUG) Log.w(TAG, "push reconcile stale callId=$callId state=${call.state}")
            return IncomingReconcile.TERMINAL
        }
        if ((_state.value as? CallUiState.IncomingRinging)?.call?.callId == callId) return IncomingReconcile.RINGING
        val name = try {
            kotlinx.coroutines.withContext(decorationDispatcher) {
                val resolved = withTimeoutOrNull(300L) { displayNameResolver(call.callerUserId) }.orEmpty()
                com.messenger.app.domain.model.DisplayNameSanitizer.peer(resolved)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { com.messenger.app.domain.model.DisplayNameSanitizer.unknownUser }
        // Network/name lookups suspend: a terminal push or a newer call may win.
        if (!ownsIncoming(ticket)) return IncomingReconcile.UNKNOWN
        if (callId in terminalPushCalls) return IncomingReconcile.TERMINAL
        // The socket can win while the authoritative lookup/name suspends.
        // Its live incoming notification is not evidence of a terminal call.
        if ((_state.value as? CallUiState.IncomingRinging)?.call?.callId == callId) return IncomingReconcile.RINGING
        if (isLocallyBlocked(call.callerUserId)) return IncomingReconcile.TERMINAL
        if (!canPresentIncoming()) return IncomingReconcile.UNKNOWN
        val kind = if (call.mediaKind == "video") CallMediaKind.VIDEO else CallMediaKind.AUDIO
        val info = CallInfo(call.id, call.callerUserId, com.messenger.app.domain.model.DisplayNameSanitizer.peer(name), kind, CallDirection.INCOMING)
        adoptIncomingCall(info)
        if (com.messenger.app.BuildConfig.DEBUG) Log.d(TAG, "push reconcile done callId=$callId")
        return IncomingReconcile.RINGING
    }

    /** `call_ended`/`answered_elsewhere` push: if we are still ringing for
     *  that call id, drop the ringing state (the notification manager also
     *  removes the notification via [IncomingCallPresentationPolicy]). */
    fun dismissIncomingCallFromPush(callId: String) {
        rememberTerminalIncoming(callId)
        val current = _state.value
        when {
            current is CallUiState.IncomingRinging && current.call.callId == callId -> {
                // The push is for the call we are currently ringing - drop
                // the state. Also signal the owner-scoped slot cleanup: in the
                // process-alive case the observer removes it (in-memory
                // ownership), but after a process restart the notification
                // belongs to a PREVIOUS process and only the persisted owner
                // can prove it should be canceled here.
                resetToIdle()
                onDismissIncomingPush(callId)
            }
            current is CallUiState.Idle -> {
                // P0.1 process-dead path: we were (re)started with no local
                // call state, yet a terminal call push arrived. Signal the
                // terminal call_id — the notification manager only cancels
                // when it matches the persisted slot owner.
                onDismissIncomingPush(callId)
            }
            // A caller may miss the socket's terminal frame while FCM still
            // works. The hint alone must not terminate accepted/live media.
            else -> if (currentCallInfoOrNull()?.callId == callId) reconcileTerminalHint(callId)
        }
    }

    /** Only the matching ringing state is invalidated; never touch accepted or newer media. */
    private fun dismissAuthoritativelyInvalidIncoming(callId: String) {
        rememberTerminalIncoming(callId)
        if ((_state.value as? CallUiState.IncomingRinging)?.call?.callId == callId) resetToIdle()
        onDismissIncomingPush(callId)
    }

    private fun rememberTerminalIncoming(callId: String) {
        terminalPushCalls.add(callId)
        if (terminalPushCalls.size > 128) terminalPushCalls.remove(terminalPushCalls.first())
    }

    private fun reconcileTerminalHint(callId: String) {
        if (terminalHintProbe?.let { it.first == callId && it.second.isActive } == true) return
        terminalHintProbe?.second?.cancel()
        val ticket = incomingGeneration to sessionOwner()
        val probe = scope.launch {
            val ended = try {
                withTimeoutOrNull(RECONCILE_TIMEOUT_MS) { callApi.getCall(callId) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { null }
            if (!ownsIncoming(ticket) || !isCurrentCall(callId) || ended == null || ended.id != callId || ended.state != "ended") return@launch
            val call = currentCallInfoOrNull() ?: return@launch
            endLocally(call, mapEndReason(ended.endReason ?: "failed"))
        }
        terminalHintProbe = callId to probe
    }

    // -------------------------------------------------------------------
    // In-call controls
    // -------------------------------------------------------------------

    fun hangup() {
        trace("local-hangup")
        val call = currentCallInfoOrNull() ?: return
        if (_state.value is CallUiState.OutgoingRinging) reduceRinging(CallRingingIndicatorPolicy.Event.CancelledHere)
        endLocally(call, CallEndReason.HANGUP)
        scope.launch { runCatching { callApi.hangup(call.callId) } }
    }

    fun toggleMute() {
        val next = when (val s = _state.value) {
            is CallUiState.OutgoingRinging -> s.copy(isMuted = !s.isMuted)
            is CallUiState.Connecting -> s.copy(isMuted = !s.isMuted)
            is CallUiState.Connected -> s.copy(isMuted = !s.isMuted)
            is CallUiState.Reconnecting -> {
                val previous = s.since as? CallUiState.Connected ?: return
                s.copy(since = previous.copy(isMuted = !previous.isMuted))
            }
            else -> return
        }
        _state.value = next
        webRtcEngine.setMuted(currentMute())
    }

    fun toggleSpeaker() {
        val connected = _state.value as? CallUiState.Connected ?: return
        val newSpeaker = !connected.isSpeakerOn
        audioRoutePolicy.selectedManually(if (newSpeaker) AudioRouteKind.SPEAKER else AudioRouteKind.EARPIECE)
        webRtcEngine.setSpeakerOn(newSpeaker)
        updateAudioRoute(webRtcEngine.currentAudioRoute().kind)
    }

    fun toggleVideo() {
        val intent = when (val current = _state.value) {
            is CallUiState.OutgoingRinging -> current.localVideoEnabled
            is CallUiState.Connecting -> current.localVideoEnabled
            else -> connectedSnapshot()?.localVideoEnabled ?: return
        }
        setCameraEnabled(!intent)
    }

    /** Camera on/off within a video call (N.1A §12/§13): the call stays
     *  video-capable, only the local capture is disabled. Notifies the peer
     *  so they can show "Camera is off" instead of a black rectangle.
     *  Turning ON goes OFF -> STARTING -> (first frame) -> ON; a capture
     *  failure lands in FAILED with a localized notice and audio continues. */
    fun setCameraEnabled(enabled: Boolean) {
        trace("camera enabled=$enabled")
        val preMedia = when (val current = _state.value) {
            is CallUiState.OutgoingRinging -> current.call
            is CallUiState.Connecting -> current.call
            else -> null
        }
        if (preMedia != null) {
            if (preMedia.mediaKind != CallMediaKind.VIDEO) return
            var accepted = enabled
            if (mediaReady) {
                accepted = try {
                    val ready = !enabled || webRtcEngine.localVideoRunning() || webRtcEngine.startLocalVideo()
                    if (!isCurrentCall(preMedia.callId)) return
                    if (!ready) false else { webRtcEngine.setVideoEnabled(enabled); enabled }
                } catch (_: Exception) {
                    if (isCurrentCall(preMedia.callId)) runCatching { webRtcEngine.setVideoEnabled(false) }
                    false
                }
            }
            if (!isCurrentCall(preMedia.callId)) return
            localCameraRequested = accepted
            if (!accepted) localCameraHasFrame = false
            if (enabled) initialCameraFailed = !accepted
            publishInitialCameraIntent(preMedia.callId, accepted)
            if (mediaReady) {
                signalingClient.sendVideoState(preMedia.callId, accepted)
                if (!accepted) updateAutomaticAudioRoute(localCameraEnabled = false)
            }
            return
        }
        val connected = connectedSnapshot() ?: return
        val pendingVideo = localOffer?.videoMode == VideoMode.VIDEO || queuedMediaMode == VideoMode.VIDEO ||
            connected.videoUpgrade == VideoUpgradeState.Upgrading
        if (connected.videoMode != VideoMode.VIDEO && !pendingVideo) return
        localCameraRequested = enabled
        if (enabled) {
            if (!webRtcEngine.localVideoRunning()) {
                val ok = webRtcEngine.startLocalVideo()
                if (!ok) {
                    localCameraRequested = false
                    publishConnectedUpdate(connected.copy(
                        localVideoEnabled = false,
                        localCameraState = LocalCameraState.FAILED,
                        notice = CallNotice.CAMERA_FAILED,
                    ))
                    updateAutomaticAudioRoute(localCameraEnabled = false)
                    clearNoticeLater()
                    return
                }
            }
            try {
                webRtcEngine.setVideoEnabled(true)
            } catch (_: Exception) {
                localCameraRequested = false
                publishConnectedUpdate(connected.copy(localVideoEnabled = false, localCameraState = LocalCameraState.FAILED, notice = CallNotice.CAMERA_FAILED))
                signalingClient.sendVideoState(connected.call.callId, false)
                updateAutomaticAudioRoute(localCameraEnabled = false)
                clearNoticeLater()
                return
            }
            publishConnectedUpdate(connected.copy(
                localCameraIsFrontFacing = webRtcEngine.localCameraIsFrontFacing(),
                localVideoEnabled = true,
                localCameraState = LocalCameraState.STARTING,
            ))
        } else {
            webRtcEngine.setVideoEnabled(false)
            publishConnectedUpdate(connected.copy(
                localVideoEnabled = false,
                localCameraState = LocalCameraState.OFF,
            ))
        }
        if (!enabled) updateAutomaticAudioRoute(localCameraEnabled = false)
        signalingClient.sendVideoState(connected.call.callId, enabled)
    }

    val torchState get() = webRtcEngine.torchState
    val localOrientation get() = webRtcEngine.localOrientation
    val remoteGeometry get() = webRtcEngine.remoteGeometry
    fun toggleTorch() {
        val c = connectedSnapshot() ?: return
        if (c.localVideoEnabled && c.localCameraState == LocalCameraState.ON && webRtcEngine.torchState.value.available) {
            webRtcEngine.toggleTorch()
        }
    }

    fun switchCamera() {
        webRtcEngine.switchCamera()
    }

    fun hasMultipleCameras(): Boolean = webRtcEngine.hasMultipleCameras()

    // -------------------------------------------------------------------
    // N.1A audio -> video upgrade
    // -------------------------------------------------------------------

    /** Requester side: create the local video track, send the upgrade
     *  request and wait for accept/reject/timeout. CAMERA permission must be
     *  resolved by the UI BEFORE this is called; a missing camera or failed
     *  capture degrades to audio-only with a notice, never a failed call. */
    fun requestVideoUpgrade() {
        trace("upgrade-request")
        val connected = connectedSnapshot() ?: return
        if (connected.videoMode != VideoMode.AUDIO || connected.videoUpgrade != VideoUpgradeState.Idle) return
        val ok = webRtcEngine.startLocalVideo()
        if (!ok) {
            publishConnectedUpdate(connected.copy(
                localVideoEnabled = false,
                localCameraState = LocalCameraState.FAILED,
                notice = CallNotice.CAMERA_FAILED,
            ))
            updateAutomaticAudioRoute(localCameraEnabled = false)
            clearNoticeLater()
            return
        }
        webRtcEngine.setVideoNegotiationEnabled(true)
        localCameraRequested = true
        signalingClient.sendVideoUpgradeRequest(connected.call.callId)
        publishConnectedUpdate(connected.copy(
            localCameraIsFrontFacing = webRtcEngine.localCameraIsFrontFacing(),
            localVideoEnabled = true,
            localCameraState = LocalCameraState.STARTING,
            videoUpgrade = VideoUpgradeState.Requesting,
        ))
        armUpgradeTimeout()
    }

    fun cancelVideoUpgrade() {
        val connected = connectedSnapshot() ?: return
        if (connected.videoUpgrade != VideoUpgradeState.Requesting) return
        signalingClient.sendVideoUpgradeCancel(connected.call.callId)
        upgradeTimeoutJob?.cancel()
        localCameraRequested = false
        webRtcEngine.stopLocalVideo()
        publishConnectedUpdate(connected.copy(localVideoEnabled = false, videoUpgrade = VideoUpgradeState.Idle))
        updateAutomaticAudioRoute(localCameraEnabled = false)
    }

    /** Recipient side: accept the incoming upgrade request. The requester
     *  then re-offers; our camera is NOT auto-enabled (N.1A §7) — but the
     *  local video track must exist and be negotiated in that re-offer.
     *  Creating it now (disabled) makes the re-offer/answer negotiate a
     *  bidirectional video m-line, so [setCameraEnabled](true) later only
     *  flips `track.setEnabled(true)` instead of needing a second on-the-fly
     *  renegotiation (which produced a black screen / no local preview for
     *  the accepting side). A missing camera still degrades gracefully: the
     *  call becomes video for the requester only, no crash. */
    fun acceptVideoUpgrade() {
        val connected = connectedSnapshot() ?: return
        if (connected.videoUpgrade != VideoUpgradeState.IncomingRequest) return
        webRtcEngine.setVideoNegotiationEnabled(true)
        localCameraRequested = false
        webRtcEngine.startLocalVideo(capture = false)
        webRtcEngine.setVideoEnabled(false)
        signalingClient.sendVideoUpgradeAccept(connected.call.callId)
        upgradeTimeoutJob?.cancel()
        publishConnectedUpdate(connected.copy(
            localCameraIsFrontFacing = webRtcEngine.localCameraIsFrontFacing(),
            videoUpgrade = VideoUpgradeState.Upgrading,
            localCameraState = LocalCameraState.OFF,
        ))
    }

    fun rejectVideoUpgrade() {
        val connected = connectedSnapshot() ?: return
        if (connected.videoUpgrade != VideoUpgradeState.IncomingRequest) return
        signalingClient.sendVideoUpgradeReject(connected.call.callId)
        upgradeTimeoutJob?.cancel()
        publishConnectedUpdate(connected.copy(videoUpgrade = VideoUpgradeState.Idle))
    }

    /** N.1A §12 video -> audio: stop capture, remove the video m-line via a
     *  renegotiation. Distinct from [setCameraEnabled](false) which keeps the
     *  call video-capable. call_id/duration/audio connection are preserved. */
    fun switchToAudio() {
        val connected = connectedSnapshot() ?: return
        val pendingVideo = localOffer?.let { it.purpose == OfferPurpose.MEDIA && it.videoMode == VideoMode.VIDEO } == true
        if (connected.videoMode != VideoMode.VIDEO && !pendingVideo && queuedMediaMode != VideoMode.VIDEO) return
        localCameraRequested = false
        webRtcEngine.stopLocalVideo()
        webRtcEngine.setVideoNegotiationEnabled(false)
        publishConnectedUpdate(connected.copy(
            videoMode = VideoMode.AUDIO,
            localVideoEnabled = false,
            remoteVideoEnabled = false,
            localCameraState = LocalCameraState.OFF,
            remoteVideoState = RemoteVideoState.OFF,
            videoUpgrade = VideoUpgradeState.Upgrading,
        ))
        updateAutomaticAudioRoute(localCameraEnabled = false)
        requestMediaOffer(connected.call.callId, VideoMode.AUDIO)
    }

    private fun armUpgradeTimeout() {
        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(videoUpgradeTimeoutMs)
            val connected = connectedSnapshot() ?: return@launch
            when (connected.videoUpgrade) {
                VideoUpgradeState.Requesting -> {
                    // Our request expired: terminal, audio stays, no hangup.
                    localCameraRequested = false
                    webRtcEngine.stopLocalVideo()
                    publishConnectedUpdate(connected.copy(
                        localVideoEnabled = false,
                        videoUpgrade = VideoUpgradeState.Idle,
                        notice = CallNotice.VIDEO_REQUEST_TIMEOUT,
                    ))
                    updateAutomaticAudioRoute(localCameraEnabled = false)
                    clearNoticeLater()
                }
                VideoUpgradeState.IncomingRequest -> {
                    // Their request expired on our side.
                    publishConnectedUpdate(connected.copy(videoUpgrade = VideoUpgradeState.Idle))
                }
                else -> Unit
            }
        }
    }

    private fun clearNoticeLater() {
        noticeJob?.cancel()
        noticeJob = scope.launch {
            kotlinx.coroutines.delay(NOTICE_CLEAR_DELAY_MS)
            val connected = _state.value as? CallUiState.Connected ?: return@launch
            if (connected.notice != null) _state.value = connected.copy(notice = null)
        }
    }

    /** Renderer attach/detach pass-throughs (Этап J.1-3) - the Compose call
     *  UI owns the `SurfaceViewRenderer` lifecycle (AndroidView factory +
     *  onRelease) but never touches [WebRtcEngine] directly. */
    fun attachLocalRenderer(renderer: Any?) = webRtcEngine.attachLocalRenderer(renderer)
    fun attachRemoteRenderer(renderer: Any?) = webRtcEngine.attachRemoteRenderer(renderer)
    fun detachLocalRenderer(renderer: Any?) = webRtcEngine.detachLocalRenderer(renderer)
    fun detachRemoteRenderer(renderer: Any?) = webRtcEngine.detachRemoteRenderer(renderer)
    fun reinitRenderers() = webRtcEngine.reinitRenderers()

    /** Only routes an actual device supports (Этап J.1 п.13/14) - the UI
     *  must never show a route the current device doesn't have. */
    fun availableAudioRoutes(): List<AudioRoute> = webRtcEngine.availableAudioRoutes()

    fun setAudioRoute(route: AudioRoute) {
        if (_state.value !is CallUiState.OutgoingRinging && _state.value !is CallUiState.Connecting && _state.value !is CallUiState.Connected && _state.value !is CallUiState.Reconnecting) return
        audioRoutePolicy.selectedManually(route.kind)
        webRtcEngine.prepareCallAudio()
        webRtcEngine.setAudioRoute(route)
        updateAudioRoute(webRtcEngine.currentAudioRoute().kind)
    }

    private fun updateAutomaticAudioRoute(localCameraEnabled: Boolean? = null) {
        if (localCameraEnabled == false) localCameraHasFrame = false
        if (currentCallInfoOrNull() == null) return
        val available = webRtcEngine.availableAudioRoutes()
        val current = webRtcEngine.currentAudioRoute().kind
        val target = if (localCameraEnabled != null) {
            audioRoutePolicy.localCameraChanged(localCameraEnabled, available.map { it.kind }, current)
        } else audioRoutePolicy.devicesChanged(available.map { it.kind }, current)
        if (target != null) available.firstOrNull { it.kind == target }?.let(webRtcEngine::setAudioRoute)
        updateAudioRoute(webRtcEngine.currentAudioRoute().kind)
    }

    private fun currentMute(): Boolean = when (val s = _state.value) {
        is CallUiState.OutgoingRinging -> s.isMuted
        is CallUiState.Connecting -> s.isMuted
        is CallUiState.Connected -> s.isMuted
        is CallUiState.Reconnecting -> (s.since as? CallUiState.Connected)?.isMuted ?: false
        else -> false
    }

    private fun updateAudioRoute(kind: AudioRouteKind) {
        _audioRoutes.value = webRtcEngine.availableAudioRoutes()
        _state.value = when (val s = _state.value) {
            is CallUiState.OutgoingRinging -> s.copy(audioRoute = kind)
            is CallUiState.Connecting -> s.copy(audioRoute = kind)
            is CallUiState.Connected -> s.copy(audioRoute = kind, isSpeakerOn = kind == AudioRouteKind.SPEAKER)
            is CallUiState.Reconnecting -> {
                val previous = s.since as? CallUiState.Connected ?: return
                s.copy(since = previous.copy(audioRoute = kind, isSpeakerOn = kind == AudioRouteKind.SPEAKER))
            }
            else -> s
        }
    }

    fun currentAudioRoute(): AudioRoute = webRtcEngine.currentAudioRoute()

    /** N.1A: CAMERA permission denied while upgrading to video - the audio
     *  call stays Connected and a localized notice is shown. No hangup, no
     *  silent no-op (code-review 9). */
    fun notifyCameraDenied() {
        val connected = _state.value as? CallUiState.Connected ?: return
        _state.value = connected.copy(notice = CallNotice.CAMERA_DENIED)
        clearNoticeLater()
    }

    /** Called by the UI once it has fully observed a terminal state - resets
     *  to Idle so a new call can start. Kept separate from the transition
     *  itself so the UI has a chance to show "Call ended" / duration first. */
    fun acknowledgeEnded() {
        if (_state.value is CallUiState.Ended || _state.value is CallUiState.Failed) {
            resetToIdle()
        }
    }

    /**
     * P1-2: account/logout cleanup. Tears down ANY current call purely
     * locally and returns to [CallUiState.Idle] with NO `Ended`/`Failed`
     * screen and NO network signaling (the old account's token is already
     * gone, so a remote hangup/cancel cannot be sent here — the backend's
     * liveness sweep terminates a still-ringing peer later). WebRTC
     * resources are released, every in-flight job/buffer is reset, and
     * [terminalPushCalls] is cleared so the next account starts clean and a
     * delayed old-account call event can never resurrect this call.
     */
    fun terminateForLogout() {
        terminalHintProbe?.second?.cancel(); terminalHintProbe = null
        incomingGeneration++
        creatingCall = null
        _startingOutgoing.value = null
        acceptingCallId = null
        terminalPushCalls.clear()
        activeCallId = null
        resetSignalingBuffers()
        statsJob?.cancel()
        statsJob = null
        webRtcEngine.dispose()
        _state.value = CallUiState.Idle
        _ringIndicator.value = null
        ringbackStartedAtElapsedRealtimeMs = null
    }

    // -------------------------------------------------------------------
    // Signaling event handling
    // -------------------------------------------------------------------

    private suspend fun handleSignalingEvent(event: CallSignalingEvent) {
        when (event) {
            is CallSignalingEvent.PeerRinging -> {
                if (event.callId == activeCallId && _state.value is CallUiState.OutgoingRinging) {
                    reduceRinging(CallRingingIndicatorPolicy.Event.PeerRinging)
                } else if (creatingCall != null && _startingOutgoing.value != null &&
                    _state.value is CallUiState.Idle && unboundPeerRinging.size < 8) {
                    // The confirmation can beat the create response/audio preparation.
                    // Only the id eventually returned by our create may be replayed.
                    unboundPeerRinging += event.callId
                }
            }
            is CallSignalingEvent.Ringing -> {
                val recipient = currentUserId()?.takeIf { it.isNotBlank() } ?: return
                if (event.callerUserId == recipient || isLocallyBlocked(event.callerUserId)) return
                // FCM and the socket have independent delivery order. A
                // terminal push may beat an already queued ringing frame;
                // it must not resurrect the same call after dismissal.
                if (event.callId in terminalPushCalls) return
                // Ignore only while ACTIVELY in a call. A previous call's
                // terminal (Ended/Failed) state is just UI waiting to be
                // acknowledged - a new incoming call must override it, never
                // be silently dropped (found live in J.1-5/J.1-8: after a
                // missed call the next incoming call was ignored).
                val busy = when (_state.value) {
                    is CallUiState.IncomingRinging, is CallUiState.OutgoingRinging,
                    is CallUiState.Connecting, is CallUiState.Connected, is CallUiState.Reconnecting,
                    -> true
                    else -> false
                }
                if (busy || externalCallBusy()) return
                val mediaKind = if (event.mediaKind == "video") CallMediaKind.VIDEO else CallMediaKind.AUDIO
                val info = CallInfo(event.callId, event.callerUserId, com.messenger.app.domain.model.DisplayNameSanitizer.unknownUser, mediaKind, CallDirection.INCOMING)
                adoptIncomingCall(info)
                // Name lookup must not delay ringing or block subsequent terminal frames.
                scope.launch {
                    val name = kotlinx.coroutines.withContext(decorationDispatcher) {
                        withTimeoutOrNull(1500L) { runCatching { displayNameResolver(event.callerUserId) }.getOrNull() }
                            ?.let { com.messenger.app.domain.model.DisplayNameSanitizer.peer(it) }
                    } ?: return@launch
                    val current = _state.value as? CallUiState.IncomingRinging ?: return@launch
                    if (current.call.callId == event.callId) _state.value = current.copy(call = current.call.copy(otherDisplayName = com.messenger.app.domain.model.DisplayNameSanitizer.peer(name)))
                }
            }
            is CallSignalingEvent.Offer -> {
                if (event.callId != activeCallId) return
                if (remoteSdpOrder.isStale(event.sdp, answer = false)) return
                rememberPeerCapabilities(event.sdp)
                handleRemoteOffer(event)
            }
            is CallSignalingEvent.Answer -> {
                if (event.callId != activeCallId) return
                rememberPeerCapabilities(event.sdp)
                val offer = localOffer ?: return // late/duplicate/abandoned answer
                if (remoteSdpOrder.isStale(event.sdp, answer = true)) return
                if (offer.sdp != null) {
                    // Reserve the peer version when accepting the event, before
                    // native work can suspend across expiry/rollback (iOS noteRemote).
                    remoteSdpOrder.applied(event.sdp)
                    applyLocalAnswer(offer, event.sdp)
                }
            }
            is CallSignalingEvent.Ice -> {
                if (event.callId != activeCallId) return
                // Real candidates (and the SAS reveal) are a few hundred characters.
                if (event.candidate.length > MAX_ICE_CANDIDATE_CHARS || (event.sdpMid?.length ?: 0) > 64) return
                if (event.candidate.startsWith(CallSasHandshake.REVEAL)) {
                    sas.receiveReveal(event.candidate, event.callId); updateComparison(event.callId); return
                }
                if (!com.messenger.app.data.call.RemoteIcePolicy.allows(event.candidate, relayOnly = false)) return
                if (event.sdpMLineIndex != null && event.sdpMLineIndex !in 0..16) return
                val key = Triple(event.candidate, event.sdpMid, event.sdpMLineIndex)
                if (key in forwardedIce) return // duplicate - ignore
                if (remoteDescriptionSet) {
                    forwardedIce += key
                    webRtcEngine.addIceCandidate(event.candidate, event.sdpMid, event.sdpMLineIndex)
                } else if (pendingRemoteIce.size < MAX_PENDING_REMOTE_ICE &&
                    pendingRemoteIce.none { it.candidate == event.candidate && it.sdpMid == event.sdpMid && it.sdpMLineIndex == event.sdpMLineIndex }) {
                    // A ringing call already receives the caller's ICE; a caller (or
                    // server) flooding it while the phone rings must not grow memory.
                    pendingRemoteIce += event
                }
            }
            is CallSignalingEvent.Accepted -> {
                if (event.callId != activeCallId) return
                val ringing = _state.value as? CallUiState.OutgoingRinging ?: return
                val call = ringing.call
                reduceRinging(CallRingingIndicatorPolicy.Event.Accepted)
                _state.value = CallUiState.Connecting(call, ringing.isMuted, ringing.audioRoute, ringing.localVideoEnabled)
                val updated = try {
                    callApi.getCall(call.callId)
                } catch (e: AuthApiException) {
                    endLocally(call, CallEndReason.FAILED)
                    return
                }
                if (!beginMediaSetup(updated)) return
                launchLocalOffer(call.callId, OfferPurpose.INITIAL,
                    if (call.mediaKind == CallMediaKind.VIDEO) VideoMode.VIDEO else VideoMode.AUDIO)
            }
            is CallSignalingEvent.AnsweredElsewhere -> {
                if (event.callId != activeCallId) return
                // This device lost the multi-device accept race - just
                // silently dismiss, nothing went wrong for the user.
                val lostCallId = event.callId
                resetToIdle()
                onDismissIncomingPush(lostCallId)
            }
            is CallSignalingEvent.VideoUpgradeRequest -> {
                if (event.callId != activeCallId) return
                val connected = connectedSnapshot() ?: return
                if (connected.videoUpgrade == VideoUpgradeState.Requesting) {
                    // Both users chose camera on. Preserve our live capture,
                    // acknowledge theirs, and let the SDP glare roles converge.
                    signalingClient.sendVideoUpgradeAccept(event.callId)
                    beginVideoUpgradeOffer(connected)
                    return
                }
                if (connected.videoUpgrade != VideoUpgradeState.Idle) return
                publishConnectedUpdate(connected.copy(videoUpgrade = VideoUpgradeState.IncomingRequest))
                // Enabling video is an in-call toggle, so do not block the
                // peer behind a second confirmation dialog. The local camera
                // remains disabled on this side; only the incoming stream is
                // negotiated and shown immediately.
                acceptVideoUpgrade()
            }
            is CallSignalingEvent.VideoUpgradeAccept -> {
                if (event.callId != activeCallId) return
                val connected = connectedSnapshot() ?: return
                if (connected.videoUpgrade != VideoUpgradeState.Requesting) return
                beginVideoUpgradeOffer(connected)
            }
            is CallSignalingEvent.VideoUpgradeReject -> {
                if (event.callId != activeCallId) return
                val connected = connectedSnapshot() ?: return
                if (connected.videoUpgrade != VideoUpgradeState.Requesting) return
                upgradeTimeoutJob?.cancel()
                localCameraRequested = false
                webRtcEngine.stopLocalVideo()
                publishConnectedUpdate(connected.copy(
                    localVideoEnabled = false,
                    videoUpgrade = VideoUpgradeState.Idle,
                    notice = CallNotice.VIDEO_REQUEST_DECLINED,
                ))
                updateAutomaticAudioRoute(localCameraEnabled = false)
                clearNoticeLater()
            }
            is CallSignalingEvent.VideoUpgradeCancel -> {
                if (event.callId != activeCallId) return
                val connected = connectedSnapshot() ?: return
                if (connected.videoUpgrade != VideoUpgradeState.IncomingRequest) return
                upgradeTimeoutJob?.cancel()
                publishConnectedUpdate(connected.copy(videoUpgrade = VideoUpgradeState.Idle))
            }
            is CallSignalingEvent.VideoState -> {
                if (event.callId != activeCallId) return
                remoteVideoSignaled = event.enabled
                remoteVideoEnabled = event.enabled
                val connected = connectedSnapshot() ?: return
                if (event.enabled) webRtcEngine.expectRemoteVideoFrame()
                publishConnectedUpdate(connected.copy(
                    remoteVideoEnabled = remoteVideoEnabled,
                    remoteVideoState = if (remoteVideoEnabled) RemoteVideoState.WAITING else RemoteVideoState.OFF,
                ))
            }
            is CallSignalingEvent.Ended -> {
                if (event.callId != activeCallId) return
                trace("remote-ended reason=${mapEndReason(event.reason)}")
                val call = currentCallInfoOrNull() ?: return
                endLocally(call, mapEndReason(event.reason))
            }
        }
    }

    private fun beginVideoUpgradeOffer(connected: CallUiState.Connected) {
        upgradeTimeoutJob?.cancel()
        publishConnectedUpdate(connected.copy(videoUpgrade = VideoUpgradeState.Upgrading))
        requestMediaOffer(connected.call.callId, VideoMode.VIDEO)
    }

    private fun ownsOffer(offer: LocalOffer): Boolean =
        negotiationGeneration == offer.generation && localOffer?.generation == offer.generation && isCurrentCall(offer.callId)

    private fun ownsRemoteOffer(callId: String, generation: Long): Boolean =
        negotiationGeneration == generation && remoteOfferGeneration == generation && isCurrentCall(callId)

    private fun resolveLocalOffer(offer: LocalOffer) {
        if (!ownsOffer(offer)) return
        pendingOfferJob?.cancel(); pendingOfferJob = null
        localOffer = null
        negotiationGeneration++
        recovery.offerResolved(elapsedRealtimeMs())
    }

    /** Registration precedes every async SDP step; retransmission keeps these exact bytes. */
    private fun launchLocalOffer(callId: String, purpose: OfferPurpose, mode: VideoMode): Boolean {
        if (!isCurrentCall(callId) || localOffer != null || remoteOfferGeneration != null || localRollbackPending) return false
        val offer = LocalOffer(callId, ++negotiationGeneration, purpose, mode)
        trace("local-offer purpose=$purpose mode=$mode")
        localOffer = offer
        recovery.offerRequested(elapsedRealtimeMs(), restart = purpose == OfferPurpose.ICE_RESTART)
        // Media negotiation progresses even while ICE/audio and stats are healthy.
        if (purpose != OfferPurpose.INITIAL) {
            pendingOfferJob?.cancel()
            pendingOfferJob = scope.launch {
                while (ownsOffer(offer)) {
                    kotlinx.coroutines.delay(1_000L)
                    driveRecovery(callId)
                }
            }
        }
        scope.launch {
            try {
                if (!ownsOffer(offer)) return@launch
                webRtcEngine.setVideoNegotiationEnabled(mode == VideoMode.VIDEO)
                if (purpose == OfferPurpose.ICE_RESTART) webRtcEngine.restartIce()
                _comparisonCode.value = null
                val sdp = sas.offer(CallSdpCapabilities.advertising(webRtcEngine.createOffer { ownsOffer(offer) }), callId)
                if (!ownsOffer(offer)) return@launch
                localOffer = offer.copy(sdp = sdp)
                if (signalingClient.sendOffer(callId, sdp)) recovery.offerSent(elapsedRealtimeMs())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!ownsOffer(offer)) return@launch
                trace("local-offer-failed purpose=$purpose error=${e.javaClass.simpleName}")
                if (purpose == OfferPurpose.INITIAL) failCurrentCall(callId)
                else abandonLocalOffer(offer, reportMediaFailure = purpose == OfferPurpose.MEDIA)
            }
        }
        return true
    }

    private fun requestMediaOffer(callId: String, mode: VideoMode) {
        if (!launchLocalOffer(callId, OfferPurpose.MEDIA, mode)) queuedMediaMode = mode
        if (localRollbackPending && remoteOfferGeneration == null) scope.launch { retryPendingRollback(callId) }
    }

    private fun drainQueuedMediaOffer(callId: String) {
        if (!isCurrentCall(callId) || connectedSnapshot() == null || localOffer != null || remoteOfferGeneration != null || localRollbackPending) return
        val mode = queuedMediaMode ?: return
        queuedMediaMode = null
        if (mode == VideoMode.VIDEO && localCameraRequested) {
            val current = connectedSnapshot() ?: return
            // Video-preserving glare keeps the existing first-frame evidence.
            // Only an actual teardown/disabled capture needs a fresh STARTING.
            if (!current.localVideoEnabled || !webRtcEngine.localVideoRunning()) {
                try {
                    if (!webRtcEngine.localVideoRunning() && !webRtcEngine.startLocalVideo()) {
                        mediaNegotiationFailed(mode)
                        return
                    }
                    webRtcEngine.setVideoEnabled(true)
                    publishConnectedUpdate(current.copy(localVideoEnabled = true,
                        localCameraState = LocalCameraState.STARTING,
                        localCameraIsFrontFacing = webRtcEngine.localCameraIsFrontFacing()))
                } catch (_: Exception) {
                    mediaNegotiationFailed(mode)
                    return
                }
            }
        }
        launchLocalOffer(callId, OfferPurpose.MEDIA, mode)
    }

    /** Invalidate before rollback waits for native SDP work, including an unfinished producer. */
    private fun beginRemoteOffer(preserveMediaIntent: Boolean = false): Long {
        // A queued target is newer than the crossed offer's intent.
        if (preserveMediaIntent && queuedMediaMode == null) {
            localOffer?.takeIf { it.purpose == OfferPurpose.MEDIA }?.let { queuedMediaMode = it.videoMode }
        }
        localOffer?.let {
            localRollbackPending = true
            resolveLocalOffer(it)
        }
        val generation = ++negotiationGeneration
        remoteOfferGeneration = generation
        return generation
    }

    /** Keep the native obligation independent of ticket ownership. A stale
     * queued rollback may skip; the current remote owner must take it over. */
    private suspend fun finishLocalRollback(callId: String, generation: Long): Boolean {
        webRtcEngine.rollbackLocalDescription { ownsRemoteOffer(callId, generation) }
        if (!ownsRemoteOffer(callId, generation)) return false
        localRollbackPending = false
        return true
    }

    private fun abandonLocalOffer(offer: LocalOffer, reportMediaFailure: Boolean) {
        if (!ownsOffer(offer)) return
        val generation = beginRemoteOffer()
        scope.launch {
            try {
                if (!finishLocalRollback(offer.callId, generation)) return@launch
                if (reportMediaFailure) mediaNegotiationFailed(offer.videoMode)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ownsRemoteOffer(offer.callId, generation)) {
                    trace("rollback-failed purpose=${offer.purpose} error=${e.javaClass.simpleName}")
                    if (reportMediaFailure) mediaNegotiationFailed(offer.videoMode)
                }
            } finally {
                if (ownsRemoteOffer(offer.callId, generation)) {
                    remoteOfferGeneration = null
                    drainQueuedMediaOffer(offer.callId)
                }
            }
        }
    }

    private suspend fun handleRemoteOffer(event: CallSignalingEvent.Offer) {
        val initial = _state.value is CallUiState.Connecting && mediaReady && !remoteDescriptionSet
        if (!initial && connectedSnapshot() == null) { pendingOffer = event; return }
        val crossed = localOffer != null
        // Caller is impolite; callee yields for every purpose, not just ICE restart.
        if (crossed && recovery.caller) { trace("glare-ignore role=caller"); return }
        trace("remote-offer crossed=$crossed role=${if (recovery.caller) "caller" else "callee"}")
        remoteSdpOrder.applied(event.sdp)
        val generation = beginRemoteOffer(preserveMediaIntent = crossed)
        try {
            // Expiry may already have removed localOffer while native local
            // application still holds the SDP mutex. Inherit its rollback even
            // though this is no longer glare with an active, impolite offer.
            if (localRollbackPending) {
                if (!finishLocalRollback(event.callId, generation)) return
            }
            if (initial) applyRemoteOffer(event.callId, event.sdp, generation)
            else applyRenegotiationOffer(event.callId, event.sdp, generation)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (initial) throw e
            if (ownsRemoteOffer(event.callId, generation)) {
                trace("remote-offer-failed error=${e.javaClass.simpleName}")
                // Rollback did not complete: keep the queued capture intent
                // until recovery can release the native signaling state.
                connectedSnapshot()?.let { publishConnectedUpdate(it.copy(notice = CallNotice.VIDEO_FAILED)) }
                clearNoticeLater()
            }
        } finally {
            if (ownsRemoteOffer(event.callId, generation)) {
                remoteOfferGeneration = null
                drainQueuedMediaOffer(event.callId)
            }
        }
    }

    private suspend fun applyRemoteOffer(callId: String, sdp: String, generation: Long) {
        _comparisonCode.value = null; sas.receiveOffer(sdp, callId)
        webRtcEngine.setRemoteDescription("offer", sdp) { ownsRemoteOffer(callId, generation) }
        if (!ownsRemoteOffer(callId, generation)) return
        remoteDescriptionSet = true
        flushPendingIce()
        val answer = webRtcEngine.createAnswer { ownsRemoteOffer(callId, generation) }
        if (!ownsRemoteOffer(callId, generation)) return
        signalingClient.sendAnswer(callId, sas.answer(CallSdpCapabilities.advertising(answer), callId))
        updateComparison(callId)
        recovery.peerNegotiated(elapsedRealtimeMs())
    }

    /** Only the effective SDP direction decides a full peer audio downgrade. */
    private suspend fun applyRenegotiationOffer(callId: String, sdp: String, generation: Long) {
        _comparisonCode.value = null; sas.receiveOffer(sdp, callId)
        val before = connectedSnapshot() ?: return
        val offeredMode = CallSdpVideoPolicy.hasActiveVideo(sdp)?.let { if (it) VideoMode.VIDEO else VideoMode.AUDIO }
            ?: if (before.videoUpgrade == VideoUpgradeState.Upgrading) VideoMode.VIDEO else before.videoMode
        // An audio ICE restart can arrive before the peer acknowledges our
        // camera request. Answer it without discarding that newer local intent.
        val keepUpgradeRequest = offeredMode == VideoMode.AUDIO && before.videoMode == VideoMode.AUDIO &&
            before.videoUpgrade == VideoUpgradeState.Requesting && localCameraRequested
        try {
            if (offeredMode == VideoMode.AUDIO && !keepUpgradeRequest) {
                if (queuedMediaMode != VideoMode.VIDEO) localCameraRequested = false
                webRtcEngine.stopLocalVideo()
                publishConnectedUpdate(before.copy(localVideoEnabled = false, localCameraState = LocalCameraState.OFF))
                updateAutomaticAudioRoute(localCameraEnabled = false)
            }
            webRtcEngine.setRemoteDescription("offer", sdp) { ownsRemoteOffer(callId, generation) }
            if (!ownsRemoteOffer(callId, generation)) return
            webRtcEngine.setVideoNegotiationEnabled(offeredMode == VideoMode.VIDEO)
            val answer = webRtcEngine.createAnswer { ownsRemoteOffer(callId, generation) }
            if (!ownsRemoteOffer(callId, generation)) return
            signalingClient.sendAnswer(callId, sas.answer(CallSdpCapabilities.advertising(answer), callId))
            updateComparison(callId)
            recovery.peerNegotiated(elapsedRealtimeMs())
            val current = connectedSnapshot() ?: return
            val becomingAudio = offeredMode == VideoMode.AUDIO
            val turnCameraOff = becomingAudio && !keepUpgradeRequest
            val peerVideo = !becomingAudio && (remoteVideoSignaled ?: (current.remoteVideoEnabled || before.videoUpgrade == VideoUpgradeState.Upgrading))
            val keepsPicture = peerVideo && current.remoteVideoEnabled && current.remoteVideoState == RemoteVideoState.ON
            publishConnectedUpdate(current.copy(
                videoMode = offeredMode,
                videoUpgrade = if (keepUpgradeRequest) current.videoUpgrade else VideoUpgradeState.Idle,
                localVideoEnabled = if (turnCameraOff) false else current.localVideoEnabled,
                localCameraState = if (turnCameraOff) LocalCameraState.OFF else current.localCameraState,
                remoteVideoEnabled = peerVideo,
                remoteVideoState = if (peerVideo) { if (keepsPicture) RemoteVideoState.ON else RemoteVideoState.WAITING } else RemoteVideoState.OFF,
            ))
            if (peerVideo && !keepsPicture) webRtcEngine.expectRemoteVideoFrame()
            if (turnCameraOff) updateAutomaticAudioRoute(localCameraEnabled = false)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!ownsRemoteOffer(callId, generation)) return
            trace("answer-produce-failed error=${e.javaClass.simpleName}")
            // Remote SDP may already be HAVE_REMOTE_OFFER. Release it through
            // the same guarded rollback before any subsequent local offer.
            localRollbackPending = true
            publishConnectedUpdate((connectedSnapshot() ?: return).copy(videoUpgrade = VideoUpgradeState.Idle, notice = CallNotice.VIDEO_FAILED))
            clearNoticeLater()
        }
    }

    private suspend fun applyLocalAnswer(offer: LocalOffer, sdp: String) {
        try {
            webRtcEngine.setRemoteDescription("answer", sdp) { ownsOffer(offer) }
            if (!ownsOffer(offer)) return
            sas.receiveAnswer(sdp, offer.callId)?.let { signalingClient.sendIce(offer.callId, it, null, null) }
            updateComparison(offer.callId)
            remoteDescriptionSet = true
            flushPendingIce()
            if (offer.purpose == OfferPurpose.MEDIA) {
                val current = connectedSnapshot() ?: return
                publishConnectedUpdate(current.copy(videoMode = queuedMediaMode ?: offer.videoMode, videoUpgrade = VideoUpgradeState.Idle))
            }
            resolveLocalOffer(offer)
            drainQueuedMediaOffer(offer.callId)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!ownsOffer(offer)) return
            trace("answer-apply-failed purpose=${offer.purpose} error=${e.javaClass.simpleName}")
            if (offer.purpose == OfferPurpose.INITIAL) failCurrentCall(offer.callId)
            else abandonLocalOffer(offer, reportMediaFailure = offer.purpose == OfferPurpose.MEDIA)
        }
    }

    private fun mediaNegotiationFailed(target: VideoMode) {
        val current = connectedSnapshot() ?: return
        val failedCameraUpgrade = current.videoMode == VideoMode.AUDIO && target == VideoMode.VIDEO
        if (failedCameraUpgrade) {
            localCameraRequested = false
            webRtcEngine.stopLocalVideo()
        }
        publishConnectedUpdate(current.copy(
            videoUpgrade = VideoUpgradeState.Idle,
            notice = CallNotice.VIDEO_FAILED,
            localVideoEnabled = if (failedCameraUpgrade) false else current.localVideoEnabled,
            localCameraState = if (failedCameraUpgrade) LocalCameraState.FAILED else current.localCameraState,
        ))
        if (failedCameraUpgrade) updateAutomaticAudioRoute(localCameraEnabled = false)
        clearNoticeLater()
    }

    private fun flushPendingIce() {
        val queued = pendingRemoteIce.toList()
        pendingRemoteIce.clear()
        for (candidate in queued) {
            val key = Triple(candidate.candidate, candidate.sdpMid, candidate.sdpMLineIndex)
            if (key in forwardedIce) continue
            forwardedIce += key
            webRtcEngine.addIceCandidate(candidate.candidate, candidate.sdpMid, candidate.sdpMLineIndex)
        }
    }

    private suspend fun beginMediaSetup(call: CallDto): Boolean {
        if (!isCurrentCall(call.id)) return false
        val iceServers = callApi.iceServers()
        if (!isCurrentCall(call.id)) return false
        val peer = if (call.callerUserId == currentUserId()) call.calleeUserId else call.callerUserId
        val contact = isContact(peer)
        if (webRtcEngine.relayUnavailable(iceServers, contact)) {
            val current = currentCallInfoOrNull() ?: return false
            val owner = sessionOwner()
            endLocally(current, CallEndReason.FAILED, CallFailureReason.RELAY_UNAVAILABLE)
            scope.launch { runCatching { callApi.markFailedIfCurrent(call.id) { owner == sessionOwner() } } }
            return false
        }
        val mediaKind = if (call.mediaKind == "video") CallMediaKind.VIDEO else CallMediaKind.AUDIO
        recovery.caller = currentCallInfoOrNull()?.direction == CallDirection.OUTGOING
        webRtcEngine.setDiagnosticsRecording(diagnosticsRecording())
        // Read after the ICE await: the user may have changed intent while it waited.
        val cameraIntent = when (val current = _state.value) {
            is CallUiState.OutgoingRinging -> current.localVideoEnabled
            is CallUiState.Connecting -> current.localVideoEnabled
            else -> localCameraRequested
        } && mediaKind == CallMediaKind.VIDEO
        webRtcEngine.setPeerContact(contact)
        webRtcEngine.initialize(iceServers, mediaKind, captureVideo = cameraIntent)
        if (!isCurrentCall(call.id)) return false
        localCameraRequested = cameraIntent && webRtcEngine.localVideoRunning()
        webRtcEngine.setMuted(currentMute())
        mediaReady = true
        if (mediaKind == CallMediaKind.VIDEO) {
            publishInitialCameraIntent(call.id, localCameraRequested)
            if (!localCameraRequested) signalingClient.sendVideoState(call.id, false)
        }
        startStatsSampling(call.id)
        // Native audio initialization can reset the route selected while ringing.
        // Reapply the per-call manual choice after setup, before connecting media.
        if (!localCameraRequested) updateAutomaticAudioRoute(localCameraEnabled = false)
        else updateAutomaticAudioRoute()
        return true
    }

    // -------------------------------------------------------------------
    // WebRTC engine event handling (local ICE, connection state)
    // -------------------------------------------------------------------

    private fun handleEngineEvent(event: WebRtcEvent) {
        val callId = activeCallId ?: return
        when (event) {
            is WebRtcEvent.Diagnostics -> onDiagnostics(event.metrics)
            is WebRtcEvent.VideoSendPausedChanged -> {
                val connected = connectedSnapshot() ?: return
                signalingClient.sendVideoState(callId, !event.paused && connected.localVideoEnabled)
            }
            is WebRtcEvent.DeadPairDetected -> {
                val restarted = connectedSnapshot()?.call?.direction == CallDirection.OUTGOING && recovery.mediaStalled(elapsedRealtimeMs())
                onDiagnostics(com.messenger.app.data.call.CallDiagnosticEvent.create(2, 118, mapOf(
                    "status" to if (restarted) { if (event.sendsRefused) 2 else 1 } else 0,
                    "count" to event.packets, "duration_ms" to event.watchedMs,
                    "items" to recovery.negotiations, "late_frames" to event.refused,
                )))
                if (restarted) scope.launch { driveRecovery(callId) }
            }
            WebRtcEvent.NetworkInterfaceChanged -> {
                recovery.interfaceChanged(elapsedRealtimeMs())
                scope.launch { driveRecovery(callId) }
            }
            is WebRtcEvent.LocalIceCandidate ->
                signalingClient.sendIce(callId, event.candidate, event.sdpMid, event.sdpMLineIndex)
            is WebRtcEvent.ConnectionStateChanged -> handleConnectionStateChanged(callId, event.state)
            is WebRtcEvent.RemoteVideoEnabled -> {
                remoteVideoEnabled = event.enabled && (remoteVideoSignaled != false)
                val connected = _state.value as? CallUiState.Connected ?: return
                _state.value = connected.copy(
                    remoteVideoEnabled = remoteVideoEnabled,
                    remoteVideoState = if (remoteVideoEnabled) RemoteVideoState.WAITING else RemoteVideoState.OFF,
                )
            }
            is WebRtcEvent.RemoteFirstFrame -> {
                val connected = _state.value as? CallUiState.Connected ?: return
                if (!connected.remoteVideoEnabled) return
                _state.value = connected.copy(
                    remoteVideoEnabled = true,
                    remoteVideoState = RemoteVideoState.ON,
                )
            }
            is WebRtcEvent.LocalFirstFrame -> {
                val connected = connectedSnapshot()
                val initialVideo = (_state.value as? CallUiState.Connecting)?.call?.mediaKind == CallMediaKind.VIDEO && localCameraRequested &&
                    !initialCameraFailed && webRtcEngine.localVideoRunning()
                if (connected?.localVideoEnabled != true && !initialVideo) return
                localCameraHasFrame = true
                if (connected != null) publishConnectedUpdate(connected.copy(localCameraState = LocalCameraState.ON))
                updateAutomaticAudioRoute(localCameraEnabled = true)
            }
            is WebRtcEvent.LocalCameraFailed -> {
                if (!webRtcEngine.isCurrentCameraGeneration(event.cameraGeneration)) return
                initialCameraFailed = true
                localCameraRequested = false
                webRtcEngine.setVideoEnabled(false)
                publishInitialCameraIntent(callId, false)
                val current = _state.value
                val c = (current as? CallUiState.Connected) ?: (current as? CallUiState.Reconnecting)?.since as? CallUiState.Connected
                if (c != null) {
                    val failed = c.copy(localVideoEnabled = false, localCameraState = LocalCameraState.FAILED, notice = CallNotice.CAMERA_FAILED)
                    _state.value = if (current is CallUiState.Reconnecting) current.copy(since = failed) else failed
                }
                updateAutomaticAudioRoute(localCameraEnabled = false)
                signalingClient.sendVideoState(callId, false)
            }
            is WebRtcEvent.LocalCameraFacingChanged -> {
                if (!webRtcEngine.isCurrentCameraGeneration(event.cameraGeneration)) return
                val current = _state.value
                val c = (current as? CallUiState.Connected) ?: (current as? CallUiState.Reconnecting)?.since as? CallUiState.Connected
                if (c != null) {
                    val updated = c.copy(localCameraIsFrontFacing = event.isFrontFacing)
                    _state.value = if (current is CallUiState.Reconnecting) current.copy(since = updated) else updated
                }
            }
            is WebRtcEvent.AudioRouteChanged -> {
                updateAutomaticAudioRoute()
            }
        }
    }

    private fun handleConnectionStateChanged(callId: String, pcState: PeerConnectionState) {
        trace("ice=$pcState")
        when (pcState) {
            PeerConnectionState.CONNECTED -> {
                recovery.mediaConnected(elapsedRealtimeMs())
                outageStartedAt = null
                reconnectJob?.cancel()
                val existing = _state.value
                val call = currentCallInfoOrNull() ?: return
                when {
                    existing is CallUiState.Connected -> Unit // duplicate event
                    existing is CallUiState.Reconnecting -> {
                        // N.1A §21: restore the pre-reconnect Connected state
                        // (preserves mute/camera/video-mode/duration start) -
                        // a reconnect must not silently drop the video mode.
                        val previous = existing.since as? CallUiState.Connected ?: return
                        // Runtime Correction A: the renderer was torn down
                        // during Reconnecting, so never restore it as "ON" -
                        // the avatar stays until a fresh frame arrives.
                        _state.value = previous.copy(
                            remoteVideoState = if (previous.remoteVideoEnabled) RemoteVideoState.WAITING else RemoteVideoState.OFF,
                        )
                    }
                    else -> {
                        scope.launch { runCatching { callApi.markConnected(callId) } }
                        _state.value = CallUiState.Connected(
                            call,
                            connectedAtElapsedRealtimeMs = elapsedRealtimeMs(),
                            localCameraIsFrontFacing = webRtcEngine.localCameraIsFrontFacing(),
                            isMuted = currentMute(),
                            isSpeakerOn = webRtcEngine.currentAudioRoute().kind == AudioRouteKind.SPEAKER,
                            localVideoEnabled = call.mediaKind == CallMediaKind.VIDEO && localCameraRequested && !initialCameraFailed && webRtcEngine.localVideoRunning(),
                            remoteVideoEnabled = remoteVideoEnabled,
                            videoMode = if (call.mediaKind == CallMediaKind.VIDEO) VideoMode.VIDEO else VideoMode.AUDIO,
                            localCameraState = if (initialCameraFailed) LocalCameraState.FAILED else if (call.mediaKind == CallMediaKind.VIDEO && localCameraRequested && webRtcEngine.localVideoRunning()) { if (localCameraHasFrame) LocalCameraState.ON else LocalCameraState.STARTING } else LocalCameraState.OFF,
                            remoteVideoState = if (remoteVideoEnabled) RemoteVideoState.WAITING else RemoteVideoState.OFF,
                            audioRoute = webRtcEngine.currentAudioRoute().kind,
                        )
                    }
                }
                if (existing !is CallUiState.Connected && (_state.value as? CallUiState.Connected)?.remoteVideoEnabled == true) webRtcEngine.expectRemoteVideoFrame()
            }
            PeerConnectionState.DISCONNECTED -> {
                val connected = _state.value as? CallUiState.Connected ?: return
                recovery.mediaDisconnected(elapsedRealtimeMs())
                outageStartedAt = elapsedRealtimeMs()
                _state.value = CallUiState.Reconnecting(connected.call, since = connected)
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    // One bounded outage budget; an unanswered offer is resent,
                    // and a resolved round observes the policy's backoff.
                    while (isCurrentCall(callId) && _state.value is CallUiState.Reconnecting) {
                        driveRecovery(callId)
                        kotlinx.coroutines.delay(minOf(1_000L, reconnectGracePeriodMs.coerceAtLeast(1)))
                    }
                }
            }
            PeerConnectionState.FAILED -> {
                val connected = connectedSnapshot()
                if (connected == null) { currentCallInfoOrNull()?.let(::failCall); return }
                recovery.mediaFailed(elapsedRealtimeMs())
                if (_state.value is CallUiState.Connected) handleConnectionStateChanged(callId, PeerConnectionState.DISCONNECTED)
                scope.launch { driveRecovery(callId) }
            }
            PeerConnectionState.CLOSED, PeerConnectionState.NEW, PeerConnectionState.CHECKING -> Unit
        }
    }

    private fun failCall(call: CallInfo) = failCurrentCall(call.callId)

    private fun rememberPeerCapabilities(sdp: String) {
        if (CallSdpCapabilities.advertises(sdp)) recovery.peerResolvesGlare = true
    }

    /** Uses the same bounded recovery for an interface change and a dead-pair nudge. */
    private suspend fun driveRecovery(callId: String) {
        if (!isCurrentCall(callId) || recoveryWorking) return
        val connected = connectedSnapshot() ?: return
        val now = elapsedRealtimeMs()
        if (!recovery.mediaUp && outageStartedAt?.let { now - it >= reconnectGracePeriodMs } == true) {
            trace("recovery-outage-expired")
            failCurrentCall(callId); return
        }
        recoveryWorking = true
        try {
            // A refused rollback is one failed negotiation round, not a hangup.
            // Retry on existing recovery wakes; no additional polling job exists.
            if (localRollbackPending && remoteOfferGeneration == null) {
                retryPendingRollback(callId)
                if (localRollbackPending) return
            }
            when (recovery.next(now)) {
                com.messenger.app.data.call.CallRecoveryPolicy.Action.RESTART -> {
                    if (!launchLocalOffer(callId, OfferPurpose.ICE_RESTART, connected.videoMode)) recovery.restartDeferred(now)
                }
                com.messenger.app.data.call.CallRecoveryPolicy.Action.RESEND_OFFER -> localOffer?.let { offer ->
                    val sdp = offer.sdp
                    if (sdp != null && ownsOffer(offer) && signalingClient.sendOffer(callId, sdp)) recovery.offerSent(now)
                }
                com.messenger.app.data.call.CallRecoveryPolicy.Action.ABANDON_OFFER -> {
                    localOffer?.let { abandonLocalOffer(it, reportMediaFailure = it.purpose == OfferPurpose.MEDIA) }
                }
                com.messenger.app.data.call.CallRecoveryPolicy.Action.FAIL -> {
                    trace("recovery-policy-expired")
                    failCurrentCall(callId)
                }
                else -> Unit
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            localOffer?.let { abandonLocalOffer(it, reportMediaFailure = it.purpose == OfferPurpose.MEDIA) }
        } finally { recoveryWorking = false }
    }

    /** Этап J.1-6: after a WS reconnect during a call, the server is the
     *  source of truth - a `call.ended` frame may have been lost while the
     *  socket was down (e.g. the remote hung up). Re-fetch the active call
     *  and, if the server no longer has one for us, end locally with the
     *  server's real reason instead of leaving a stale "Connected" screen. */
    private suspend fun reconcileAfterReconnect() {
        val call = currentCallInfoOrNull() ?: return
        if (_state.value is CallUiState.OutgoingRinging) { reconcileOutgoingRinging(call.callId); return }
        val active = try {
            callApi.getActiveCall()
        } catch (e: AuthApiException) {
            return // transient fetch failure - leave the call state as-is
        }
        if (active != null) return // still active server-side - nothing to do
        val ended = try {
            callApi.getCall(call.callId)
        } catch (e: AuthApiException) {
            null
        }
        val reason = if (ended != null) mapEndReason(ended.endReason ?: "failed") else CallEndReason.FAILED
        endLocally(call, reason)
    }

    /** One full read drives media policies and opt-in diagnostics in every build.
     * Debug logs contain numeric media counters, never SDP or addresses. */
    private fun startStatsSampling(callId: String) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isCurrentCall(callId)) {
                webRtcEngine.setDiagnosticsRecording(diagnosticsRecording())
                val snapshot = try { webRtcEngine.getStats() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                if (!isCurrentCall(callId)) return@launch
                _callStats.value = snapshot
                driveRecovery(callId)
                if (com.messenger.app.BuildConfig.DEBUG && snapshot != null) android.util.Log.d("CallStats", "$snapshot")
                kotlinx.coroutines.delay(1_000L)
            }
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private fun currentCallInfoOrNull(): CallInfo? = when (val s = _state.value) {
        is CallUiState.OutgoingRinging -> s.call
        is CallUiState.IncomingRinging -> s.call
        is CallUiState.Connecting -> s.call
        is CallUiState.Connected -> s.call
        is CallUiState.Reconnecting -> s.call
        else -> null
    }

    private fun connectedSnapshot(): CallUiState.Connected? = when (val current = _state.value) {
        is CallUiState.Connected -> current
        is CallUiState.Reconnecting -> current.since as? CallUiState.Connected
        else -> null
    }

    private fun publishInitialCameraIntent(callId: String, enabled: Boolean) {
        if (!isCurrentCall(callId)) return
        _state.value = when (val current = _state.value) {
            is CallUiState.OutgoingRinging -> current.copy(localVideoEnabled = enabled)
            is CallUiState.Connecting -> current.copy(localVideoEnabled = enabled)
            else -> current
        }
    }

    private fun publishConnectedUpdate(updated: CallUiState.Connected) {
        if (!isCurrentCall(updated.call.callId)) return
        _state.value = when (val current = _state.value) {
            is CallUiState.Connected -> updated
            is CallUiState.Reconnecting -> current.copy(since = updated)
            else -> current
        }
    }

    private fun isCurrentCall(callId: String): Boolean = activeCallId == callId && currentCallInfoOrNull()?.callId == callId

    private fun failCurrentCall(callId: String) {
        if (!isCurrentCall(callId)) return
        trace("local-failed")
        val call = currentCallInfoOrNull() ?: return
        endLocally(call, CallEndReason.FAILED)
        scope.launch { runCatching { callApi.markFailed(callId) } }
    }

    private fun endLocally(call: CallInfo, reason: CallEndReason, failure: CallFailureReason? = null) {
        if (!isCurrentCall(call.callId)) return
        trace("end reason=$reason failure=$failure")
        if (call.direction == CallDirection.OUTGOING) reduceRinging(CallRingingIndicatorPolicy.Event.Ended(when (reason) {
            CallEndReason.REJECTED -> "rejected"
            CallEndReason.CANCELLED -> "cancelled"
            CallEndReason.MISSED -> "missed"
            CallEndReason.HANGUP -> "hangup"
            else -> "failed"
        }))
        activeCallId = null
        resetSignalingBuffers()
        statsJob?.cancel()
        upgradeTimeoutJob?.cancel()
        noticeJob?.cancel()
        webRtcEngine.dispose()
        _state.value = if (failure != null) CallUiState.Failed(call, failure) else CallUiState.Ended(call, reason)
        // P0.1: the incoming-call slot (if any) must be cleared whenever the
        // call goes terminal — including the WS path, where the observer's
        // in-memory ownership is null after a process restart. Owner-scoped,
        // so it can never cancel a newer call's notification.
        onDismissIncomingPush(call.callId)
    }

    /** Does not select a new connection after suspension; the engine also checks this ticket. */
    private suspend fun retryPendingRollback(callId: String) {
        if (!isCurrentCall(callId) || !localRollbackPending || remoteOfferGeneration != null) return
        val generation = beginRemoteOffer()
        try {
            finishLocalRollback(callId, generation)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { trace("rollback-retry-failed error=${e.javaClass.simpleName}") }
        finally {
            if (ownsRemoteOffer(callId, generation)) {
                remoteOfferGeneration = null
                if (!localRollbackPending) drainQueuedMediaOffer(callId)
            }
        }
    }

    private fun trace(message: String) {
        if (com.messenger.app.BuildConfig.DEBUG) Log.d("CallNegotiation",
            "$message ui=${_state.value.javaClass.simpleName} offer=${localOffer?.purpose} remote=${remoteOfferGeneration != null}")
    }

    private fun resetToIdle() {
        // Not `webRtcEngine.dispose()` here - every caller of this function
        // either already disposed via [endLocally] (Ended/Failed -> user
        // acknowledged) or never initialized the engine in the first place
        // (AnsweredElsewhere / lost the accept race) - calling dispose again
        // would be a harmless but wasteful no-op.
        statsJob?.cancel()
        if (mediaReady) webRtcEngine.dispose()
        activeCallId = null
        resetSignalingBuffers()
        _state.value = CallUiState.Idle
        _ringIndicator.value = null
        ringbackStartedAtElapsedRealtimeMs = null
    }

    /** Incoming adoption must erase the caller's pending creation/presentation too.
     * Keep the create ticket valid so a late REST success can cancel its own call. */
    private fun adoptIncomingCall(call: CallInfo) {
        if (isLocallyBlocked(call.otherUserId)) return
        creatingCall = null
        _startingOutgoing.value = null
        _ringIndicator.value = null
        ringbackStartedAtElapsedRealtimeMs = null
        activeCallId = call.callId
        resetSignalingBuffers()
        _state.value = CallUiState.IncomingRinging(call)
    }

    private fun resetSignalingBuffers() {
        sas.reset(); _comparisonCode.value = null
        pendingOfferJob?.cancel(); pendingOfferJob = null
        remoteSdpOrder = CallRemoteSdpOrder()
        recovery = com.messenger.app.data.call.CallRecoveryPolicy()
        negotiationGeneration++
        localOffer = null
        remoteOfferGeneration = null
        localRollbackPending = false
        queuedMediaMode = null
        recoveryWorking = false
        outageStartedAt = null
        _callStats.value = null
        _audioRoutes.value = emptyList()
        outgoingRingingJob?.cancel(); outgoingRingingJob = null
        incomingConfirmationJob?.cancel(); incomingConfirmationJob = null
        confirmedIncomingCallId = null
        outgoingReadGeneration++
        outgoingReadJob?.cancel(); outgoingReadJob = null
        outgoingReads = null
        audioRoutePolicy.reset()
        localCameraHasFrame = false
        localCameraRequested = false
        mediaReady = false
        initialCameraFailed = false
        remoteVideoSignaled = null
        remoteDescriptionSet = false
        pendingOffer = null
        pendingRemoteIce.clear()
        forwardedIce.clear()
        reconnectJob?.cancel()
        reconnectJob = null
        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = null
        noticeJob?.cancel()
        noticeJob = null
        remoteVideoEnabled = false
    }

    private companion object {
        /** N.2R: call-liveness heartbeat interval. The server sweeps a
         *  connected call whose last_activity_at is >40s stale, so a 10s
         *  cadence leaves a comfortable multi-frame margin. */
        const val CALL_KEEP_ALIVE_INTERVAL_MS = 10_000L
        /** Same bound as the server's relay (calls/signaling.rs MAX_ICE_CANDIDATE_BYTES). */
        const val MAX_ICE_CANDIDATE_CHARS = 2048
        /** Real calls gather a few dozen candidates before the answer. */
        const val MAX_PENDING_REMOTE_ICE = 64
    }

    private fun mapEndReason(reason: String): CallEndReason = when (reason) {
        "rejected" -> CallEndReason.REJECTED
        "cancelled" -> CallEndReason.CANCELLED
        "busy" -> CallEndReason.BUSY
        "missed" -> CallEndReason.MISSED
        "hangup" -> CallEndReason.HANGUP
        "failed" -> CallEndReason.FAILED
        "timeout" -> CallEndReason.TIMEOUT
        else -> CallEndReason.LOCAL_ERROR
    }

    private fun CallMediaKind.wireValue() = if (this == CallMediaKind.VIDEO) "video" else "audio"

    /** Wrapped for testability - `android.os.SystemClock.elapsedRealtime()`
     *  isn't available in plain JVM unit tests. */
    internal var elapsedRealtimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
}

/** Only effective media direction decides availability; one-way video is still video. */
private object CallSdpVideoPolicy {
    fun hasActiveVideo(sdp: String): Boolean? {
        var sessionDirection = "a=sendrecv"
        var mediaKind: String? = null
        var mediaPort: Int? = null
        var mediaDirection: String? = null
        var hasMedia = false
        var uncertain = false
        var activeVideo = false
        fun finishMedia() {
            if (mediaKind == "video" && mediaPort != null && mediaPort!! > 0 &&
                (mediaDirection ?: sessionDirection) != "a=inactive") activeVideo = true
        }
        for (raw in sdp.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("m=")) {
                finishMedia()
                val fields = line.drop(2).split(Regex("\\s+"))
                hasMedia = true
                mediaKind = fields.firstOrNull()
                mediaPort = fields.getOrNull(1)?.substringBefore('/')?.toIntOrNull()?.takeIf { it in 0..65535 }
                if (mediaPort == null) uncertain = true
                mediaDirection = null
            } else if (line == "a=sendrecv" || line == "a=sendonly" || line == "a=recvonly" || line == "a=inactive") {
                if (hasMedia) mediaDirection = line else sessionDirection = line
            }
        }
        finishMedia()
        return if (!hasMedia || uncertain && !activeVideo) null else activeVideo
    }
}
