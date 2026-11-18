package com.messenger.app.ui.chats

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Pinning is reading intent, not a property of the newly measured (possibly taller) row. */
internal class ChatBottomPin(initiallyPinned: Boolean) {
    var pinned by mutableStateOf(initiallyPinned)
        private set
    private var userScrolling = false

    fun release() { userScrolling = false; pinned = false }
    fun follow() { userScrolling = false; pinned = true }
    fun arrival(own: Boolean, addressedOffer: Boolean): Boolean {
        if (own || addressedOffer) follow()
        return pinned
    }
    fun userScroll() { userScrolling = true; pinned = false }
    fun settled(nearBottom: Boolean) {
        if (userScrolling) { pinned = nearBottom; userScrolling = false }
    }
    fun shouldAnchor(ready: Boolean, covered: Boolean, scrolling: Boolean): Boolean =
        pinned && ready && !covered && !scrolling

    companion object {
        val Saver = Saver<ChatBottomPin, Boolean>(save = { it.pinned }, restore = { ChatBottomPin(it) })
    }
}

internal fun chatBottomOverflow(rowBottom: Int, viewportEnd: Int, afterContentPadding: Int): Int =
    (rowBottom - (viewportEnd - afterContentPadding)).coerceAtLeast(0)

/** User-input priority may cancel a scroll; keep the layout observer alive for later arrivals. */
internal suspend fun chatBottomCorrection(block: suspend () -> Unit) = coroutineScope {
    launch { block() }.join()
}

internal fun Modifier.chatBottomPinInput(pin: ChatBottomPin): Modifier = nestedScroll(
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) pin.userScroll()
            return Offset.Zero
        }
    },
)

/** Observe completed lazy-list measures, including row-only direct-transfer/media updates.
 * The existing bottom content padding includes composer + navigation bar; IME movement is
 * owned by ChatKeyboardLift, so it must not be added to the scroll distance a second time. */
@Composable
internal fun MaintainChatBottomPin(
    state: LazyListState, keys: List<String>, ready: Boolean, covered: Boolean,
    pin: ChatBottomPin, gate: NotificationScrollGate, threshold: Int, onBottom: () -> Unit,
) {
    val currentKeys by rememberUpdatedState(keys)
    val currentReady by rememberUpdatedState(ready)
    val currentCovered by rememberUpdatedState(covered)
    val bottomReached by rememberUpdatedState(onBottom)
    LaunchedEffect(state, pin, threshold) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val layout = state.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()
                pin.settled(ChatViewportPolicy.isNearBottom(layout.totalItemsCount, last?.index ?: -1,
                    last?.let { it.offset + it.size } ?: 0,
                    layout.viewportEndOffset - layout.afterContentPadding, threshold))
            }
        }
    }
    LaunchedEffect(state, pin, gate) {
        snapshotFlow {
            // LayoutInfo changes only on layout/scroll, with no idle polling or row observation.
            Triple(state.layoutInfo, currentKeys,
                pin.shouldAnchor(currentReady, currentCovered, state.isScrollInProgress))
        }.collect { (layout, expectedKeys, anchor) ->
            if (!anchor || expectedKeys.isEmpty() || !NotificationTargetLayout.current(expectedKeys,
                    layout.totalItemsCount, layout.visibleItemsInfo.map { it.index to it.key }, layout.viewportSize.height)) return@collect
            val ticket = gate.followTicket() ?: return@collect
            val last = layout.visibleItemsInfo.lastOrNull()
            val overflow = last?.let { chatBottomOverflow(it.offset + it.size, layout.viewportEndOffset, layout.afterContentPadding) } ?: 0
            if (last?.index == expectedKeys.lastIndex && overflow <= 0) {
                bottomReached()
                return@collect
            }
            // Leave the measure pass before forcing a remeasure; this is layout synchronization,
            // not a time-based retry. Recheck ownership after yielding to gestures/target jumps.
            withFrameNanos { }
            if (!pin.shouldAnchor(currentReady, currentCovered, state.isScrollInProgress) ||
                !gate.mayFollow(ticket) || currentKeys != expectedKeys) return@collect
            chatBottomCorrection {
                if (!pin.shouldAnchor(currentReady, currentCovered, state.isScrollInProgress) ||
                    !gate.mayFollow(ticket) || currentKeys != expectedKeys) return@chatBottomCorrection
                if (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index != expectedKeys.lastIndex) {
                    state.scrollToItem(expectedKeys.lastIndex)
                }
                val measured = state.layoutInfo
                val tail = measured.visibleItemsInfo.lastOrNull()
                if (tail?.index == expectedKeys.lastIndex &&
                    pin.shouldAnchor(currentReady, currentCovered, state.isScrollInProgress) && gate.mayFollow(ticket)) {
                    val remaining = chatBottomOverflow(tail.offset + tail.size, measured.viewportEndOffset, measured.afterContentPadding)
                    if (remaining > 0) state.scrollBy(remaining.toFloat())
                    bottomReached()
                }
            }
        }
    }
}
