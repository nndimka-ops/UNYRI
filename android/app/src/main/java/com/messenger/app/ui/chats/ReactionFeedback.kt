package com.messenger.app.ui.chats

import com.messenger.app.domain.model.ReactionSummary

/** Match toggleReaction's current-owned-emoji rule. A removal never schedules an add landing. */
internal fun dispatchReactionToggle(reactions: List<ReactionSummary>, emoji: String,
    added: () -> Unit, removed: () -> Unit, expectAdd: () -> Unit, toggle: () -> Unit,
    onSelect: (() -> Unit)? = null) {
    if (onSelect != null) { added(); onSelect(); return }
    val removing = reactions.firstOrNull { it.reactedByMe }?.emoji == emoji
    if (removing) removed() else { added(); expectAdd() }
    toggle()
}
