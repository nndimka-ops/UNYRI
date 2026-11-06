package com.messenger.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.messenger.app.ui.settings.SettingsDialogButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.messenger.app.R
import com.messenger.app.data.local.MediaCacheManager
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors

/**
 * Data & Storage screen (Phase 9.1): truthful LOCAL cache accounting + a real
 * "Clear Media Cache" action. The message/chat database is shown only
 * informationally and can never be cleared from here.
 */
@Composable
fun StorageScreen(viewModel: StorageViewModel, onBack: () -> Unit) {
    val colors = LocalCocoColors.current
    val usage by viewModel.usage.collectAsState()
    val clearing by viewModel.clearing.collectAsState()
    val clearFailedCount by viewModel.clearFailedCount.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }
    var confirmChat by remember { mutableStateOf<String?>(null) }
    val cachePolicy by viewModel.policy.collectAsState()
    val chatUsage by viewModel.chats.collectAsState()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val chats by app.chatRepository.chats.collectAsState(initial = emptyList())

    SettingsScaffold(title = stringResource(R.string.storage_title), onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = CocoSpacing.xl),
        ) {
            val unlimited = stringResource(R.string.cache_unlimited)
            val forever = stringResource(R.string.cache_forever)
            val maxOptions = listOf(0L, 512L*1024*1024, 1024L*1024*1024, 2L*1024*1024*1024, 5L*1024*1024*1024, 10L*1024*1024*1024, 20L*1024*1024*1024)
                .map { it to if (it == 0L) unlimited else if (it == 512L*1024*1024) "0.5 GB" else "${it/(1024L*1024*1024)} GB" }
            SettingChoiceRow(stringResource(R.string.cache_size_limit), maxOptions.firstOrNull { it.first == cachePolicy.maxBytes }?.second ?: FileSizeFormatter.format(cachePolicy.maxBytes), maxOptions, !clearing) {
                viewModel.savePolicy(cachePolicy.copy(maxBytes = it))
            }
            val dayOptions = listOf(0,3,7,30).map { it to if (it == 0) forever else stringResource(R.string.cache_days, it) }
            SettingChoiceRow(stringResource(R.string.cache_retention), dayOptions.firstOrNull { it.first == cachePolicy.retentionDays }?.second ?: forever, dayOptions, !clearing) {
                viewModel.savePolicy(cachePolicy.copy(retentionDays = it))
            }
            Text(stringResource(R.string.cache_policy_detail), style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
            Spacer(Modifier.height(CocoSpacing.lg))
            val u = usage
            if (u == null) {
                Text(
                    text = stringResource(R.string.storage_calculating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.mutedText,
                    modifier = Modifier.padding(CocoSpacing.lg),
                )
            } else {
                StorageCard(
                    title = stringResource(R.string.storage_media_cache),
                    rows = listOf(
                        stringResource(R.string.storage_photos) to u.photosBytes,
                        stringResource(R.string.storage_voice) to u.voiceBytes,
                        stringResource(R.string.storage_files) to u.filesBytes,
                    ),
                    total = u.mediaBytes,
                )
                Spacer(modifier = Modifier.height(CocoSpacing.lg))
                StorageCard(
                    title = stringResource(R.string.storage_avatars),
                    rows = emptyList(),
                    total = u.avatarsBytes,
                )
                Spacer(modifier = Modifier.height(CocoSpacing.lg))
                StorageCard(
                    title = stringResource(R.string.storage_temp),
                    rows = emptyList(),
                    total = u.tempBytes,
                )
                Spacer(modifier = Modifier.height(CocoSpacing.lg))

                Text(
                    text = stringResource(R.string.storage_total_cache, FileSizeFormatter.format(u.totalCacheBytes)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.primaryText,
                    modifier = Modifier.padding(vertical = CocoSpacing.sm),
                )

                Spacer(modifier = Modifier.height(CocoSpacing.lg))
                // Informational only — never a clear/delete affordance.
                Text(
                    text = stringResource(R.string.storage_database, FileSizeFormatter.format(u.databaseBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.mutedText,
                )

                Spacer(modifier = Modifier.height(CocoSpacing.xl))

                if (clearFailedCount > 0) {
                    Text(
                        text = stringResource(R.string.storage_clear_partial),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                        modifier = Modifier.padding(bottom = CocoSpacing.sm),
                    )
                }

                SettingsSecondaryButton(
                    onClick = { confirmChat = null; showConfirm = true },
                    destructive = true,
                    enabled = !clearing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (clearing) stringResource(R.string.storage_clearing)
                        else stringResource(R.string.storage_clear_media),
                    )
                }
                Spacer(Modifier.height(CocoSpacing.lg))
                Text(stringResource(R.string.cache_by_chat), style = MaterialTheme.typography.titleMedium, color = colors.primaryText)
                chatUsage.forEach { entry ->
                    val chat = chats.firstOrNull { it.chatId == entry.chatId }
                    val title = chat?.otherUser?.displayName ?: if (chat?.type == "saved") stringResource(R.string.cache_saved_messages) else stringResource(R.string.cache_chat_fallback, entry.chatId.take(8))
                    SettingActionRow(title, FileSizeFormatter.format(entry.bytes), !clearing) { confirmChat = entry.chatId; showConfirm = true }
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.storage_clear_confirm_title)) },
            text = { Text(stringResource(R.string.storage_clear_confirm_text)) },
            confirmButton = {
                TextButton(destructive = true, onClick = { showConfirm = false; viewModel.clear(confirmChat) }) {
                    Text(stringResource(R.string.storage_clear_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun StorageCard(title: String, rows: List<Pair<String, Long>>, total: Long) {
    val colors = LocalCocoColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                shape = CocoShapes.radiusCard,
                color = colors.surface,
            ).padding(CocoSpacing.md),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.primaryText,
        )
        Spacer(modifier = Modifier.height(CocoSpacing.xs))
        rows.forEach { (label, bytes) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = CocoSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = FileSizeFormatter.format(bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.primaryText,
                )
            }
        }
        if (rows.isEmpty()) {
            Text(
                text = FileSizeFormatter.format(total),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.primaryText,
            )
        } else {
            Spacer(modifier = Modifier.height(CocoSpacing.xs))
            Text(
                text = stringResource(R.string.storage_subtotal, FileSizeFormatter.format(total)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.mutedText,
            )
        }
    }
}
