package com.messenger.app.ui.chats

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlin.math.roundToInt

/** Placement layers can move without a new global-position callback. Query at use time. */
internal class LiveChatBounds {
    var coordinates: LayoutCoordinates? = null
    val bounds get() = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
}

/** All arguments are bottom overlaps in pixels, including the navigation bar. */
internal fun chatKeyboardLift(imeBottom: Int, navigationBottom: Int, emojiReservationBottom: Int): Int =
    (maxOf(0, imeBottom, emojiReservationBottom) - navigationBottom.coerceAtLeast(0)).coerceAtLeast(0)

/**
 * Empty space between the newest row and the composer edge, in list item coordinates.
 * Only the tail of the list has such space: a short chat, or an empty one. A list whose
 * newest row is off screen (scrolled up, or simply long) has none.
 */
internal fun timelineBottomSlack(lastVisibleIndex: Int?, totalItems: Int, lastVisibleEnd: Int, contentLimit: Int): Int = when {
    totalItems <= 0 -> Int.MAX_VALUE
    lastVisibleIndex == null || lastVisibleIndex != totalItems - 1 -> 0
    else -> (contentLimit - lastVisibleEnd).coerceAtLeast(0)
}

/** The timeline moves only by the part of the input surface that actually covers rows. */
internal fun chatTimelineShift(lift: Int, slack: Int): Int {
    val input = lift.coerceAtLeast(0)
    return (input - slack.coerceAtLeast(0)).coerceIn(0, input)
}

internal fun timelineBottomSlack(layout: LazyListLayoutInfo): Int {
    val last = layout.visibleItemsInfo.lastOrNull()
    return timelineBottomSlack(last?.index, layout.totalItemsCount, last?.let { it.offset + it.size } ?: 0,
        layout.viewportEndOffset - layout.afterContentPadding)
}

@Stable
internal class ChatKeyboardLift {
    // Read only in measure/placement or snapshotFlow, never in row composition.
    var read: () -> Int by mutableStateOf({ 0 })
    val pixels: Int get() = read()
    internal var list: LazyListState? = null
    private var topScroll by mutableFloatStateOf(0f)
    private var topAnchor: Any? = null
    private fun atTopAnchor(): Boolean {
        val state = list ?: return true
        val anchor = topAnchor ?: return !state.canScrollBackward
        return state.firstVisibleItemScrollOffset == 0 && state.layoutInfo.visibleItemsInfo.any {
            it.index == state.firstVisibleItemIndex && it.key == anchor
        }
    }
    val topSpaceAbandoned: Boolean get() = topScroll > 0f && !atTopAnchor()
    // 14 Nov: a short or empty chat used to move by the full keyboard height, so its rows
    // went under the header and the space above the composer stayed empty. Rows move only
    // once the input surface reaches the newest one. The list's own layout is unaffected
    // by this placement offset, so reading it here cannot feed back.
    private val shift: Int get() = list?.let { chatTimelineShift(pixels, timelineBottomSlack(it.layoutInfo)) } ?: pixels
    private val topSpace: Float get() = if (atTopAnchor()) topScroll.coerceIn(0f, shift.toFloat()) else 0f
    val timelinePixels: Int get() = shift - topSpace.roundToInt()
    fun resetTopScroll() { topScroll = 0f; topAnchor = null }
    fun visibleRows(layout: LazyListLayoutInfo, header: Int, composer: Int) =
        visibleTimelineRows(layout, header, composer, timelinePixels, pixels - timelinePixels)

    // The visual top inset also supplies scroll range at the oldest boundary.
    // Only an actual scroll consumes it; opening an input never changes the anchor.
    val scrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y >= 0f || topSpace == 0f) return Offset.Zero
            val consumed = available.y.coerceAtLeast(-topSpace)
            topScroll = topSpace + consumed
            return Offset(0f, consumed)
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y <= 0f || list?.canScrollBackward == true) return Offset.Zero
            val previous = topSpace
            topAnchor = list?.let { state -> state.layoutInfo.visibleItemsInfo.firstOrNull {
                it.index == state.firstVisibleItemIndex
            }?.key }
            val delta = available.y.coerceAtMost((shift - previous).coerceAtLeast(0f))
            topScroll = previous + delta
            return Offset(0f, delta)
        }
    }
}

internal val LocalChatKeyboardLift = staticCompositionLocalOf<ChatKeyboardLift> { error("No chat input host") }

/** Input chrome moves on the same frame as the list, without resizing the content root. */
internal fun Modifier.chatInputLift(lift: ChatKeyboardLift): Modifier = offset { IntOffset(0, -lift.pixels) }

@Composable internal fun Modifier.chatInputLift(): Modifier = chatInputLift(LocalChatKeyboardLift.current)

/** LazyColumn keeps exactly the same constraints, padding and scroll offset throughout an
 * IME animation. Rows above the header stay built for the return journey and drags own
 * the normal LazyListState without a competing scroll coroutine.
 */
internal fun Modifier.chatTimelineLift(lift: ChatKeyboardLift, list: LazyListState): Modifier {
    lift.list = list
    return nestedScroll(lift.scrollConnection).offset { IntOffset(0, -lift.timelinePixels) }
}
