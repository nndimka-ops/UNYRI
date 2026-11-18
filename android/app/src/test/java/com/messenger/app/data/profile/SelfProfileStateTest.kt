package com.messenger.app.data.profile

import com.messenger.app.domain.model.UserProfile
import kotlin.test.*

class SelfProfileStateTest {
    private val first = SelfProfileOwner("me", 1, "https://first.example")
    private fun profile(version: Long, avatar: Long = 0, id: String = "me") =
        UserProfile(id, "user", "v$version", null, profileVersion = version, avatarVersion = avatar,
            hasAvatar = avatar > 0, createdAt = "", updatedAt = "")

    @Test fun lateGetCannotReplaceNewerSaveOrItsAvatar() {
        val state = SelfProfileState { first }
        state.publish(first, profile(5, 3))
        var persisted: UserProfile? = null
        assertEquals(profile(5, 3), state.publish(first, profile(4, 2)) { persisted = it })
        assertEquals(profile(5, 3), persisted)
        assertEquals(profile(5, 3), state.updates.value?.profile)
    }

    @Test fun avatarRemovalRemainsCanonicalAfterLateImageResponse() {
        val state = SelfProfileState { first }
        state.publish(first, profile(6, 4).copy(hasAvatar = false))
        state.publish(first, profile(5, 3))
        assertFalse(state.current(first)!!.hasAvatar)
        assertEquals(4L, state.current(first)!!.avatarVersion)
    }

    @Test fun differentAccountEpochOrOriginCannotReadOrPublishPreviousOwner() {
        for (next in listOf(first.copy(userId = "other"), first.copy(epoch = 2), first.copy(origin = "https://second.example"))) {
            var owner = first
            val state = SelfProfileState { owner }
            state.publish(first, profile(5))
            owner = next
            var writes = 0
            assertNull(state.current(first))
            assertNull(state.current(next))
            assertNull(state.publish(first, profile(6)) { writes++ })
            assertEquals(0, writes)
            state.clear()
            assertNull(state.updates.value)
        }
    }

    @Test fun ownerChangeDuringPersistencePreventsPublication() {
        var owner = first
        val state = SelfProfileState { owner }
        assertNull(state.publish(first, profile(5)) { owner = first.copy(epoch = 2) })
        assertNull(state.updates.value)
    }

    @Test fun malformedIdentityAndNegativeVersionsNeverBecomeCanonical() {
        val state = SelfProfileState { first }
        assertNull(state.publish(first, profile(1, id = "foreign")))
        assertNull(state.publish(first, profile(-1)))
        assertNull(state.publish(first, profile(1, -1)))
        assertNull(state.updates.value)
    }

    @Test fun newSessionMayStartAtLowerVersionWithoutReplayingPreviousAccount() {
        var owner = first
        val state = SelfProfileState { owner }
        state.publish(first, profile(9))
        owner = first.copy(userId = "other", epoch = 3)
        assertEquals(profile(1, id = "other"), state.publish(owner, profile(1, id = "other")))
        assertEquals(owner, state.updates.value!!.owner)
    }
}
