package com.messenger.app.data.attachment

import com.messenger.app.data.auth.SessionWriteGate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DownloadLifecycleTest {
    @Test fun cancellationResetsSuspendingStateBeforeAReplacementWriterEnters() = runTest {
        val transfers = AttachmentDownloadGate(); val session = SessionWriteGate { 1 }
        val started = CompletableDeferred<Unit>(); val cleaning = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var state = "not_downloaded"; val events = mutableListOf<String>()
        val automatic = launch { transfers.run("1:photo") {
            downloadWithCleanup(cleanup = { session.write(1) { cleaning.complete(Unit); release.await(); state = "not_downloaded"; events += "reset" } }) {
                state = "downloading"; started.complete(Unit); awaitCancellation()
            }
        } }
        started.await(); automatic.cancel(); cleaning.await()
        val manual = launch { transfers.run("1:photo") { assertEquals("not_downloaded", state); events += "retry" } }
        yield(); assertEquals("downloading", state); assertFalse(manual.isCompleted)
        release.complete(Unit); automatic.join(); manual.join()
        assertEquals(listOf("reset", "retry"), events)
    }
    @Test fun oldSessionCleanupCannotWriteIntoTheNewSession() = runTest {
        var epoch = 1; val session = SessionWriteGate { epoch }; var state = "old-downloading"
        val started = CompletableDeferred<Unit>()
        val transfer = launch { downloadWithCleanup(cleanup = { session.write(1) { state = "not_downloaded" } }) { started.complete(Unit); awaitCancellation() } }
        started.await(); epoch = 2; state = "new-account"; transfer.cancelAndJoin()
        assertEquals("new-account", state)
    }
    @Test fun failureCleanupPreservesOriginalErrorAndSuccessDoesNotCleanCommittedData() = runTest {
        val original = java.io.IOException("network"); val cleanup = java.io.IOException("disk")
        val actual = runCatching { downloadWithCleanup(cleanup = { throw cleanup }) { throw original } }.exceptionOrNull()
        assertSame(original, actual); assertTrue(original.suppressed.contains(cleanup))
        assertEquals("downloaded", downloadWithCleanup(cleanup = { error("Committed data cleaned") }) { "downloaded" })
    }
    @Test fun launchRecoveryRunsBeforeNewWorkOncePerEpochAndRetriesFailedRecovery() = runTest {
        val recovery = DownloadRecovery(); var calls = 0; var orphan = "downloading"
        recovery.ensure(1) { calls++; yield(); orphan = "not_downloaded" }
        coroutineScope { repeat(8) { launch { recovery.ensure(1) { calls++ } } } }
        assertEquals(1, calls); assertEquals("not_downloaded", orphan)
        assertTrue(runCatching { recovery.ensure(2) { calls++; error("database unavailable") } }.isFailure)
        recovery.ensure(2) { calls++ }; assertEquals(3, calls)
    }
}
