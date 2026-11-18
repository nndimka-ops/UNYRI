package com.messenger.app.ui.chats

import androidx.compose.ui.geometry.Rect
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class MediaViewerNavigationTest {
    @Test fun fitPreservesAspectAndCentersInBothOrientations() {
        for ((width, height) in listOf(402f to 874f, 874f to 402f)) {
            for ((imageWidth, imageHeight) in listOf(4000f to 2000f, 1000f to 4000f)) {
                val rect = viewerFitRect(width, height, imageWidth, imageHeight)!!
                assertEquals(width / 2, rect.center.x, .001f)
                assertEquals(height / 2, rect.center.y, .001f)
                assertEquals(imageWidth / imageHeight, rect.width / rect.height, .001f)
                assertTrue(rect.width <= width && rect.height <= height)
                assertTrue(kotlin.math.abs(rect.width - width) < .001f || kotlin.math.abs(rect.height - height) < .001f)
            }
        }
        assertNull(viewerFitRect(Float.NaN, 800f, 100f, 100f))
        assertNull(viewerFitRect(400f, 800f, 0f, 100f))
    }

    @Test fun closingStartsAtDraggedOrZoomedGeometryInsteadOfJumpingBackToFit() {
        val fit = viewerFitRect(400f, 800f, 100f, 100f)!!
        val moved = viewerTransformedRect(fit, 400f, 800f, .8f, 20f, 100f)
        assertEquals(fit.center.x + 20, moved.center.x, .001f)
        assertEquals(fit.center.y + 100, moved.center.y, .001f)
        assertEquals(fit.width * .8f, moved.width, .001f)
        assertEquals(fit.height * .8f, moved.height, .001f)
        assertEquals(fit, viewerTransformedRect(fit, 400f, 800f, 1f, 0f, 0f))
    }

    @Test fun partialDragCancelsWhileLongDragAndDeliberateFlickDismissBothDirections() {
        for (direction in listOf(-1f, 1f)) {
            assertFalse(viewerDismisses(50f * direction, 100f * direction, 800f))
            assertTrue(viewerDismisses(200f * direction, 0f, 800f))
            assertTrue(viewerDismisses(50f * direction, 1000f * direction, 800f))
            assertFalse(viewerDismisses(50f * direction, -1000f * direction, 800f))
            assertFalse(viewerDismisses(10f * direction, 5000f * direction, 800f))
        }
        assertFalse(viewerDismisses(Float.NaN, 1000f, 800f))
        assertFalse(viewerDismisses(200f, Float.POSITIVE_INFINITY, 800f))
        assertFalse(viewerDismisses(200f, 0f, 0f))
        assertEquals(0f, viewerDragFraction(0f, 800f), 0f)
        assertEquals(1f, viewerDragFraction(10000f, 800f), 0f)
        assertEquals(viewerDragFraction(80f, 800f), viewerDragFraction(-80f, 800f), 0f)
        assertEquals(viewerVideoDragFraction(80f), viewerVideoDragFraction(-80f), 0f)
        assertEquals(0f, viewerVideoDragFraction(Float.NaN), 0f)
        assertEquals(1f, viewerVideoDragFraction(10000f), 0f)
    }

    @Test fun missingClippedOrDetachedSourceUsesFadeFallback() {
        assertTrue(viewerUsableSource(Rect(10f, 20f, 110f, 120f), 400f, 800f))
        for (bounds in listOf(Rect(-1f, 20f, 99f, 120f), Rect(10f, -2f, 110f, 98f),
            Rect(350f, 20f, 450f, 120f), Rect(10f, 750f, 110f, 850f), Rect.Zero)) {
            assertFalse(viewerUsableSource(bounds, 400f, 800f))
        }
    }

    private fun date(value: String?, now: String, zone: String = "Europe/Bucharest") = viewerDateLine(value,
        Instant.parse(now), ZoneId.of(zone), Locale.forLanguageTag("ru"), { "today:$it" }, { "yesterday:$it" })

    @Test fun metadataUsesCalendarDayInLocalZoneIncludingDstAndMidnight() {
        assertEquals("today:00:30", date("2026-11-12T22:30:00Z", "2026-11-13T00:00:00Z"))
        assertEquals("yesterday:23:30", date("2026-11-12T21:30:00Z", "2026-11-13T00:00:00Z"))
        // Yesterday may have 25 hours. Subtracting a fixed duration would be wrong.
        assertEquals("yesterday:00:30", date("2026-10-24T21:30:00Z", "2026-10-25T22:30:00Z"))
        assertFalse(date("2025-11-13T00:00:00Z", "2026-11-13T00:00:00Z")!!.startsWith("today:"))
        assertNull(date(null, "2026-11-13T00:00:00Z"))
        assertNull(date("not-a-date", "2026-11-13T00:00:00Z"))
    }
}
