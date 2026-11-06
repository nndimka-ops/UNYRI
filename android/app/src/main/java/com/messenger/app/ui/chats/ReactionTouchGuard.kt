package com.messenger.app.ui.chats

import androidx.compose.ui.geometry.Rect

/** Touch geometry only. Reaction chips must not trigger the message double-tap action. */
internal class ReactionTouchGuard {
    var bounds = Rect.Zero
    var reactionArea = Rect.Zero
    var touchOnReaction = false
}
