package com.messenger.app.security

/** Only elapsed time spent in the background counts toward automatic locking. */
object AppLockPolicy {
    fun shouldLock(enabled: Boolean, backgroundAt: Long?, now: Long, timeoutMs: Long): Boolean =
        enabled && backgroundAt != null && now - backgroundAt >= timeoutMs.coerceAtLeast(0)
    fun protectsOwner(enabled: Boolean, owner: String?): Boolean = enabled && owner != null
    fun permitsViewedCursor(boundOwner: String?, liveOwner: String?, locked: Boolean): Boolean =
        boundOwner != null && boundOwner == liveOwner && !locked
    fun acceptsForegroundProof(foreground: Boolean, requestBackground: Long, currentBackground: Long, credentialReturned: Boolean): Boolean =
        foreground && (requestBackground == currentBackground || credentialReturned && currentBackground == requestBackground + 1)
    fun accepts(requestOwner: String?, currentOwner: String?, request: Long, currentRequest: Long): Boolean =
        requestOwner != null && requestOwner == currentOwner && request == currentRequest
}
