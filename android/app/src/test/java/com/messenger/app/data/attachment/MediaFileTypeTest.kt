package com.messenger.app.data.attachment
import kotlin.test.*
class MediaFileTypeTest {
    @Test fun `signatures override misleading names`() {
        assertEquals("image/png", MediaFileType.detect(byteArrayOf(137.toByte(),80,78,71,13,10,26,10),"image/jpeg"))
        assertEquals("image/jpeg",MediaFileType.detect(byteArrayOf(255.toByte(),216.toByte(),255.toByte()),"application/pdf"))
        assertEquals("image/gif",MediaFileType.detect("GIF89a".toByteArray(),"image/png"))
        assertEquals("application/pdf",MediaFileType.detect("%PDF-1.7".toByteArray(),"image/jpeg"))
        assertEquals("image/webp",MediaFileType.detect("RIFF0000WEBP".toByteArray(),"text/plain"))
    }
    @Test fun `container distinguishes HEIC from MP4 and audio`() {
        for ((brand,mime) in listOf("heic" to "image/heic","avif" to "image/avif","M4A " to "audio/mp4","qt  " to "video/quicktime","isom" to "video/mp4")) {
            assertEquals(mime,MediaFileType.detect(("0000ftyp"+brand).toByteArray(),"application/octet-stream"))
        }
    }
    @Test fun `truncated and unknown input does not crash or invent format`() {
        for (n in 0..11) assertEquals("application/octet-stream",MediaFileType.detect(ByteArray(n),"application/octet-stream"))
        assertEquals("text/plain",MediaFileType.detect("notes".toByteArray(),"text/plain"))
    }
}
