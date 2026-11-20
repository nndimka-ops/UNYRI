package com.messenger.app.ui.chats.peer

/** Availability is derived from existing state; opening a profile grants no new capability. */
internal data class PeerCardActions(
    val message: Boolean,
    val call: Boolean,
    val qr: Boolean,
    val verify: Boolean,
    val changeContact: Boolean,
    val confirmRemoval: Boolean,
)

internal fun peerCardActions(self: Boolean, blocked: Boolean, connected: Boolean,
    identityAvailable: Boolean, contactKnown: Boolean, member: Boolean, busy: Boolean): PeerCardActions =
    PeerCardActions(message = !self && !blocked, call = !self && !blocked && connected,
        qr = !blocked && identityAvailable, verify = !self,
        changeContact = !self && contactKnown && !busy && connected,
        confirmRemoval = member)
