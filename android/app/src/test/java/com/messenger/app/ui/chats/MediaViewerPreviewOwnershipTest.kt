package com.messenger.app.ui.chats

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Real Compose ownership and the production warm-source holder; no Android decoder or device. */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaViewerPreviewOwnershipTest {
    private data class Request(val item: String, val source: String, val thumbnail: String)

    @Test fun pagingDropsThePreviousPhotoWhileAFailedPosterFallsBackAndSameItemUpgradesStayWarm() = runTest {
        val scene = Scene(this)
        val selected = mutableStateOf(Request("A", "photo", "A-thumb"))
        val failedPoster = CompletableDeferred<String?>()
        val localPoster = CompletableDeferred<String?>()
        val rendered = mutableListOf<Pair<String, String?>>()
        var fallbacks = 0
        try {
        scene.composition.setContent {
            val item = selected.value
            val preview = rememberViewerItemPreview(item.item) {
                developingPreview(item, decode = {
                    when (it) {
                        "photo" -> "A-photo"
                        "video" -> failedPoster.await()
                        "local-video" -> localPoster.await()
                        else -> error("Unexpected source")
                    }
                }, fallback = { fallbacks++; item.thumbnail })
            }
            SideEffect { rendered += item.item to preview }
        }
            scene.flush()
            assertEquals("A" to "A-photo", rendered.last())

            Snapshot.withMutableSnapshot { selected.value = Request("B", "video", "B-embedded") }
            scene.flush()
            assertEquals("B" to null, rendered.last())
            assertEquals(0, fallbacks)
            failedPoster.complete(null)
            scene.flush()
            assertEquals("B" to "B-embedded", rendered.last())
            assertEquals(1, fallbacks)
            assertFalse("B must never expose A to material or closing-flight consumers",
                rendered.any { it.first == "B" && it.second == "A-photo" })

            Snapshot.withMutableSnapshot { selected.value = Request("B", "local-video", "B-embedded") }
            scene.flush()
            assertEquals("The same item's thumbnail stays visible during a local-source upgrade",
                "B" to "B-embedded", rendered.last())
            localPoster.complete("B-poster")
            scene.flush()
            assertEquals("B" to "B-poster", rendered.last())
            assertEquals(1, fallbacks)
        } finally { scene.dispose() }
    }

    /** Mirrors the existing loader's fallback contract using text instead of Android bitmaps. */
    @Composable private fun developingPreview(item: Request, decode: suspend (String) -> String?, fallback: () -> String): String? {
        val source = remember { PreviewSourceState<String>() }
        val preview by produceState<String?>(null, item.source) {
            val decoded = source.load(item.source, true) { decode(item.source) }
            currentCoroutineContext().ensureActive()
            value = decoded ?: fallback()
        }
        return preview
    }

    private class Scene(private val scope: TestScope) {
        private val clock = BroadcastFrameClock()
        private var frame = 0L
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val runner = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)

        fun flush() {
            repeat(6) {
                Snapshot.sendApplyNotifications(); scope.runCurrent()
                clock.sendFrame(frame); frame += 16_000_000L
                scope.runCurrent()
            }
        }

        suspend fun dispose() {
            composition.dispose(); recomposer.cancel(); runner.cancelAndJoin()
        }
    }
}
