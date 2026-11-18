package com.messenger.app.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.*

class DirectCaptureTransactionsTest {
    private val owner = CaptureOwner("alice", "https://coco.example", "phone")
    private val session = CaptureSession(owner, 4)
    private val review = PendingExternalCapture("request", owner, "content://app.fileprovider/camera/capture.jpg", false,
        launched = true, directSource = "/private/cache/camera/capture.jpg", destination = "chat-a", stage = "review", outputVideo = true)

    /** Reproduces SharedPreferences: memory mutates even when disk commit fails. */
    private class Disk(initial: PendingExternalCapture?) {
        var durable = initial
        var volatile = initial
        var succeeds = true
        var throws: Exception? = null
        var writes = 0
        val store = DirectCapturePersistence({ volatile }) { _, next ->
            writes++
            volatile = next
            throws?.let { throw it }
            if (succeeds) durable = next
            succeeds
        }
    }

    @Test fun failedConfirmationNeverUsesTheUncommittedMemoryMap() = runTest {
        val disk = Disk(review).apply { succeeds = false }
        val confirmed = review.copy(confirmed = true)
        var handled = 0
        directCaptureOperation({ handled++ }) { disk.store.replace(review, confirmed) }
        assertEquals(1, handled)
        assertEquals(review, disk.volatile, "Rollback must prevent a later unrelated commit from persisting failed confirmed=true")
        assertEquals(review, disk.durable)
        assertEquals(review, disk.store.read(review.id))
        assertNull(confirmedDirectCapture(review, disk.store.read(review.id), owner))
        assertEquals(DirectCaptureDisposition.DISCARD_UNCONFIRMED,
            directCaptureDisposition(review, disk.store.read(review.id), owner, false), "Explicit Cancel can discard unconfirmed bytes")
        disk.succeeds = true
        assertTrue(disk.store.replace(review, confirmed), "The same review can retry after space is available")
        assertEquals(confirmed, confirmedDirectCapture(review, disk.store.read(review.id), owner))
    }

    @Test fun failedInitialStageClosesSafelyWithoutLeakingHardwareReservation() = runTest {
        val coordinator = NonCallCaptureCoordinator({ session }, { false })
        val ticket = assertNotNull(coordinator.acquire(NonCallCaptureKind.CAMERA, session) {})
        assertTrue(coordinator.markExternal(ticket))
        val preview = review.copy(stage = "preview")
        val disk = Disk(preview).apply { succeeds = false }
        var stopRequested = false
        directCaptureOperation({ stopRequested = true }) { disk.store.replace(preview, preview.copy(stage = "capturing")) }
        assertTrue(stopRequested)
        assertTrue(coordinator.busy, "Failure handler must still await actual camera close")
        assertNull(confirmedDirectCapture(preview, disk.store.read(preview.id), owner))
        coordinator.release(ticket) // Camera CLOSED / photo completion / encoder Finalize acknowledgement.
        assertFalse(coordinator.busy)
    }

    @Test fun thrownWriteFailureIsContainedAndRetainsTheAcknowledgedReview() = runTest {
        val disk = Disk(review).apply { throws = IOException("disk full") }
        var failure: Exception? = null
        directCaptureOperation({ failure = it }) { disk.store.replace(review, review.copy(confirmed = true)) }
        assertIs<IOException>(failure)
        assertEquals(review, disk.store.read(review.id))
        assertEquals(review, disk.durable)
    }

    @Test fun laterUnrelatedPreferenceWriteCannotConfirmAFailedSend() = runTest {
        val disk = Disk(review).apply { succeeds = false }
        directCaptureOperation({}) { disk.store.replace(review, review.copy(confirmed = true)) }
        // An avatar/external request may later flush the same preferences map.
        // The failed direct confirmation must already have been rolled back.
        disk.succeeds = true
        disk.durable = disk.volatile
        val restarted = DirectCapturePersistence({ disk.durable }) { _, _ -> error("No write expected") }
        assertEquals(review, restarted.read(review.id))
        assertNull(confirmedDirectCapture(review, restarted.read(review.id), owner))
    }

    @Test fun cancellationEscapesTheOperationBoundaryWithoutBecomingStorageSuccess() = runTest {
        var errorUiCalled = false
        assertFailsWith<CancellationException> {
            directCaptureOperation({ errorUiCalled = true }) { throw CancellationException("Activity stopped") }
        }
        assertFalse(errorUiCalled)
    }

    @Test fun coroutineCancellationAfterDiskAcknowledgementStillPreservesConfirmation() = runTest {
        val disk = Disk(review)
        var errorUiCalled = false
        assertFailsWith<CancellationException> {
            directCaptureOperation({ errorUiCalled = true }) {
                assertTrue(disk.store.replace(review, review.copy(confirmed = true)))
                throw CancellationException("Activity destroyed before local request assignment")
            }
        }
        assertFalse(errorUiCalled)
        assertNotNull(confirmedDirectCapture(review, disk.store.read(review.id), owner))
        assertEquals(DirectCaptureDisposition.DELIVER_CONFIRMED,
            directCaptureDisposition(review, disk.store.read(review.id), owner, true))
    }

