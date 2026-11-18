package com.messenger.app.ui.settings

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** A provider can ignore cancellation or block in read/close. Never join it on the UI path. */
internal class FeedbackImportWorker {
    private val occupied = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "feedback-import").apply { isDaemon = true } }
    private val cleanup = Executors.newFixedThreadPool(3) { Thread(it, "feedback-import-close").apply { isDaemon = true } }

    internal class Cancellation(private val dispatch: (() -> Unit) -> Unit) {
        private var cancelled = false
        private val callbacks = mutableListOf<() -> Unit>()
        fun check() = synchronized(this) { if (cancelled) throw CancellationException("Screenshot import cancelled") }
        fun register(close: () -> Unit): () -> Unit {
            val alreadyCancelled = synchronized(this) { if (!cancelled) callbacks.add(close); cancelled }
            if (alreadyCancelled) dispatch(close)
            return { synchronized(this) { callbacks.remove(close) }; Unit }
        }
        fun cancel() {
            val pending = synchronized(this) {
                if (cancelled) return
                cancelled = true
                callbacks.toList().also { callbacks.clear() }
            }
            pending.forEach(dispatch)
        }
    }

    suspend fun <T> run(timeoutMs: Long = 30_000, operation: (Cancellation) -> T): T = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            // Hold the slot until the provider actually exits, even after timeout.
            // Repeated taps cannot accumulate blocked threads or queued imports.
            if (!occupied.compareAndSet(false, true)) {
                continuation.resumeWith(Result.failure(IllegalStateException("Previous screenshot provider is still closing")))
            } else {
                val cancellation = Cancellation { close -> cleanup.execute { runCatching(close) } }
                continuation.invokeOnCancellation { cancellation.cancel() }
                worker.execute {
                    val result = runCatching { cancellation.check(); operation(cancellation) }
                    occupied.set(false)
                    continuation.resumeWith(result)
                }
            }
        }
    }
}

internal val feedbackImportWorker = FeedbackImportWorker()
