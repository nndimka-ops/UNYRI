package com.messenger.app.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.messenger.app.domain.call.CallController
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.model.CallMediaKind
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import livekit.org.webrtc.SurfaceViewRenderer

/** Thin pass-through over the app-scoped [CallController] singleton (Этап J
 *  п.28) - kept as a ViewModel only so Compose screens can consume it via
 *  the same `viewModels { Factory }` convention as every other screen in
 *  this app; the actual state/logic all lives in [CallController], which
 *  survives across this ViewModel's lifecycle (it's owned by
 *  [com.messenger.app.MessengerApp], not by any one Activity/screen). */
class CallViewModel(private val controller: CallController) : ViewModel() {
    val state: StateFlow<CallUiState> = controller.state
    internal val presentation = CallPresentationMemory()
    init {
        viewModelScope.launch {
            combine(controller.state, controller.startingOutgoing) { state, starting ->
                if (state is CallUiState.Idle && starting != null) CallUiState.OutgoingRinging(starting) else state
            }.collect(presentation::synchronize)
        }
    }
    val ringIndicator = controller.ringIndicator
    val startingOutgoing = controller.startingOutgoing
    val ringbackStartedAtElapsedRealtimeMs: Long? get() = controller.ringbackStartedAtElapsedRealtimeMs
    val callStats = controller.callStats
    val comparisonCode = controller.comparisonCode
    val audioRoutes = controller.audioRoutes
    val voiceLevels = controller.remoteVoiceLevels()
    fun setRendererVisibility(local: Boolean, remote: Boolean) = controller.setRendererVisibility(local, remote)
    private val qualityPolicy = CallQualityPresentation()
    private var qualityCallId: String? = null
    private var reconnectAt: Long? = null
    private var dropReset = false
    private var qualityLastStats: com.messenger.app.data.call.WebRtcCallStats? = null
    /** Existing stats cadence only. StateFlow publishes only changed presentation values. */
    val quality = combine(state, callStats) { state, stats ->
        val connected = CallPresentationPolicy.connected(state)
        val id = connected?.call?.callId
        if (id != qualityCallId) { qualityPolicy.reset(); qualityCallId = id; reconnectAt = null; dropReset = false; qualityLastStats = null }
        val now = android.os.SystemClock.elapsedRealtime()
        if (state is CallUiState.Reconnecting) {
            if (reconnectAt == null) { reconnectAt = now; qualityPolicy.reset(keepGrade = true) }
            if (now - reconnectAt!! >= 1_200 && !dropReset) { qualityPolicy.reset(); dropReset = true }
        } else { reconnectAt = null; dropReset = false }
        val result = if (stats != null && stats !== qualityLastStats && connected != null && state !is CallUiState.Reconnecting) {
            qualityLastStats = stats
            qualityPolicy.update(now, stats,
                connected.remoteVideoState == com.messenger.app.domain.call.RemoteVideoState.ON,
                connected.localVideoEnabled, connected.isMuted)
        }
        else qualityPolicy.result(now)
        val pair = stats?.selectedCandidatePair
        CallQualityUiState(result, pair?.takeIf { it.localCandidateType != null && it.remoteCandidateType != null }
            ?.let { it.localCandidateType == "relay" || it.remoteCandidateType == "relay" }, pair?.relayProtocol ?: pair?.protocol)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), CallQualityUiState())

    fun startCall(otherUserId: String, otherDisplayName: String, mediaKind: CallMediaKind) =
        controller.startCall(otherUserId, otherDisplayName, mediaKind)

    fun acceptIncoming() = controller.acceptIncoming()
    fun rejectIncoming() = controller.rejectIncoming()
    fun cancelOutgoing() = controller.cancelOutgoing()
    fun cancelStartingOutgoing() = controller.cancelStartingOutgoing()
    fun hangup() = controller.hangup()
    fun toggleMute() = controller.toggleMute()
    fun toggleSpeaker() = controller.toggleSpeaker()
    fun toggleVideo() = controller.toggleVideo()
    val torchState = controller.torchState
    val localOrientation = controller.localOrientation
    val remoteGeometry = controller.remoteGeometry
    fun toggleTorch() = controller.toggleTorch()
    fun switchCamera() = controller.switchCamera()
    fun hasMultipleCameras() = controller.hasMultipleCameras()
    fun attachLocalRenderer(renderer: com.messenger.app.data.call.LocalTextureRenderer) = controller.attachLocalRenderer(renderer)
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) = controller.attachRemoteRenderer(renderer)
    fun detachLocalRenderer(renderer: com.messenger.app.data.call.LocalTextureRenderer) = controller.detachLocalRenderer(renderer)
    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) = controller.detachRemoteRenderer(renderer)
    fun reinitRenderers() = controller.reinitRenderers()
    fun acknowledgeEnded() = controller.acknowledgeEnded()
    fun availableAudioRoutes() = controller.availableAudioRoutes()
    fun setAudioRoute(route: com.messenger.app.data.call.AudioRoute) = controller.setAudioRoute(route)
    fun currentAudioRoute() = controller.currentAudioRoute()
    fun notifyCameraDenied() = controller.notifyCameraDenied()

    // N.1A audio -> video upgrade
    fun requestVideoUpgrade() = controller.requestVideoUpgrade()
    fun cancelVideoUpgrade() = controller.cancelVideoUpgrade()
    fun acceptVideoUpgrade() = controller.acceptVideoUpgrade()
    fun rejectVideoUpgrade() = controller.rejectVideoUpgrade()
    fun switchToAudio() = controller.switchToAudio()
    fun setCameraEnabled(enabled: Boolean) = controller.setCameraEnabled(enabled)
}

class CallViewModelFactory(private val controller: CallController) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return CallViewModel(controller) as T
    }
}
