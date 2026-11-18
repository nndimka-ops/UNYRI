package com.messenger.app.data.attachment

import kotlin.test.Test
import kotlin.test.assertEquals

class FilePresentationTest {
    @Test fun `file types retain a readable fallback`() {
        for ((name, expected) in listOf("report.PDF" to "PDF", "report.docx" to "DOC", "table.csv" to "XLS", "slides.pptx" to "PPT", "backup.7z" to "ZIP", "data.xyz" to "XYZ", "noextension" to "FILE", "file.verylongextension" to "FILE")) {
            assertEquals(expected, FilePresentation.label("application/octet-stream", name))
        }
        assertEquals("VIDEO", FilePresentation.label("VIDEO/MP4", null))
        assertEquals("AUDIO", FilePresentation.label("audio/mp4", null))
        assertEquals("FILE", FilePresentation.label("", "test.<>"))
    }
    @Test fun `duration handles absent negative and long media`() {
        assertEquals("", FilePresentation.duration(null))
        assertEquals("0:00", FilePresentation.duration(-1))
        assertEquals("1:05", FilePresentation.duration(65_999))
        assertEquals("1:01:05", FilePresentation.duration(3_665_000))
    }
    @Test fun `video metadata survives roundtrip and legacy has no duration`() {
        val legacy = """{"kind":"attachment","attachment":{"attachment_id":"video","media_type":"file","object_key":"attachments/test","size":12,"encrypted_size":28,"mime_type":"video/mp4","encryption":{"version":2,"sha256":"test"}}}"""
        val old = MessageContentCodec.decode(legacy.toByteArray()).attachment!!
        assertEquals(null, old.video)
        val video = old.copy(video = VideoInfoDto(65000), image = ImageInfoDto(144, 256), thumbnail = "jpeg")
        val decoded = MessageContentCodec.decode(MessageContentCodec.encodeAttachment(video)).attachment
        assertEquals(video, decoded)
        assertEquals(null, decoded?.voice)
    }
}
