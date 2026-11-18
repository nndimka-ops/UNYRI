package com.messenger.app.ui.chats

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GalleryPermissionGateTest {
    @Test fun originalAndEditedImagesWaitForTheSameLegacyPermissionFlow() = runTest {
        val gate = GalleryPermissionGate(); var requests = 0; val written = mutableListOf<String>()
        for (source in listOf("original", "edited")) {
            val save = async { gate.save(galleryNeedsPermission(26, false), { requests++ }) { written += source } }
            yield(); assertFalse(save.isCompleted); assertFalse(source in written)
            gate.result(true); save.await(); assertEquals(source, written.last())
        }
        assertEquals(2, requests)
    }
    @Test fun denialAndCancellationNeverWriteAndDoNotLatchTheNextSave() = runTest {
        val gate = GalleryPermissionGate(); var writes = 0
        val denied = async { runCatching { gate.save(true, {}) { writes++ } } }
        yield(); gate.result(false); assertTrue(denied.await().exceptionOrNull() is SecurityException)
        val cancelled = launch { gate.save(true, {}) { writes++ } }; yield(); cancelled.cancelAndJoin()
        gate.save(false, { error("Modern API requested permission") }) { writes++ }; assertEquals(1, writes)
        assertFalse(galleryNeedsPermission(29, false)); assertFalse(galleryNeedsPermission(28, true)); assertTrue(galleryNeedsPermission(28, false))
    }
}
