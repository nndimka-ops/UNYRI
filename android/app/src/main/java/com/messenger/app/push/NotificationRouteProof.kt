package com.messenger.app.push

/** Only the OS-supplied creator and exact app-issued capability authorize a route. */
internal object NotificationRouteProofPolicy {
    fun accepts(appUid: Int, creatorUid: Int?, exactCapability: Boolean): Boolean =
        appUid >= 0 && creatorUid == appUid && exactCapability
}
