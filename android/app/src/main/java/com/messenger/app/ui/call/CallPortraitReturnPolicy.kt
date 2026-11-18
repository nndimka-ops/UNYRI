package com.messenger.app.ui.call

import kotlinx.coroutines.withTimeoutOrNull

internal object CallPortraitReturnPolicy {
    const val TIMEOUT_MS = 700L
    suspend fun await(block: suspend () -> Unit) { withTimeoutOrNull(TIMEOUT_MS) { block() } }
    fun shouldWait(held: Boolean, requestChanged: Boolean, portraitRequested: Boolean,
                   portrait: Boolean, multiWindow: Boolean): Boolean =
        held && requestChanged && portraitRequested && !portrait && !multiWindow

    fun fullScreen(nonIdle: Boolean, terminal: Boolean, expanded: Boolean, wasExpanded: Boolean): Boolean =
        nonIdle && if (terminal) wasExpanded else expanded
}
