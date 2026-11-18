package com.messenger.app.push

import com.messenger.app.domain.call.CallUiState

/** Presentation-only call policy shared by active and inactive account message alerts. */
internal object MessageNotificationPolicy {
    fun callActive(state: CallUiState): Boolean = when (state) {
        is CallUiState.OutgoingRinging, is CallUiState.IncomingRinging, is CallUiState.Connecting,
        is CallUiState.Connected, is CallUiState.Reconnecting -> true
        else -> false
    }
    fun silent(soundEnabled: Boolean, forced: Boolean, activeCall: Boolean): Boolean =
        !soundEnabled || forced || activeCall
}
