package com.messenger.app.data.local

import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.messenger.app.data.sync.InMemoryAttachmentDao
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MediaExportStoreTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun providerDirectoryAndFailedHandoffsHaveDeterministicOwnership() {
        val root = temp.newFolder(); val exports = MediaExportStore(root)
        val owned = exports.create { it.writeText("edited") }
        assertEquals(File(root, "media-exports"), owned.file.parentFile)
        owned.close(); assertFalse(owned.file.exists())
        assertTrue(runCatching { exports.create { it.writeText("partial"); error("encode failed") } }.isFailure)
        assertTrue(File(root, "media-exports").listFiles().orEmpty().isEmpty())
    }
    @Test fun sharedFilesExpireOnNaturalCleanupAndPendingExportsStayProtected() {
        val root = temp.newFolder(); var now = System.currentTimeMillis(); val exports = MediaExportStore(root) { now }
        val shared = exports.create { it.writeText("shared") }; shared.shared(); shared.close()
        val pending = exports.create { it.writeText("pending") }
        now += MediaExportStore.RETENTION_MS + 100; exports.prune()
        assertFalse(shared.file.exists()); assertTrue(pending.file.exists()); pending.close()
    }
    @Test fun crashAndLegacyRecoveryEraseOnlyOwnedPlaintext() {
        val root = temp.newFolder(); val directory = File(root, "media-exports").apply { mkdirs() }
        File(directory, "old-process.jpg").writeText("abandoned")
        File(root, "markup-legacy.jpg").writeText("legacy plaintext")
        val unrelated = File(root, "unrelated.jpg").apply { writeText("keep") }
        MediaExportStore(root).recover()
        assertTrue(directory.listFiles().orEmpty().isEmpty()); assertFalse(File(root, "markup-legacy.jpg").exists()); assertTrue(unrelated.exists())
    }
    @Test fun countBoundDoesNotEvictPendingHandoffs() {
        val root = temp.newFolder(); val exports = MediaExportStore(root)
        val active = List(MediaExportStore.MAX_FILES) { exports.create { it.writeText("pending") } }
        try { assertTrue(runCatching { exports.create { it.writeText("excess") } }.isFailure); assertTrue(active.all { it.file.exists() }) }
        finally { active.forEach { it.close() } }
    }
    @Test fun accountingClearAndLogoutIncludeExportsAndLegacyFiles() = runTest {
        val root = temp.newFolder(); val store = MediaExportStore(root)
        val owned = store.create { it.writeText("12345") }
        val manager = MediaCacheManager(root, InMemoryAttachmentDao(), temp.newFolder(), File(root, "db"))
        assertEquals(5L, manager.scan().tempBytes)
        assertEquals(1, manager.clear().protectedFiles)
        owned.shared(); owned.close(); assertEquals(1, manager.clear().deletedFiles); assertFalse(owned.file.exists())
        val logout = store.create { it.writeText("private") }; logout.shared(); logout.close()
        File(root, "markup-old.jpg").writeText("legacy")
        PrivateCachePurger.erase(root, temp.newFolder())
        assertFalse(logout.file.exists()); assertFalse(File(root, "markup-old.jpg").exists())
    }
}
