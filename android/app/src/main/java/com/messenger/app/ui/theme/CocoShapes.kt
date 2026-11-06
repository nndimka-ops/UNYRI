package com.messenger.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// COCO FLOW shape tokens (Этап N.1 §5). Soft rounded, compact.
// ---------------------------------------------------------------------------
object CocoShapes {
    val small = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(16.dp)
    val large = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(50)
    /** Message bubbles - visually characteristic smaller corner on one side
     *  is achieved by the bubble composable choosing its own shape; these
     *  are the two base bubble shapes. */
    val bubble = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val bubbleIncoming = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)

    // --- COCO Glass Flow semantic radii (consumed in later component phases;
    // --- never sprinkle raw radius values through components). ---
    val radiusSmall = RoundedCornerShape(10.dp)
    val radiusMedium = RoundedCornerShape(14.dp)
    val radiusCard = RoundedCornerShape(20.dp)
    val radiusDialog = RoundedCornerShape(24.dp)
    val radiusBubble = RoundedCornerShape(18.dp)
    val radiusChatRow = RoundedCornerShape(20.dp)
    val radiusAttachment = RoundedCornerShape(18.dp)
    val radiusChip = RoundedCornerShape(19.dp)
    val radiusComposer = RoundedCornerShape(27.dp)
    val radiusSearch = RoundedCornerShape(23.dp)
    val radiusBottomNav = RoundedCornerShape(30.dp)
    val radiusSheetTop = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val radiusLocalVideo = RoundedCornerShape(20.dp)
    val radiusCallControl = RoundedCornerShape(28.dp)
}
