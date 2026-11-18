package com.messenger.app.data.local

import com.messenger.app.data.auth.SessionWriteGate
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateCachePurgerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun erasesMediaAndHistoryWithoutTouchingOtherPrivateStorage() {
        val cache = temp.newFolder("cache")
        val history = temp.newFolder("call-history")
        for (name in listOf("attachments/pending.jpg", "camera/picker.jpg", "voice_123.m4a")) {
            File(cache, name).apply { parentFile!!.mkdirs(); writeText("private") }
        }
        File(history, "calls.json").writeText("private")
        val unrelated = File(cache, "unrelated").apply { writeText("keep") }
        PrivateCachePurger.erase(cache, history)
        assertArrayEquals(arrayOf("unrelated"), cache.list()!!.sortedArray())
        assertFalse(history.exists())
        assertEquals("keep", unrelated.readText())
        PrivateCachePurger.erase(cache, history)
    }

    @Test fun logoutWaitsForAnExistingFileWriterAndRejectsItsLaterWrites() = runBlocking {
        val cache = temp.newFolder("cache")
        val history = temp.newFolder("call-history")
        val photo = File(cache, "attachments/photo.jpg")
        var epoch = 1
        val gate = SessionWriteGate { epoch }
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val writer = launch {
            gate.write(1) {
                started.complete(Unit)
                finish.await()
                photo.parentFile!!.mkdirs()
                photo.writeText("private")
            }
        }
        started.await()
        epoch = 2
        val cleanup = launch { gate.clear { PrivateCachePurger.erase(cache, history) } }
        yield()
        assertFalse(cleanup.isCompleted)
        finish.complete(Unit)
        writer.join(); cleanup.join()
        assertNull(gate.write(1) { photo.parentFile!!.mkdirs(); photo.writeText("late") })
        assertFalse(photo.exists())
    }
}
