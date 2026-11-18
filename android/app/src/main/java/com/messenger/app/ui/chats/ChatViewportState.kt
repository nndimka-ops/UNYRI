package com.messenger.app.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Position and initial-placement ownership are one saved unit. Hydration is process-local:
 * do not let an early calls-only layout clamp a restored index before messages arrive. */
internal class ChatViewportState(index: Int, offset: Int, placed: Boolean = false) {
    val listState = LazyListState(index, offset)
    var initialPlaced by mutableStateOf(placed)
    var layoutReady by mutableStateOf(false)
        private set
    private var presentedHydratedTimeline by mutableStateOf(false)

    fun canPresent(dataReady: Boolean, frozen: Boolean): Boolean = dataReady && (!frozen || presentedHydratedTimeline)
    fun hydrated(dataReady: Boolean, frozen: Boolean) {
        if (dataReady && !frozen) presentedHydratedTimeline = true
    }

    fun placed() { initialPlaced = true; layoutReady = true }

    companion object {
        val Saver = listSaver<ChatViewportState, Any>(
            save = { listOf(it.listState.firstVisibleItemIndex, it.listState.firstVisibleItemScrollOffset, it.initialPlaced) },
            restore = { ChatViewportState(it[0] as Int, it[1] as Int, it.getOrNull(2) as? Boolean ?: true) },
        )
    }
}

@Composable
internal fun rememberChatViewportState(chatId: String, index: Int, offset: Int): ChatViewportState =
    rememberSaveable(chatId, saver = ChatViewportState.Saver) { ChatViewportState(index, offset) }

internal suspend fun placeInitialChatViewport(state: ChatViewportState, keys: List<String>,
    bookmark: ChatScrollBookmark?, hasNotificationTarget: Boolean) {
    if (state.layoutReady) return
    if (keys.isNotEmpty()) {
        awaitCurrentTimelineLayout(state.listState, keys)
        if (!state.initialPlaced && !hasNotificationTarget) {
            val position = bookmark?.resolve(keys) ?: (keys.lastIndex to 0)
            state.listState.scrollToItem(position.first, position.second)
        }
    }
    state.placed()
}
