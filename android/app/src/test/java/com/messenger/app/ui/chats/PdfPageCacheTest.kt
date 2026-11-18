package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class PdfPageCacheTest {
    private fun viewport(scale: Float, width: Int = 1080): List<PdfPageViewport> {
        val size = PdfPageSize(600, 900)
        val height = pdfRasterGeometry(width, scale, width, size, 0f).height.toInt()
        return List(6) { PdfPageViewport(it, size, it * (height + 24)) }
    }

    @Test fun heldSixToOnePinchBudgetsAllVisiblePagesAndLiveAllocations() {
        val owner = PdfTileResidency<PdfTileKey, Long>(PDF_BITMAP_BUDGET, PDF_MAX_LIVE_TILES)
        // raster target remains at 6 for the entire held gesture, as in the review.
        for (step in 0..50) {
            val scale = 6f - step / 10f
            val pages = viewport(scale)
            val plan = pdfTilePlan(pages, 1080, 0, 2340, scale, 6f, 0f)
            assertFalse(plan.limited)
            assertTrue(plan.width <= (1080 * scale + 1).toInt())
            owner.setWindow(plan.keys.toSet())
            for (key in plan.keys) {
                if (owner.contains(key)) continue
                val bounds = pdfTileBounds(key, pages[key.page].size)
                val bytes = bounds.width.toLong() * bounds.height * 4
                val allocation = requireNotNull(owner.reserve(key, bytes))
                assertTrue(owner.bytes() <= PDF_BITMAP_BUDGET)
                assertTrue(owner.publish(allocation, bytes, bytes))
            }
            assertEquals(plan.bytes, owner.bytes())
            assertTrue(owner.keys().all { it in plan.keys })
        }
        assertTrue(owner.peakBytes <= PDF_BITMAP_BUDGET)
        // The unfixed retained 6x grid really would exceed the resident budget.
        val oldKeys = viewport(1f).flatMap { page -> pdfVisibleTiles(page.index, 6480, page.size.rasterHeight(6480),
            0f, -page.top * 6f, 6480f, (2340 - page.top) * 6f) }
        assertTrue(oldKeys.sumOf { val b = pdfTileBounds(it, PdfPageSize(600, 900)); b.width.toLong() * b.height * 4 } > 250L * 1024 * 1024)
    }

    @Test fun oversizedViewportIsResampledAsAWholeWithoutChangingPageGeometry() {
        val pages = viewport(1f, 4320)
        val plan = pdfTilePlan(pages, 4320, 0, 9360, 1f, 6f, 0f)
        assertTrue(plan.width < 4320)
        assertTrue(plan.bytes <= PDF_BITMAP_BUDGET)
        assertFalse(plan.limited)
        val size = PdfPageSize(600, 900)
        assertEquals(pdfRasterGeometry(4320, 1f, 4320, size, 0f).height,
            pdfRasterGeometry(4320, 1f, plan.width, size, 0f).height)
        assertTrue(plan.keys.any { it.page == 0 }); assertTrue(plan.keys.any { it.page == 1 })
    }

    @Test fun totalBudgetIncludesInFlightValuesAndRejectsStaleWindowPublication() {
        val owner = PdfTileResidency<Int, String>(10, 2)
        owner.setWindow(setOf(1, 2))
        val first = requireNotNull(owner.reserve(1, 6))
        assertNull(owner.reserve(2, 6)) // single allocation; no second unaccounted value
        assertTrue(owner.publish(first, "old", 6))
        assertNull(owner.reserve(2, 5)) // includes the six bytes already displayed
        val second = requireNotNull(owner.reserve(2, 4))
        assertEquals(10, owner.bytes())
        owner.setWindow(setOf(3))
        assertEquals(4, owner.bytes()) // canceled native allocation is still accounted
        assertFalse(owner.publish(second, "stale", 4))
        assertEquals(0, owner.bytes())
        val replacement = requireNotNull(owner.reserve(3, 10))
        assertTrue(owner.publish(replacement, "new", 10))
        val drawn = mutableListOf<String>()
        owner.draw(listOf(1, 2, 3)) { _, value -> drawn.add(value) }
        assertEquals(listOf("new"), drawn)
        assertTrue(owner.peakBytes <= 10)
        owner.clear(); assertEquals(0, owner.bytes())
    }

    @Test fun cancellationAndActualAllocationSizeCannotEscapeTheBudget() {
        val owner = PdfTileResidency<Int, String>(10, 1)
        owner.setWindow(setOf(1, 2))
        val ticket = requireNotNull(owner.reserve(1, 5))
        assertFalse(owner.publish(ticket, "too large", 6))
        assertEquals(0, owner.bytes())
        val canceled = requireNotNull(owner.reserve(1, 10))
        owner.cancel(canceled); owner.cancel(canceled)
        assertFalse(owner.publish(canceled, "late", 10))
        val kept = requireNotNull(owner.reserve(2, 1))
        assertTrue(owner.publish(kept, "kept", 1))
        assertNull(owner.reserve(1, 1)) // entry count, not only bytes, is bounded
    }

    @Test fun pathologicalPlansTerminateAndDoNotBuildUnboundedKeyLists() {
        val pages = List(20) { PdfPageViewport(it, PdfPageSize(1, 1), 0) }
        val plan = pdfTilePlan(pages, 10000, 0, 10000, 1f, 1f, 0f, budget = 1, maxTiles = 2)
        assertTrue(plan.limited); assertTrue(plan.keys.isEmpty())
        assertEquals(3, pdfVisibleTiles(0, 1000000, 1000000, 0f, 0f, 1000000f, 1000000f, maxTiles = 3).size)
    }

    @Test fun evictionIsByteBoundedAndUsesRecentReads() {
        val cache = PdfWeightedCache<String, String>(10, 10)
        cache.put("a", "first", 4); cache.put("b", "second", 4)
        assertEquals("first", cache["a"])
        cache.put("c", "third", 4)
        assertNull(cache["b"]); assertEquals("first", cache["a"])
        assertEquals("third", cache["c"]); assertEquals(8, cache.bytes)
    }

    @Test fun manyTinyEntriesCannotEscapeEntryLimit() {
        val cache = PdfWeightedCache<Int, Int>(1000, 3)
        repeat(100) { cache.put(it, it, 0) }
        assertEquals(3, cache.size); assertNull(cache[96]); assertEquals(99, cache[99])
    }

    @Test fun replacementOversizedValuesAndClearKeepAccountingCorrect() {
        val cache = PdfWeightedCache<Int, String>(10, 3)
        cache.put(1, "before", 5); cache.put(1, "after", 2)
        assertEquals(2, cache.bytes)
        cache.put(1, "too big", 11)
        assertNull(cache[1]); assertEquals(0, cache.bytes)
        cache.put(2, "kept", 7); cache.clear()
        assertEquals(0, cache.size); assertEquals(0, cache.bytes)
    }

    @Test fun viewportWorkIsIndependentOfPageCountAndWholePageArea() {
        val tiles = pdfVisibleTiles(9999, 6000, 1000000, 2048f, 400000f, 3048f, 402000f)
        assertTrue(tiles.size <= 18)
        assertTrue(tiles.all { it.page == 9999 && it.x in 3..5 && it.y in 781..785 })
        assertTrue(pdfVisibleTiles(0, 6000, 1000000, 0f, -100f, 1000f, -1f).isEmpty())
        assertTrue(pdfVisibleTiles(0, 6000, 1000000, Float.NaN, 0f, 1f, 1f).isEmpty())
    }

    @Test fun edgeTilesStayInsideThePdfAndRetainAspectRatio() {
        val size = PdfPageSize(600, 900)
        assertEquals(1536, size.rasterHeight(1024))
        assertEquals(PdfTileBounds(1024, 1536, 1, 2), pdfTileBounds(PdfTileKey(0, 1025, 2, 3), size))
        val all = pdfVisibleTiles(0, 1025, 1538, 0f, 0f, 1025f, 1538f)
        assertEquals(12, all.size)
        assertEquals(1025L * 1538, all.sumOf { val b = pdfTileBounds(it, size); b.width.toLong() * b.height })
    }

    @Test fun outOfRangeTileCannotAllocateOrRender() {
        assertThrows(IllegalArgumentException::class.java) { pdfTileBounds(PdfTileKey(0, 100, 1, 0), PdfPageSize(600, 900)) }
        assertThrows(IllegalArgumentException::class.java) { PdfPageSize(0, 100) }
    }
}
