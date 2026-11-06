package com.messenger.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors

// ---------------------------------------------------------------------------
// Admin UX Pack 1 - admin session and security.
//
// Presentation only: the unlock flow, key proof, session expiry and lock
// behaviour are untouched. Owner-only key rotation is separated from ordinary
// server settings and always goes through an explicit confirmation.
// ---------------------------------------------------------------------------
@Composable
internal fun AdminSecurityScreen(
    role: String,
    serverId: String,
    busy: Boolean,
    paperEnabled: Boolean = true,
    recoveryCodes: String,
    onDismissRecovery: () -> Unit,
    rotating: Boolean,
    onToggleRotate: () -> Unit,
    paperKey: String,
    onPaperKey: (String) -> Unit,
    replacementKey: String,
    onReplacementKey: (String) -> Unit,
    replacementSaved: Boolean,
    onReplacementSaved: (Boolean) -> Unit,
    onGenerateReplacement: () -> Unit,
    onRotate: () -> Unit,
    onLock: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalCocoColors.current
    val owner = AdminNavigation.isOwner(role)
    var confirmRotate by remember { mutableStateOf(false) }

    AdminScrollingScreen(title = stringResource(R.string.admin_nav_security), onBack = onBack) {
        AdminCard {
            Text(
                stringResource(R.string.admin_security_session),
                style = MaterialTheme.typography.titleSmall,
                color = colors.secondaryText,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CocoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                AdminStatusChip(
                    stringResource(if (owner) R.string.admin_role_owner else R.string.admin_role_moderator),
                    colors.accentSoft,
                )
                AdminStatusChip(stringResource(R.string.admin_session_unlocked), colors.success)
            }
            if (serverId.isNotBlank()) {
                AdminKeyValue(stringResource(R.string.admin_server_id), AdminPresentation.shortId(serverId), monospace = true)
            }
            Text(stringResource(R.string.admin_security_timeout), style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
            Text(stringResource(R.string.admin_factor_windows), style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
        }

        AdminCard {
            AdminActionRow(
                title = stringResource(R.string.admin_security_lock),
                subtitle = stringResource(R.string.admin_lock),
                icon = com.messenger.app.ui.theme.CocoIcons.Security,
                enabled = !busy,
                onClick = onLock,
            )
        }

        if (recoveryCodes.isNotEmpty()) {
            AdminSectionHeader(stringResource(R.string.admin_recovery))
            AdminCard {
                Text(stringResource(R.string.admin_recovery), style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(recoveryCodes, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = colors.primaryText)
                }
                AdminActionRow(
                    title = stringResource(R.string.admin_saved),
                    enabled = !busy,
                    onClick = onDismissRecovery,
                )
            }
        }

        if (owner && paperEnabled) {
            AdminSectionHeader(stringResource(R.string.admin_security_key), stringResource(R.string.admin_security_rotate_hint))
            AdminCard {
                AdminActionRow(
                    title = stringResource(R.string.admin_key_rotate),
                    icon = com.messenger.app.ui.theme.CocoIcons.Security,
                    enabled = !busy,
                    onClick = onToggleRotate,
                )
                if (rotating) {
                    AdminDivider()
                    Text(stringResource(R.string.admin_key_old), style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
                    AdminPaperKeyFields(paperKey, onPaperKey, false, !busy)
                    AdminActionRow(
                        title = stringResource(R.string.admin_key_generate),
                        enabled = !busy,
                        onClick = onGenerateReplacement,
                    )
                    AdminPaperKeyFields(replacementKey, onReplacementKey, true, !busy)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(replacementSaved, onReplacementSaved, enabled = !busy)
                        Text(stringResource(R.string.admin_key_saved), Modifier.weight(1f))
                    }
                    AdminActionRow(
                        title = stringResource(R.string.admin_key_rotate),
                        destructive = true,
                        enabled = !busy && replacementSaved &&
                            AdminSigningKey.valid(paperKey) && AdminSigningKey.valid(replacementKey),
                        onClick = { confirmRotate = true },
                    )
                    Text(stringResource(R.string.admin_security_rotate_hint), style = MaterialTheme.typography.bodySmall, color = colors.warning)
                }
            }
        }
    }

    if (confirmRotate) {
        AdminConfirmDialog(
            title = stringResource(R.string.admin_security_rotate_confirm),
            body = stringResource(R.string.admin_security_rotate_confirm_body),
            confirmLabel = stringResource(R.string.admin_key_rotate),
            destructive = true,
            enabled = true,
            busy = busy,
            onConfirm = {
                confirmRotate = false
                onRotate()
            },
            onDismiss = { confirmRotate = false },
        )
    }
}
