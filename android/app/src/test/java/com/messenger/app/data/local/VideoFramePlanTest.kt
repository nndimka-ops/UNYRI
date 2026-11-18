package com.messenger.app.data.local
import kotlin.test.*
class VideoFramePlanTest {
    @Test fun api26ReadbackOf4kPortraitAndLandscapeIsAlwaysBounded() {
        for (rotation in listOf(0, 90, 180, 270)) for (edge in listOf(480, 768)) {
            val plan = VideoFramePlan.create(3840, 2160, rotation, edge, 500_000)
            assertEquals(edge, maxOf(plan.width, plan.height)); assertTrue(plan.rgbaBytes <= edge * edge * 4)
            assertEquals(rotation == 90 || rotation == 270, plan.height > plan.width)
            assertEquals(500_000L, plan.targetUs)
        }
    }
    @Test fun aCancelledApi26RequestNeverOpensAFileOrCreatesNativeDecoders() {
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            Api26VideoFrame.read(java.io.File("never-open-this-video"), 768, 0) { throw kotlinx.coroutines.CancellationException("row hidden") }
        }
    }
    @Test fun smallFramesNeverUpscaleAndInvalidDimensionsDoNotAllocate() {
        assertEquals(VideoFramePlan(80, 40, 0, 0), VideoFramePlan.create(80,40,0,768,-1))
        assertFailsWith<IllegalArgumentException> { VideoFramePlan.create(0,1,0,768,0) }
        assertFailsWith<IllegalArgumentException> { VideoFramePlan.create(50_000,50_000,0,768,0) }
        assertFailsWith<IllegalArgumentException> { VideoFramePlan.create(80,40,45,768,0) }
    }
}
