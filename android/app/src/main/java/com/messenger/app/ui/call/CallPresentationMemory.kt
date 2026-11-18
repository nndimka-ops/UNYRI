package com.messenger.app.ui.call

import androidx.compose.runtime.mutableStateOf
import com.messenger.app.domain.call.CallRingIndicator
import com.messenger.app.domain.call.CallUiState

/** Activity ViewModel-owned presentation. Recreating a composition does not create a new call. */
internal class CallPresentationMemory {
    val expanded = mutableStateOf(false)
    val callId = mutableStateOf<String?>(null)
    val miniHidden = mutableStateOf(false)
    val lastAnnouncedPhase = mutableStateOf<CallRingIndicator.Phase?>(null)
    val rangHaptic = mutableStateOf(false)
    val mini = CallMiniWindowMemory()
    val duration = CallDurationMemory()

    fun synchronize(state: CallUiState) {
        val id = when (state) {
            is CallUiState.IncomingRinging -> state.call.callId
            else -> CallMinimizePolicy.callId(state)
        }
        if (callId.value == id) return
        callId.value = id
        expanded.value = id != null && state !is CallUiState.IncomingRinging
        miniHidden.value = false
        mini.clear()
        lastAnnouncedPhase.value = null
        rangHaptic.value = false
    }
}
