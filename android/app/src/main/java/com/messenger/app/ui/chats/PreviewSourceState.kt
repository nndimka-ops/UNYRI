package com.messenger.app.ui.chats

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep the old picture while developing a new source, but never treat it as that source's result. */
internal class PreviewSourceState<T> {
    private var source: String? = null
    private var loaded: String? = null
    private var generation = 0L
    private var value: T? = null
    suspend fun load(key: String, allowed: Boolean, decode: suspend () -> T?): T? {
        if (source != key) { source = key; generation++ }
        val expected = generation
        if (!allowed || loaded == key && value != null) return value
        val result = decode()
        currentCoroutineContext().ensureActive()
        if (source == key && generation == expected && result != null) { value = result; loaded = key }
        return value
    }
}
