package com.messenger.app.ui.chats

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.lang.reflect.Proxy
import kotlin.test.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoSeekSessionTest {
    private class Deadline : VideoSeekTimeout {
        class Job(val action: () -> Unit, var cancelled: Boolean = false)
        val jobs = mutableListOf<Job>()
        override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
            assertEquals(VIDEO_SEEK_TIMEOUT_MS, delayMillis)
            val job = Job(action); jobs += job
            return { job.cancelled = true }
        }
        fun expire() { jobs.first { !it.cancelled }.action() }
        fun assertIdle() = assertTrue(jobs.all { it.cancelled })
    }
    /** Models Media3 1.4.1: seekTo masks BUFFERING/position immediately; processing an
     * adjusted no-op restores READY, with neither SEEK_ADJUSTMENT nor a rendered frame.
     * See ExoPlayerImpl.seekTo/handlePlaybackInfo and ExoPlayerImplInternal.seekToInternal.
     */
    private class NativePlayer {
        var position = 0L; var state = Player.STATE_READY
        var parameters: SeekParameters = SeekParameters.EXACT
        val parametersAtSeek = mutableListOf<SeekParameters>()
        var keyframes: LongArray? = null
        var throwOnSeek = false
        val seeks = mutableListOf<Long>(); val listeners = mutableListOf<Player.Listener>()
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, method, args -> when (method.name) {
            "addListener" -> { listeners += args!![0] as Player.Listener; null }
            "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
            "getPlaybackState" -> state
            "getCurrentPosition" -> position
            "setSeekParameters" -> { parameters = args!![0] as SeekParameters; null }
            "seekTo" -> {
                parametersAtSeek += parameters
                if (throwOnSeek) throw IllegalStateException("Native seek rejected")
                val old = position
                position = (args!!.last() as Number).toLong(); seeks += position
                if (state == Player.STATE_READY) {
                    state = Player.STATE_BUFFERING
                    listeners.toList().forEach { it.onPlaybackStateChanged(state) }
                }
                discontinuity(old, position, Player.DISCONTINUITY_REASON_SEEK)
                null
            }
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            else -> when (method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Long.TYPE -> 0L; java.lang.Integer.TYPE -> 0; else -> null }
        } } as ExoPlayer
        private fun info(value: Long) = Player.PositionInfo(null, 0, null, null, 0, value, value, -1, -1)
        fun discontinuity(old: Long, next: Long, reason: Int) {
            listeners.toList().forEach { it.onPositionDiscontinuity(info(old), info(next), reason) }
        }
        private fun resolve(requested: Long): Long {
            val sync = keyframes ?: return requested
            val before = sync.lastOrNull { it <= requested } ?: sync.first()
            val after = sync.firstOrNull { it >= requested } ?: sync.last()
            // Run Media3's real resolver with the parameters captured when the command was issued.
            return parametersAtSeek.last().resolveSeekPositionUs(requested * 1000, before * 1000, after * 1000) / 1000
        }
        fun processed(resolved: Long = resolve(seeks.last()), rendered: Boolean = true) {
            val observers = listeners.toList()
            position = resolved; state = Player.STATE_READY
            if (rendered) observers.forEach { it.onRenderedFirstFrame() }
            observers.forEach { it.onPlaybackStateChanged(Player.STATE_READY) }
        }
        fun frameWhileBuffering() { listeners.toList().forEach { it.onRenderedFirstFrame() } }
    }
    @Test fun rapidInputWaitsForNativeProcessingAndRetainsOnlyNewestThenExactRelease() {
        val native = NativePlayer(); val deadline = Deadline(); var resumes = 0
        val session = VideoSeekSession(RenderedVideoSeekBackend(native.player, deadline, isScrubbing = { true }) { 60_000 }) { resumes++ }
        session.offer(VideoSeekRequest(1000)); (2L..20L).forEach { session.offer(VideoSeekRequest(it * 1000)) }
        assertEquals(listOf(1000L), native.seeks)
        native.frameWhileBuffering(); assertEquals(listOf(1000L), native.seeks)
        native.processed(); assertEquals(listOf(1000L, 20_000L), native.seeks)
        session.offer(VideoSeekRequest(23_000, exact = true, resume = true)); assertEquals(0, resumes)
        native.processed(); assertEquals(listOf(1000L,20_000L,23_000L), native.seeks); assertEquals(0, resumes)
        native.processed(); assertEquals(1, resumes); assertTrue(native.listeners.isEmpty()); deadline.assertIdle()
    }
    @Test fun closestSyncAdjustedToCurrentPositionDrainsExactReleaseWithoutAnyRenderedFrame() {
        val native = NativePlayer().apply { position = 30_000 }; val deadline = Deadline(); var resumes = 0
        val session = VideoSeekSession(RenderedVideoSeekBackend(native.player, deadline, isScrubbing = { true }) { 900_000 }) { resumes++ }
        session.offer(VideoSeekRequest(31_234, exact = false)); assertEquals(SeekParameters.CLOSEST_SYNC, native.parametersAtSeek.last())
        session.offer(VideoSeekRequest(74_567, exact = true, resume = true))
        // The synchronous SEEK discontinuity and masked target must not complete the flight.
        assertEquals(listOf(31_234L), native.seeks); assertEquals(0, resumes)
        native.processed(resolved = 30_000, rendered = false)
        assertEquals(listOf(31_234L, 74_567L), native.seeks); assertEquals(SeekParameters.EXACT, native.parametersAtSeek.last()); assertEquals(0, resumes)
        native.processed(rendered = false)
        assertEquals(1, resumes); assertTrue(native.listeners.isEmpty()); deadline.assertIdle()
    }
    @Test fun nativeAdjustmentAcknowledgesReadySeekButSynchronousSeekDoesNot() {
        val native = NativePlayer(); val deadline = Deadline(); var resumes = 0
        val session = VideoSeekSession(RenderedVideoSeekBackend(native.player, deadline, isScrubbing = { true }) { 900_000 }) { resumes++ }
        session.offer(VideoSeekRequest(31_234, exact = true, resume = true))
        assertEquals(0, resumes)
        native.position = 30_000; native.state = Player.STATE_READY
        native.discontinuity(31_234, 30_000, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
        assertEquals(1, resumes); deadline.assertIdle()
    }
    @Test fun settledSeekWithMissingCallbacksHasBoundedFallback() {
        val native = NativePlayer().apply { position = 30_000 }; val deadline = Deadline(); var resumes = 0
        val session = VideoSeekSession(RenderedVideoSeekBackend(native.player, deadline, isScrubbing = { true }) { 900_000 }) { resumes++ }
        session.offer(VideoSeekRequest(31_234, exact = false)); session.offer(VideoSeekRequest(74_567, true, true))
        native.position = 30_000; native.state = Player.STATE_READY // processed without any listener notification
        deadline.expire()
        assertEquals(listOf(31_234L, 74_567L), native.seeks); assertEquals(0, resumes)
        native.processed(rendered = false); assertEquals(1, resumes); deadline.assertIdle()
    }
    @Test fun bufferingTimeoutKeepsNewestExactTargetAndNeverResumesUnacknowledgedPlayback() {
        val native = NativePlayer(); val deadline = Deadline(); var resumes = 0
        val session = VideoSeekSession(RenderedVideoSeekBackend(native.player, deadline, isScrubbing = { true }) { 900_000 }) { resumes++ }
        session.offer(VideoSeekRequest(1000)); session.offer(VideoSeekRequest(2000)); session.offer(VideoSeekRequest(3000, true, true))
        deadline.expire(); assertEquals(listOf(1000L, 3000L), native.seeks); assertEquals(0, resumes)
        deadline.expire(); assertEquals(0, resumes); assertTrue(native.listeners.isEmpty()); deadline.assertIdle()
        // A lost callback cannot permanently latch admission of later drags.
        session.offer(VideoSeekRequest(4000, true, true)); native.processed(rendered = false)
        assertEquals(listOf(1000L, 3000L, 4000L), native.seeks); assertEquals(1, resumes); deadline.assertIdle()
    }
    @Test fun cancellationRemovesDeadlineAndLateNativeSignalsCannotCompleteNewFlight() {
        val native = NativePlayer(); val deadline = Deadline(); var resumes = 0
        val session = VideoSeekSession(RenderedVideoSeekBackend(native.player, deadline, isScrubbing = { true }) { 60_000 }) { resumes++ }
        session.offer(VideoSeekRequest(1000, true, true))
        val staleObserver = native.listeners.single(); val staleDeadline = deadline.jobs.single()
        session.cancel(); assertTrue(native.listeners.isEmpty()); deadline.assertIdle()
        session.offer(VideoSeekRequest(2000, true, true))
        native.state = Player.STATE_READY
        staleObserver.onRenderedFirstFrame(); staleObserver.onPlaybackStateChanged(Player.STATE_READY); staleDeadline.action()
        assertEquals(0, resumes); assertEquals(1, native.listeners.size)
        native.processed(rendered = false); assertEquals(1, resumes); deadline.assertIdle()
    }
    @Test fun aPastSeekTargetIsNotACacheHitAfterPlaybackHasMoved() {
        val native = NativePlayer(); val deadline = Deadline()
        val session = VideoSeekSession(RenderedVideoSeekBackend(native.player, deadline, isScrubbing = { true }) { 60_000 }) {}
        session.offer(VideoSeekRequest(1000, exact = true)); native.processed()
        native.position = 45_000
        session.offer(VideoSeekRequest(1000, exact = true))
        assertEquals(listOf(1000L, 1000L), native.seeks)
        native.processed(); deadline.assertIdle()
    }
    @Test fun alreadyCurrentReadyTargetNeedsNoNativeSeekOrTimer() {
        val native = NativePlayer().apply { position = 1000 }; val deadline = Deadline(); var resumes = 0
        VideoSeekSession(RenderedVideoSeekBackend(native.player, deadline, isScrubbing = { true }) { 60_000 }) { resumes++ }
            .offer(VideoSeekRequest(1000, true, true))
        assertEquals(1, resumes); assertTrue(native.seeks.isEmpty()); assertTrue(deadline.jobs.isEmpty()); assertTrue(native.listeners.isEmpty())
    }
    @Test fun cancellationAndLateAcknowledgementsCannotResumeOrDrainTheNextSession() {
        val callbacks = mutableListOf<(VideoSeekResult) -> Unit>(); var resumed = false
        val backend = object : VideoSeekBackend { override fun seek(request: VideoSeekRequest, completed: (VideoSeekResult) -> Unit) { callbacks += completed }; override fun cancel() {} }
        val session = VideoSeekSession(backend) { resumed = true }
        session.offer(VideoSeekRequest(1000, true, true)); session.cancel(); session.offer(VideoSeekRequest(2000))
        callbacks[0](VideoSeekResult.LANDED); assertFalse(resumed); assertEquals(2, callbacks.size)
        callbacks[1](VideoSeekResult.LANDED); assertFalse(resumed)
    }
    @Test fun failedNativeSeekDropsQueuedWorkAndDoesNotResume() {
        var done: ((VideoSeekResult) -> Unit)? = null; var calls = 0
        val backend = object : VideoSeekBackend { override fun seek(request: VideoSeekRequest, completed: (VideoSeekResult) -> Unit) { calls++; done = completed }; override fun cancel() {} }
        val session = VideoSeekSession(backend) { error("Failed seek resumed") }
        session.offer(VideoSeekRequest(1000)); session.offer(VideoSeekRequest(2000, true, true)); done!!(VideoSeekResult.FAILED)
        assertEquals(1, calls)
    }

    @Test fun pausedLongVideoSamePositionExactReleaseThenForwardTenLandsAtTenNotKeyframe() {
        val native = NativePlayer().apply { position = 20_000; keyframes = longArrayOf(0, 6000, 12_000) }
        val deadline = Deadline(); var dragging = true; var resumes = 0
        val session = VideoSeekSession(RenderedVideoSeekBackend(native.player, deadline, { dragging }) { 900_000 }) { resumes++ }
        assertFalse(native.player.playWhenReady)
        session.offer(VideoSeekRequest(0, exact = false))
        assertEquals(SeekParameters.CLOSEST_SYNC, native.parametersAtSeek.single())
        native.processed(rendered = false); assertEquals(0L, native.position)
        dragging = false
        session.offer(VideoSeekRequest(0, exact = true, resume = false))
        // Same-position release needs no native seek, but must leave EXACT selected.
        assertEquals(listOf(0L), native.seeks); assertEquals(SeekParameters.EXACT, native.parameters)
        native.player.seekVideoExact(videoSeekTarget(native.player.currentPosition, 10_000, 900_000))
        native.processed(rendered = false)
        assertEquals(SeekParameters.EXACT, native.parametersAtSeek.last())
        assertEquals(10_000L, native.position); assertFalse(native.player.playWhenReady); assertEquals(0, resumes)
        assertTrue(native.listeners.isEmpty()); deadline.assertIdle()
    }
    @Test fun samePositionReturnActivelyResetsPreviouslySelectedApproximation() {
        val native = NativePlayer().apply { position = 0; parameters = SeekParameters.CLOSEST_SYNC }
        val deadline = Deadline(); val results = mutableListOf<VideoSeekResult>()
        RenderedVideoSeekBackend(native.player, deadline) { 900_000 }.seek(VideoSeekRequest(0), results::add)
        assertEquals(listOf(VideoSeekResult.LANDED), results)
        assertEquals(SeekParameters.EXACT, native.parameters); assertTrue(native.seeks.isEmpty()); assertTrue(deadline.jobs.isEmpty())
    }
    @Test fun approximateRequestOutsideLiveDragAndQueuedAfterDragAreExact() {
        val native = NativePlayer().apply { keyframes = longArrayOf(0, 6000, 12_000) }
        val deadline = Deadline(); var dragging = false
        val session = VideoSeekSession(RenderedVideoSeekBackend(native.player, deadline, { dragging }) { 900_000 }) {}
        session.offer(VideoSeekRequest(10_000, exact = false))
        native.processed(rendered = false); assertEquals(10_000L, native.position)
        assertEquals(SeekParameters.EXACT, native.parametersAtSeek.last())
        dragging = true
        session.offer(VideoSeekRequest(6500, exact = false)); session.offer(VideoSeekRequest(10_000, exact = false))
        assertEquals(SeekParameters.CLOSEST_SYNC, native.parametersAtSeek.last())
        dragging = false
        native.processed(rendered = false) // queued request is issued after the drag ended
        assertEquals(SeekParameters.EXACT, native.parametersAtSeek.last())
        native.processed(rendered = false); assertEquals(10_000L, native.position); deadline.assertIdle()
    }
    @Test fun failedOrCancelledScrubCommandsRestoreExactAndRemoveDeadline() {
        val native = NativePlayer().apply { throwOnSeek = true }; val deadline = Deadline()
        val results = mutableListOf<VideoSeekResult>()
        val backend = RenderedVideoSeekBackend(native.player, deadline, { true }) { 900_000 }
        backend.seek(VideoSeekRequest(10_000, exact = false), results::add)
        assertEquals(listOf(VideoSeekResult.FAILED), results)
        assertEquals(SeekParameters.CLOSEST_SYNC, native.parametersAtSeek.single())
        assertEquals(SeekParameters.EXACT, native.parameters); assertTrue(native.listeners.isEmpty()); deadline.assertIdle()
        native.throwOnSeek = false
        backend.seek(VideoSeekRequest(20_000, exact = false), results::add)
        native.parameters = SeekParameters.CLOSEST_SYNC
        backend.cancel()
        assertEquals(SeekParameters.EXACT, native.parameters); assertTrue(native.listeners.isEmpty()); deadline.assertIdle()
    }
    @Test fun discreteSkipDoubleTapReplayAndRestoreUseExactDespiteAnApproximatePlayerDefault() {
        val native = NativePlayer().apply { keyframes = longArrayOf(0, 6000, 12_000, 24_000) }
        for (target in listOf(10_000L, 20_000L, 0L, 17_000L)) {
            native.parameters = SeekParameters.CLOSEST_SYNC
            native.player.seekVideoExact(target)
            native.processed(rendered = false)
            assertEquals(target, native.position); assertEquals(SeekParameters.EXACT, native.parametersAtSeek.last())
        }
    }
    @Test fun shortVideoToleranceIsBoundedAndOnlyAllowedDuringLiveScrub() {
        assertEquals(SeekParameters(100_000, 100_000), videoSeekParameters(exact = false, activeScrub = true, duration = 600_000))
        assertEquals(SeekParameters.CLOSEST_SYNC, videoSeekParameters(exact = false, activeScrub = true, duration = 600_001))
        assertEquals(SeekParameters.EXACT, videoSeekParameters(exact = false, activeScrub = false, duration = 900_000))
        assertEquals(SeekParameters.EXACT, videoSeekParameters(exact = true, activeScrub = true, duration = 900_000))
    }
}
