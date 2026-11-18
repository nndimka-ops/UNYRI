package com.messenger.app.ui.call

import kotlin.test.*

class CallLandscapeLayoutPolicyTest {
    @Test fun landscape360And411KeepFourControlsAndShortSidePreview() {
        for ((height, avatar, preview) in listOf(Triple(360f, 115.2f, 100.8f), Triple(411f, 128f, 115.08f))) {
            val p = CallLayoutPolicy.landscape(800f, height)
            assertEquals(avatar, p.avatar, .01f)
            assertEquals(preview, p.previewWidth, .01f)
            assertEquals(16f, p.bottomGap)
            assertTrue(4 * CallLayoutPolicy.controlSize(800f, 4) + 3 * CallLayoutPolicy.controlSpacing(800f, 4) <= CallLayoutPolicy.trayWidth(800f))
            val size = CallLayoutPolicy.selfViewSize(800f, height, 104f, 1f, true)
            assertEquals(preview * CallLayoutPolicy.PREVIEW_ASPECT, size.first, .01f)
            assertEquals(preview, size.second, .01f)
            assertEquals(800f, CallLayoutPolicy.videoIdentityWidth(800f, height, true) + size.first +
                2 * CallLayoutPolicy.PREVIEW_MARGIN, .01f)
            assertEquals(800f, CallLayoutPolicy.videoIdentityWidth(800f, height, false))
            val origin = CallLayoutPolicy.selfViewOrigin(1000f, 0f, 800f, height, size.first, size.second, 104f, true)
            assertNotNull(origin)
            assertTrue(origin.first + size.first <= 784f)
            assertEquals(CallLayoutPolicy.PREVIEW_HEADER_CLEARANCE, origin.second)
        }
    }
    @Test fun tileShapeAndPinchScaleFollowOnlyScreenShape() {
        for ((w, h) in listOf(360f to 760f, 800f to 360f)) for (scale in listOf(.8f, 1f, 1.5f)) {
            val size = CallLayoutPolicy.selfViewSize(w, h, 144f, scale, w > h)
            assertEquals(w > h, size.first > size.second)
            for (rotation in listOf(0, 90, -90, 180)) {
                val inner = if (rotation % 180 != 0) size.second to size.first else size
                assertEquals(size.first * size.second, inner.first * inner.second, .01f)
                if (w < h && rotation % 180 != 0) assertTrue(inner.first > inner.second)
            }
        }
    }
}
