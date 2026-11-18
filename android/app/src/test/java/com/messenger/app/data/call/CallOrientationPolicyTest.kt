package com.messenger.app.data.call

import kotlin.test.*
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.VideoFrame

class CallOrientationPolicyTest {
    @Test fun oneGravityVectorSuppliesBothAngleAndFlatRejection() {
        assertEquals(0, CallOrientationPolicy.physicalFromGravity(0f, 9.81f, 0f))
        assertEquals(90, CallOrientationPolicy.physicalFromGravity(9.81f, 0f, 0f))
        assertEquals(180, CallOrientationPolicy.physicalFromGravity(0f, -9.81f, 0f))
        assertEquals(270, CallOrientationPolicy.physicalFromGravity(-9.81f, 0f, 0f))
        assertEquals(90, CallOrientationPolicy.physicalFromGravity(6f, 0f, 7.8f))
        assertEquals(-1, CallOrientationPolicy.physicalFromGravity(0f, 0f, 0f))
        assertEquals(-1, CallOrientationPolicy.physicalFromGravity(0f, 0f, 9.81f))
        assertEquals(-1, CallOrientationPolicy.physicalFromGravity(0f, 0f, -9.81f))
    }
    @Test fun fullScreenAutoRotateAllowsSlowSystemTurnBeforePhysicalFallback() {
        assertEquals(0, CallOrientationPolicy.tag(0, 90, 1_499, preferScreen = true))
        assertEquals(90, CallOrientationPolicy.tag(0, 90, 1_500, preferScreen = true))
        assertEquals(90, CallOrientationPolicy.tag(0, 90, 600, preferScreen = false))
        assertEquals(270, CallOrientationPolicy.tag(270, 90, 0, preferScreen = true))
    }
    @Test fun selfViewNeverCombinesNewDisplayAndOldFrameTag() {
        assertEquals(0, CallFrameOrientation(0, 0).selfViewDelta(90, 0))
        assertEquals(90, CallFrameOrientation(0, 90).selfViewDelta(90, 90))
        assertEquals(0, CallFrameOrientation(90, 90).selfViewDelta(90, 90))
        assertEquals(-90, CallFrameOrientation(0, 270).selfViewDelta(0, 0))
    }
    @Test fun previewCenterCropsBothShapesIncludingSwappedCompensationBounds() {
        for ((width, height) in listOf(400 to 300, 300 to 400, 128 to 172, 172 to 128)) {
            for (aspect in listOf(16f / 9f, 9f / 16f, 4f / 3f, 3f / 4f)) {
                for (delta in listOf(0, 90, 180, 270)) {
                    val innerWidth = if (delta % 180 == 0) width else height
                    val innerHeight = if (delta % 180 == 0) height else width
                    val (x, y) = CallOrientationPolicy.previewCrop(innerWidth, innerHeight, aspect)
                    assertTrue(x >= 1f && y >= 1f)
                    assertTrue(x == 1f || y == 1f)
                    assertEquals(aspect, innerWidth.toFloat() / innerHeight * x / y, .00001f)
                }
            }
        }
        assertEquals(1f to 1f, CallOrientationPolicy.previewCrop(400, 300, 4f / 3f))
        for (aspect in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(1f to 1f, CallOrientationPolicy.previewCrop(400, 300, aspect))
        assertEquals(1f to 1f, CallOrientationPolicy.previewCrop(0, 300, 1f))
    }
    @Test fun cameraPauseInterruptsPendingTurnAndRetainsLastStableOrientation() {
        val p = CallOrientationHysteresis()
        p.update(0, 0f, 100)
        p.update(90, 0f, 200)
        p.interrupt()
        assertEquals(0, p.update(90, 0f, 1_000))
        assertEquals(100L, p.stableSinceMs)
        assertEquals(0, p.update(90, 0f, 1_249))
        assertEquals(90, p.update(90, 0f, 1_250))
        p.interrupt()
        assertEquals(90, p.update(0, 1f, 2_000))
        assertEquals(1_250L, p.stableSinceMs)
    }

    @Test fun stableInitialAxisStartsTagDwellWithoutAnotherAngleChange() {
        val p = CallOrientationHysteresis(270)
        assertEquals(Long.MAX_VALUE, p.stableSinceMs)
        assertEquals(270, p.update(270, 0f, 100))
        assertEquals(100L, p.stableSinceMs)
        assertEquals(270, p.update(270, 0f, 700))
        assertEquals(100L, p.stableSinceMs)
        assertEquals(0, CallOrientationPolicy.tag(0, p.orientation, 599))
        assertEquals(270, CallOrientationPolicy.tag(0, p.orientation, 600))
    }

    @Test fun bundledCamera2RotationTablesForBothSensorMountsAndCameras() {
        val devices = listOf(0, 90, 180, 270)
        assertEquals(listOf(90, 180, 270, 0), devices.map { CallOrientationPolicy.frameRotation(90, true, it) })
        assertEquals(listOf(90, 0, 270, 180), devices.map { CallOrientationPolicy.frameRotation(90, false, it) })
        assertEquals(listOf(270, 0, 90, 180), devices.map { CallOrientationPolicy.frameRotation(270, true, it) })
        assertEquals(listOf(270, 180, 90, 0), devices.map { CallOrientationPolicy.frameRotation(270, false, it) })
        assertEquals(listOf(0, 270, 180, 90), devices.map(CallOrientationPolicy::physicalDegrees))
    }

    @Test fun sixtyDegreeThresholdAndContinuousQuarterSecondDwell() {
        val p = CallOrientationHysteresis()
        assertEquals(0, p.update(59, 0f, 0))
        assertEquals(0, p.update(60, 0f, 100))
        assertEquals(0, p.update(90, 0f, 349))
        assertEquals(90, p.update(90, 0f, 350))
        assertEquals(350L, p.stableSinceMs)
        assertEquals(90, p.update(31, 0f, 600)) // only 59 degrees from current axis
        p.update(30, 0f, 700)
        p.update(40, 0f, 900) // interrupts dwell
        assertEquals(90, p.update(30, 0f, 1000))
        assertEquals(0, p.update(0, 0f, 1250))
    }

    @Test fun flatFaceUpDownAndUnknownKeepLastAndInterruptDwell() {
        val p = CallOrientationHysteresis(270)
        p.update(0, 0f, 0)
        assertEquals(270, p.update(0, .86f, 500))
        assertEquals(270, p.update(0, -.99f, 1000))
        assertEquals(270, p.update(-1, 0f, 1500))
        assertEquals(270, p.update(0, 0f, 2000))
        assertEquals(0, p.update(0, .85f, 2250))
        assertEquals(0, p.update(359, 0f, 2500))
    }

    @Test fun screenLandscapeWinsOtherwisePhysicalWaitsSixTenths() {
        assertEquals(90, CallOrientationPolicy.tag(90, 270, 0))
        assertEquals(270, CallOrientationPolicy.tag(270, 90, 900))
        assertEquals(0, CallOrientationPolicy.tag(0, 90, 599))
        assertEquals(90, CallOrientationPolicy.tag(0, 90, 600))
        assertEquals(180, CallOrientationPolicy.tag(0, 180, 600))
    }

    @Test fun selfViewDeltaAndAutomaticFitFillUseRotatedGeometry() {
        for (screen in listOf(0, 90, 180, 270)) for (tag in listOf(0, 90, 180, 270)) {
            val delta = CallOrientationPolicy.selfViewDelta(screen, tag)
            assertEquals(tag, CallOrientationPolicy.normalize(screen + delta))
            assertTrue(delta in -90..180)
        }
        assertEquals(-90, CallOrientationPolicy.selfViewDelta(0, 270))
        assertTrue(CallOrientationPolicy.remoteFills(720, 1280, 360f, 800f))
        assertTrue(CallOrientationPolicy.remoteFills(1280, 720, 800f, 360f))
        assertFalse(CallOrientationPolicy.remoteFills(1280, 720, 360f, 800f))
        assertFalse(CallOrientationPolicy.remoteFills(720, 1280, 800f, 360f))
        assertTrue(CallOrientationPolicy.remoteFills(0, 0, 360f, 800f))
    }

    @Test fun observerBorrowsInputAndBalancesRetainEvenWhenDelegateThrows() {
        var references = 1
        val buffer = object : VideoFrame.Buffer {
            override fun getWidth() = 640
            override fun getHeight() = 480
            override fun retain() { references++ }
            override fun release() { references-- }
            override fun toI420(): VideoFrame.I420Buffer = error("No conversion")
            override fun cropAndScale(x: Int, y: Int, w: Int, h: Int, sw: Int, sh: Int): VideoFrame.Buffer = error("No crop")
        }
        val input = VideoFrame(buffer, 90, 123L)
        val observer = CallRotationCapturerObserver(object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) = Unit
            override fun onCapturerStopped() = Unit
            override fun onFrameCaptured(frame: VideoFrame) {
                assertSame(buffer, frame.buffer)
                assertEquals(270, frame.rotation)
                assertEquals(123L, frame.timestampNs)
                assertEquals(2, references)
                throw IllegalStateException("delegate")
            }
        }, { 270 }, {})
        assertFailsWith<IllegalStateException> { observer.onFrameCaptured(input) }
        assertEquals(1, references)
        input.release()
        assertEquals(0, references)
    }
}
