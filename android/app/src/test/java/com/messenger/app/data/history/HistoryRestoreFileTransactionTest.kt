package com.messenger.app.data.history

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HistoryRestoreFileTransactionTest {
    @Test fun `failed message transaction rolls back the call projection and removes staging`() = runBlocking {
        val directory = Files.createTempDirectory("history-file-rollback").toFile()
        val target = File(directory, "calls.json").apply { writeText("previous") }
        try {
            val failure = runCatching {
                HistoryRestoreFileTransaction(target, "restored".toByteArray()).apply { commit ->
                    assertEquals("previous", target.readText())
                    commit()
                    assertEquals("restored", target.readText())
                    error("SQLite commit rejected")
                }
            }.exceptionOrNull()
            assertEquals("SQLite commit rejected", failure?.message)
            assertEquals("previous", target.readText())
            assertEquals(listOf(target), directory.listFiles().orEmpty().toList())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `failed new import removes a projection that did not exist before`() = runBlocking {
        val directory = Files.createTempDirectory("history-file-new").toFile()
        val target = File(directory, "calls.json")
        try {
            assertTrue(runCatching {
                HistoryRestoreFileTransaction(target, byteArrayOf(1)).apply { commit -> commit(); error("Room rollback") }
            }.isFailure)
            assertFalse(target.exists())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `real filesystem replacement failure leaves the other account file intact`() = runBlocking {
        val directory = Files.createTempDirectory("history-file-io").toFile()
        val other = File(directory, "other-account.json").apply { writeText("untouched") }
        val target = File(directory, "calls.json")
        try {
            val staged = HistoryRestoreFileTransaction(target, byteArrayOf(1))
            // A nonempty directory cannot be replaced by the staged regular file.
            // This exercises an actual Files.move failure, without a mocked writer.
            check(target.mkdir()); File(target, "blocker").writeText("keep")
            assertTrue(runCatching { staged.apply { commit -> commit() } }.isFailure)
            assertEquals("untouched", other.readText())
            assertEquals("keep", File(target, "blocker").readText())
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("restore-") })
        } finally { directory.deleteRecursively() }
    }
}
