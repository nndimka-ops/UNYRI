package com.messenger.app.data.directtransfer

import com.messenger.app.data.auth.WorkspaceWipe
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DirectFileWipeTest {
    @Test fun fullWipeDeletesFinishedAndUnfinishedTransfersAndPreservesSiblingWorkspace() {
        val root = Files.createTempDirectory("direct-wipe").toFile()
        try {
            val files = root.resolve("selected").apply { mkdirs() }
            val transfers = files.resolve("direct-transfers/owner/00000000-0000-4000-8000-000000000001").apply { mkdirs() }
            listOf("source", "source.tmp", "partial", "complete").forEach { transfers.resolve(it).writeText("private bytes") }
            val other = root.resolve("other/direct-transfers/complete").apply { parentFile.mkdirs(); writeText("other") }
            DirectFileStorage.eraseAll(files)
            assertFalse(files.resolve("direct-transfers").exists())
            assertEquals("other", other.readText())
            DirectFileStorage.eraseAll(files) // Crash/retry is idempotent.
        } finally { root.deleteRecursively() }
    }

    @Test fun privateFileErasureRejectsTraversalOutsideTheWorkspace() {
        val root = Files.createTempDirectory("wipe-boundary").toFile()
        try {
            val workspace = root.resolve("workspace").apply { mkdirs() }
            val sibling = root.resolve("sibling").apply { writeText("retain") }
            assertThrows(IllegalArgumentException::class.java) { WorkspaceWipe.eraseWithin(workspace, sibling) }
            assertEquals("retain", sibling.readText())
        } finally { root.deleteRecursively() }
    }
}
