package com.messenger.app.data.call

import com.messenger.app.data.api.CallDto
import com.messenger.app.domain.model.PublicUser

/** Identity does not depend on admitting the peer's chat into local history. */
data class CallPeerIdentity(val name: String?, val avatarVersion: Long, val hasAvatar: Boolean)

fun callPeerIdentity(call: CallDto, owner: String, known: PublicUser?, legacy: Boolean = false): CallPeerIdentity {
    val peerId = when (owner) {
        call.callerUserId -> call.calleeUserId
        call.calleeUserId -> call.callerUserId
        else -> return CallPeerIdentity(null, 0, false)
    }
    val current = known?.takeIf { it.id == peerId }
    val stored = call.peer?.takeIf { it.id == peerId }
    fun label(display: String?, username: String?, number: String?, publicUsername: String?): String? =
        display?.let { com.messenger.app.domain.model.DisplayNamePolicy.display(it, "") }?.takeIf { it.isNotEmpty() }
            ?: com.messenger.app.data.identity.PeerIdentityPolicy.handle(publicUsername, number, username.orEmpty(), legacy).takeIf { it.isNotEmpty() }
    // A newer local profile wins; otherwise prefer the history response identity.
    val preferCurrent = current != null && (stored == null || current.profileVersion > stored.profileVersion)
    return if (preferCurrent) CallPeerIdentity(
        label(current?.displayName, current?.username, current?.publicNumber, current?.publicUsername) ?: label(stored?.displayName, stored?.username, stored?.publicNumber, stored?.publicUsername),
        current?.avatarVersion ?: 0, current?.hasAvatar ?: false,
    ) else CallPeerIdentity(
        label(stored?.displayName, stored?.username, stored?.publicNumber, stored?.publicUsername) ?: label(current?.displayName, current?.username, current?.publicNumber, current?.publicUsername),
        stored?.avatarVersion ?: 0, stored?.hasAvatar ?: false,
    )
}

internal fun retainCallPeerIdentity(next: CallDto, previous: CallDto?): CallDto {
    if (previous == null || next.peer != null || previous.id != next.id || previous.callerUserId != next.callerUserId || previous.calleeUserId != next.calleeUserId) return next
    return next.copy(peer = previous.peer)
}
