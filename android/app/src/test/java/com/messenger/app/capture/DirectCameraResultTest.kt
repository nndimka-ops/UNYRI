package com.messenger.app.capture

import kotlinx.serialization.json.Json
import kotlin.test.*

class DirectCameraResultTest {
    private val owner = CaptureOwner("alice", "https://coco.example", "phone")
    private val capture = PendingExternalCapture("capture-id", owner, "content://app.fileprovider/camera/capture.jpg", false,
        launched = true, directSource = "/private/cache/camera/capture.jpg", destination = "chat-a",
        stage = "review", outputVideo = true, confirmed = true)

    @Test fun legacyExternalRecordsDecodeWithoutChangingTheirResultContract() {
        val legacy = """{"id":"old","owner":{"userId":"alice","origin":"https://coco.example","deviceId":"phone"},"uri":"content://camera/photo.jpg","video":false,"launched":true}"""
        val restored = Json.decodeFromString(PendingExternalCapture.serializer(), legacy)
        assertTrue(restored.acceptsCompletedResult(owner))
        assertNull(restored.directSource)
        assertNull(restored.destination)
        assertFalse(restored.confirmed)
        assertFalse(restored.acceptsDirectResult(owner, "chat-a", true))
    }

    @Test fun modeAndExplicitConfirmationSurviveProcessSerialization() {
        val restored = Json.decodeFromString(PendingExternalCapture.serializer(), Json.encodeToString(PendingExternalCapture.serializer(), capture))
        assertEquals(capture, restored)
        assertTrue(restored.outputVideo)
        assertTrue(restored.acceptsDirectResult(owner, "chat-a", true))
    }

    @Test fun previewCaptureAndUnconfirmedReviewCanNeverSend() {
        for (stage in listOf("preview", "capturing", "review")) {
            assertFalse(capture.copy(stage = stage, confirmed = false).acceptsDirectResult(owner, "chat-a", true))
        }
        assertFalse(capture.copy(stage = "capturing").acceptsDirectResult(owner, "chat-a", true))
        assertFalse(capture.copy(launched = false).acceptsDirectResult(owner, "chat-a", true))
    }

    @Test fun retiredOrDifferentOwnerServerDeviceAndChatRejectCapturedBytes() {
        assertFalse(capture.acceptsDirectResult(owner, "chat-a", false))
        assertFalse(capture.acceptsDirectResult(null, "chat-a", true))
        assertFalse(capture.acceptsDirectResult(owner.copy(userId = "bob"), "chat-a", true))
        assertFalse(capture.acceptsDirectResult(owner.copy(origin = "https://other.example"), "chat-a", true))
        assertFalse(capture.acceptsDirectResult(owner.copy(deviceId = "other"), "chat-a", true))
        assertFalse(capture.acceptsDirectResult(owner, "chat-b", true))
    }

    @Test fun incomingCallBlocksHardwareButDoesNotRetirePreviouslyConfirmedResult() {
        val coordinator = NonCallCaptureCoordinator({ CaptureSession(owner, 1) }, { true })
        assertNull(coordinator.acquire(NonCallCaptureKind.CAMERA, CaptureSession(owner, 1)) {})
        assertTrue(capture.acceptsDirectResult(owner, "chat-a", true))
    }
}
