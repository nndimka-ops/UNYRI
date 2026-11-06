package com.messenger.app.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Avatars are optional: failure must leave the already-posted alert intact.
 * Preserve owner cancellation so logout/newer notifications cannot decorate later. */
internal suspend fun <T> loadNotificationDecoration(load: suspend () -> T): T? = try {
    withTimeoutOrNull(2_500) { load() }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
