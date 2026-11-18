package com.messenger.app.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaViewerPresentationTest {
    @Test fun drawOnlyPanAndDragChangesPublishWithoutACompositionAndStayQuietAtRest() = runTest {
        val panX = mutableFloatStateOf(0f)
        val dragY = mutableFloatStateOf(0f)
        val values = mutableListOf<ViewerImagePresentation>()
        val fit = Rect(0f, 200f, 400f, 600f)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeViewerPresentation({ ViewerImagePresentation(fit.translate(panX.floatValue, dragY.floatValue),
                viewerDragFraction(dragY.floatValue, 800f)) }) { values += it }
        }
        assertEquals(fit, values.last().frame)
        Snapshot.withMutableSnapshot { panX.floatValue = 45f }
        runCurrent()
        assertEquals(fit.translate(45f, 0f), values.last().frame)
        Snapshot.withMutableSnapshot { dragY.floatValue = 70f }
        runCurrent()
        assertTrue(values.last().dismissFraction > 0f)
        assertEquals(fit.translate(45f, 70f), values.last().frame)
        val count = values.size
        Snapshot.withMutableSnapshot { dragY.floatValue = 70f }
        advanceTimeBy(60_000); runCurrent()
        assertEquals("An unchanged viewport must not poll or republish", count, values.size)
    }

    @Test fun cancelAnimationPublishesIntermediateGeometryAndFinishesAtRest() = runTest {
        val returning = Animatable(100f)
        val fit = Rect(0f, 200f, 400f, 600f)
        val values = mutableListOf<ViewerImagePresentation>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeViewerPresentation({ ViewerImagePresentation(fit.translate(0f, returning.value),
                viewerDragFraction(returning.value, 800f)) }) { values += it }
        }
        val clock = BroadcastFrameClock()
        val animation = launch(clock) { returning.animateTo(0f, tween(180)) }
        runCurrent()
        repeat(24) { frame ->
            clock.sendFrame(frame * 16_000_000L)
            runCurrent(); Snapshot.sendApplyNotifications(); runCurrent()
        }
        animation.join()
        assertTrue(values.any { value -> value.frame?.top?.let { it > fit.top && it < fit.top + 100f } == true })
        assertEquals(fit, values.last().frame)
        assertEquals(0f, values.last().dismissFraction, 0f)
        val count = values.size
        advanceTimeBy(60_000); runCurrent()
        assertEquals(count, values.size)
    }

    @Test fun zoomReportingDeduplicatesZoomedStatesAndReleasesOnlyWhenItsOwnerLeaves() = runTest {
        val scale = mutableFloatStateOf(1f)
        val values = mutableListOf<Boolean>()
        val owner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeViewerZoom({ scale.floatValue }) { values += it }
        }
        Snapshot.withMutableSnapshot { scale.floatValue = 2f }; runCurrent()
        Snapshot.withMutableSnapshot { scale.floatValue = 3f }; runCurrent()
        assertEquals(listOf(false, true), values)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(false, true), values)
        owner.cancel(); runCurrent()
        assertEquals(listOf(false, true, false), values)
    }
}
