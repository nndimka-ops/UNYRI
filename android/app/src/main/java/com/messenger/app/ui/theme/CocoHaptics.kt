package com.messenger.app.ui.theme

import android.view.HapticFeedbackConstants
import android.view.View

/** Discrete platform feedback. No vibration permission, timers or setting overrides. */
object CocoHaptics {
    fun tap(view: View) { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    fun select(view: View) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun hold(view: View) { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    /** A softer native detent for taking back a reaction; never bypass system settings. */
    fun reactionRemoved(view: View) {
        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 34)
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }
}
