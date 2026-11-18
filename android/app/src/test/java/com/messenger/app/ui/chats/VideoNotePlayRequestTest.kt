package com.messenger.app.ui.chats
import kotlin.test.*
class VideoNotePlayRequestTest {
    private val quiet = NoteAudioSnapshot(0, 0, 0)
    @Test fun downloadTapPlaysExactlyOnceWhenTheLocalFileArrives() {
        val intent = VideoNotePlayRequest(); intent.request("note", quiet)
        assertFalse(intent.consume("note", false, false, true, false, false, quiet)); assertTrue(intent.pending)
        assertTrue(intent.consume("note", true, false, true, false, false, quiet)); assertFalse(intent.pending)
        assertFalse(intent.consume("note", true, false, true, false, false, quiet))
    }
    @Test fun hiddenFailedBusyRecycledOrCompetingNotesNeverAutoplay() {
        val cases = listOf("hidden", "failed", "call", "voice", "recycled")
        for (case in cases) {
            val intent = VideoNotePlayRequest(); intent.request("note", quiet)
            assertFalse(intent.consume(if (case == "recycled") "other" else "note", true, case == "failed", case != "hidden", case == "call", case == "voice", quiet))
            assertFalse(intent.pending, case)
        }
    }
    @Test fun competingAudioThatStartedAndEndedStillInvalidatesTheRequest() {
        for (snapshot in listOf(NoteAudioSnapshot(1,0,0), NoteAudioSnapshot(0,1,0), NoteAudioSnapshot(0,0,1))) {
            val intent = VideoNotePlayRequest(); intent.request("note", quiet)
            assertFalse(intent.consume("note", true, false, true, false, false, snapshot)); assertFalse(intent.pending)
        }
    }
}
