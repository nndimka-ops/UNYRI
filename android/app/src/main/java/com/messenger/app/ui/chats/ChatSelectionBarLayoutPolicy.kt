package com.messenger.app.ui.chats

/** Pure dp layout rules for the floating chat-selection island. */
internal object ChatSelectionBarLayoutPolicy {
    const val sideMarginDp = 12f
    const val navigationGapDp = 6f
    const val cornerRadiusDp = 26f

    /** The host can already include navigation bars; reserve the larger inset once. */
    fun bottomInsetDp(hostBottomDp: Float, navigationBottomDp: Float): Float =
        maxOf(hostBottomDp, navigationBottomDp) + navigationGapDp

    /**
     * The bar follows the weighted list in the same Column, reserving its own
     * height and navigation clearance. Keep list spacing without counting that
     * navigation inset again while selecting.
     */
    fun listBottomPaddingDp(selecting: Boolean, contentPaddingDp: Float, hostBottomDp: Float): Float =
        contentPaddingDp + if (selecting) 0f else hostBottomDp
}
