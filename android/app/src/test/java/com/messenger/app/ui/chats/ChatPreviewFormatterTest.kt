package com.messenger.app.ui.chats

import com.messenger.app.domain.model.ChatPreviewFormatter
import com.messenger.app.domain.model.LastMessagePreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatPreviewFormatterTest {
    @Test fun `video preview hides generated filename`() {
        assertEquals(LastMessagePreview.Video, ChatPreviewFormatter.from("video", null, "9534E325-FA88.mp4"))
    }


    @Test
    fun `text message maps to text preview`() {
        assertEquals(LastMessagePreview.Text("See you tonight"), ChatPreviewFormatter.from("text", "See you tonight", null))
    }

    @Test
    fun `image message maps to photo preview`() {
        assertEquals(LastMessagePreview.Photo, ChatPreviewFormatter.from("image", "[Photo]", null))
    }

    @Test
    fun `voice message maps to voice preview`() {
        assertEquals(LastMessagePreview.Voice, ChatPreviewFormatter.from("voice", "[Voice message]", null))
    }

    @Test
    fun `file message maps to file preview with filename`() {
        assertEquals(LastMessagePreview.File("report.pdf"), ChatPreviewFormatter.from("file", "[File] report.pdf", "report.pdf"))
    }

    @Test
    fun `file message without filename maps to file preview null filename`() {
        assertEquals(LastMessagePreview.File(null), ChatPreviewFormatter.from("file", "[File] ", null))
    }

    @Test
    fun `deleted message maps to deleted preview`() {
        assertEquals(LastMessagePreview.Deleted, ChatPreviewFormatter.from("deleted", "", null))
    }

    @Test
    fun `blank text yields null fallback`() {
        assertNull(ChatPreviewFormatter.from("text", "   ", null))
    }

    @Test
    fun `null kind yields null fallback`() {
        assertNull(ChatPreviewFormatter.from(null, "anything", null))
    }

    @Test
    fun `empty kind yields null fallback`() {
        assertNull(ChatPreviewFormatter.from("", "anything", null))
    }

    @Test
    fun `unknown kind falls back to text content`() {
        assertEquals(LastMessagePreview.Text("hello"), ChatPreviewFormatter.from("mystery", "hello", null))
    }
}
