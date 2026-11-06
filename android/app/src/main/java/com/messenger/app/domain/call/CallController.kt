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
    private val reconnectGracePeriodMs: Long = 15_000L,
    private val videoUpgradeTimeoutMs: Long = VIDEO_UPGRADE_TIMEOUT_MS,
    private val sessionOwner: () -> String? = { "standalone" },
    private val externalCallBusy: () -> Boolean = { false },
    private val currentUserId: () -> String? = { null },
    private val nonCallCaptureBusy: () -> Boolean = { false },
    private val drainNonCallCapture: suspend () -> Boolean = { true },
    private val onCaptureStillBusy: () -> Unit = {},
    private val onStartBlocked: (CallStartBlockedReason) -> Unit = {},
) {
    private val _state = MutableStateFlow<CallUiState>(CallUiState.Idle)
    val state: StateFlow<CallUiState> = _state

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
    private var creatingCall: Pair<Long, String?>? = null
    private var acceptingCallId: String? = null
    private var mediaReady = false
    private var initialCameraFailed = false
    private var localCameraHasFrame = false
    private var remoteVideoSignaled: Boolean? = null
    private var remoteDescriptionSet = false
    private var pendingOffer: CallSignalingEvent.Offer? = null
    private val pendingRemoteIce = mutableListOf<CallSignalingEvent.Ice>()
    private val forwardedIce = mutableSetOf<Triple<String, String?, Int?>>()
    private var reconnectJob: Job? = null
    private var signalingJob: Job? = null
    private var engineJob: Job? = null
    private var statsJob: Job? = null
    private var socketStateJob: Job? = null
    private var upgradeTimeoutJob: Job? = null
    private var noticeJob: Job? = null
    private var keepAliveJob: Job? = null

    /** True while a renegotiation offer/answer round-trip is in flight
     *  (audio->video upgrade or video->audio downgrade) - the Offer/Answer
     *  handlers use this to distinguish renegotiation from the initial
     *  handshake. */
    private var renegotiating = false

    /** The target [VideoMode] of the in-flight renegotiation, set by the
     *  offer-sender before it sends its offer (the answer handler applies
     *  this when the answer arrives). */
    private var renegotiationTarget: VideoMode = VideoMode.AUDIO

    /** Tracks the signaling socket's previous connection state so we can
     *  distinguish an initial connect from a genuine reconnect (Этап
     *  J.1-6): only a DISCONNECTED/CONNECTING -> CONNECTED transition while
     *  a call is in progress triggers a state reconciliation. */
    private var lastSocketState: SocketConnectionState? = null

    /** Buffered so a remote track arriving during [CallUiState.Connecting]
     *  (which happens for video, where `onTrack` fires at
     *  setRemoteDescription time, before ICE reaches CONNECTED) is not lost. */
    private var remoteVideoEnabled = false

    /** Bounded ICE-restart counter (Этап J.1-6): a transient network drop
     *  triggers a standard `restartIce()`, never an infinite retry loop. */
    private var iceRestartAttempts = 0

    init {
        signalingJob = scope.launch {
            signalingClient.events.collect { event ->
                val owner = activeCallId
                try { handleSignalingEvent(event) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { owner?.let(::failCurrentCall) }
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
        creatingCall = ticket
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
                if (_state.value !is CallUiState.Idle) {
                    runCatching { callApi.cancel(call.id) }
                    return@launch
                }
                val info = CallInfo(call.id, otherUserId, otherDisplayName, mediaKind, CallDirection.OUTGOING)
                createdCallInfo = info
                activeCallId = call.id
                resetSignalingBuffers()
                // Ringback observes this state. Prepare communication mode
                // and the real output before its first audible publication.
                webRtcEngine.prepareCallAudio()
                if (!ownsIncoming(ticket) || activeCallId != call.id || _state.value !is CallUiState.Idle) return@launch
                _state.value = CallUiState.OutgoingRinging(info, audioRoute = webRtcEngine.currentAudioRoute().kind)
            } catch (e: CancellationException) {
                releaseUnpublishedAudio()
                throw e
            }
            catch (e: Exception) {
                if (!ownsIncoming(ticket)) return@launch
                releaseUnpublishedAudio()?.let { callId ->
                    scope.launch { if (ownsIncoming(ticket)) runCatching { callApi.markFailed(callId) } }
                }
                val owner = activeCallId
                if (owner != null && _state.value is CallUiState.OutgoingRinging) failCurrentCall(owner)
                else if (_state.value is CallUiState.Idle) {
                    val reason = when {
                        e is AuthApiException.Api && e.httpStatus == 409 && e.code == "conflict" && e.message == "busy" -> CallFailureReason.BUSY
                        e is AuthApiException.Network -> CallFailureReason.NETWORK
                        else -> CallFailureReason.GENERIC
                    }
                    _state.value = CallUiState.Failed(createdCallInfo, reason)
                }
            } finally {
                // The old request may finish while a new account's create is
                // still pending. Only its own ticket may release that guard.
                if (creatingCall == ticket) creatingCall = null
            }
        }
    }

    fun cancelOutgoing() {
        val call = (_state.value as? CallUiState.OutgoingRinging)?.call ?: return
        endLocally(call, CallEndReason.CANCELLED)
        scope.launch { runCatching { callApi.cancel(call.callId) }.onFailure { runCatching { callApi.hangup(call.callId) } } }
    }

    // -------------------------------------------------------------------
    // Incoming
    // -------------------------------------------------------------------

    fun acceptIncoming() {
        if (externalCallBusy()) return
        val call = (_state.value as? CallUiState.IncomingRinging)?.call ?: return
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
                val updated = callApi.accept(call.callId)
                if (!ownsIncoming(ticket) || !isCurrentCall(call.callId)) return@launch
                _state.value = CallUiState.Connecting(call)
                if (!beginMediaSetup(updated)) return@launch
                pendingOffer?.let { offer ->
                    pendingOffer = null
                    applyRemoteOffer(call.callId, offer.sdp)
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
        if (call.id != callId || call.calleeUserId != recipient || call.callerUserId == recipient || call.state != "ringing") {
            if (com.messenger.app.BuildConfig.DEBUG) Log.w(TAG, "push reconcile stale callId=$callId state=${call.state}")
            return IncomingReconcile.TERMINAL
        }
        if ((_state.value as? CallUiState.IncomingRinging)?.call?.callId == callId) return IncomingReconcile.RINGING
        val name = try {
            withTimeoutOrNull(300L) { displayNameResolver(call.callerUserId) } ?: "Incoming call"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { "Incoming call" }
        // Network/name lookups suspend: a terminal push or a newer call may win.
        if (!ownsIncoming(ticket)) return IncomingReconcile.UNKNOWN
        if (callId in terminalPushCalls) return IncomingReconcile.TERMINAL
        // The socket can win while the authoritative lookup/name suspends.
        // Its live incoming notification is not evidence of a terminal call.
        if ((_state.value as? CallUiState.IncomingRinging)?.call?.callId == callId) return IncomingReconcile.RINGING
        if (!canPresentIncoming()) return IncomingReconcile.UNKNOWN
        activeCallId = call.id
        resetSignalingBuffers()
        val kind = if (call.mediaKind == "video") CallMediaKind.VIDEO else CallMediaKind.AUDIO
        val info = CallInfo(call.id, call.callerUserId, name, kind, CallDirection.INCOMING)
        _state.value = CallUiState.IncomingRinging(info)
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
        val call = currentCallInfoOrNull() ?: return
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
        val connected = connectedSnapshot() ?: return
        setCameraEnabled(!connected.localVideoEnabled)
    }

    /** Camera on/off within a video call (N.1A §12/§13): the call stays
     *  video-capable, only the local capture is disabled. Notifies the peer
     *  so they can show "Camera is off" instead of a black rectangle.
     *  Turning ON goes OFF -> STARTING -> (first frame) -> ON; a capture
     *  failure lands in FAILED with a localized notice and audio continues. */
    fun setCameraEnabled(enabled: Boolean) {
        val connected = connectedSnapshot() ?: return
        if (connected.videoMode != VideoMode.VIDEO) return
        if (enabled) {
            if (!webRtcEngine.localVideoRunning()) {
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
            }
            try {
                webRtcEngine.setVideoEnabled(true)
            } catch (_: Exception) {
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
        val connected = _state.value as? CallUiState.Connected ?: return
        if (connected.videoMode != VideoMode.AUDIO || connected.videoUpgrade != VideoUpgradeState.Idle) return
        val ok = webRtcEngine.startLocalVideo()
        if (!ok) {
            _state.value = connected.copy(
                localVideoEnabled = false,
                localCameraState = LocalCameraState.FAILED,
                notice = CallNotice.CAMERA_FAILED,
            )
            updateAutomaticAudioRoute(localCameraEnabled = false)
            clearNoticeLater()
            return
        }
        webRtcEngine.setVideoNegotiationEnabled(true)
        signalingClient.sendVideoUpgradeRequest(connected.call.callId)
        _state.value = connected.copy(
            localCameraIsFrontFacing = webRtcEngine.localCameraIsFrontFacing(),
            localVideoEnabled = true,
            localCameraState = LocalCameraState.STARTING,
            videoUpgrade = VideoUpgradeState.Requesting,
        )
        armUpgradeTimeout()
    }

    fun cancelVideoUpgrade() {
        val connected = _state.value as? CallUiState.Connected ?: return
        if (connected.videoUpgrade != VideoUpgradeState.Requesting) return
        signalingClient.sendVideoUpgradeCancel(connected.call.callId)
        upgradeTimeoutJob?.cancel()
        webRtcEngine.stopLocalVideo()
        _state.value = connected.copy(localVideoEnabled = false, videoUpgrade = VideoUpgradeState.Idle)
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
        val connected = _state.value as? CallUiState.Connected ?: return
        if (connected.videoUpgrade != VideoUpgradeState.IncomingRequest) return
        webRtcEngine.setVideoNegotiationEnabled(true)
        webRtcEngine.startLocalVideo(capture = false)
        webRtcEngine.setVideoEnabled(false)
        signalingClient.sendVideoUpgradeAccept(connected.call.callId)
        upgradeTimeoutJob?.cancel()
        _state.value = connected.copy(
            localCameraIsFrontFacing = webRtcEngine.localCameraIsFrontFacing(),
            videoUpgrade = VideoUpgradeState.Upgrading,
            localCameraState = LocalCameraState.OFF,
        )
    }

    fun rejectVideoUpgrade() {
        val connected = _state.value as? CallUiState.Connected ?: return
        if (connected.videoUpgrade != VideoUpgradeState.IncomingRequest) return
        signalingClient.sendVideoUpgradeReject(connected.call.callId)
        upgradeTimeoutJob?.cancel()
        _state.value = connected.copy(videoUpgrade = VideoUpgradeState.Idle)
    }

    /** N.1A §12 video -> audio: stop capture, remove the video m-line via a
     *  renegotiation. Distinct from [setCameraEnabled](false) which keeps the
     *  call video-capable. call_id/duration/audio connection are preserved. */
    fun switchToAudio() {
        val connected = _state.value as? CallUiState.Connected ?: return
        if (connected.videoMode != VideoMode.VIDEO) return
        webRtcEngine.stopLocalVideo()
        webRtcEngine.setVideoNegotiationEnabled(false)
        renegotiating = true
        renegotiationTarget = VideoMode.AUDIO
        _state.value = connected.copy(
            videoMode = VideoMode.AUDIO,
            localVideoEnabled = false,
            remoteVideoEnabled = false,
            localCameraState = LocalCameraState.OFF,
            remoteVideoState = RemoteVideoState.OFF,
            videoUpgrade = VideoUpgradeState.Upgrading,
        )
        updateAutomaticAudioRoute(localCameraEnabled = false)
        scope.launch {
            try {
                val sdp = webRtcEngine.createOffer()
                if (isCurrentCall(connected.call.callId)) signalingClient.sendOffer(connected.call.callId, sdp)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failCurrentCall(connected.call.callId) }
        }
    }

    private fun armUpgradeTimeout() {
        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(videoUpgradeTimeoutMs)
            val connected = _state.value as? CallUiState.Connected ?: return@launch
            when (connected.videoUpgrade) {
                VideoUpgradeState.Requesting -> {
                    // Our request expired: terminal, audio stays, no hangup.
                    webRtcEngine.stopLocalVideo()
                    _state.value = connected.copy(
                        localVideoEnabled = false,
                        videoUpgrade = VideoUpgradeState.Idle,
                        notice = CallNotice.VIDEO_REQUEST_TIMEOUT,
                    )
                    updateAutomaticAudioRoute(localCameraEnabled = false)
                    clearNoticeLater()
                }
                VideoUpgradeState.IncomingRequest -> {
                    // Their request expired on our side.
                    _state.value = connected.copy(videoUpgrade = VideoUpgradeState.Idle)
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
        acceptingCallId = null
        terminalPushCalls.clear()
        activeCallId = null
        resetSignalingBuffers()
        statsJob?.cancel()
        statsJob = null
        webRtcEngine.dispose()
        _state.value = CallUiState.Idle
    }

    // -------------------------------------------------------------------
    // Signaling event handling
    // -------------------------------------------------------------------

    private suspend fun handleSignalingEvent(event: CallSignalingEvent) {
        when (event) {
            is CallSignalingEvent.Ringing -> {
                val recipient = currentUserId()?.takeIf { it.isNotBlank() } ?: return
                if (event.callerUserId == recipient) return
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
                activeCallId = event.callId
                resetSignalingBuffers()
                val mediaKind = if (event.mediaKind == "video") CallMediaKind.VIDEO else CallMediaKind.AUDIO
                val info = CallInfo(event.callId, event.callerUserId, "Incoming call", mediaKind, CallDirection.INCOMING)
                _state.value = CallUiState.IncomingRinging(info)
                // Name lookup must not delay ringing or block subsequent terminal frames.
                scope.launch {
                    val name = withTimeoutOrNull(1500L) { runCatching { displayNameResolver(event.callerUserId) }.getOrNull() } ?: return@launch
                    val current = _state.value as? CallUiState.IncomingRinging ?: return@launch
                    if (current.call.callId == event.callId) _state.value = current.copy(call = current.call.copy(otherDisplayName = name))
                }
            }
            is CallSignalingEvent.Offer -> {
                if (event.callId != activeCallId) return
                when {
                    _state.value is CallUiState.Connecting && mediaReady && !remoteDescriptionSet ->
                        applyRemoteOffer(event.callId, event.sdp)
                    // A connected call receiving an offer is a renegotiation
                    // (audio->video upgrade or video->audio downgrade).
                    _state.value is CallUiState.Connected || _state.value is CallUiState.Reconnecting ->
                        applyRenegotiationOffer(event.callId, event.sdp)
                    else -> pendingOffer = event
                }
            }
            is CallSignalingEvent.Answer -> {
                if (event.callId != activeCallId) return
                when {
                    _state.value is CallUiState.Connecting && !remoteDescriptionSet -> {
                        webRtcEngine.setRemoteDescription("answer", event.sdp)
                        if (!isCurrentCall(event.callId)) return
                        remoteDescriptionSet = true
                        flushPendingIce()
                    }
                    renegotiating && (_state.value is CallUiState.Connected || _state.value is CallUiState.Reconnecting) ->
                        applyRenegotiationAnswer(event.callId, event.sdp)
                    else -> Unit // late/duplicate answer after we already have one
                }
            }
            is CallSignalingEvent.Ice -> {
                if (event.callId != activeCallId) return
                val key = Triple(event.candidate, event.sdpMid, event.sdpMLineIndex)
                if (key in forwardedIce) return // duplicate - ignore
                if (remoteDescriptionSet) {
                    forwardedIce += key
                    webRtcEngine.addIceCandidate(event.candidate, event.sdpMid, event.sdpMLineIndex)
                } else {
                    pendingRemoteIce += event
                }
            }
            is CallSignalingEvent.Accepted -> {
                if (event.callId != activeCallId) return
                val ringing = _state.value as? CallUiState.OutgoingRinging ?: return
                val call = ringing.call
                _state.value = CallUiState.Connecting(call, ringing.isMuted, ringing.audioRoute)
                val updated = try {
                    callApi.getCall(call.callId)
                } catch (e: AuthApiException) {
                    endLocally(call, CallEndReason.FAILED)
                    return
                }
                if (!beginMediaSetup(updated)) return
                val sdp = webRtcEngine.createOffer()
                if (isCurrentCall(call.callId)) signalingClient.sendOffer(call.callId, sdp)
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
                val connected = _state.value as? CallUiState.Connected ?: return
                // Ignore duplicates or a request while we already have our
                // own in flight (simultaneous-request race resolves by
                // timeout on both sides - deterministic, no crash).
                if (connected.videoUpgrade != VideoUpgradeState.Idle) return
                _state.value = connected.copy(videoUpgrade = VideoUpgradeState.IncomingRequest)
                // Enabling video is an in-call toggle, so do not block the
                // peer behind a second confirmation dialog. The local camera
                // remains disabled on this side; only the incoming stream is
                // negotiated and shown immediately.
                acceptVideoUpgrade()
            }
            is CallSignalingEvent.VideoUpgradeAccept -> {
                if (event.callId != activeCallId) return
                val connected = _state.value as? CallUiState.Connected ?: return
                if (connected.videoUpgrade != VideoUpgradeState.Requesting) return
                upgradeTimeoutJob?.cancel()
                renegotiating = true
                renegotiationTarget = VideoMode.VIDEO
                _state.value = connected.copy(videoUpgrade = VideoUpgradeState.Upgrading)
                val sdp = webRtcEngine.createOffer()
                if (isCurrentCall(event.callId)) signalingClient.sendOffer(event.callId, sdp)
            }
            is CallSignalingEvent.VideoUpgradeReject -> {
                if (event.callId != activeCallId) return
                val connected = _state.value as? CallUiState.Connected ?: return
                if (connected.videoUpgrade != VideoUpgradeState.Requesting) return
                upgradeTimeoutJob?.cancel()
                webRtcEngine.stopLocalVideo()
                _state.value = connected.copy(
                    localVideoEnabled = false,
                    videoUpgrade = VideoUpgradeState.Idle,
                    notice = CallNotice.VIDEO_REQUEST_DECLINED,
                )
                updateAutomaticAudioRoute(localCameraEnabled = false)
                clearNoticeLater()
            }
            is CallSignalingEvent.VideoUpgradeCancel -> {
                if (event.callId != activeCallId) return
                val connected = _state.value as? CallUiState.Connected ?: return
                if (connected.videoUpgrade != VideoUpgradeState.IncomingRequest) return
                upgradeTimeoutJob?.cancel()
                _state.value = connected.copy(videoUpgrade = VideoUpgradeState.Idle)
            }
            is CallSignalingEvent.VideoState -> {
                if (event.callId != activeCallId) return
                remoteVideoSignaled = event.enabled
                remoteVideoEnabled = event.enabled
                val connected = _state.value as? CallUiState.Connected ?: return
                if (event.enabled) webRtcEngine.expectRemoteVideoFrame()
                _state.value = connected.copy(
                    remoteVideoEnabled = remoteVideoEnabled,
                    remoteVideoState = if (remoteVideoEnabled) RemoteVideoState.WAITING else RemoteVideoState.OFF,
                )
            }
            is CallSignalingEvent.Ended -> {
                if (event.callId != activeCallId) return
                val call = currentCallInfoOrNull() ?: return
                endLocally(call, mapEndReason(event.reason))
            }
        }
    }

    private suspend fun applyRemoteOffer(callId: String, sdp: String) {
        webRtcEngine.setRemoteDescription("offer", sdp)
        if (!isCurrentCall(callId)) return
        remoteDescriptionSet = true
        flushPendingIce()
        val answerSdp = webRtcEngine.createAnswer()
        if (isCurrentCall(callId)) signalingClient.sendAnswer(callId, answerSdp)
    }

    /** Renegotiation offer received while connected (upgrade recipient or
     *  downgrade recipient): apply it, answer, and flip the mode. The target
     *  mode is inferred from our own state - an offer arriving while we are
     *  still AUDIO (having accepted an upgrade) means video; one arriving
     *  while VIDEO means the peer downgraded to audio. */
    private suspend fun applyRenegotiationOffer(callId: String, sdp: String) {
        val before = connectedSnapshot() ?: return
        val offeredMode = CallSdpVideoPolicy.hasActiveVideo(sdp)?.let { if (it) VideoMode.VIDEO else VideoMode.AUDIO }
            ?: if (before.videoUpgrade == VideoUpgradeState.Upgrading) VideoMode.VIDEO else before.videoMode
        try {
            webRtcEngine.setRemoteDescription("offer", sdp)
            if (!isCurrentCall(callId)) return
            webRtcEngine.setVideoNegotiationEnabled(offeredMode == VideoMode.VIDEO)
            val answerSdp = webRtcEngine.createAnswer()
            if (!isCurrentCall(callId)) return
            signalingClient.sendAnswer(callId, answerSdp)
            val current = connectedSnapshot() ?: return
            val mode = offeredMode
            val becomingAudio = mode == VideoMode.AUDIO
            val peerVideo = !becomingAudio && (remoteVideoSignaled ?: (current.remoteVideoEnabled || before.videoUpgrade == VideoUpgradeState.Upgrading))
            // A full audio-only negotiation ends both video directions, unlike
            // the peer merely switching their camera off. Release our capture
            // too; otherwise an AUDIO call can keep using the local camera.
            if (becomingAudio) webRtcEngine.stopLocalVideo()
            publishConnectedUpdate(current.copy(
                videoMode = if (becomingAudio) VideoMode.AUDIO else VideoMode.VIDEO,
                videoUpgrade = VideoUpgradeState.Idle,
                localVideoEnabled = if (becomingAudio) false else current.localVideoEnabled,
                localCameraState = if (becomingAudio) LocalCameraState.OFF else current.localCameraState,
                // Upgrade: the peer just started sending video (their camera
                // is on by construction in requestVideoUpgrade). Downgrade:
                // the peer stopped sending. Never carry over the stale value.
                remoteVideoEnabled = peerVideo,
                remoteVideoState = if (peerVideo) RemoteVideoState.WAITING else RemoteVideoState.OFF,
            ))
            if (becomingAudio) updateAutomaticAudioRoute(localCameraEnabled = false)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!isCurrentCall(callId)) return
            val current = connectedSnapshot() ?: return
            publishConnectedUpdate(current.copy(
                videoUpgrade = VideoUpgradeState.Failed,
                notice = CallNotice.VIDEO_FAILED,
            ))
            clearNoticeLater()
        }
    }

    /** Renegotiation answer received while connected (upgrade/downgrade
     *  offer sender): apply it and flip to the mode we offered. */
    private suspend fun applyRenegotiationAnswer(callId: String, sdp: String) {
        val before = connectedSnapshot() ?: return
        try {
            webRtcEngine.setRemoteDescription("answer", sdp)
            if (!isCurrentCall(callId)) return
            val current = connectedSnapshot() ?: return
            renegotiating = false
            publishConnectedUpdate(current.copy(
                videoMode = renegotiationTarget,
                videoUpgrade = VideoUpgradeState.Idle,
            ))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!isCurrentCall(callId)) return
            val current = connectedSnapshot() ?: return
            renegotiating = false
            val failedCameraUpgrade = current.videoMode == VideoMode.AUDIO && renegotiationTarget == VideoMode.VIDEO
            if (failedCameraUpgrade) webRtcEngine.stopLocalVideo()
            publishConnectedUpdate(current.copy(
                videoUpgrade = VideoUpgradeState.Failed,
                notice = CallNotice.VIDEO_FAILED,
                localVideoEnabled = if (failedCameraUpgrade) false else current.localVideoEnabled,
                localCameraState = if (failedCameraUpgrade) LocalCameraState.FAILED else current.localCameraState,
            ))
            if (failedCameraUpgrade) updateAutomaticAudioRoute(localCameraEnabled = false)
            clearNoticeLater()
        }
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
        val mediaKind = if (call.mediaKind == "video") CallMediaKind.VIDEO else CallMediaKind.AUDIO
        webRtcEngine.initialize(iceServers, mediaKind)
        webRtcEngine.setMuted(currentMute())
        mediaReady = true
        if (mediaKind != CallMediaKind.VIDEO || !webRtcEngine.localVideoRunning()) updateAutomaticAudioRoute(localCameraEnabled = false)
        return true
    }

    // -------------------------------------------------------------------
    // WebRTC engine event handling (local ICE, connection state)
    // -------------------------------------------------------------------

    private fun handleEngineEvent(event: WebRtcEvent) {
        val callId = activeCallId ?: return
        when (event) {
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
                val initialVideo = (_state.value as? CallUiState.Connecting)?.call?.mediaKind == CallMediaKind.VIDEO &&
                    !initialCameraFailed && webRtcEngine.localVideoRunning()
                if (connected?.localVideoEnabled != true && !initialVideo) return
                localCameraHasFrame = true
                if (connected != null) publishConnectedUpdate(connected.copy(localCameraState = LocalCameraState.ON))
                updateAutomaticAudioRoute(localCameraEnabled = true)
            }
            is WebRtcEvent.LocalCameraFailed -> {
                if (!webRtcEngine.isCurrentCameraGeneration(event.cameraGeneration)) return
                initialCameraFailed = true
                webRtcEngine.setVideoEnabled(false)
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
        when (pcState) {
            PeerConnectionState.CONNECTED -> {
                reconnectJob?.cancel()
                iceRestartAttempts = 0
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
                            localVideoEnabled = call.mediaKind == CallMediaKind.VIDEO && !initialCameraFailed && webRtcEngine.localVideoRunning(),
                            remoteVideoEnabled = remoteVideoEnabled,
                            videoMode = if (call.mediaKind == CallMediaKind.VIDEO) VideoMode.VIDEO else VideoMode.AUDIO,
                            localCameraState = if (initialCameraFailed) LocalCameraState.FAILED else if (call.mediaKind == CallMediaKind.VIDEO && webRtcEngine.localVideoRunning()) { if (localCameraHasFrame) LocalCameraState.ON else LocalCameraState.STARTING } else LocalCameraState.OFF,
                            remoteVideoState = if (remoteVideoEnabled) RemoteVideoState.WAITING else RemoteVideoState.OFF,
                            audioRoute = webRtcEngine.currentAudioRoute().kind,
                        )
                        startStatsLogging(callId)
                    }
                }
                if (existing !is CallUiState.Connected && (_state.value as? CallUiState.Connected)?.remoteVideoEnabled == true) webRtcEngine.expectRemoteVideoFrame()
            }
            PeerConnectionState.DISCONNECTED -> {
                val connected = _state.value as? CallUiState.Connected ?: return
                _state.value = CallUiState.Reconnecting(connected.call, since = connected)
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    // Bounded ICE restart (Этап J.1-6): up to MAX_ICE_RESTARTS
                    // `restartIce()` attempts spread across the grace period,
                    // then give up - never an infinite retry loop.
                    iceRestartAttempts = 0
                    val retryDelay = reconnectGracePeriodMs / MAX_ICE_RESTARTS
                    while (iceRestartAttempts < MAX_ICE_RESTARTS && _state.value is CallUiState.Reconnecting) {
                        iceRestartAttempts++
                        webRtcEngine.restartIce()
                        // Only the original caller offers, avoiding simultaneous SDP glare.
                        if (connected.call.direction == CallDirection.OUTGOING) {
                            renegotiating = true
                            renegotiationTarget = connected.videoMode
                            try {
                                webRtcEngine.setVideoNegotiationEnabled(connected.videoMode == VideoMode.VIDEO)
                                val offer = webRtcEngine.createOffer()
                                if (!isCurrentCall(callId)) return@launch
                                signalingClient.sendOffer(callId, offer)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* Retry within the bounded reconnect window. */ }
                        }
                        kotlinx.coroutines.delay(retryDelay)
                    }
                    if (_state.value is CallUiState.Reconnecting) {
                        failCall(connected.call)
                    }
                }
            }
            PeerConnectionState.FAILED -> {
                val call = currentCallInfoOrNull() ?: return
                failCall(call)
            }
            PeerConnectionState.CLOSED, PeerConnectionState.NEW, PeerConnectionState.CHECKING -> Unit
        }
    }

    private fun failCall(call: CallInfo) = failCurrentCall(call.callId)

    /** Этап J.1-6: after a WS reconnect during a call, the server is the
     *  source of truth - a `call.ended` frame may have been lost while the
     *  socket was down (e.g. the remote hung up). Re-fetch the active call
     *  and, if the server no longer has one for us, end locally with the
     *  server's real reason instead of leaving a stale "Connected" screen. */
    private suspend fun reconcileAfterReconnect() {
        val call = currentCallInfoOrNull() ?: return
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

    /** Этап J.1 п.3: debug-only WebRTC stats - proves real bidirectional media
     *  (packets/bytes both directions) and the selected candidate pair type
     *  (host/srflx/relay), never full SDP/IP addresses. Gated by
     *  BuildConfig.DEBUG so it never runs in a release build. */
    private fun startStatsLogging(callId: String) {
        statsJob?.cancel()
        if (!com.messenger.app.BuildConfig.DEBUG) return
        statsJob = scope.launch {
            while (true) {
                val snapshot = webRtcEngine.getStats()
                android.util.Log.d("CallStats", "call=$callId $snapshot")
                kotlinx.coroutines.delay(3_000L)
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
        val call = currentCallInfoOrNull() ?: return
        endLocally(call, CallEndReason.FAILED)
        scope.launch { runCatching { callApi.markFailed(callId) } }
    }

    private fun endLocally(call: CallInfo, reason: CallEndReason, failure: CallFailureReason? = null) {
        if (!isCurrentCall(call.callId)) return
        activeCallId = null
        resetSignalingBuffers()
        statsJob?.cancel()
        upgradeTimeoutJob?.cancel()
        noticeJob?.cancel()
        renegotiating = false
        webRtcEngine.dispose()
        _state.value = if (failure != null) CallUiState.Failed(call, failure) else CallUiState.Ended(call, reason)
        // P0.1: the incoming-call slot (if any) must be cleared whenever the
        // call goes terminal — including the WS path, where the observer's
        // in-memory ownership is null after a process restart. Owner-scoped,
        // so it can never cancel a newer call's notification.
        onDismissIncomingPush(call.callId)
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
    }

    private fun resetSignalingBuffers() {
        audioRoutePolicy.reset()
        localCameraHasFrame = false
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
        renegotiating = false
        renegotiationTarget = VideoMode.AUDIO
        remoteVideoEnabled = false
        iceRestartAttempts = 0
    }

    private companion object {
        const val MAX_ICE_RESTARTS = 2

        /** N.2R: call-liveness heartbeat interval. The server sweeps a
         *  connected call whose last_activity_at is >40s stale, so a 10s
         *  cadence leaves a comfortable multi-frame margin. */
        const val CALL_KEEP_ALIVE_INTERVAL_MS = 10_000L
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
