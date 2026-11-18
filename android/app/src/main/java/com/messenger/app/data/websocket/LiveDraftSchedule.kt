package com.messenger.app.data.websocket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object LiveDraftSchedule {
    fun sendDelayMillis(now: Long, sentAt: Long): Long =
        if (sentAt <= 0) 0 else (1_000 - (now - sentAt)).coerceIn(0, 1_000)

    fun refreshDelay(enabled: Boolean, active: Boolean, connected: Boolean, text: String): Long? =
        // Changes still use the one-second throttle; a still, focused draft only
        // renews its peer's eight-second lease, with three seconds of headroom.
        5_000L.takeIf { enabled && active && connected && text.isNotEmpty() }

    fun expiryDelay(text: String): Long? = 8_000L.takeIf { text.isNotEmpty() }
}

/** Main-thread owner; replacing or clearing a draft cancels its sole deadline. */
internal class DraftExpiry(private val scope: CoroutineScope, private val publish: (String) -> Unit) {
    private var expiry: Job? = null
    fun update(text: String) {
        expiry?.cancel(); expiry = null
        publish(text)
        val wait = LiveDraftSchedule.expiryDelay(text) ?: return
        expiry = scope.launch { delay(wait); publish(""); expiry = null }
    }
}
