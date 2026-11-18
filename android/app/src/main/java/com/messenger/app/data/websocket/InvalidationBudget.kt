package com.messenger.app.data.websocket

/** Shared across reconnects. Remote hints cannot create unlimited repository fetch jobs.
 * Exhaustion disconnects the socket; the durable sync path repairs missed hints. */
internal class InvalidationBudget(private val nanoTime: () -> Long = System::nanoTime) {
    private var epoch = -1
    private var start = 0L
    private var count = 0
    @Synchronized fun accept(ownerEpoch: Int): Boolean {
        val now = nanoTime()
        if (epoch != ownerEpoch || now - start >= 30_000_000_000L) {
            epoch = ownerEpoch; start = now; count = 0
        }
        return ++count <= 12
    }
}
