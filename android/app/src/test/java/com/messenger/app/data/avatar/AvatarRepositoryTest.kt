package com.messenger.app.data.avatar

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async

/** JVM tests for the app-scoped avatar cache contract (item 10): same version
 *  reuses the cache, a new version invalidates + refetches, removal and
 *  download failure fall back to the letter (null), never a broken image. */
class AvatarRepositoryTest {

    @Test fun `concurrent avatar rows share one download and all get the image`() = runBlocking {
        val downloads = java.util.concurrent.atomic.AtomicInteger()
        val (repo, _) = repo {
            downloads.incrementAndGet()
            kotlinx.coroutines.delay(30)
            byteArrayOf(1, 2, 3)
        }
        val results = kotlinx.coroutines.coroutineScope {
            (1..12).map { async { repo.resolve("same-user", 3, true) } }
                .map { it.await() }
        }
        assertEquals(1, downloads.get())
        assertTrue(results.all { it.contentEquals(byteArrayOf(1, 2, 3)) })
        kotlin.test.assertSame(results.first(), repo.cached("same-user", 3, true))
        repo.invalidate("same-user")
        assertNull(repo.cached("same-user", 3, true))
    }

    private fun repo(
        downloader: suspend (String) -> ByteArray?,
    ): Pair<AvatarRepository, (Int) -> Unit> {
        val dir = File.createTempFile("avatar_test", "").apply { delete(); mkdirs() }
        return AvatarRepository(dir, "testserver", downloader) to { expected ->
            assertEquals(expected, dir.listFiles()?.size ?: 0, "cache file count")
        }
    }

    @Test
    fun `same version reuses the cache (no second download)`() = runBlocking {
        var downloads = 0
        val (repo, _) = repo { downloads++; "userA".toByteArray() }

        repo.resolve("u1", 3, true)
        repo.resolve("u1", 3, true)

        assertEquals(1, downloads, "same (user, version) must be fetched once")
    }

    @Test
    fun `new version invalidates and refetches`() = runBlocking {
        var downloads = 0
        val (repo, _) = repo { downloads++; (downloads * 1000).toString().toByteArray() }

        val v1 = repo.resolve("u1", 1, true)
        val v2 = repo.resolve("u1", 2, true)

        assertEquals(2, downloads)
        assertFalse(v1.contentEquals(v2), "new version must replace the old bytes")
    }

    @Test
    fun `removed avatar clears the cache and falls back to null`() = runBlocking {
        var downloads = 0
        val (repo, count) = repo { downloads++; byteArrayOf(1, 2, 3) }

        assertTrue(repo.resolve("u1", 5, true) != null)
        count(1)

        val removed = repo.resolve("u1", 0, false)
        assertNull(removed, "removed avatar must render the fallback")
        count(0)
    }

    @Test
    fun `download failure falls back to null with no broken image`() = runBlocking {
        val (repo, _) = repo { null }

        assertNull(repo.resolve("u1", 7, true))
    }

    @Test
    fun `different users are cached independently`() = runBlocking {
        var downloads = 0
        val (repo, _) = repo { id -> downloads++; id.toByteArray() }

        repo.resolve("userA", 1, true)
        repo.resolve("userB", 1, true)

        assertEquals(2, downloads, "cache is keyed per user")
    }
}
