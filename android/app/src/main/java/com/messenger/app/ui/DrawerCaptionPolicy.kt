package com.messenger.app.ui

/** B-031: fill two lines at the normal size before trying smaller caption text. */
internal object DrawerCaptionPolicy {
    const val nameSizeSp = 17f
    const val minimumNameSizeSp = 13f
    const val nameLineHeightSp = 21f
    const val nameMaxLines = 2
    const val numberSizeSp = 12f
    const val numberLineHeightSp = 15f
    const val rowGapDp = 8f
    const val textGapDp = 2f

    fun nameSize(fits: (fontSizeSp: Float, maxLines: Int) -> Boolean): Float {
        // At most nine measurements when the name, available width or typography changes.
        for (halfSp in (nameSizeSp * 2).toInt() downTo (minimumNameSizeSp * 2).toInt()) {
            val size = halfSp / 2f
            if (fits(size, nameMaxLines)) return size
        }
        return minimumNameSizeSp
    }
}
