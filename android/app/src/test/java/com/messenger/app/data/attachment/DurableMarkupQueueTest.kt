package com.messenger.app.data.attachment

import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DurableMarkupQueueTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun offlineQueueReadsOwnedBytesAfterTheTemporaryMarkupIsRetired() = runTest {
        val original = temp.newFile("markup.jpg").apply { writeText("full edited image") }
        val draft = File(temp.newFolder("draft"), "Photo.jpg")
        val durable = durableQueueSource(original, owned = false) { it.copyTo(draft); draft }
        val offlineOutbox = listOf(durable.toURI())
        original.delete()
        assertEquals("full edited image", File(offlineOutbox.single()).readText())
        assertTrue(draft.exists()) // Only outbox retirement is allowed to delete this source.
        draft.delete(); assertFalse(draft.exists())
    }
    @Test fun cancelledQueueReleasesOnlyUnreferencedDrafts() = runTest {
        val original = temp.newFile("temporary.jpg").apply { writeText("full resolution") }
        val draft = File(temp.newFolder("durable"), "Photo.jpg")
        val outbox = mutableSetOf<File>()
        val cancelled = kotlinx.coroutines.CancellationException("caller left")
        assertTrue(runCatching {
            queueDurableMedia(original, false, { it.copyTo(draft); draft },
                discardUnreferenced = { if (it !in outbox) it.delete() }) { throw cancelled }
        }.isFailure)
        assertFalse(draft.exists()); assertTrue(original.exists())
        assertTrue(runCatching {
            queueDurableMedia(original, false, { it.copyTo(draft); draft },
                discardUnreferenced = { if (it !in outbox) it.delete() }) { outbox += it; throw cancelled }
        }.isFailure)
        assertEquals("full resolution", draft.readText())
    }
    @Test fun alreadyOwnedDraftIsNotCopiedAndFailedStagingPreservesOriginal() = runTest {
        val original = temp.newFile("source.jpg").apply { writeText("keep") }
        assertSame(original, durableQueueSource(original, owned = true) { error("Double staging") })
        assertTrue(runCatching { durableQueueSource(original, owned = false) { throw java.io.IOException("disk full") } }.isFailure)
        assertEquals("keep", original.readText())
    }
}
