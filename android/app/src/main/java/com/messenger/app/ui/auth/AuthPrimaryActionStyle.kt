package com.messenger.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.messenger.app.ui.theme.LocalCocoColors

internal data class AuthPrimaryActionStyle(val background: Modifier, val colors: ButtonColors, val ink: Color)

/** Current iOS sign-in action colors; no dimensions, gestures or auth policy. */
@Composable
internal fun authPrimaryActionStyle(enabled: Boolean, shape: Shape): AuthPrimaryActionStyle {
    val palette = LocalCocoColors.current
    val ink = if (palette.isMonochrome) palette.onAccent else Color.White
    val brush = remember(palette.isMonochrome, palette.accent) {
        Brush.horizontalGradient(if (palette.isMonochrome) listOf(palette.accent, palette.accent)
            else listOf(Color(0xFF8574FF), Color(0xFF576CEA)))
    }
    return AuthPrimaryActionStyle(
        background = if (enabled) Modifier.background(brush, shape) else Modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = ink,
            disabledContainerColor = palette.surfaceVariant, disabledContentColor = palette.secondaryText),
        ink = ink,
    )
}
