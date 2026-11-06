package com.messenger.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.messenger.app.ui.theme.cocoPressFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.LocalCocoColors

/** Shared touch targets, shape and disabled states for user settings actions. */
@Composable
fun SettingsPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = LocalCocoColors.current
    Button(onClick = onClick, interactionSource = interaction, modifier = modifier.then(cocoPressFeedback(interaction)).heightIn(min = 48.dp), enabled = enabled,
        shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if(destructive)colors.dangerPressed else colors.accent,
            contentColor = if(destructive)colors.onDanger else colors.onAccent,
            disabledContainerColor = (if(destructive)colors.danger else colors.accent).copy(alpha = 0.15f), disabledContentColor = colors.mutedText),
        content = content)
}

@Composable
fun SettingsSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = LocalCocoColors.current
    val tint = if (destructive) colors.danger else colors.accentSoft
    OutlinedButton(onClick = onClick, interactionSource = interaction, modifier = modifier.then(cocoPressFeedback(interaction)).heightIn(min = 48.dp), enabled = enabled,
        shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        border = BorderStroke(1.dp, if (enabled && destructive) tint.copy(alpha = 0.45f) else colors.outlineSecondary),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if(destructive)colors.danger.copy(alpha=.10f) else colors.surfaceVariant,
            contentColor = tint, disabledContentColor = colors.mutedText),
        content = content)
}

@Composable
fun SettingsDialogButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = LocalCocoColors.current
    TextButton(onClick = onClick, interactionSource = interaction, modifier = modifier.then(cocoPressFeedback(interaction)).heightIn(min = 48.dp), enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) colors.danger else colors.accentSoft,
            disabledContentColor = colors.mutedText), content = content)
}
