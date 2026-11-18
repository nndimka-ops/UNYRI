package com.messenger.app.data.avatar

import com.messenger.app.domain.model.UserProfile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class OwnAccountAvatarTest {
    @Test fun failedEncodingRetriesTheSameRevision() {
        val root = Files.createTempDirectory("own-avatar-retry").toFile()
        try {
            val store = AccountAvatarStore(root)
            var attempts = 0
            val mirror = OwnAccountAvatar(store, "legacy", { _, _ -> true }, { attempts++; if (attempts == 1) null else it })
            mirror.profile(profile(1), "https://qa.invalid")
            mirror.resolved("self", 1, byteArrayOf(1))
            val owner = AccountAvatarStore.owner("https://qa.invalid", "self")
            assertNull(store.read("legacy", owner))
            mirror.resolved("self", 1, byteArrayOf(2))
            assertEquals(2, attempts)
            assertArrayEquals(byteArrayOf(2), store.read("legacy", owner)?.jpeg)
        } finally { root.deleteRecursively() }
    }
    private fun profile(version: Long, hasAvatar: Boolean = true) = UserProfile(
        id = "self", username = "qa", displayName = "QA", avatarUrl = if (hasAvatar) "avatars/$version" else null,
        profileVersion = version, avatarVersion = version, hasAvatar = hasAvatar, createdAt = "", updatedAt = "",
    )

    @Test fun onlyTheCanonicalOwnAvatarIsCopiedOnceAndRemovalCannotBeUndone() {
        val root = Files.createTempDirectory("own-avatar-contract").toFile()
        try {
            val store = AccountAvatarStore(root)
            var canonical = profile(1)
            var encodes = 0
            val mirror = OwnAccountAvatar(store, "legacy", current = { id, version -> id == canonical.id && version == canonical.avatarVersion },
                encode = { encodes++; it })
            val owner = AccountAvatarStore.owner("https://qa.invalid", "self")
            mirror.profile(canonical, "https://qa.invalid")
            mirror.resolved("peer", 1, byteArrayOf(9))
            mirror.resolved("self", 2, byteArrayOf(9))
            assertNull(store.read("legacy", owner))
            mirror.resolved("self", 1, byteArrayOf(1))
            mirror.resolved("self", 1, byteArrayOf(2))
            assertArrayEquals(byteArrayOf(1), store.read("legacy", owner)?.jpeg)
            assertEquals(1, encodes)
            canonical = profile(2)
            mirror.profile(canonical, "https://qa.invalid")
            mirror.resolved("self", 1, byteArrayOf(9))
            mirror.resolved("self", 2, byteArrayOf(2))
            assertArrayEquals(byteArrayOf(2), store.read("legacy", owner)?.jpeg)
            canonical = profile(3, false)
            mirror.profile(canonical, "https://qa.invalid")
            mirror.resolved("self", 2, byteArrayOf(9))
            assertNull(store.read("legacy", owner))
            assertEquals(2, encodes)
        } finally { root.deleteRecursively() }
    }

    @Test fun completedThumbnailSurvivesRecreationWithoutEncodingAgain() {
        val root = Files.createTempDirectory("own-avatar-contract").toFile()
        try {
            val store = AccountAvatarStore(root)
            val canonical = profile(1)
            val first = OwnAccountAvatar(store, "legacy", { _, _ -> true }, { it })
            first.profile(canonical, "https://qa.invalid"); first.resolved("self", 1, byteArrayOf(1))
            val second = OwnAccountAvatar(store, "legacy", { _, _ -> true }, { error("Unchanged avatar decoded again") })
            second.profile(canonical, "https://qa.invalid"); second.resolved("self", 1, byteArrayOf(2))
            assertArrayEquals(byteArrayOf(1), store.read("legacy", AccountAvatarStore.owner("https://qa.invalid", "self"))?.jpeg)
        } finally { root.deleteRecursively() }
    }

    @Test fun sessionChangeOrPurgeDuringEncodingRejectsTheResult() {
        val root = Files.createTempDirectory("own-avatar-contract").toFile()
        try {
            val store = AccountAvatarStore(root)
            var active = true
            val mirror = OwnAccountAvatar(store, "legacy", { _, _ -> active }, { active = false; it })
            mirror.profile(profile(1), "https://qa.invalid"); mirror.resolved("self", 1, byteArrayOf(1))
            val owner = AccountAvatarStore.owner("https://qa.invalid", "self")
            assertNull(store.read("legacy", owner))
            val purged = OwnAccountAvatar(store, "legacy", { _, _ -> true }, { store.purge("legacy"); it })
            purged.profile(profile(2), "https://qa.invalid"); purged.resolved("self", 2, byteArrayOf(2))
            assertNull(store.read("legacy", owner))
            purged.retire(); purged.resolved("self", 2, byteArrayOf(3))
            assertNull(store.read("legacy", owner))
        } finally { root.deleteRecursively() }
    }
}
