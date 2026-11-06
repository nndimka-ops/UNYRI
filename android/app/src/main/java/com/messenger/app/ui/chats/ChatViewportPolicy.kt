package com.messenger.app.ui.chats

/** Follow the visible end, not a count of rows: one photo can fill a screen. */
internal object ChatViewportPolicy {
    fun isNearBottom(
        totalItems: Int,
        lastVisibleIndex: Int,
        lastVisibleBottom: Int,
        contentBottom: Int,
        threshold: Int,
    ): Boolean = totalItems > 0 && lastVisibleIndex == totalItems - 1 &&
        lastVisibleBottom.toLong() <= contentBottom.toLong() + threshold.coerceAtLeast(0)
    /** The unread badge does not create a scroll destination by itself. */
    fun showJumpToLatest(
        ready: Boolean,
        timelineCount: Int,
        measuredCount: Int,
        viewportHeight: Int,
        visibleRowsMatch: Boolean,
        canScrollForward: Boolean,
        nearBottom: Boolean,
    ): Boolean = ready && timelineCount > 0 && measuredCount == timelineCount &&
        viewportHeight > 0 && visibleRowsMatch && canScrollForward && !nearBottom

}
