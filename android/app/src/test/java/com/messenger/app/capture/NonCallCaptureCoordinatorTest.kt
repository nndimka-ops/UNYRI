package com.messenger.app.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NonCallCaptureCoordinatorTest {
    private val owner = CaptureOwner("alice", "https://coco.example", "device")
    private val session = CaptureSession(owner, 7)

    @Test fun `all new non-call capture is blocked while a call owns media`() {
        val coordinator = NonCallCaptureCoordinator({ session }, { true })
        NonCallCaptureKind.entries.forEach { assertNull(coordinator.acquire(it, session) {}) }
        assertFalse(coordinator.busy)
    }

    @Test fun `a lease reserves permission and registration before any hardware opens`() {
        val coordinator = NonCallCaptureCoordinator({ session }, { false })
        val lease = assertNotNull(coordinator.acquire(NonCallCaptureKind.CAMERA, session) {})
        assertTrue(coordinator.busy)
        assertTrue(coordinator.state.value.busy)
        assertNull(coordinator.acquire(NonCallCaptureKind.VOICE, session) {})
        coordinator.release(lease)
        assertFalse(coordinator.busy)
    }

    @Test fun `preview handoff stays busy until actual CameraDevice closed acknowledgement`() = runTest {
        val coordinator = NonCallCaptureCoordinator({ session }, { false })
        var closeRequested = false
        val preview = assertNotNull(coordinator.acquire(NonCallCaptureKind.PREVIEW, session) { closeRequested = true })
        val camera = assertNotNull(coordinator.acquire(NonCallCaptureKind.CAMERA, session) {})
        assertTrue(closeRequested)
        assertFalse(coordinator.valid(preview))
        val ready = async { coordinator.awaitReady(camera) }
        runCurrent()
        assertFalse(ready.isCompleted)
        assertTrue(coordinator.busy)
        coordinator.release(preview) // The actual onClosed callback.
        assertTrue(ready.await())
        assertTrue(coordinator.busy)
        coordinator.release(camera)
        assertFalse(coordinator.busy)
    }

    @Test fun `stop callbacks run outside the registry lock`() {
        val coordinator = NonCallCaptureCoordinator({ session }, { false })
        var ranOutsideLock = false
        val preview = assertNotNull(coordinator.acquire(NonCallCaptureKind.PREVIEW, session) {
            val reached = CountDownLatch(1)
            Thread { coordinator.busy; reached.countDown() }.start()
            ranOutsideLock = reached.await(1, TimeUnit.SECONDS)
        })
        val camera = assertNotNull(coordinator.acquire(NonCallCaptureKind.CAMERA, session) {})
        assertTrue(ranOutsideLock)
        coordinator.release(preview); coordinator.release(camera)
    }

    @Test fun `delayed permission cannot revive after incoming call even when it later ends`() {
        var callBusy = false
        val coordinator = NonCallCaptureCoordinator({ session }, { callBusy })
        lateinit var permission: NonCallCaptureCoordinator.Lease
        permission = assertNotNull(coordinator.acquire(NonCallCaptureKind.CAMERA, session) { coordinator.release(permission) })
        callBusy = true
        coordinator.interruptAll()
        callBusy = false
        assertFalse(coordinator.valid(permission))
        assertFalse(coordinator.ready(permission))
        assertFalse(coordinator.busy)
    }

    @Test fun `epoch account server or device replacement invalidates pending capture`() {
        for (replacement in listOf(session.copy(epoch = 8), CaptureSession(owner.copy(userId = "bob"), 7),
            CaptureSession(owner.copy(origin = "https://other.example"), 7), CaptureSession(owner.copy(deviceId = "other-device"), 7))) {
            var current = session
            val coordinator = NonCallCaptureCoordinator({ current }, { false })
            lateinit var lease: NonCallCaptureCoordinator.Lease
            lease = assertNotNull(coordinator.acquire(NonCallCaptureKind.VOICE, session) { coordinator.release(lease) })
            current = replacement
            assertFalse(coordinator.valid(lease))
            coordinator.interruptStaleSessions()
            assertFalse(coordinator.busy)
        }
    }

    @Test fun `interrupted external camera remains reserved until its real result`() = runTest {
        val coordinator = NonCallCaptureCoordinator({ session }, { false })
        val camera = assertNotNull(coordinator.acquire(NonCallCaptureKind.CAMERA, session) {})
        assertTrue(coordinator.markExternal(camera))
        assertFalse(coordinator.stopAndAwaitIdle(20))
        assertTrue(coordinator.busy)
        assertTrue(coordinator.isExternal(camera))
        assertNull(coordinator.acquire(NonCallCaptureKind.PREVIEW, session) {})
        coordinator.release(camera)
        assertTrue(coordinator.stopAndAwaitIdle(20))
    }

    @Test fun `voice interrupt acknowledges stop before incoming answer can continue`() = runTest {
        val coordinator = NonCallCaptureCoordinator({ session }, { false })
        var recorderReleased = false
        lateinit var voice: NonCallCaptureCoordinator.Lease
        voice = assertNotNull(coordinator.acquire(NonCallCaptureKind.VOICE, session) {
            recorderReleased = true
            coordinator.release(voice)
        })
        assertTrue(coordinator.stopAndAwaitIdle())
        assertTrue(recorderReleased)
        assertFalse(coordinator.busy)
    }

    @Test fun `registration completion after incoming call cannot launch and cleanup retains reservation`() = runTest {
        var callBusy = false
        val coordinator = NonCallCaptureCoordinator({ session }, { callBusy })
        val registered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val camera = assertNotNull(coordinator.acquire(NonCallCaptureKind.CAMERA, session) {})
        var opened = false
        val operation = launch {
            try {
                assertTrue(coordinator.awaitReady(camera))
                registered.await()
                if (coordinator.ready(camera)) opened = true
            } finally { cleanup.await(); coordinator.release(camera) }
        }
        runCurrent()
        callBusy = true; coordinator.interruptAll()
        registered.complete(Unit); runCurrent()
        assertFalse(opened)
        assertTrue(coordinator.busy)
        cleanup.complete(Unit); operation.join()
        assertFalse(coordinator.busy)
    }

    @Test fun `restored completed result uses durable owner rather than lost RAM epoch`() {
        val record = PendingExternalCapture("request", owner, "content://app.fileprovider/camera/image.jpg", false, launched = true)
        val decoded = Json.decodeFromString(PendingExternalCapture.serializer(), Json.encodeToString(PendingExternalCapture.serializer(), record))
        val recreated = CaptureSession(owner, 1)
        val coordinator = NonCallCaptureCoordinator({ recreated }, { false })
        assertFalse(coordinator.busy, "metadata alone must not restore hardware ownership at app startup")
        val restored = coordinator.restoreExternal(decoded.id, decoded.owner)
        assertTrue(coordinator.busy)
        assertTrue(decoded.acceptsCompletedResult(recreated.owner))
        assertFalse(decoded.mayLaunch(recreated.owner, true), "restoration must never reopen an already launched camera")
        coordinator.release(restored)
        assertFalse(coordinator.busy)
    }

    @Test fun `persisted unlaunched request cannot auto-launch without its original RAM claim`() {
        val record = PendingExternalCapture("request", owner, "content://app.fileprovider/camera/image.jpg", false)
        assertFalse(record.mayLaunch(owner, false))
        assertFalse(record.acceptsCompletedResult(owner))
        assertTrue(record.mayLaunch(owner, true))
    }

    @Test fun `restored old-account camera blocks media until result but never applies result to new owner`() {
        val next = CaptureSession(owner.copy(userId = "bob"), 1)
        val coordinator = NonCallCaptureCoordinator({ next }, { false })
        val record = PendingExternalCapture("request", owner, "content://app.fileprovider/camera/image.jpg", true, launched = true)
        val restored = coordinator.restoreExternal(record.id, owner)
        assertTrue(coordinator.busy)
        assertFalse(coordinator.valid(restored))
        assertFalse(record.acceptsCompletedResult(next.owner))
        assertNull(coordinator.acquire(NonCallCaptureKind.CAMERA, next) {})
        coordinator.release(restored)
        assertNotNull(coordinator.acquire(NonCallCaptureKind.CAMERA, next) {})
    }

    @Test fun `late old-token cleanup cannot release a successor with the same persisted request id`() {
        val coordinator = NonCallCaptureCoordinator({ session }, { false })
        val old = coordinator.restoreExternal("request", owner)
        coordinator.release(old)
        val successor = coordinator.restoreExternal("request", owner)
        coordinator.release(old)
        assertSame(successor, coordinator.find("request"))
        assertTrue(coordinator.busy)
    }

    @Test fun `failed actual stop retains a busy lease instead of opening another capturer`() = runTest {
        val coordinator = NonCallCaptureCoordinator({ session }, { false })
        coordinator.acquire(NonCallCaptureKind.VOICE, session) { throw IllegalStateException("stop failed") }
        assertFalse(coordinator.stopAndAwaitIdle(20))
        assertTrue(coordinator.busy)
        assertNull(coordinator.acquire(NonCallCaptureKind.CAMERA, session) {})
    }

    @Test fun `orphan metadata after task removal does not reserve a fresh process`() {
        val orphan = PendingExternalCapture("removed-task", owner, "content://app.fileprovider/camera/image.jpg", true, launched = true)
        val freshProcess = NonCallCaptureCoordinator({ session }, { false })
        assertFalse(freshProcess.busy)
        assertNotNull(freshProcess.acquire(NonCallCaptureKind.VOICE, session) {})
        assertTrue(orphan.acceptsCompletedResult(owner), "durable owner checks still permit a legitimate result if its wrapper is restored")
    }
    @Test fun `logout retirement rejects old completed data even after same-owner relogin`() {
        val completed = PendingExternalCapture("retired", owner, "content://app.fileprovider/camera/image.jpg", false, launched = true)
        assertFalse(completed.acceptsCompletedResult(owner, requestStillPending = false))
        assertTrue(completed.acceptsCompletedResult(owner, requestStillPending = true))
    }
}
