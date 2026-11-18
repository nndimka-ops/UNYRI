package com.messenger.app.data.local
import java.io.File
import java.nio.file.Files
import kotlin.test.*
class CacheEvictionPolicyTest {
    @Test fun defaultsNeverDeleteAndSizeUsesOldestFirstInBoundedBatches() {
        val entries = (1..300).map { CacheEvictionPolicy.Entry("$it", 10, it.toLong()) }
        assertTrue(CacheEvictionPolicy.select(entries, CachePolicy(), 99999).isEmpty())
        assertEquals(setOf("1", "2"), CacheEvictionPolicy.select(entries.take(3), CachePolicy(maxBytes=10), 99999))
        assertEquals(100, CacheEvictionPolicy.select(entries, CachePolicy(maxBytes=1), 99999).size)
    }
    @Test fun retentionUsesLastAccessAge() {
        val now = 30 * 86400000L
        val entries = listOf(CacheEvictionPolicy.Entry("old", 10, 1), CacheEvictionPolicy.Entry("recent", 10, now-1))
        assertEquals(setOf("old"), CacheEvictionPolicy.select(entries, CachePolicy(retentionDays=7), now))
    }
    @Test fun activeTransferAndViewerLeasesRefuseDeletionUntilAllRelease() {
        val dir=Files.createTempDirectory("cache-leases").toFile()
        try {
            val file=File(dir,"media").apply { writeText("data") }
            val transfer=MediaCacheUse.acquire(attachmentId="id")
            val viewer=MediaCacheUse.acquire(listOf(file))
            assertNull(MediaCacheUse.deleteUnlessUsed(file,"id"))
            transfer.close(); assertNull(MediaCacheUse.deleteUnlessUsed(file,"id"))
            viewer.close(); viewer.close()
            assertEquals(true, MediaCacheUse.deleteUnlessUsed(file,"id")); assertFalse(file.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun protectedBytesStillCountAgainstBudgetWithoutStarvingEligibleFiles() {
        val eligible=listOf(CacheEvictionPolicy.Entry("newer-eligible",10,1000))
        assertEquals(setOf("newer-eligible"),CacheEvictionPolicy.select(eligible,CachePolicy(maxBytes=1000),99999,protectedBytes=1000))
    }

}
