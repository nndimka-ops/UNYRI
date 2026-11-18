package com.messenger.app.data.avatar

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class AvatarRaceTest {
    @Test fun `late download cannot replace freshly uploaded avatar`() = runBlocking {
        val dir = Files.createTempDirectory("avatar-race").toFile()
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repo = AvatarRepository(dir, "test") {
                started.complete(Unit)
                release.await()
                byteArrayOf(1)
            }
            val old = async { repo.resolve("a", 1, true) }
            started.await()
            repo.put("a", 2, byteArrayOf(2))
            release.complete(Unit)
            assertNull(old.await())
            assertContentEquals(byteArrayOf(2), repo.resolve("a", 2, true))
        } finally { dir.deleteRecursively() }
    }
}
