package com.messenger.app.data.attachment

import kotlinx.serialization.json.Json
import kotlin.test.*

class VideoNoteMetadataTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun olderVideosDefaultToRectangleAndNotesKeepFlagOnRoundTrip() {
        assertFalse(json.decodeFromString<VideoInfoDto>("{\"duration_ms\":1200}").note)
        val note = json.decodeFromString<VideoInfoDto>("{\"duration_ms\":1200,\"note\":true}")
        assertTrue(note.note)
        assertEquals(note, json.decodeFromString<VideoInfoDto>(json.encodeToString(VideoInfoDto.serializer(), note)))
    }
}
