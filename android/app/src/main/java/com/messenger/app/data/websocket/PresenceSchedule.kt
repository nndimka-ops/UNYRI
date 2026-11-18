package com.messenger.app.data.websocket

/** Owner, 17 Nov: presence is not a 2-second loop. A server that grants the long lease
 *  (snapshot valid_for_ms >= 30 s, after presence.subscribe lease_ms) is renewed every 25 s;
 *  an older server (3 s foreground TTL, 5 s snapshots) still needs the 2 s renewal. */
internal object PresenceSchedule {
    const val HEARTBEAT_MILLIS = 25_000L
    const val LEGACY_HEARTBEAT_MILLIS = 2_000L
    const val LEASE_REQUEST_MILLIS = 60_000L
    const val LONG_LEASE_MIN_MILLIS = 30_000L

    fun heartbeatDelay(connected: Boolean, foreground: Boolean, sameOwner: Boolean, longLease: Boolean = false): Long? =
        (if (longLease) HEARTBEAT_MILLIS else LEGACY_HEARTBEAT_MILLIS).takeIf { connected && foreground && sameOwner }

    fun expiryDelay(peers: Collection<PeerPresence>, now: Long): Long? =
        peers.minOfOrNull { it.expiresAt }?.let { (it - now).coerceAtLeast(0) }

    /** A renewal may keep the existing earlier wake; only an earlier lease preempts it. */
    fun replaceExpiry(scheduledAt: Long?, nextAt: Long?): Boolean =
        scheduledAt == null || nextAt == null || nextAt < scheduledAt

    // Lease timestamps are transport bookkeeping. Online labels do not display last-seen.
    fun presentation(peers: Map<String, PeerPresence>): Map<String, PeerPresence> = peers.mapValues { (_, value) ->
        value.copy(lastSeenAt = value.lastSeenAt.takeUnless { value.online }, expiresAt = 0)
    }
}
