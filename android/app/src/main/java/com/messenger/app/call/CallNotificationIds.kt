package com.messenger.app.call

/** Single authoritative notification-ID policy for calls (P0.1). Distinct
 *  namespaces so call cleanup can never collide with — or accidentally
 *  cancel — message/security notifications. Incoming calls are strictly
 *  one-at-a-time per device (backend one-active-call-per-user invariant),
 *  so a stable slot is sufficient: a terminal event always knows exactly
 *  which notification to cancel, with no in-memory mapping that would be
 *  lost after process death. */
object CallNotificationIds {
    /** Incoming (ringing) call notification slot. */
    const val INCOMING_CALL = 4301

    /** Active-call foreground-service notification slot. */
    const val ACTIVE_CALL = 4201
}
