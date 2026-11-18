package com.messenger.app.ui.call

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.messenger.app.domain.call.CallUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

/** PiP is visible but paused. Window ownership must not depend on chat composition or RESUMED readers. */
internal suspend fun observeCallWindow(
    lifecycle: Lifecycle,
    calls: StateFlow<CallUiState>,
    locked: StateFlow<Boolean>,
    update: (CallUiState, Boolean) -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        combine(calls, locked) { call, deviceLocked -> call to deviceLocked }
            .collect { (call, deviceLocked) -> update(call, deviceLocked) }
    }
}
