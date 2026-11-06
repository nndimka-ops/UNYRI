package com.messenger.app.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.messenger.app.domain.call.CallController
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.model.CallMediaKind
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.SurfaceViewRenderer

/** Thin pass-through over the app-scoped [CallController] singleton (Этап J
 *  п.28) - kept as a ViewModel only so Compose screens can consume it via
 *  the same `viewModels { Factory }` convention as every other screen in
 *  this app; the actual state/logic all lives in [CallController], which
 *  survives across this ViewModel's lifecycle (it's owned by
 *  [com.messenger.app.MessengerApp], not by any one Activity/screen). */
class CallViewModel(private val controller: CallController) : ViewModel() {
    val state: StateFlow<CallUiState> = controller.state

    fun startCall(otherUserId: String, otherDisplayName: String, mediaKind: CallMediaKind) =
        controller.startCall(otherUserId, otherDisplayName, mediaKind)

    fun acceptIncoming() = controller.acceptIncoming()
    fun rejectIncoming() = controller.rejectIncoming()
    fun cancelOutgoing() = controller.cancelOutgoing()
    fun hangup() = controller.hangup()
    fun toggleMute() = controller.toggleMute()
    fun toggleSpeaker() = controller.toggleSpeaker()
    fun toggleVideo() = controller.toggleVideo()
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
