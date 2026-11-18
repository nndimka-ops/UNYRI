package com.messenger.app.ui.chats

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PreviewSourceStateTest {
    @Test fun localArrivalDevelopsAnExistingThumbnailWithoutDisposal() = runTest {
        val source = PreviewSourceState<String>(); var decodes = 0
        assertEquals("soft", source.load("inline", true) { decodes++; "soft" })
        assertEquals("soft", source.load("file", false) { error("Hidden source decoded") })
        assertEquals("sharp", source.load("file", true) { decodes++; "sharp" })
        assertEquals("sharp", source.load("file", true) { error("Same source decoded twice") }); assertEquals(2, decodes)
    }
    @Test fun lateOldDecodeCannotReplaceTheNewSourceAndFailureRetriesNaturally() = runTest {
        val source = PreviewSourceState<String>(); val finish = CompletableDeferred<Unit>()
        val old = async { source.load("old", true) { finish.await(); "old" } }; yield()
        assertEquals("new", source.load("new", true) { "new" }); finish.complete(Unit); assertEquals("new", old.await())
        assertEquals("new", source.load("newer", true) { null })
        assertEquals("newer", source.load("newer", true) { "newer" })
    }
    @Test fun cancelledDecodeDoesNotLatchSourceCompletion() = runTest {
        val source = PreviewSourceState<String>(); val work = launch { source.load("file", true) { awaitCancellation() } }
        yield(); work.cancelAndJoin(); assertEquals("retry", source.load("file", true) { "retry" })
    }
}
