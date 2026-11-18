package com.messenger.app.ui.chats

import com.messenger.app.ui.security.rejectObscuredTouches

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val enabled: Boolean = true, val sensitive: Boolean = false, val onClick: () -> Unit)

/** The caller retains account/session/busy guards and owns all confirmation state. */
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
    DismissChatMenuKeyboard()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val blurred = ChatMenuWindow()
        fun click(item: ContactChatAction) {
            if (enabled && item.enabled && canAct()) { CocoHaptics.tap(view); item.onClick() }
        }
        Box(Modifier.fillMaxSize()) {
            ChatMenuBackdrop(blurred, enabled = enabled, onDismiss = onDismiss)
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = hostTop, bottom = hostBottom)
                .imePadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp)) {
                val shape = RoundedCornerShape(24.dp)
                Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 440.dp).fillMaxWidth()
                    .heightIn(max = maxHeight).semantics { paneTitle = subtitle }
                    .chatMenuPanel(shape, blurred)
                    .verticalScroll(rememberScrollState()).padding(14.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).width(34.dp).height(4.dp).background(muted.copy(alpha = .5f), CircleShape))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { avatar() }
                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                            Text(title, color = primary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(subtitle, color = muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { if (enabled && canAct()) { CocoHaptics.tap(view); onDismiss() } }, enabled = enabled,
                            modifier = Modifier.size(44.dp)) {
                            Icon(CocoIcons.Close, stringResource(R.string.chat_controls_close), tint = muted, modifier = Modifier.size(19.dp))
                        }
                    }
                    // Archive belongs with Open/Pin/Mute; the first separator precedes Copy/Report.
                    val split = rows.indexOfFirst { it.icon == CocoIcons.Copy || it.icon == CocoIcons.ReportFlag }.let { if (it < 0) rows.size else it }
                    listOf(quick + rows.take(split), rows.drop(split), danger).filter { it.isNotEmpty() }.forEachIndexed { group, actions ->
                        if (group > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = .5.dp, color = primary.copy(alpha = .08f))
                        actions.forEach { item ->
                            val tint = if (item in danger && item.tint == colors.danger) colors.danger else primary
                            val icon = when {
                                item in danger && item.icon == CocoIcons.Security -> CocoIcons.HandRaised
                                item.icon == CocoIcons.Speaker -> CocoIcons.Bell
                                item.icon == CocoIcons.Pin -> CocoIcons.MenuPin
                                else -> item.icon
                            }
                            val alpha = if (enabled && item.enabled) 1f else .4f
                            val label = when (item.icon) {
                                CocoIcons.Copy -> stringResource(R.string.chat_menu_copy_name)
                                CocoIcons.Archive -> stringResource(R.string.chat_menu_archive)
                                CocoIcons.RestoreChat -> stringResource(R.string.chat_menu_restore)
                                else -> item.label
                            }
                            Row(Modifier.fillMaxWidth()
                                .then(if (item.sensitive) Modifier.rejectObscuredTouches() else Modifier).clickable(enabled = enabled && item.enabled, role = Role.Button) { click(item) }
                                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(21.dp)) }
                                Text(label, color = tint.copy(alpha = alpha), fontSize = 15.sp,
                                    modifier = Modifier.weight(1f).padding(start = 12.dp))
                            }
                        }
                    }
                    if (error != null) Text(error, color = colors.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
