package com.messenger.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors

/** One compact surface; large accessibility text may grow vertically instead of clipping. */
@Composable
internal fun ChatSelectionActionsBar(canForward: Boolean, onForward: () -> Unit, onDelete: () -> Unit) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("chat_selection_actions")
        .clip(RoundedCornerShape(24.dp)).background(colors.surface.copy(alpha = .86f))
        .border(.5.dp, colors.primaryText.copy(alpha = .10f), RoundedCornerShape(24.dp)),
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onForward, enabled = canForward,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("chat_selection_forward"),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.primaryText,
                disabledContentColor = colors.primaryText.copy(alpha = .35f))) {
            Icon(CocoIcons.Send, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.forward))
        }
        Box(Modifier.width(.5.dp).height(20.dp).background(colors.primaryText.copy(alpha = .12f)))
        TextButton(onClick = onDelete, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("chat_selection_delete"),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.danger)) {
            Icon(CocoIcons.Delete, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.delete))
        }
    }
}
