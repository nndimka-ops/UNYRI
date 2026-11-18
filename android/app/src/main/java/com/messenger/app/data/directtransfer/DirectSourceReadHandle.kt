package com.messenger.app.data.directtransfer

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/** One owned source-provider stream; cancellation closes it before the worker can be joined/released. */
internal class DirectSourceReadHandle {
    private val cancelled = AtomicBoolean(false)
    private val input = AtomicReference<InputStream?>(null)

    fun attach(source: InputStream) {
        if (!input.compareAndSet(null, source)) { source.close(); throw IllegalStateException("Source stream already owned") }
        if (cancelled.get()) {
            if (input.compareAndSet(source, null)) source.close()
            throw CancellationException("Source preparation cancelled")
        }
    }
    fun checkCurrent() { if (cancelled.get()) throw CancellationException("Source preparation cancelled") }
    fun finish(source: InputStream) { if (input.compareAndSet(source, null)) source.close() }
    /** Run on a bulk/IO worker, never the UI thread; a failed close never authorizes releasing its worker. */
    fun cancel() { if (cancelled.compareAndSet(false, true)) input.getAndSet(null)?.close() }
}
