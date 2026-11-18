package com.messenger.app.data.profile

import com.messenger.app.domain.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SelfProfileOwner(val userId: String, val epoch: Int, val origin: String)
internal data class OwnedSelfProfile(val owner: SelfProfileOwner, val profile: UserProfile)

/** One canonical snapshot. A late GET cannot roll back a newer PATCH or avatar commit. */
internal class SelfProfileState(private val currentOwner: () -> SelfProfileOwner?) {
    private val mutable = MutableStateFlow<OwnedSelfProfile?>(null)
    val updates = mutable.asStateFlow()

    @Synchronized fun clear() { mutable.value = null }

    @Synchronized fun current(owner: SelfProfileOwner): UserProfile? {
        if (currentOwner() != owner) return null
        return mutable.value?.takeIf { it.owner == owner }?.profile
    }

    @Synchronized fun publish(owner: SelfProfileOwner, incoming: UserProfile, persist: (UserProfile) -> Unit = {}): UserProfile? {
        if (currentOwner() != owner || incoming.id != owner.userId || incoming.profileVersion < 0 || incoming.avatarVersion < 0) return null
        val existing = current(owner)
        val chosen = if (existing != null && (incoming.profileVersion < existing.profileVersion ||
                incoming.avatarVersion < existing.avatarVersion)) existing else incoming
        persist(chosen)
        if (currentOwner() != owner) return null
        mutable.value = OwnedSelfProfile(owner, chosen)
        return chosen
    }
}
