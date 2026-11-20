package com.messenger.app.data.profile

import com.messenger.app.domain.model.PublicUser
import org.junit.Assert.*
import org.junit.Test

class DirectoryCacheTest {
    @Test fun `legacy cached logins are removed on read and never written into new people caches`() {
        val privatePeer = PublicUser("peer", "private_login", "Alice", null, publicNumber = "123456789", publicUsername = "alice")
        val historical = DirectoryCache(listOf(privatePeer))
        assertEquals("", historical.search("Alice", 30, 0).items.single().username)
        for (clean in listOf(DirectoryCache().append(0, listOf(privatePeer)), historical.remember(listOf(privatePeer)))) {
            val encoded = kotlinx.serialization.json.Json.encodeToString(DirectoryCache.serializer(), clean)
            assertFalse(encoded.contains("private_login"))
            assertEquals("alice", clean.items.single().publicUsername)
            assertEquals("123456789", clean.items.single().publicNumber)
        }
    }

    private fun users(start: Int, count: Int) = (start until start + count).map { PublicUser("$it", "user$it", "Person $it", "temporary-url", "bio", publicUsername = "user$it") }
    @Test fun `only contiguous prefix is cached with bounded identities and no volatile fields`() {
        val initial = DirectoryCache().append(0, users(0, 30))
        assertEquals(initial, initial.append(60, users(60, 30)))
        val full = initial.append(30, users(30, 30)).append(60, users(60, 30)).append(90, users(90, 30))
        assertEquals(100, full.items.size)
        assertTrue(full.items.all { it.bio == null && it.avatarUrl == null && it.username.isEmpty() })
        assertEquals(2, full.append(0, users(200, 2)).items.size)
    }
    @Test fun `saved directory survives encoding and supports local nickname and display filtering`() {
        val cache = DirectoryCache().append(0, users(0, 30))
        val encoded = kotlinx.serialization.json.Json.encodeToString(DirectoryCache.serializer(), cache)
        val restored = kotlinx.serialization.json.Json.decodeFromString<DirectoryCache>(encoded)
        assertEquals("12", restored.search("@USER12", 30, 0).items.single().id)
        assertEquals("12", restored.search("person 12", 30, 0).items.single().id)
        val first = restored.search("", 10, 0)
        assertTrue(first.cached); assertEquals(10L, first.nextOffset)
        assertEquals(10, restored.search("", 10, first.nextOffset!!).items.size)
        assertTrue(restored.search("missing", 30, 0).items.isEmpty())
    }
    @Test fun `HTTP unsupported auth and cancellation are never disguised as cached success`() {
        assertTrue(canUseDirectoryCache(com.messenger.app.data.api.AuthApiException.Network("Unable to resolve host")))
        assertFalse(canUseDirectoryCache(com.messenger.app.data.api.AuthApiException.Network("HTTP 501")))
        assertFalse(canUseDirectoryCache(com.messenger.app.data.api.AuthApiException.Api("http_error", "HTTP 404")))
        assertFalse(canUseDirectoryCache(kotlinx.coroutines.CancellationException()))
    }

}
