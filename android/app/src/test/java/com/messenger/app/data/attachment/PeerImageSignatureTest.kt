package com.messenger.app.data.attachment
import kotlin.test.*
class PeerImageSignatureTest {
    @Test fun supportedAndRejectedSignatures() {
        for (bytes in listOf(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()),
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10), "GIF87a".toByteArray(), "GIF89a".toByteArray(),
            "RIFF1234WEBP".toByteArray())) assertTrue(PeerImageSignature.allowed(bytes))
        for (bytes in listOf(byteArrayOf(), "GIF89".toByteArray(), "II*".toByteArray(), "MM".toByteArray(),
            "%PDF-1.7".toByteArray(), "8BPS".toByteArray(), "RIFF1234WAVE".toByteArray(), "<svg/>".toByteArray(),
            "0000ftypavif".toByteArray(), "0000ftypheic".toByteArray(), byteArrayOf(0xff.toByte(), 0xd8.toByte())))
            assertFalse(PeerImageSignature.allowed(bytes))
    }
    @Test fun offsetAndTruncation() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
        for (n in 0 until png.size) assertFalse(PeerImageSignature.allowed(png, 0, n))
        assertTrue(PeerImageSignature.allowed(byteArrayOf(0) + png, 1, png.size))
        assertFalse(PeerImageSignature.allowed(png, -1, 3)); assertFalse(PeerImageSignature.allowed(png, Int.MAX_VALUE, 3))
        assertFalse(PeerImageSignature.allowed(png, 0, Int.MAX_VALUE))
    }
}
