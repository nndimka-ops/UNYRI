package com.messenger.app.ui.theme

import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// COCO FLOW spacing scale (Этап N.1 §5): 4 / 8 / 12 / 16 / 20 / 24 / 32.
// No random 13.dp / 17.dp / 19.dp values.
// ---------------------------------------------------------------------------
object CocoSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

/** Minimum touch target (Этап N.1 §41/§62). */
val CocoTouchTarget = 48.dp
