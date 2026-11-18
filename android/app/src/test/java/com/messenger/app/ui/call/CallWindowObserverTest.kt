package com.messenger.app.ui.call

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CallWindowObserverTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }
    private val call = CallInfo("call", "peer", "Peer", CallMediaKind.VIDEO, CallDirection.OUTGOING)
    private val video = CallUiState.Connected(call, 1L, videoMode = VideoMode.VIDEO, remoteVideoState = RemoteVideoState.ON)

    @Test fun `peer hangup closes paused PiP without resuming or composing the chat`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            val calls = MutableStateFlow<CallUiState>(video)
            val locked = MutableStateFlow(false)
            var pip = true
            val windowChanges = mutableListOf<CallUiState>()
            backgroundScope.launch { observeCallWindow(owner.lifecycle, calls, locked) { state, lock ->
                windowChanges += state
                if (pip && !CallPipPolicy.canRemain(state, true, lock)) pip = false
            } }
            owner.lifecycle.currentState = Lifecycle.State.RESUMED; runCurrent()
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); runCurrent()
            assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)
            val ended = CallUiState.Ended(call, CallEndReason.HANGUP)
            calls.value = ended; runCurrent()
            assertFalse(pip)
            assertEquals(ended, windowChanges.last())
            assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED; runCurrent()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `locking a paused PiP closes it independently of UI collectors`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            val calls = MutableStateFlow<CallUiState>(video)
            val locked = MutableStateFlow(false)
            var pip = true
            backgroundScope.launch { observeCallWindow(owner.lifecycle, calls, locked) { state, lock ->
                if (!CallPipPolicy.canRemain(state, true, lock)) pip = false
            } }
            owner.lifecycle.currentState = Lifecycle.State.STARTED; runCurrent()
            assertTrue(pip)
            locked.value = true; runCurrent()
            assertFalse(pip)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED; runCurrent()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `stopped windows suspend observation and reconcile latest state on start`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            val calls = MutableStateFlow<CallUiState>(video)
            val changes = mutableListOf<CallUiState>()
            backgroundScope.launch { observeCallWindow(owner.lifecycle, calls, MutableStateFlow(false)) { state, _ -> changes += state } }
            owner.lifecycle.currentState = Lifecycle.State.STARTED; runCurrent()
            assertEquals(listOf<CallUiState>(video), changes)
            owner.lifecycle.currentState = Lifecycle.State.CREATED; runCurrent()
            calls.value = CallUiState.Ended(call, CallEndReason.HANGUP); runCurrent()
            assertEquals(1, changes.size)
            owner.lifecycle.currentState = Lifecycle.State.STARTED; runCurrent()
            assertEquals(calls.value, changes.last())
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED; runCurrent()
            calls.value = CallUiState.Idle; runCurrent()
            assertEquals(2, changes.size)
        } finally { Dispatchers.resetMain() }
    }
}
