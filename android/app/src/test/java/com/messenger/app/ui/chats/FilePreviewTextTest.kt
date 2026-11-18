package com.messenger.app.ui.chats

import com.messenger.app.domain.model.ChatPreviewFormatter
import com.messenger.app.domain.model.FilePreviewText
import com.messenger.app.domain.model.LastMessagePreview
import com.messenger.app.data.search.SearchQueryBuilder
import com.messenger.app.ui.search.auxiliarySearchHighlight
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class FilePreviewTextTest {
    @Test fun `offers and banners use localized filename plus caption including legacy fallback`() {
        val offer = com.messenger.app.data.directtransfer.DirectPreviewFixture.offer()
        for (directory in listOf("values-ru", "values", "values-uk")) {
            val label = label(directory)
            for (fallback in listOf("Direct file transfer", "Передача файла напрямую")) {
                for (caption in listOf("", "Подпись Caption Підпис 👋")) {
                    val raw = fallback + if (caption.isEmpty()) "" else "\n\n$caption"
                    val expected = "$label: b023-report.pdf" + if (caption.isEmpty()) "" else "\n\n$caption"
                    val preview = ChatPreviewFormatter.from("text", raw, null, offer.stored()) as LastMessagePreview.File
                    val banner = com.messenger.app.push.ForegroundMessageBannerPolicy.previewFor(
                        "content", "delivered", false, raw, null, null, directFileOfferJson = offer.stored()) as LastMessagePreview.File
                    assertEquals(preview, banner)
                    assertEquals(expected, FilePreviewText.file(label, preview.filename, preview.caption))
                    assertEquals(expected, FilePreviewText.display(FilePreviewText.project(raw, offer.stored()), label))
                    assertEquals(expected, FilePreviewText.message(com.messenger.app.domain.model.Message(
                        "id", "server", "chat", offer.senderUserId, raw, 1, "date", com.messenger.app.domain.model.MessageStatus.DELIVERED,
                        filePreview = FilePreviewText.direct(raw, offer.stored())), label))
                }
            }
        }
    }

    @Test fun `missing and undecodable offers show only the label and never either wire fallback`() {
        for (directory in listOf("values-ru", "values", "values-uk")) {
            val label = label(directory)
            for (raw in listOf("Direct file transfer", "Передача файла напрямую", "Direct file transfer\n\ncaption", "Передача файла напрямую\n\ncaption")) {
                for (json in listOf(null, "{broken", "{}")) {
                    val preview = ChatPreviewFormatter.from("text", raw, null, json) as LastMessagePreview.File
                    assertEquals(label, FilePreviewText.file(label, preview.filename, preview.caption))
                    val banner = com.messenger.app.push.ForegroundMessageBannerPolicy.previewFor("content", "delivered", false, raw, null, null, directFileOfferJson = json)
                    assertEquals(preview, banner)
                    assertEquals(label, FilePreviewText.display(FilePreviewText.project(raw, json), label))
                }
            }
        }
    }
    private fun label(directory: String): String {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$directory/strings.xml"))
        val strings = xml.getElementsByTagName("string")
        return (0 until strings.length).map { strings.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == "file_preview" }.textContent
    }

    @Test fun `search snippet and chat preview share real ru en uk file labels`() {
        for ((directory, expectedLabel) in mapOf("values-ru" to "Файл", "values" to "File", "values-uk" to "Файл")) {
            val label = label(directory)
            assertEquals(expectedLabel, label)
            val preview = ChatPreviewFormatter.from("text", "[File] report.pdf", null) as LastMessagePreview.File
            val expected = "$expectedLabel: report.pdf"
            val display = FilePreviewText.display("[File] report.pdf", label)
            assertEquals(expected, display)
            assertEquals(expected, FilePreviewText.file(label, preview.filename))
            assertEquals(preview, ChatPreviewFormatter.from("file", "[File] report.pdf", null))
            assertEquals(expected, SearchQueryBuilder.snippet(display, "report"))
            assertEquals(expected, auxiliarySearchHighlight(display, "report").text)
            assertEquals(label, FilePreviewText.display("[File] ", label))
        }
    }

    @Test fun `all existing locales have a file label and never expose the stored marker`() {
        File("src/main/res").listFiles()!!.filter { it.name == "values" || it.name.startsWith("values-") }
            .filter { File(it, "strings.xml").isFile }.forEach { directory ->
                val localized = label(directory.name)
                assertTrue(localized.isNotBlank())
                assertEquals("$localized: photo.heic", FilePreviewText.display("[File] photo.heic", localized))
            }
    }

    @Test fun `ordinary text and filenames containing marker are preserved`() {
        assertEquals("send [File] report.pdf", FilePreviewText.display("send [File] report.pdf", "File"))
        assertEquals("File: [File].pdf", FilePreviewText.display("[File] [File].pdf", "File"))
        assertEquals("File", FilePreviewText.display("[File]", "File"))
        assertNull(FilePreviewText.storedFilename("[File]suffix"))
    }
}
