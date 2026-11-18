package livekit.org.webrtc

import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.test.*

/** Executes the production request controller and the capturer's production queue guard. */
class CallTorchControlTest {
    private class Request(val light: Boolean)
    private class Camera {
        val off = Request(false)
        val lit = Request(true)
        var active = off
        var open = true
        var frames = 0
        var onBuildFailure: Exception? = null
        var reject: (Request) -> Exception? = { null }
        val submitted = mutableListOf<Request>()
        val failures = mutableListOf<Exception>()
        val rejections = mutableListOf<Exception>()
        val notifications = mutableListOf<Pair<Boolean, Boolean>>()
        val control = CallTorchControl(true, off,
            { onBuildFailure?.let { throw it }; lit },
            { request ->
                submitted += request
                reject(request)?.let { throw it }
                active = request
            },
            { available, on -> notifications += available to on },
            { failure -> failures += failure; open = false },
            { rejections += it })

        init { control.publish() }
        fun frame() { check(open); frames++ }
    }

    @Test fun rejectedEnableRestoresExactWorkingOffRequestAndKeepsVideoRunning() {
        val camera = Camera()
        camera.frame()
        camera.reject = { if (it.light) IllegalArgumentException("unsupported torch") else null }
        camera.control.toggle()
        assertEquals(listOf(camera.lit, camera.off), camera.submitted)
        assertSame(camera.off, camera.active)
        assertEquals(false to false, camera.notifications.last())
        assertTrue(camera.failures.isEmpty())
        assertEquals(1, camera.rejections.size)
        camera.frame()
        assertEquals(2, camera.frames)
        camera.control.toggle()
        assertEquals(2, camera.submitted.size, "Rejected torch stays disabled for this session")
    }

    @Test fun rejectedRequestBuildAlsoRestoresTheUnmodifiedOffRequest() {
        val camera = Camera()
        camera.onBuildFailure = IllegalArgumentException("builder rejected flash")
        camera.control.toggle()
        assertEquals(listOf(camera.off), camera.submitted)
        assertSame(camera.off, camera.active)
        assertTrue(camera.open)
        assertEquals(false to false, camera.notifications.last())
    }

    @Test fun rejectedDisableRetriesOffWithoutClosingTheCamera() {
        val camera = Camera()
        camera.control.toggle()
        var offAttempts = 0
        camera.reject = { if (!it.light && ++offAttempts == 1) IllegalArgumentException("transient rejection") else null }
        camera.control.toggle()
        assertEquals(listOf(camera.lit, camera.off, camera.off), camera.submitted)
        assertSame(camera.off, camera.active)
        assertEquals(false to false, camera.notifications.last())
        camera.frame()
        assertTrue(camera.failures.isEmpty())
    }

    @Test fun rejectedRollbackKeepsLastAcceptedStateAndAllowsExplicitOffRetry() {
        val camera = Camera()
        camera.control.toggle()
        camera.reject = { IllegalArgumentException("request rejected") }
        camera.control.toggle()
        assertSame(camera.lit, camera.active)
        assertEquals(false to true, camera.notifications.last(), "Do not claim rejected OFF succeeded")
        assertTrue(camera.open)
        assertEquals(1, camera.rejections.single().suppressed.size)
        camera.reject = { null }
        camera.control.set(false)
        assertSame(camera.off, camera.active)
        assertEquals(false to false, camera.notifications.last())
    }

    @Test fun reusedRejectionDuringRollbackCannotThrowSelfSuppression() {
        val camera = Camera()
        camera.control.toggle()
        val rejection = IllegalArgumentException("reused HAL rejection")
        camera.reject = { rejection }
        camera.control.toggle()
        assertTrue(camera.open)
        assertSame(rejection, camera.rejections.single())
        assertEquals(false to true, camera.notifications.last())
        assertTrue(camera.failures.isEmpty())
    }

    @Test fun actualClosedOrDisconnectedCameraFailuresRemainFatal() {
        for (failure in listOf(IllegalStateException("closed session"), Exception("camera access failure"))) {
            val camera = Camera()
            camera.reject = { failure }
            camera.control.toggle()
            assertSame(failure, camera.failures.single())
            assertFalse(camera.open)
            assertTrue(camera.rejections.isEmpty())
            assertEquals(listOf(camera.lit), camera.submitted, "Fatal camera errors do not retry requests")
        }
    }

    @Test fun failureRestoringOffUsesFatalPathOnlyForAnActualCameraFailure() {
        val camera = Camera()
        camera.reject = { if (it.light) IllegalArgumentException("torch rejected") else IllegalStateException("closed") }
        camera.control.toggle()
        assertIs<IllegalStateException>(camera.failures.single())
        assertEquals(listOf(camera.lit, camera.off), camera.submitted)
    }

    @Test fun queuedRapidTogglesUseCaptureStateWhileMainNotificationsAreDelayed() {
        for (taps in listOf(2, 3, 4)) {
            val camera = Camera()
            val pending = ArrayDeque<Runnable>()
            val executor = Executor { pending.addLast(it) }
            repeat(taps) { CallTorchControl.enqueue(executor, { camera.control }) { it.toggle() } }
            assertEquals(true to false, camera.notifications.single(), "No Main update before capture thread runs")
            while (pending.isNotEmpty()) pending.removeFirst().run()
            assertEquals((0 until taps).map { if (it % 2 == 0) camera.lit else camera.off }, camera.submitted)
            assertEquals(taps % 2 == 1, camera.active.light)
        }
    }

    @Test fun queuedInputCannotLightAReplacementOrStoppedSession() {
        val old = Camera()
        val replacement = Camera()
        var current = old.control
        val pending = ArrayDeque<Runnable>()
        val executor = Executor { pending.addLast(it) }
        CallTorchControl.enqueue(executor, { current }) { it.toggle() }
        current = replacement.control
        pending.removeFirst().run()
        assertTrue(old.submitted.isEmpty())
        assertTrue(replacement.submitted.isEmpty())
        CallTorchControl.enqueue(executor, { current }) { it.toggle() }
        current.stop()
        pending.removeFirst().run()
        assertTrue(replacement.submitted.isEmpty())
        assertEquals(false to false, replacement.notifications.last())
    }

    @Test fun noFlashAndTeardownNeverQueueAnEnableAndStopRemainsIdempotent() {
        val noFlashRequests = mutableListOf<Boolean>()
        val noFlash = CallTorchControl(false, false, { true },
            { noFlashRequests += it }, { _, _ -> }, { throw AssertionError(it) }, { throw AssertionError(it) })
        noFlash.toggle()
        noFlash.set(true)
        assertTrue(noFlashRequests.isEmpty())

        val camera = Camera()
        camera.control.toggle()
        camera.reject = { IllegalArgumentException("off rejected during teardown") }
        camera.control.stop()
        camera.open = false // Session teardown closes the device regardless of OFF outcome.
        camera.control.stop()
        camera.control.toggle()
        assertEquals(listOf(camera.lit, camera.off), camera.submitted)
        assertEquals(false to false, camera.notifications.last())
        assertTrue(camera.failures.isEmpty())
    }
}
