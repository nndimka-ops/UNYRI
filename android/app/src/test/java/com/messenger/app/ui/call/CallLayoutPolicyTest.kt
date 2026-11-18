package com.messenger.app.ui.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Pure layout-policy contract checks (Phase 4 §42): no Android framework
 *  types, deterministic dp math. Keeps the call UI's responsive rules from
 *  regressing when the composables are reworked. */
class CallLayoutPolicyTest {
    @Test fun `short landscape fits self view and both accessory targets between header and tray`() {
        val (width,height)=CallLayoutPolicy.selfViewSize(640f,336f,144f,1f)
        assertEquals(81f,width,.001f)
        assertEquals(108f,height,.001f)
        // Two 44dp flip/torch targets with the documented 8dp overlap fit.
        assertTrue(width >= 44f+44f-8f)
        val origin=assertNotNull(CallLayoutPolicy.selfViewOrigin(10000f,0f,640f,336f,width,height,144f,true))
        assertEquals(68f,origin.second,.001f)
        assertTrue(origin.second+height <= 336f-144f-16f+.001f)
        assertTrue(origin.first+width <= 640f-16f+.001f)
    }

    @Test fun `self view pinch and rotation stay inside the same placement clearances`() {
        for ((viewportWidth,viewportHeight) in listOf(640f to 336f,336f to 640f,360f to 720f,640f to 320f,640.123f to 336.789f)) {
            for (scale in listOf(.01f,1f,100f)) {
                val (width,height)=CallLayoutPolicy.selfViewSize(viewportWidth,viewportHeight,144f,scale)
                assertTrue(CallLayoutPolicy.canShowPreview(width,height))
                assertEquals(width*CallLayoutPolicy.PREVIEW_ASPECT,height,.001f)
                for (edge in listOf(-10000f,10000f)) {
                    val origin=assertNotNull(CallLayoutPolicy.selfViewOrigin(edge,edge,
                        viewportWidth,viewportHeight,width,height,144f,true))
                    assertTrue(origin.first >= 16f && origin.second >= 68f)
                    assertTrue(origin.first+width <= viewportWidth-16f+.001f)
                    assertTrue(origin.second+height <= viewportHeight-144f-16f+.001f)
                }
            }
        }
    }

    @Test fun `self view uses measured tall tray and the conservative unmeasured clearance`() {
        assertEquals(CallLayoutPolicy.selfViewSize(640f,336f,144f,1f),
            CallLayoutPolicy.selfViewSize(640f,336f,0f,1f))
        val (width,height)=CallLayoutPolicy.selfViewSize(640f,336f,176f,1f)
        assertEquals(76f,height,.001f)
        val origin=assertNotNull(CallLayoutPolicy.selfViewOrigin(0f,10000f,640f,336f,width,height,176f,true))
        assertTrue(origin.second+height <= 336f-176f-16f+.001f)
    }

    @Test fun `impossibly short self view does not expose undersized accessory targets`() {
        val (width,height)=CallLayoutPolicy.selfViewSize(640f,280f,144f,1f)
        assertTrue(!CallLayoutPolicy.canShowPreview(width,height))
        assertNull(CallLayoutPolicy.selfViewOrigin(0f,0f,640f,280f,width,height,144f,true))
    }

    @Test fun `narrow actual content width keeps native targets and five controls fit at 280`() {
        for (width in listOf(280f, 300f, 320f)) for (count in 2..5) {
            val size = CallLayoutPolicy.controlSize(width, count)
            val inner = CallLayoutPolicy.trayWidth(width) - 2 * CallLayoutPolicy.trayPadding(width)
            assertTrue(size >= 48f)
            assertTrue(count * size + (count - 1) * CallLayoutPolicy.controlSpacing(width, count) <= inner + 0.001f)
        }
        // 300dp audio window minus its real 20dp horizontal margins.
        val available = 300f - 2 * 20f
        val row = 4 * CallLayoutPolicy.controlSize(available, 4) + 3 * CallLayoutPolicy.controlSpacing(available, 4)
        assertTrue(row <= CallLayoutPolicy.trayWidth(available) - 2 * CallLayoutPolicy.trayPadding(available))
    }

    @Test fun `preview needs a useful drag area instead of a one pixel target`() {
        assertTrue(CallLayoutPolicy.canShowPreview(48f, 64f))
        assertTrue(!CallLayoutPolicy.canShowPreview(47f, 64f))
        assertTrue(!CallLayoutPolicy.canShowPreview(100f, 47f))
        assertTrue(!CallLayoutPolicy.canShowPreview(100f, -10f))
        val tooSmall = CallLayoutPolicy.resizedPreviewSize(48f, 64f, 1f)
        assertTrue(!CallLayoutPolicy.canShowPreview(tooSmall.first, tooSmall.second))
        val useful = CallLayoutPolicy.resizedPreviewSize(70f, 94f, 1f)
        assertTrue(CallLayoutPolicy.canShowPreview(useful.first, useful.second))
        assertTrue(!CallLayoutPolicy.canShowPreview(Float.POSITIVE_INFINITY, 64f))
    }

