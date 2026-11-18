package com.messenger.app.data.attachment

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import com.messenger.app.data.auth.SessionWriteGate

class SessionMediaWorkTest {
    @Test fun logoutCancelsExportBeforeDrainingMediaGate() = runBlocking {
        withTimeout(3000) {
            val epoch = MutableStateFlow(1)
            val gate = SessionWriteGate { epoch.value }
            val started = CompletableDeferred<Unit>()
            var exportReleased = false
            val export = async {
                sessionMediaWork(epoch, { epoch.value == 1 }) {
                    gate.write(1) {
                        try { started.complete(Unit); awaitCancellation() }
                        finally { exportReleased = true }
                    }
                }
            }
            started.await()
            epoch.value = 2
            gate.clear { assertTrue(exportReleased) }
            assertFailsWith<CancellationException> { export.await() }
            Unit
        }
    }

    @Test fun completedExportDoesNotLeaveAnObserverOrCancelLaterWork() = runBlocking {
        val epoch = MutableStateFlow(1)
        assertEquals("done", sessionMediaWork(epoch, { epoch.value == 1 }) { "done" })
        epoch.value = 2
        yield()
        assertTrue(isActive)
        var started = false
        assertFailsWith<CancellationException> {
            sessionMediaWork(epoch, { epoch.value == 1 }) { started = true }
        }
        assertFalse(started)
    }
}
