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
            assertThrows(IllegalArgumentException::class.java) { WorkspaceWipe.eraseWithin(workspace, java.io.File(workspace, "../sibling")) }
            assertEquals("retain", sibling.readText())
        } finally { root.deleteRecursively() }
    }

    // B-072: Android hands out filesDir as /data/user/0/<app>/files, and /data/user/0 is a link to
    // /data/data. The wipe of a workspace reached through such a linked folder must still work.
    @Test fun wipeWorksWhenTheWorkspaceIsReachedThroughALinkedFolder() {
        val root = Files.createTempDirectory("wipe-linked").toFile()
        try {
            val real = root.resolve("data/app/files").apply { mkdirs() }
            val alias = root.resolve("user0").toPath()
            try { Files.createSymbolicLink(alias, root.resolve("data").toPath()) }
            catch (_: Exception) { org.junit.Assume.assumeTrue("symbolic links unavailable on this host", false) }
            val files = alias.resolve("app/files").toFile()
            val transfer = files.resolve("direct-transfers/owner/00000000-0000-4000-8000-000000000001").apply { mkdirs() }
            transfer.resolve("complete").writeText("private bytes")
            val outside = root.resolve("outside").apply { writeText("retain") }
            DirectFileStorage.eraseAll(files)
            assertFalse(real.resolve("direct-transfers").exists())
            assertThrows(IllegalArgumentException::class.java) { WorkspaceWipe.eraseWithin(files, outside) }
            assertEquals("retain", outside.readText())
        } finally { root.deleteRecursively() }
    }
}
