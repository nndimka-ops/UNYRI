package com.messenger.app.call

import android.content.pm.ServiceInfo
import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class CallForegroundServiceControllerTest {
    private class Host {
        val states = MutableStateFlow<CallUiState>(CallUiState.Idle)
        var session = CallForegroundServiceTicket("call-0", 1, "owner-a")
        val starts = mutableListOf<CallForegroundServiceRequest>()
        val denied = mutableListOf<CallForegroundServiceTicket>()
        val escaped = mutableListOf<Throwable>()
        var failure: RuntimeException? = null
        var startAccepted = true
        var stops = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> escaped += error })
        val controller = CallForegroundServiceController(states, { session }, scope, { true },
            startService = { starts += it; failure?.let { error -> throw error }; startAccepted },
            stopService = { stops++ }, onDenied = { denied += it })
        init { controller.start() }
        fun connecting(id: String = "call-0", video: Boolean = false) {
            session = session.copy(callId = id)
            states.value = CallUiState.Connecting(CallInfo(id, "peer", "Peer $id", if (video) CallMediaKind.VIDEO else CallMediaKind.AUDIO, CallDirection.OUTGOING))
        }
        fun close() = scope.cancel()
    }

    @Test fun `background start refusal is contained and reports the owning call`() {
        for (error in listOf(SecurityException("while-in-use"), IllegalStateException("background start denied"))) {
            val h = Host()
            try {
                h.failure = error
                h.connecting()
                assertTrue(h.escaped.isEmpty(), "Platform refusal must not escape the application coroutine")
                assertEquals(listOf(h.session), h.denied)
            } finally { h.close() }
        }
    }

    @Test fun `a rejected start without a component is not treated as success`() {
        val h = Host()
        try {
            h.startAccepted = false
            h.connecting()
            assertEquals(listOf(h.session), h.denied)
        } finally { h.close() }
    }

    @Test fun `a second same kind call owns a new service request`() {
        val h = Host()
        try {
            h.connecting()
            h.connecting("call-1")
            assertEquals(listOf("call-0", "call-1"), h.starts.map { it.ticket.callId })
            assertEquals("Peer call-1", h.starts.last().title)
        } finally { h.close() }
    }

    @Test fun `receive only video requests microphone without camera service access`() {
        val h = Host()
        try {
            val call = CallInfo("call-0", "peer", "Peer", CallMediaKind.VIDEO, CallDirection.INCOMING)
            h.states.value = CallUiState.Connected(call, 10, videoMode = VideoMode.VIDEO, localVideoEnabled = false, remoteVideoEnabled = true)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, h.starts.single().foregroundType)
        } finally { h.close() }
    }

    @Test fun `healthy unchanged type does not restart and terminal state stops service`() {
        val h = Host()
        try {
            h.connecting()
            val call = (h.states.value as CallUiState.Connecting).call
            h.states.value = CallUiState.Connected(call, 10)
            h.states.value = CallUiState.Connected(call, 10, isMuted = true)
            assertEquals(1, h.starts.size)
            h.states.value = CallUiState.Ended(call, CallEndReason.HANGUP)
            assertEquals(2, h.stops, "Initial Idle and terminal cleanup")
            assertTrue(h.denied.isEmpty())
        } finally { h.close() }
    }

    @Test fun `coroutine cancellation does not become a call admission error`() {
        val h = Host()
        try {
            h.failure = CancellationException("owner removed")
            h.connecting()
            assertTrue(h.denied.isEmpty())
            assertTrue(h.escaped.isEmpty())
        } finally { h.close() }
    }

    @Test fun `refused start does not kill observation of the next call`() {
        val h = Host()
        try {
            h.failure = SecurityException("background")
            h.connecting()
            h.failure = null
            h.connecting("call-1")
            assertTrue(h.escaped.isEmpty())
            assertEquals(listOf("call-0", "call-1"), h.starts.map { it.ticket.callId })
            assertEquals(listOf("call-0"), h.denied.map { it.callId })
        } finally { h.close() }
    }

    @Test fun `camera off and receive only do not keep the camera service type`() {
        val h = Host()
        try {
            h.connecting(video = true)
            val call = (h.states.value as CallUiState.Connecting).call
            assertTrue(h.starts.last().foregroundType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0)
            h.states.value = CallUiState.Connected(call, 10, videoMode = VideoMode.VIDEO, localVideoEnabled = true)
            assertEquals(1, h.starts.size)
            h.states.value = CallUiState.Connected(call, 10, videoMode = VideoMode.VIDEO, localVideoEnabled = false)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, h.starts.last().foregroundType)
        } finally { h.close() }
    }

    @Test fun `promotion refusal is reported once without throwing and stale promotion is skipped`() {
        val owner = CallForegroundServiceTicket("call", 1, "account-a")
        var current: CallForegroundServiceTicket? = owner
        val failures = mutableListOf<CallForegroundServiceTicket>()
        assertFalse(admitCallForegroundService(owner, { current }, { failures += it }) { throw SecurityException("while-in-use") })
        assertEquals(listOf(owner), failures)
        current = owner.copy(generation = 2, owner = "account-b")
        var attempted = false
        assertFalse(admitCallForegroundService(owner, { current }, { failures += it }) { attempted = true; true })
        assertFalse(attempted)
        assertEquals(1, failures.size)
    }

    @Test fun `session replacement during platform refusal never fails the replacement call`() {
        val owner = CallForegroundServiceTicket("call", 1, "account-a")
        var current = owner
        val failures = mutableListOf<CallForegroundServiceTicket>()
        assertFalse(admitCallForegroundService(owner, { current }, { failures += it }) {
            current = owner.copy(generation = 2, owner = "account-b")
            throw IllegalStateException("late refusal")
        })
        assertTrue(failures.isEmpty())
    }

    @Test fun `a late successful start cannot acquire a replacement session ticket`() {
        val owner = CallForegroundServiceTicket("call", 1, "account-a")
        var current = owner
        val failures = mutableListOf<CallForegroundServiceTicket>()
        assertFalse(admitCallForegroundService(owner, { current }, { failures += it }) {
            current = owner.copy(generation = 2, owner = "account-b")
            true
        })
        assertTrue(failures.isEmpty())
    }

    @Test fun `stale intent cannot stop a newer already promoted call service`() {
        val current = CallForegroundServiceTicket("call", 2, "account-b")
        assertFalse(shouldStopStaleCallService(current, current))
        assertTrue(shouldStopStaleCallService(null, current))
        assertTrue(shouldStopStaleCallService(current.copy(generation = 1), current))
        assertTrue(shouldStopStaleCallService(current, null))
    }
}
