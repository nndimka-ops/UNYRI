package com.messenger.app.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first

internal suspend fun scrollToNotificationTarget(state: LazyListState, keys: List<String>, index: Int, headerHeight: Int) {
    val key = keys[index]
    awaitCurrentTimelineLayout(state, keys)
    state.scrollToItem(index, -(state.layoutInfo.viewportSize.height / 2 - headerHeight).coerceAtLeast(0))
    // Completion owns the visible row, rather than a frame tick against an older/clamped dataset.
    snapshotFlow { state.layoutInfo.visibleItemsInfo.any { it.key == key } }.first { it }
}

internal suspend fun awaitCurrentTimelineLayout(state: LazyListState, keys: List<String>) {
    snapshotFlow { state.layoutInfo }.first { layout ->
        NotificationTargetLayout.current(keys, layout.totalItemsCount,
            layout.visibleItemsInfo.map { it.index to it.key }, layout.viewportSize.height)
    }
    // Snapshot observers can resume inline while LazyColumn is still measuring.
    // A scroll forces remeasurement, so leave that pass before placing the viewport.
    withFrameNanos { }
}
