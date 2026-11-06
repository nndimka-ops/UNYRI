package com.messenger.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** Root navigation owns the drawer; a form, selection or modal owns its Back. */
internal object RootDrawerPolicy {
    fun allowsOpening(root: RootDestination, hostAvailable: Boolean, resumed: Boolean, blocked: Boolean): Boolean =
        hostAvailable && resumed && !blocked && when (root) {
            RootDestination.CHATS, RootDestination.CALLS, RootDestination.ROOMS,
            RootDestination.TRANSFERS, RootDestination.SEARCH, RootDestination.SETTINGS -> true
        }
}

/** Unique scopes prevent an old child's disposal from clearing a new blocker. */
internal class RootDrawerBlockers {
    private val owners = mutableStateMapOf<Any, Unit>()
    val blocked: Boolean get() = owners.isNotEmpty()
    fun acquire(owner: Any) { owners[owner] = Unit }
    fun release(owner: Any) { owners.remove(owner) }
}

internal val LocalRootDrawerBlockers = staticCompositionLocalOf<RootDrawerBlockers?> { null }

/** No input interception, IO or timers; automatically scoped to this screen. */
@Composable
internal fun BlockRootDrawerWhile(blocked: Boolean) {
    val registry = LocalRootDrawerBlockers.current
    val owner = remember(registry) { Any() }
    DisposableEffect(registry, blocked) {
        if (blocked) registry?.acquire(owner)
        onDispose { registry?.release(owner) }
    }
}
