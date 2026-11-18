package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

class PdfReaderTest {
    @Test fun extractedPageTextHasNoDescriptionThatCanMaskIt() {
        val semantics = SemanticsConfiguration().apply { pdfPageContent("Page 3 of 6", "Readable document content", "Unavailable", true) }
        assertNull(semantics.getOrNull(SemanticsProperties.ContentDescription))
        val readable = semantics[SemanticsProperties.Text].joinToString("\n") { it.text }
        assertTrue(readable.contains("Page 3 of 6")); assertTrue(readable.contains("Readable document content"))
    }

    @Test fun textlessPageRetainsItsAccessibleFallback() {
        val semantics = SemanticsConfiguration().apply { pdfPageContent("Page 3 of 6", "  ", "Text unavailable", true) }
        assertNull(semantics.getOrNull(SemanticsProperties.Text))
        assertEquals(listOf("Page 3 of 6. Text unavailable"), semantics[SemanticsProperties.ContentDescription])
    }

    @Test fun widePagesReachTheSameNativeScaleAsPdfKit() {
        val widthDp = 400f
        val pageWidth = 1000
        val limit = pdfZoomLimit(pageWidth, widthDp)
        val zoomed = pdfZoom(PdfZoom(), 100f, 200f, 0f, widthDp, limit)
        assertEquals(4f, zoomed.scale * widthDp / pageWidth)
        assertTrue(zoomed.scale > 6f)
        val normalLimit = pdfZoomLimit(600, widthDp)
        assertEquals(6f, pdfZoom(PdfZoom(), 100f, 200f, 0f, widthDp, normalLimit).scale)
    }

    @Test fun zoomKeepsThePointUnderTheFingerAndClampsPan() {
        val initial = PdfZoom()
        val zoom = pdfZoom(initial, 2f, 300f, 0f, 400f)
        assertEquals(2f, zoom.scale)
        assertEquals(-100f, zoom.pan)
        val originalPoint = (300 - 200 - initial.pan) / initial.scale
        assertEquals(300f, originalPoint * zoom.scale + 200 + zoom.pan)
        assertEquals(200f, pdfZoom(zoom, 2f, 200f, 10000f, 400f).pan)
        assertEquals(PdfZoom(), pdfZoom(zoom, .5f, 300f, 0f, 400f))
    }

    @Test fun invalidGestureCannotPoisonTheViewport() {
        val previous = PdfZoom(2f, -40f)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(previous, pdfZoom(previous, bad, 100f, 0f, 400f))
            assertEquals(previous, pdfZoom(previous, 2f, bad, 0f, 400f))
        }
        assertEquals(previous, pdfZoom(previous, 2f, 100f, 0f, 0f))
        assertEquals(120, pdfZoomScrollOffset(120, Float.NaN, 2f, 0f))
        assertEquals(120, pdfZoomScrollOffset(120, 100f, 2f, Float.NaN))
    }

    @Test fun zoomAndItsInversePreserveTheScrollAnchor() {
        val enlarged = pdfZoomScrollOffset(120, 180f, 2f, 0f)
        assertEquals(420, enlarged)
        assertEquals(120, pdfZoomScrollOffset(enlarged, 180f, .5f, 0f))
        assertEquals(400, pdfZoomScrollOffset(120, 180f, 2f, 20f))
    }

    @Test fun zoomOutOnLaterPagePreservesSignedOffsetAndFocalPoint() {
        val offset = pdfZoomScrollOffset(0, 200f, .5f, 0f)
        assertEquals(-100, offset)
        val pointOnPageAfterZoom = 200f * .5f
        assertEquals(200f, pointOnPageAfterZoom - offset)
        // Reversing the gesture at the same point must restore the exact anchor.
        assertEquals(0, pdfZoomScrollOffset(offset, 200f, 2f, 0f))
        assertEquals(-100, pdfZoomScrollOffset(-100, Float.NaN, .5f, 0f))
    }

    @Test fun currentPageIsTheMostVisibleDocumentPage() {
        assertEquals(3, pdfCurrentPage(listOf(PdfVisiblePage(2, -100, 600), PdfVisiblePage(3, 508, 600)), 0, 874))
        assertEquals(4, pdfCurrentPage(listOf(PdfVisiblePage(2, -400, 600), PdfVisiblePage(3, 208, 600)), 0, 874))
        assertEquals(1, pdfCurrentPage(emptyList(), 0, 874))
        assertEquals(6, pdfCurrentPage(listOf(PdfVisiblePage(5, 0, Int.MAX_VALUE)), 0, 874))
    }

    @Test fun middleTruncationPreservesBothEndsWithoutSplittingEmoji() {
        val filename = "📄 Signed contract for a long name.pdf"
        val result = pdfEllipsizedTitle(filename) { it.codePointCount(0, it.length) <= 12 }
        assertTrue(result.startsWith("📄")); assertTrue(result.endsWith(".pdf")); assertTrue(result.contains("…"))
        assertTrue(result.codePointCount(0, result.length) <= 12)
        assertEquals(filename, pdfEllipsizedTitle(filename) { true })
    }

    @Test fun failuresKeepLockedCorruptAndMemoryStatesDistinct() {
        assertEquals(PdfFailure.Locked, pdfFailure(SecurityException()))
        assertEquals(PdfFailure.Invalid, pdfFailure(java.io.IOException()))
        assertEquals(PdfFailure.Memory, pdfFailure(OutOfMemoryError()))
    }
}
