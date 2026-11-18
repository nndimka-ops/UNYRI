package com.messenger.app.ui.chats

internal data class NoteAudioSnapshot(val note: Long, val voice: Long, val capture: Long)
internal class VideoNotePlayRequest {
    private var request: Pair<String, NoteAudioSnapshot>? = null
    fun request(id: String, snapshot: NoteAudioSnapshot) { request = id to snapshot }
    val pending: Boolean get() = request != null
    fun cancel() { request = null }
    fun consume(id: String, local: Boolean, failed: Boolean, visible: Boolean, busy: Boolean,
        competing: Boolean, snapshot: NoteAudioSnapshot): Boolean {
        val pending = request ?: return false
        if (pending.first != id || failed || !visible || busy || competing || pending.second != snapshot) { cancel(); return false }
        if (!local) return false
        cancel(); return true
    }
}
