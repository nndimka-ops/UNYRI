package com.messenger.app.ui.chats

/** Main-thread, chat-screen scoped gate. Failed requests remain retryable. */
internal class OlderHistoryLoader {
    private var loading = false
    private var exhausted = false

    suspend fun load(request: suspend () -> Result<Boolean>) {
        if (loading || exhausted) return
        loading = true
        try {
            if (request().getOrNull() == false) exhausted = true
        } finally {
            loading = false
        }
    }
}
