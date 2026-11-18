package com.messenger.app.ui.chats

internal data class VideoSeekRequest(val position: Long, val exact: Boolean = true, val resume: Boolean = false)
internal enum class VideoSeekResult { LANDED, TIMED_OUT, FAILED }
internal interface VideoSeekBackend { fun seek(request: VideoSeekRequest, completed: (VideoSeekResult) -> Unit); fun cancel() }

/** One native seek in flight. A timeout frees the chaser but never authorizes playback resume. */
internal class VideoSeekSession(private val backend: VideoSeekBackend, private val resume: () -> Unit) {
    private var generation = 0L
    private var flight: VideoSeekRequest? = null
    private var newest: VideoSeekRequest? = null
    fun offer(request: VideoSeekRequest) {
        if (flight != null) { newest = request; return }
        issue(request)
    }
    private fun issue(request: VideoSeekRequest) {
        flight = request
        val expected = ++generation
        backend.seek(request) { result ->
            if (expected == generation && flight === request) {
                flight = null
                val next = newest; newest = null
                if (result != VideoSeekResult.FAILED && next != null) issue(next)
                else if (result == VideoSeekResult.LANDED && request.resume) resume()
            }
        }
    }
    fun cancel() { generation++; flight = null; newest = null; backend.cancel() }
}
