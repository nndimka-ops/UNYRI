package com.messenger.app.ui.transfers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.directtransfer.*
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.ui.components.CocoBackButton
import com.messenger.app.ui.components.UserAvatar
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.widget.Toast

private enum class TransferFilter { ALL, SENDING, RECEIVING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DirectTransferManagerScreen(
    modifier: Modifier = Modifier,
    chatSummaries: StateFlow<List<ChatSummary>>,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as MessengerApp
    val coordinator = app.directTransfers
    val colors = LocalCocoColors.current
    val context = LocalContext.current
    LaunchedEffect(coordinator) {
        coordinator.errors.collect { reason ->
            val resource = directTransferErrorResource(reason)
            Toast.makeText(context, resource, Toast.LENGTH_LONG).show()
        }
    }
    var filter by rememberSaveable { mutableStateOf(TransferFilter.ALL) }
    val chats by chatSummaries.collectAsState()
    val chatsById = remember(chats) { chats.associateBy { it.chatId } }
    // Progress is intentionally absent from this stable ordering signature.
    val orderFlow = remember(coordinator) { coordinator.managerItems.map(DirectTransferUiPolicy::managerOrder).distinctUntilChanged() }
    val order by orderFlow.collectAsState(initial = emptyList())
    val visible = remember(order, filter) { order.filter { filter == TransferFilter.ALL || it.outgoing == (filter == TransferFilter.SENDING) } }
    Column(modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CocoBackButton(onClick = onBack)
            Text(stringResource(R.string.direct_manager_title), Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge, color = colors.primaryText, maxLines = 2)
        }
        Text(stringResource(R.string.direct_manager_device_only), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TransferFilter.entries.forEach { item ->
                FilterChip(selected = filter == item, onClick = { filter = item }, modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    label = { Text(stringResource(when (item) {
                        TransferFilter.ALL -> R.string.direct_filter_all
                        TransferFilter.SENDING -> R.string.direct_filter_sending
                        TransferFilter.RECEIVING -> R.string.direct_filter_receiving
                    }), maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        }
        if (visible.isEmpty()) Text(stringResource(R.string.direct_manager_empty), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DirectTransferManagerGroup.entries.forEach { group ->
                val groupRows = visible.filter { it.group == group }
                if (groupRows.isNotEmpty()) {
                    item("group-$group") { Text(stringResource(groupResource(group)), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = colors.secondaryText) }
                    items(groupRows, key = { it.id }) { row ->
                        // Only this visible ID observes its own four-Hz progress projection.
                        val rowFlow = remember(coordinator, row.id) { coordinator.managerItems.map { list -> list.firstOrNull { it.id == row.id } }.distinctUntilChanged() }
                        val entry by rowFlow.collectAsState(initial = coordinator.managerItems.value.firstOrNull { it.id == row.id })
                        entry?.let { ManagerRow(it, chatsById[it.chatId], onOpenChat) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagerRow(entry: DirectTransferManagerItem, chat: ChatSummary?, onOpenChat: (String) -> Unit) {
    val colors = LocalCocoColors.current
    val context = LocalContext.current
    val coordinator = (context.applicationContext as MessengerApp).directTransfers
    val value = entry.presentation
    val peer = chat?.otherUser
    val name = peer?.let { it.displayName.ifBlank { it.username } } ?: stringResource(R.string.direct_manager_chat)
    val tint = colors.avatarTints[Math.floorMod(entry.chatId.hashCode(), colors.avatarTints.size)]
    val permissions = DirectTransferActionPermissions(entry.canCancel, entry.canOpen, entry.canRetry, entry.canRestartFromZero)
    Surface(color = colors.surface, shape = CocoShapes.radiusMedium, border = BorderStroke(.5.dp, colors.divider)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 44.dp).clickable(enabled = chat != null && entry.chatId.isNotBlank(), role = Role.Button, onClick = { onOpenChat(entry.chatId) }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UserAvatar(peer?.id.orEmpty(), peer?.avatarVersion ?: 0, peer?.hasAvatar == true, name,
                        tint.copy(alpha = .18f), tint, size = 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, color = colors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(if (entry.outgoing) R.string.direct_direction_sending else R.string.direct_direction_receiving), style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
                    }
                }
                if (value != null) DirectTransferCancelButton(value, false, {}, permissions)
                else if (entry.canCancel) IconButton(onClick = { coordinator.cancel(entry.id) }, modifier = Modifier.size(44.dp)) {
                    Icon(CocoIcons.Close, stringResource(R.string.direct_cancel), tint = colors.secondaryText)
                }
            }
            if (value != null) {
                Text(value.offer.filename, style = MaterialTheme.typography.bodyMedium, color = colors.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val units = listOf(stringResource(R.string.direct_unit_b), stringResource(R.string.direct_unit_kb), stringResource(R.string.direct_unit_mb), stringResource(R.string.direct_unit_gb))
                val locale = context.resources.configuration.locales[0]
                Text(stringResource(R.string.direct_progress, (value.offset * 100 / value.offer.size).toInt(),
                    DirectTransferFormat.size(value.offset, locale, units), DirectTransferFormat.size(value.offer.size, locale, units)),
                    style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
                LinearProgressIndicator(progress = { (value.offset.toDouble() / value.offer.size).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(2.dp), color = colors.primaryText, trackColor = colors.secondaryText.copy(alpha = .15f))
                DirectTransferStatusActions(value, false, {}, permissions)
            } else {
                entry.displayFilename?.takeIf { it.isNotBlank() }?.let { filename ->
                    Text(filename, style = MaterialTheme.typography.bodyMedium,
                        color = colors.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                entry.declaredBytes?.takeIf { it in 0..DirectFileWire.MAX_FILE_BYTES }?.let { bytes ->
                    val units = listOf(stringResource(R.string.direct_unit_b), stringResource(R.string.direct_unit_kb), stringResource(R.string.direct_unit_mb), stringResource(R.string.direct_unit_gb))
                    Text(DirectTransferFormat.size(bytes, context.resources.configuration.locales[0], units),
                        style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
                }
                Text(stringResource(if (entry.preparing) R.string.direct_manager_preparing else R.string.direct_manager_reselect),
                    style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
            }
            entry.queuePosition?.takeIf { it > 0 }?.let { position ->
                Text(stringResource(R.string.direct_queue_position, position), style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
            }
        }
    }
}

private fun groupResource(group: DirectTransferManagerGroup): Int = when (group) {
    DirectTransferManagerGroup.ACTIVE -> R.string.direct_group_active
    DirectTransferManagerGroup.QUEUED -> R.string.direct_group_queued
    DirectTransferManagerGroup.HELD -> R.string.direct_group_held
    DirectTransferManagerGroup.ATTENTION -> R.string.direct_group_attention
    DirectTransferManagerGroup.COMPLETED -> R.string.direct_group_completed
}
