package com.messenger.app.ui.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Remember an ungranted request, not a past grant: one-time permission expiry can prompt again. */
internal class VoicePermissionState(previouslyRequested: Boolean, private val rememberRequest: (Boolean) -> Unit) {
    enum class Action { Granted, Prompt, ExplainSettings }
    enum class Feedback { Denied, Settings }
    private var requested = previouslyRequested
    var feedback by mutableStateOf<Feedback?>(null)
        private set

    fun request(granted: Boolean, showRationale: Boolean): Action {
        if (granted) { result(true, showRationale); return Action.Granted }
        if (requested && !showRationale) { feedback = Feedback.Settings; return Action.ExplainSettings }
        feedback = null
        return Action.Prompt
    }

    private fun remember(value: Boolean) {
        if (requested != value) { requested = value; rememberRequest(value) }
    }
    fun launched() { remember(true) }

    fun result(granted: Boolean, showRationale: Boolean) {
        remember(!granted)
        feedback = if (granted) null else if (showRationale) Feedback.Denied else Feedback.Settings
    }
    fun dismiss() { feedback = null }
}
