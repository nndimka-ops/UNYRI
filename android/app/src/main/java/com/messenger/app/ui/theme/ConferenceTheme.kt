package com.messenger.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Room surfaces share one theme entry point; changing them never changes room state or media. */
internal object ConferenceRoomStyle {
    fun backdrop(c: CocoColors) = Brush.verticalGradient(if (c.isMonochrome) listOf(c.callBackground, c.background)
        else listOf(Color(0xFF0D111C), Color(0xFF111725)))
    fun panel(c: CocoColors) = if (c.isMonochrome) c.surfaceVariant.copy(alpha = .94f) else Color(0xF0151E2B)
    fun tile(c: CocoColors) = Brush.verticalGradient(if (c.isMonochrome) listOf(c.elevated, c.surfaceVariant)
        else listOf(Color(0xFF202C40), Color(0xFF151E2B)))
    fun outline(c: CocoColors) = if (c.isMonochrome) c.outlineSubtle else Color(0x66303B50)
}
