package com.messenger.app.data.directtransfer

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** One bounded native writer. Authority is checked after BOTH the mutex and native-buffer waits. */
internal class DirectPeerWriter {
    private val writer = Mutex()
    suspend fun send(size: Int, checkCurrent: () -> Unit, buffered: () -> Long, enqueue: () -> Unit) = writer.withLock {
        withTimeout(30_000) {
            while (true) {
                checkCurrent()
                if (buffered() + size <= DirectPeerPolicy.HIGH_WATER) {
                    checkCurrent()
                    enqueue()
                    break
                }
                while (buffered() > DirectPeerPolicy.LOW_WATER) { checkCurrent(); delay(10) }
            }
        }
    }
}