    @Test
    fun `resizing respects viewport and preserves camera aspect`() {
        for ((width, height) in listOf(320f to 500f, 700f to 120f, 40f to 30f)) {
            for (scale in listOf(0.01f, 1f, 100f)) {
                val size = CallLayoutPolicy.resizedPreviewSize(width, height, scale)
                assertTrue(size.first > 0 && size.first <= width)
                assertTrue(size.second > 0 && size.second <= height)
                assertTrue(size.first <= 240f)
                assertEquals(size.first * CallLayoutPolicy.PREVIEW_ASPECT, size.second)
            }
        }
        assertEquals(96f, CallLayoutPolicy.resizedPreviewSize(400f, 600f, 0.01f).first)
        assertEquals(240f, CallLayoutPolicy.resizedPreviewSize(400f, 600f, 100f).first)
    }

    @Test
    fun `avatar scales with height between 104 and 136`() {
        assertEquals(136f, CallLayoutPolicy.avatarSize(800f, isVideo = false))
        assertEquals(104f, CallLayoutPolicy.avatarSize(300f, isVideo = false))
        val mid = CallLayoutPolicy.avatarSize(500f, isVideo = false)
        assertTrue(mid in 104f..136f, "mid avatar $mid must stay in band")
    }

    @Test
    fun `video placeholder avatar is fixed compact 96`() {
        assertEquals(96f, CallLayoutPolicy.avatarSize(800f, isVideo = true))
        assertEquals(96f, CallLayoutPolicy.avatarSize(300f, isVideo = true))
    }

    @Test
    fun `narrow screen control sizing stays within the comfort band`() {
        val size = CallLayoutPolicy.controlSize(320f, 5)
        assertTrue(size >= CallLayoutPolicy.CONTROL_MIN, "narrow size $size below floor")
        assertTrue(size <= CallLayoutPolicy.CONTROL_MAX, "narrow size $size above cap")
    }

    @Test
    fun `five controls never overflow the tray`() {
        for (width in floatArrayOf(320f, 360f, 400f, 480f, 800f)) {
            val tray = CallLayoutPolicy.trayWidth(width)
            val padding = CallLayoutPolicy.trayPadding(width)
            val size = CallLayoutPolicy.controlSize(width, 5)
            val spacing = CallLayoutPolicy.controlSpacing(width, 5)
            val total = 5 * size + 4 * spacing
            assertTrue(total <= tray - 2 * padding, "5 controls at $width must fit inside the tray")
        }
    }

    @Test
    fun `visible captions stay shown across supported widths`() {
        for (width in floatArrayOf(320f, 360f, 400f, 480f, 800f)) {
            assertTrue(CallLayoutPolicy.showCallCaptions(width, 5), "captions should show at $width")
        }
    }

    @Test
    fun `large screen control size is capped`() {
        assertEquals(CallLayoutPolicy.CONTROL_MAX, CallLayoutPolicy.controlSize(480f, 5))
        assertEquals(CallLayoutPolicy.CONTROL_MAX, CallLayoutPolicy.controlSize(320f, 3))
    }

    @Test
    fun `control spacing tightens on narrow screens`() {
        assertEquals(6f, CallLayoutPolicy.controlSpacing(320f, 5))
        assertEquals(12f, CallLayoutPolicy.controlSpacing(400f, 5))
    }

    @Test
    fun `local preview width clamps 96 to 150 with 4_3 aspect`() {
        assertEquals(96f, CallLayoutPolicy.localPreviewSize(320f).first)
        assertEquals(150f, CallLayoutPolicy.localPreviewSize(600f).first)
        val (w, h) = CallLayoutPolicy.localPreviewSize(411f)
        assertTrue(w in 96f..150f, "preview width $w must clamp")
        assertEquals(w * 4f / 3f, h)
    }

    @Test
    fun `caller name truncates to two lines max`() {
        assertEquals(2, CallLayoutPolicy.CALL_NAME_MAX_LINES)
        assertEquals(1, CallLayoutPolicy.USERNAME_MAX_LINES)
    }

    @Test
    fun `tray width is capped on large screens`() {
        assertEquals(CallLayoutPolicy.TRAY_MAX_WIDTH, CallLayoutPolicy.trayWidth(800f))
        assertEquals(CallLayoutPolicy.TRAY_MAX_WIDTH, CallLayoutPolicy.trayWidth(480f))
    }

    @Test
    fun `tray width leaves adaptive margins on phones`() {
        assertEquals(320f - 2f * CallLayoutPolicy.TRAY_MARGIN_NARROW, CallLayoutPolicy.trayWidth(320f))
        assertEquals(400f - 2f * CallLayoutPolicy.TRAY_MARGIN_STANDARD, CallLayoutPolicy.trayWidth(400f))
    }
    @org.junit.Test fun previewFitsShortLandscapeViewport() {
        val (width, height) = CallLayoutPolicy.localPreviewSize(700f, 72f)
        org.junit.Assert.assertTrue(height <= 72f)
        org.junit.Assert.assertEquals(CallLayoutPolicy.PREVIEW_ASPECT, height / width, 0.001f)
        val narrow = CallLayoutPolicy.localPreviewSize(40f, 200f)
        org.junit.Assert.assertTrue(narrow.first <= 40f)
    }
}
