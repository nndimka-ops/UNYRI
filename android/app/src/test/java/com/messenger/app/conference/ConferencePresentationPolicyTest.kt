package com.messenger.app.conference
import kotlin.test.*

class ConferencePresentationPolicyTest {
    @Test fun `focus preserves the measured page and adds at most one participant`() {
        val page = (1..5).mapTo(linkedSetOf()) { "peer-$it" }
        assertEquals(page, ConferencePresentationPolicy.videoDemand(page, null))
        assertEquals(page, ConferencePresentationPolicy.videoDemand(page, "peer-2"))
        assertEquals(page + "peer-29", ConferencePresentationPolicy.videoDemand(page, "peer-29"))
        assertEquals(6, ConferencePresentationPolicy.videoDemand(page, "peer-29").size)
    }
    @Test fun `mirror is only a local front camera presentation preference`() {
        assertTrue(ConferencePresentationPolicy.mirrorsPreview(local = true, frontCamera = true))
        assertFalse(ConferencePresentationPolicy.mirrorsPreview(local = true, frontCamera = false))
        assertFalse(ConferencePresentationPolicy.mirrorsPreview(local = false, frontCamera = true))
        assertFalse(ConferencePresentationPolicy.mirrorsPreview(local = false, frontCamera = false))
    }
    @Test fun `SDK camera only permission blocks microphone while empty source list allows it`() {
        assertTrue(ConferencePresentationPolicy.microphonePermissionBlocked(false, false, false))
        assertTrue(ConferencePresentationPolicy.microphonePermissionBlocked(true, true, false))
        assertFalse(ConferencePresentationPolicy.microphonePermissionBlocked(true, false, false))
        assertFalse(ConferencePresentationPolicy.microphonePermissionBlocked(true, true, true))
    }
    @Test fun `host audio restriction wins over local unmute and never suppresses camera video`() {
        for (screen in listOf(false, true)) for (visible in listOf(false, true)) for (localMute in listOf(false, true)) for (hiddenVideo in listOf(false, true)) {
            assertFalse(ConferencePresentationPolicy.subscribe(true, screen, visible, localMute, hiddenVideo, hostBlocked = true))
            assertEquals(!localMute, ConferencePresentationPolicy.subscribe(true, screen, visible, localMute, hiddenVideo, hostBlocked = false))
            assertEquals(screen && visible && !hiddenVideo,
                ConferencePresentationPolicy.subscribe(false, screen, visible, localMute, hiddenVideo, hostBlocked = true))
        }
    }
    @Test fun `failed microphone toggle cannot restore consent cleared by owner even after permission returns`() {
        assertTrue(ConferencePresentationPolicy.microphoneAfterFailure(true, false, 1, 1))
        assertFalse(ConferencePresentationPolicy.microphoneAfterFailure(true, true, 1, 1))
        assertFalse(ConferencePresentationPolicy.microphoneAfterFailure(true, true, 1, 2))
        assertFalse(ConferencePresentationPolicy.microphoneAfterFailure(true, false, 1, 2))
        assertFalse(ConferencePresentationPolicy.microphoneAfterFailure(false, false, 1, 1))
    }
    @Test fun `local audio and video suppression apply independently of viewport and other people`() {
        for (screen in listOf(false, true)) for (visible in listOf(false, true)) for (hidden in listOf(false, true)) {
            assertFalse(ConferencePresentationPolicy.subscribe(true, screen, visible, true, hidden))
            assertTrue(ConferencePresentationPolicy.subscribe(true, screen, visible, false, hidden))
            assertFalse(ConferencePresentationPolicy.subscribe(false, screen, visible, false, true))
            assertEquals(screen && visible, ConferencePresentationPolicy.subscribe(false, screen, visible, true, false))
        }
    }
    @Test fun `own tile remains first and participates in layout while camera is disabled`() {
        val own = ConferenceTile("own", "Original name", null, true, false, false)
        val others = (1..29).map { ConferenceTile("remote-$it", "Person $it", null, false, false, true) }
        val state = ConferenceState(active = true, own = own, tiles = others)
        assertEquals(30, state.presentationTiles().size)
        assertEquals("Вы", state.presentationTiles().first().name)
        assertEquals(listOf("own") + others.map { it.id }, state.presentationTiles().map { it.id })
        assertEquals(state.presentationTiles().map { it.id }, state.copy(camera = true, own = own.copy(cameraEnabled = true)).presentationTiles().map { it.id })
        assertEquals(5, ConferencePresentationPolicy.pageCount(state.presentationTiles().size, 6))
    }
    @Test fun `stale hidden or logged out presentation cannot publish or change anchor`() {
        assertTrue(ConferencePresentationPolicy.acceptsPresentation(7, 7, true, true, false))
        assertFalse(ConferencePresentationPolicy.acceptsPresentation(6, 7, true, true, false))
        assertFalse(ConferencePresentationPolicy.acceptsPresentation(7, 7, false, true, false))
        assertFalse(ConferencePresentationPolicy.acceptsPresentation(7, 7, true, false, false))
        assertFalse(ConferencePresentationPolicy.acceptsPresentation(7, 7, true, true, true))
    }
    @Test fun `an older metadata response cannot undo a settings PATCH result`() {
        var revision = 0L
        val beforeEdit = revision
        revision++ // PATCH begins, invalidates earlier GET.
        val duringEdit = revision
        assertFalse(ConferencePresentationPolicy.acceptsMetadata(beforeEdit, revision, true))
        assertFalse(ConferencePresentationPolicy.acceptsMetadata(duringEdit, revision, true))
        revision++ // PATCH succeeds or fails; even a GET during it is now stale.
        assertFalse(ConferencePresentationPolicy.acceptsMetadata(beforeEdit, revision, false))
        assertFalse(ConferencePresentationPolicy.acceptsMetadata(duringEdit, revision, false))
        assertTrue(ConferencePresentationPolicy.acceptsMetadata(revision, revision, false))
    }
    @Test fun `custom count never escapes one to thirty and every scroll block closes its last row`() {
        assertEquals(6, ConferencePresentationPolicy.displayCount(0, 6))
        assertEquals(1, ConferencePresentationPolicy.displayCount(-9, 6))
        assertEquals(30, ConferencePresentationPolicy.displayCount(90, 6))
        for (size in 1..30) for (columns in 1..minOf(3, size)) {
            val rowCells = (0 until size).sumOf { ConferencePresentationPolicy.span(it, size, columns) }
            assertEquals(0, rowCells % columns, "Each scroll block must finish its own row: $size/$columns")
            assertEquals((size + columns - 1) / columns, rowCells / columns)
        }
        assertEquals(listOf(1, 1, 1, 1, 2), (0..4).map { ConferencePresentationPolicy.span(it, 5, 2) })
    }
    @Test fun `video subscriptions include only positive viewport area up to selected limit`() {
        val areas = (1..30).map { it.toString() to if (it <= 9) (10 - it).toFloat() else 0f }
        assertEquals(setOf("1", "2", "3", "4", "5"), ConferencePresentationPolicy.visibleIds(areas, 5))
        assertEquals(setOf("1", "2", "3", "4", "5", "6", "7", "8", "9"), ConferencePresentationPolicy.visibleIds(areas, 30))
        assertEquals(emptySet(), ConferencePresentationPolicy.visibleIds(listOf("hidden" to 0f), 30))
        for (count in 1..30) for (bounds in listOf(320f to 500f, 640f to 200f)) {
            assertTrue(ConferencePresentationPolicy.fitColumns(count, bounds.first, bounds.second) in 1..minOf(6, count))
        }
    }
    @Test fun `all twenty nine remote cameras are reachable in bounded nonoverlapping pages`() {
        val ids = (1..29).map(Int::toString)
        for (landscape in listOf(false, true)) for (font in listOf(1f, 1.8f)) {
            val size = ConferenceLayoutPolicy.pageSize(landscape, font)
            val pages = (0 until ConferencePresentationPolicy.pageCount(ids.size, size)).map { ids.drop(it * size).take(size) }
            assertEquals(ids, pages.flatten()); assertTrue(pages.all { it.size <= size })
        }
        assertEquals(5, ConferencePresentationPolicy.pageCount(29, 6)); assertEquals(4, ConferencePresentationPolicy.pageCount(29, 9))
    }
    @Test fun `anchor survives rotation minimization and participant removal`() {
        val ids = (1..29).map(Int::toString)
        assertEquals(4, ConferencePresentationPolicy.page(ids, "25", 0, 6))
        assertEquals(2, ConferencePresentationPolicy.page(ids, "25", 0, 9))
        assertEquals(4, ConferencePresentationPolicy.page(ids, "25", 0, 6))
        val rebased = ConferencePresentationPolicy.rebase(ids, ids - "25", "25", 4, "25")
        assertEquals("26", rebased.anchor); assertNull(rebased.pin)
        val empty = ConferencePresentationPolicy.rebase(ids, emptyList(), "25", 4, "25")
        assertEquals(ConferenceSelection(null, 0, null), empty)
        val returned = ConferencePresentationPolicy.rebase(emptyList(), ids, empty.anchor, empty.page, empty.pin)
        assertEquals(0, ConferencePresentationPolicy.page(ids, returned.anchor, returned.page, 6)); assertNull(returned.pin)
    }
    @Test fun `small groups use larger tiles and empty room has safe first page`() {
        assertEquals(1, ConferencePresentationPolicy.columns(1, false)); assertEquals(1, ConferencePresentationPolicy.columns(2, false))
        assertEquals(2, ConferencePresentationPolicy.columns(3, false)); assertEquals(3, ConferencePresentationPolicy.columns(9, true))
        assertEquals(0, ConferencePresentationPolicy.page(emptyList(), "gone", 9, 6))
    }
}
