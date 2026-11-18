package com.messenger.app.data.attachment

import java.io.File

/** Pausing deliberately removes active capture on some devices; that is not an interruption. */
internal fun voiceCaptureRequiresReview(paused: Boolean, silenced: Boolean, configurationMissing: Boolean): Boolean =
    silenced || (!paused && configurationMissing)

/** Platform port. Every callback belongs to the one capture that installed it. */
internal interface VoiceCapture {
    fun start()
    fun stop()
    fun release()
    fun pause()
    fun resume()
    fun level(): Float
    fun silenced(): Boolean = false
    fun requiresReview(): Boolean = silenced()
}

/** Serialized on the UI thread. A terminal take is detached before releasing platform resources. */
internal class VoiceCaptureSession(
    private val output: () -> File,
    private val create: (File, () -> Unit) -> VoiceCapture,
    private val now: () -> Long,
    private val audioDuration: (File) -> Long?,
) {
    private class Take(val file: File) {
        var capture: VoiceCapture? = null
        var started = 0L
        var pausedAt: Long? = null
        var pausedDuration = 0L
        var running = false
    }
    private var active: Take? = null
    private var preserved: Pair<File, Long>? = null
    var onInterrupted: () -> Unit = {}
    var wasInterrupted = false
        private set
    val hasRecordingSession: Boolean get() = active != null
    val isPaused: Boolean get() = active?.pausedAt != null
    val recordedDurationMs: Long get() = active?.let(::elapsed) ?: (preserved?.second ?: 0L)
    private fun elapsed(take: Take): Long = if (!take.running) 0L else
        ((take.pausedAt ?: now()) - take.started - take.pausedDuration).coerceAtLeast(0L)

    fun start(): File {
        check(active == null && preserved == null) { "Recording already active or awaiting preservation" }
        wasInterrupted = false
        val take = Take(output())
        active = take
        try {
            take.capture = create(take.file) { interrupt(take) }
            take.started = now()
            take.capture!!.start()
            take.running = true
            if (active === take && take.capture!!.silenced()) interrupt(take)
            return take.file
        } catch (error: Exception) {
            if (active === take) {
                active = null
                runCatching { take.capture?.release() }
                take.file.delete()
            }
            throw error
        }
    }

    private fun interrupt(take: Take) {
        if (active !== take) return
        wasInterrupted = true
        preserved = finish(take)
        // Also notify for an unusable/error take: the UI must stop its timer and release its lease.
        onInterrupted()
    }

    fun stop(): Pair<File, Long>? {
        val take = active
        if (take == null) return preserved.also { preserved = null }
        // Covers Send/release arriving before the recording-configuration callback.
        if (runCatching { take.capture?.requiresReview() == true }.getOrDefault(true)) wasInterrupted = true
        return finish(take)
    }

    private fun finish(take: Take): Pair<File, Long>? {
        if (active !== take) return null
        active = null
        try { take.capture?.stop() } catch (_: Exception) {
            wasInterrupted = true
            // A recorder error may leave a finalized, readable take. Inspect it after release.
        } finally { runCatching { take.capture?.release() } }
        val duration = runCatching { audioDuration(take.file) }.getOrNull()
        if (duration != null && duration > 0 && take.file.isFile) return take.file to duration
        take.file.delete()
        return null
    }

    fun cancel() {
        val take = active
        active = null
        if (take != null) {
            try { take.capture?.stop() } catch (_: Exception) { }
            finally { runCatching { take.capture?.release() }; take.file.delete() }
        }
        preserved?.first?.delete()
        preserved = null
    }

    fun togglePause() {
        val take = active ?: return
        try {
            val pausedAt = take.pausedAt
            if (pausedAt == null) { take.capture!!.pause(); take.pausedAt = now() }
            else { take.capture!!.resume(); take.pausedDuration += now() - pausedAt; take.pausedAt = null }
        } catch (_: Exception) { interrupt(take) }
    }

    fun level(): Float {
        val take = active ?: return 0f
        if (take.pausedAt != null) return 0f
        return try { take.capture!!.level() } catch (_: Exception) { interrupt(take); 0f }
    }
}