    @Test fun staleCompareAndSetDoesNotWriteOrConfirmAnything() {
        val disk = Disk(review.copy(front = true))
        assertFalse(disk.store.replace(review, review.copy(confirmed = true)))
        assertEquals(0, disk.writes)
        assertFalse(assertNotNull(disk.store.read(review.id)).confirmed)
    }

    @Test fun callBeforeConfirmationAdmissionCannotPromoteUnconfirmedBytes() = runTest {
        var callsBusy = false
        val coordinator = NonCallCaptureCoordinator({ session }, { callsBusy })
        val ticket = assertNotNull(coordinator.acquire(NonCallCaptureKind.CAMERA, session) {})
        val disk = Disk(review)
        callsBusy = true
        coordinator.interruptAll()
        directCaptureOperation({ fail("No storage error expected") }) {
            if (coordinator.valid(ticket)) disk.store.replace(review, review.copy(confirmed = true))
        }
        assertEquals(0, disk.writes)
        assertNull(confirmedDirectCapture(review, disk.store.read(review.id), owner))
        coordinator.release(ticket)
        assertFalse(coordinator.busy)
    }

    @Test fun callAfterConfirmationCasPreservesAndReplaysTheExactResultOnce() = runTest {
        var callsBusy = false
        val coordinator = NonCallCaptureCoordinator({ session }, { callsBusy })
        val ticket = assertNotNull(coordinator.acquire(NonCallCaptureKind.CAMERA, session) {})
        assertTrue(coordinator.markExternal(ticket))
        val disk = Disk(review)
        directCaptureOperation({ fail("No storage error expected") }) {
            assertTrue(coordinator.valid(ticket))
            assertTrue(disk.store.replace(review, review.copy(confirmed = true)))
            // Inject interruption after disk acknowledgement, before Activity's
            // local request assignment/post-check, exactly as the review found.
            callsBusy = true
            coordinator.interruptAll()
        }
        assertFalse(coordinator.valid(ticket))
        assertTrue(coordinator.busy)
        val pending = disk.store.read(review.id)
        assertEquals(DirectCaptureDisposition.DELIVER_CONFIRMED, directCaptureDisposition(review, pending, owner, false))
        assertEquals(review.copy(confirmed = true), confirmedDirectCapture(review, pending, owner))
        coordinator.release(ticket) // Actual CLOSED occurs after cancellation starts.
        assertFalse(coordinator.busy)
        disk.store.remove(review.id) // VM has durably consumed the exact result.
        assertNull(confirmedDirectCapture(review, disk.store.read(review.id), owner), "A retired record cannot replay twice")
    }

    @Test fun cancellationAfterCloseStartsStillCannotOwnDurableConfirmedBytes() {
        val current = review.copy(confirmed = true)
        assertEquals(DirectCaptureDisposition.DELIVER_CONFIRMED, directCaptureDisposition(review, current, owner, false))
        assertEquals(DirectCaptureDisposition.DELIVER_CONFIRMED, directCaptureDisposition(review, current, owner, true))
        // There is deliberately no transient accepted/cancelled/lease boolean
        // in this decision: the acknowledged record owns the confirmed bytes.
    }

    @Test fun differentAccountOrRetiredSameOwnerNeverReceivesTheResult() {
        val current = review.copy(confirmed = true)
        val nextOwner = owner.copy(userId = "bob")
        assertNull(confirmedDirectCapture(review, current, nextOwner))
        assertEquals(DirectCaptureDisposition.PRESERVE, directCaptureDisposition(review, current, nextOwner, false),
            "Old-account cleanup belongs to the workspace purger")
        assertNull(confirmedDirectCapture(review, null, owner), "Same-owner relogin cannot revive retired metadata")
        assertNull(confirmedDirectCapture(review, current.copy(destination = "chat-b"), owner))
        assertNull(confirmedDirectCapture(review, current.copy(directSource = "/other/path.jpg"), owner))
    }

    @Test fun failedRetirementCannotHideTheStillDurableRecord() = runTest {
        val current = review.copy(confirmed = true)
        val disk = Disk(current).apply { succeeds = false }
        directCaptureOperation({}) { disk.store.remove(current.id) }
        assertEquals(current, disk.volatile)
        assertEquals(current, disk.durable)
        assertEquals(current, disk.store.read(current.id))
        assertTrue(current.id in disk.store.retainedIds())
        disk.succeeds = true
        disk.store.remove(current.id)
        assertNull(disk.store.read(current.id))
        assertTrue(disk.store.retainedIds().isEmpty())
    }
}
