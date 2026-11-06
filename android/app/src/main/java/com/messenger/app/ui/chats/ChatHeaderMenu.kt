package com.messenger.app.ui.chats

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors

internal data class ChatHeaderAction(val title: String, val icon: ImageVector, val tint: Color, val fill: Color,
    val group: Int = 0, val enabled: Boolean = true, val checked: Boolean? = null, val action: () -> Unit)

@Composable
internal fun ChatHeaderMenu(actions: List<ChatHeaderAction>, onDismiss: () -> Unit) {
    val colors = LocalCocoColors.current
    val title = stringResource(R.string.chat_menu_title)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Static scrim keeps the same modal interaction without a blur layer.
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)).clickable(onClick = onDismiss))
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                val top = minOf(52.dp, maxHeight / 8)
                Surface(Modifier.align(Alignment.TopEnd).padding(top = top).widthIn(max = 300.dp)
                    .heightIn(max = (maxHeight - top).coerceAtLeast(48.dp)).semantics { paneTitle = title },
                    shape = RoundedCornerShape(24.dp), color = colors.deepPanel, contentColor = colors.primaryText,
                    border = BorderStroke(.5.dp, colors.divider)) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = onDismiss) { Icon(CocoIcons.Close, stringResource(R.string.cancel), tint = colors.secondaryText, modifier = Modifier.size(19.dp)) }
                        }
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                            actions.forEachIndexed { index, item ->
                                if (index > 0 && item.group != actions[index - 1].group) HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), color = colors.primaryText.copy(alpha = .07f))
                                Row(Modifier.fillMaxWidth().clickable(enabled = item.enabled, role = if (item.checked == null) Role.Button else Role.Checkbox, onClick = item.action)
                                    .semantics { item.checked?.let { toggleableState = if (it) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off } }
                                    .padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(item.icon, null, tint = item.tint.copy(alpha = if (item.enabled) 1f else .4f), modifier = Modifier.size(34.dp).background(item.fill, RoundedCornerShape(11.dp)).padding(7.dp))
                                    Text(item.title, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, color = colors.primaryText.copy(alpha = if (item.enabled) 1f else .4f))
                                    if (item.checked == true) Icon(CocoIcons.Check, null, tint = colors.categoryMint, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
