package com.messenger.app.ui.chats

/** Window-local dimensions only. Menus reserve their controls before any preview. */
internal data class MessageMenuBudget(
    val showClose: Boolean,
    val showInlineReactions: Boolean,
    val menuHeight: Float,
    val previewLimit: Float,
    val fixedHeight: Float,
) {
    fun total(reactionHeight: Float) = fixedHeight + menuHeight + previewLimit +
        (if (showInlineReactions) reactionHeight else 0f)
}

internal fun messageMenuBudget(height: Float, rows: Int, reactionHeight: Float, preview: Boolean): MessageMenuBudget {
    val available = height.coerceAtLeast(0f)
    val close = available >= 96f
    val closeHeight = if (close) 41f else 0f
    val reactions = reactionHeight > 0f && available >= closeHeight + reactionHeight + 16f + 48f
    val fixed = closeHeight + (if (reactions) 8f else 0f) + minOf(8f, (available - closeHeight).coerceAtLeast(0f))
    val content = (available - fixed - (if (reactions) reactionHeight else 0f)).coerceAtLeast(0f)
    val hasPreview = preview && content >= 72f
    val reserve = if (hasPreview) maxOf(24f, minOf(220f, content * .25f)) else 0f
    val menu = minOf(rows.coerceAtLeast(1) * 48f + 8f, (content - reserve).coerceAtLeast(0f))
    val previewLimit = if (hasPreview) minOf(220f, (content - menu).coerceAtLeast(0f)) else 0f
    return MessageMenuBudget(close, reactions, menu, previewLimit, fixed)
}
