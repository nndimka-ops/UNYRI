package com.messenger.app.security

/** Call controls are allowed above keyguard; leaving them is a separate,
 * system-authenticated action. Never trust a cached lock state or a success
 * callback without checking the current system state again. */
class PrivateContentAccess(
    private val isLocked: () -> Boolean,
    private val requestSystemUnlock: (onSuccess: () -> Unit) -> Unit,
) {
    fun open(onAllowed: () -> Unit) {
        if (!isLocked()) {
            onAllowed()
        } else {
            requestSystemUnlock { if (!isLocked()) onAllowed() }
        }
    }
}
