package com.messenger.app.ui.chats

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors

internal data class ContactChatAction(val label: String, val icon: ImageVector, val tint: Color,
    val enabled: Boolean = true, val onClick: () -> Unit)

/** One scrollable graphite panel; no backdrop capture or platform blur. */
@Composable
internal fun ContactChatMenu(title: String, avatar: @Composable () -> Unit,
    quick: List<ContactChatAction>, rows: List<ContactChatAction>, danger: List<ContactChatAction>,
    enabled: Boolean, canAct: () -> Boolean, error: String?, onDismiss: () -> Unit) {
    val colors = LocalCocoColors.current
    val primary = colors.primaryText
    val muted = colors.secondaryText
    val subtitle = stringResource(R.string.chat_menu_actions_subtitle)
    // Read unconsumed insets from the activity before entering the dialog.
    // Xiaomi reports zero Compose navigation insets inside this dialog window.
    val hostView = LocalView.current
    val hostInsets = ViewCompat.getRootWindowInsets(hostView)?.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
    val density = LocalDensity.current
    val hostTop = with(density) { (hostInsets?.top ?: 0).toDp() }
    val hostBottom = with(density) { (hostInsets?.bottom ?: 0).toDp() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        val view = LocalView.current
        fun click(item: ContactChatAction) {
            if (enabled && item.enabled && canAct()) { CocoHaptics.tap(view); item.onClick() }
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).clickable(enabled = enabled, onClick = onDismiss))
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = hostTop, bottom = hostBottom)
                .imePadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
                val shape = RoundedCornerShape(20.dp)
                Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 380.dp).fillMaxWidth()
                    .heightIn(max = maxHeight).semantics { paneTitle = subtitle }
                    .clip(shape)
                    .background(colors.deepPanel, shape)
                    .border(.5.dp, colors.divider.copy(alpha=.6f), shape)
                    .verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).width(32.dp).height(4.dp).background(muted.copy(alpha = .65f), CircleShape))
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        avatar()
                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                            Text(title, color = primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(subtitle, color = muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { if (enabled && canAct()) { CocoHaptics.tap(view); onDismiss() } }, enabled = enabled,
                            modifier = Modifier.size(44.dp).background(Color.White.copy(alpha = .06f), CircleShape)) {
                            Icon(CocoIcons.Close, stringResource(R.string.chat_controls_close), tint = primary, modifier = Modifier.size(22.dp))
                        }
                    }
                    // One compact list. All icon/material colors come from the active palette;
                    // monochrome never inherits the older multicolor quick-action cards.
                    listOf(quick, rows, danger).filter { it.isNotEmpty() }.forEachIndexed { group, actions ->
                        if (group > 0) HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = colors.divider.copy(alpha = .5f))
                        actions.forEach { item ->
                            val tint = if (item in danger) item.tint else if (colors.isMonochrome) primary else item.tint
                            val alpha = if (enabled && item.enabled) 1f else .4f
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = enabled && item.enabled, role = Role.Button) { click(item) }
                                .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(item.icon, null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(21.dp))
                                Text(item.label, color = (if (item in danger) tint else primary).copy(alpha = alpha), fontSize = 15.sp,
                                    modifier = Modifier.weight(1f).padding(start = 14.dp))
                            }
                        }
                    }
                    if (error != null) Text(error, color = colors.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
