package com.messenger.app.data.directtransfer

import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DirectFileMediaPreviewTest {
    private val expires = System.currentTimeMillis() / 1000 + 3600
    // Independent baseline/progressive JPEG fixtures generated with JDK ImageIO, not the Android encoder.
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/direct-previews/$name")).use { it.readBytes() }
    private fun jpeg(width: Int = 40, height: Int = 20): ByteArray = fixture("${width}x${height}.jpg")
    private fun preview(bytes: ByteArray = jpeg(), width: Int = 40, height: Int = 20, kind: String = "image", duration: Long? = null) =
        DirectFileMediaPreview(kind, Base64.getEncoder().encodeToString(bytes), width, height, duration)
    private fun offer(value: DirectFileMediaPreview? = null, mime: String = "image/jpeg") = DirectFileOffer(
        transferId = "00000000-0000-0000-0000-000000000001", offerClientMessageId = "00000000-0000-0000-0000-000000000002",
        senderUserId = "00000000-0000-0000-0000-000000000003", senderDeviceId = "00000000-0000-0000-0000-000000000004",
        recipientUserId = "00000000-0000-0000-0000-000000000005", offerNonce = Base64.getEncoder().encodeToString(ByteArray(32)),
        filename = "photo.jpg", mimeType = mime, size = 123, sha256 = "ab".repeat(32),
        expiresAt = expires, preview = value,
    )

    @Test fun validJpegRoundTripsWithExactIosWireNames() {
        val poster = preview(kind = "video", duration = 1234)
        val value = offer(poster, "video/mp4")
        assertTrue(poster.valid)
        assertEquals(40 to 20, DirectFileMediaPreview.jpegDimensions(poster.jpegData()!!))
        val wire = value.json().jsonObject
        assertEquals(setOf("kind", "thumbnail", "width", "height", "duration_ms"), wire.getValue("preview").jsonObject.keys)
        assertEquals(value, DirectFileOffer.decode(wire))
        assertEquals(value, DirectFileOffer.decodeStored(value.stored()))
        assertEquals(poster, value.safeMediaPreview)
        assertFalse(offer().json().jsonObject.containsKey("preview"))
    }

    @Test fun peerPngGifWebpAndRandomDataNeverBecomePictures() {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6p8AAAAASUVORK5CYII=")
        for (bytes in listOf(png, "GIF89a".toByteArray(), "RIFFxxxxWEBP".toByteArray(), ByteArray(200), byteArrayOf(-1, -40, -1, -39))) {
            assertFalse(preview(bytes).valid)
            val wire = JsonObject(offer().json().jsonObject + ("preview" to DirectFileOffer.codec.encodeToJsonElement(preview(bytes))))
            assertEquals(offer(), DirectFileOffer.decode(wire))
        }
    }

    @Test fun malformedOptionalFieldsDoNotDiscardOfferOrDurableRecord() {
        val malformed = listOf(
            JsonNull, JsonPrimitive("not an object"), JsonArray(emptyList()), buildJsonObject { put("kind", "image") },
            DirectFileOffer.codec.encodeToJsonElement(preview()).jsonObject.let { JsonObject(it + ("width" to JsonPrimitive("huge"))) },
            DirectFileOffer.codec.encodeToJsonElement(preview()).jsonObject.let { JsonObject(it + ("width" to JsonPrimitive("40"))) },
            DirectFileOffer.codec.encodeToJsonElement(preview(kind = "video")).jsonObject.let { JsonObject(it + ("duration_ms" to JsonPrimitive("100"))) },
            DirectFileOffer.codec.encodeToJsonElement(preview(kind = "unknown")),
            DirectFileOffer.codec.encodeToJsonElement(preview().copy(thumbnail = "%%%")),
        )
        for (bad in malformed) {
            val wire = JsonObject(offer().json().jsonObject + ("preview" to bad))
            assertEquals(offer(), DirectFileOffer.decode(wire))
            assertEquals(offer(), DirectFileOffer.decodeStored(wire.toString()))
            val record = DirectTransferRecord(offer(), "00000000-0000-0000-0000-000000000006", "receiver")
            val stored = DirectFileOffer.codec.encodeToJsonElement(record).jsonObject
            val decoded = DirectTransferCoordinator.decodeRecord(JsonObject(stored + ("offer" to wire)).toString())
            assertNotNull(decoded)
            assertNull(decoded!!.offer.preview)
        }
    }

    @Test fun base64MustBeCanonicalAndBounded() {
        val good = preview()
        for (text in listOf("", "%bad%", good.thumbnail + "\n", good.thumbnail.trimEnd('='), "A".repeat(DirectFileMediaPreview.MAX_BASE64_BYTES + 1))) {
            // The fixture ends in padding; unpadded input must fail canonical roundtrip.
            if (text != good.thumbnail) assertFalse(text.take(12), good.copy(thumbnail = text).valid)
        }
        assertFalse(preview(jpeg() + ByteArray(DirectFileMediaPreview.MAX_BYTES)).valid)
        val bytes = jpeg()
        val extra = DirectFileMediaPreview.MAX_BYTES - bytes.size
        val comment = ByteArray(extra).apply {
            this[0] = 255.toByte(); this[1] = 254.toByte()
            this[2] = ((extra - 2) / 256).toByte(); this[3] = ((extra - 2) % 256).toByte()
        }
        val exactCap = bytes.take(2).toByteArray() + comment + bytes.drop(2).toByteArray()
        assertEquals(16 * 1024, exactCap.size)
        assertTrue(preview(exactCap).valid)
    }

    @Test fun dimensionsAndDurationAreCheckedAgainstRealJpeg() {
        val good = preview()
        for (bad in listOf(good.copy(width = 0), good.copy(height = -1), good.copy(width = 513), good.copy(width = 41),
            good.copy(height = Int.MAX_VALUE), preview(jpeg(513, 20), 513, 20), good.copy(durationMs = 0),
            good.copy(kind = "video", durationMs = -1), good.copy(kind = "video", durationMs = DirectFileMediaPreview.MAX_DURATION_MS + 1))) assertFalse(bad.valid)
        assertTrue(good.copy(kind = "video", durationMs = 0).valid)
        assertTrue(good.copy(kind = "video", durationMs = DirectFileMediaPreview.MAX_DURATION_MS).valid)
        assertTrue(preview(jpeg(512, 1), 512, 1).valid)
        assertNull(offer(good, "application/pdf").safeMediaPreview)
        assertNull(offer(good, "video/mp4").safeMediaPreview)
    }

    @Test fun malformedJpegMarkersAndPrecisionAreRejectedBeforeNativeDecode() {
        val bytes = jpeg()
        assertNull(DirectFileMediaPreview.jpegDimensions(bytes.copyOf(bytes.size - 2)))
        assertNull(DirectFileMediaPreview.jpegDimensions(bytes.copyOf().apply { this[2] = 0 }))
        val sof = (2 until bytes.size - 1).first { bytes[it] == 0xff.toByte() && bytes[it + 1] == 0xc0.toByte() }
        for ((offset, replacement) in listOf(1 to 0xc3, 4 to 12, 9 to 4, 3 to 1)) {
            assertNull(DirectFileMediaPreview.jpegDimensions(bytes.copyOf().apply { this[sof + offset] = replacement.toByte() }))
        }
    }

    @Test fun decoderAdmitsOnlyOneJpegCodestreamEvenWhenOfferHeaderIsValid() {
        val bytes = jpeg()
        assertTrue(DirectFileMediaPreview.singleJpeg(bytes))
        val concatenated = bytes + bytes
        assertTrue(preview(concatenated).valid) // iOS validates headers separately from its single-image decode gate.
        assertFalse(DirectFileMediaPreview.singleJpeg(concatenated))
        assertFalse(DirectFileMediaPreview.singleJpeg(bytes.copyOf(bytes.size - 2)))
        val progressive = fixture("progressive.jpg")
        assertTrue(preview(progressive).valid)
        assertTrue(DirectFileMediaPreview.singleJpeg(progressive))
    }

    @Test fun generationSourceAndDimensionBudgetsHaveInclusiveBoundaries() {
        assertTrue(DirectPreviewBounds.imageAllowed(256L * 1024 * 1024, 8000, 8000))
        assertTrue(DirectPreviewBounds.imageAllowed(1, 100_000, 1))
        for ((bytes, w, h) in listOf(Triple(0L, 1, 1), Triple(256L * 1024 * 1024 + 1, 1, 1), Triple(1L, 100_001, 1),
            Triple(1L, 8001, 8000), Triple(1L, 0, 1), Triple(1L, Int.MAX_VALUE, Int.MAX_VALUE))) assertFalse(DirectPreviewBounds.imageAllowed(bytes, w, h))
        assertEquals(512 to 256, DirectPreviewBounds.fit(8000, 4000, 512))
        assertEquals(1 to 512, DirectPreviewBounds.fit(1, 100_000, 512))
        assertEquals(20 to 10, DirectPreviewBounds.fit(20, 10, 512))
    }

    @Test fun generationTriesIosEdgesAndQualitiesAndNeverReturnsOversize() {
        val attempts = mutableListOf<Triple<Int, Int, Int>>()
        val output = DirectPreviewBounds.encode(2000, 1000) { w, h, quality ->
            attempts += Triple(w, h, quality)
            if (w == 128 && quality == 55) jpeg(w, h) else ByteArray(16 * 1024 + 1)
        }
        assertNotNull(output)
        assertTrue(output!!.size <= 16 * 1024)
        assertEquals(128 to 64, DirectFileMediaPreview.jpegDimensions(output))
        assertEquals(listOf(512, 384, 256, 128).flatMap { edge -> listOf(82, 70, 55).map { Triple(edge, edge / 2, it) } }, attempts)
        assertNull(DirectPreviewBounds.encode(20, 10) { _, _, _ -> ByteArray(16 * 1024 + 1) })
        assertNull(DirectPreviewBounds.encode(20, 10) { _, _, _ -> "not JPEG".toByteArray() })
    }

    @Test fun cardMappingRequiresCheckedCompletionAndFallsBackToValidOffer() {
        val image = offer(preview())
        assertEquals(DirectFilePosterSource.OFFER, directFilePosterSource(image, false, false))
        assertEquals(DirectFilePosterSource.OFFER, directFilePosterSource(image, false, true))
        assertEquals(DirectFilePosterSource.COMPLETED, directFilePosterSource(image, true, true))
        assertEquals(DirectFilePosterSource.OFFER, directFilePosterSource(image, true, false))
        assertEquals(DirectFilePosterSource.NONE, directFilePosterSource(offer(), false, false))
        assertEquals(DirectFilePosterSource.COMPLETED, directFilePosterSource(offer(), true, true))
        assertEquals(DirectFilePosterSource.NONE, directFilePosterSource(offer(preview().copy(thumbnail = "bad")), false, false))
        assertEquals(DirectFilePosterSource.NONE, directFilePosterSource(offer(preview(), "application/pdf"), true, true))
        assertEquals(DirectFilePosterSource.OFFER, directFilePosterSource(offer(preview(kind = "video", duration = 1000), "video/mp4"), false, false))
    }
}
