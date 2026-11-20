package com.messenger.app.ui.components

internal enum class PageHeaderMode { Symmetric, Asymmetric, ActionsBelow }

internal data class PageHeaderPlacement(val mode: PageHeaderMode, val titleStart: Int, val titleWidth: Int)

/** Keep each word intact before sacrificing page-wide centering or moving controls. */
internal fun pageHeaderPlacement(
    width: Int,
    leading: Int,
    trailing: Int,
    gap: Int,
    longestWord: Int,
): PageHeaderPlacement {
    val start = leading + if (leading > 0) gap else 0
    val end = trailing + if (trailing > 0) gap else 0
    val side = maxOf(start, end)
    val symmetric = (width - 2 * side).coerceAtLeast(0)
    if (longestWord <= symmetric) return PageHeaderPlacement(PageHeaderMode.Symmetric, side, symmetric)
    val asymmetric = (width - start - end).coerceAtLeast(0)
    if (longestWord <= asymmetric) return PageHeaderPlacement(PageHeaderMode.Asymmetric, start, asymmetric)
    return PageHeaderPlacement(PageHeaderMode.ActionsBelow, 0, width.coerceAtLeast(0))
}
