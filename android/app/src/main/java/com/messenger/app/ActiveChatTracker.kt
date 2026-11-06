package com.messenger.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tracks the chat currently open in the UI so the notification policy can
 *  suppress a local/FCM notification for the exact chat the user is looking
 *  at (or was just looking at before backgrounding the app). Kept as a tiny
 *  app-scoped holder rather than threading a callback through the whole
 *  navigation graph; the chat screen owns set/clear via composition lifecycle.
 */
object ActiveChatTracker {
    private val _activeChatId = MutableStateFlow<String?>(null)
    val activeChatId: StateFlow<String?> = _activeChatId
    val headerBottom = MutableStateFlow(0f)
    fun updateHeader(chatId: String, bottom: Float) { if (_activeChatId.value == chatId) headerBottom.value = bottom }

    fun onChatOpened(chatId: String) {
        _activeChatId.value = chatId
    }

    fun onChatClosed(chatId: String) {
        // Only clear if the closed chat is still the one being tracked - a
        // newer screen must not be wiped by an older one disposing.
        if (_activeChatId.value == chatId) {
            _activeChatId.value = null
            headerBottom.value = 0f
        }
    }
}
