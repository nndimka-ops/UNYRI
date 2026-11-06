package com.messenger.app.ui.transfers

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.directtransfer.DirectFilePresentation
import com.messenger.app.data.directtransfer.DirectTransferFormat
import com.messenger.app.ui.BlockRootDrawerWhile
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors

/** Shared by document cards and this device's manager; clicks carry only transfer ID. */
@Composable
internal fun DirectTransferCancelButton(
    value: DirectFilePresentation,
    selectionMode: Boolean,
    onSelection: () -> Unit,
    permissions: DirectTransferActionPermissions = DirectTransferUiPolicy.cardPermissions(value),
) {
    val coordinator = (LocalContext.current.applicationContext as MessengerApp).directTransfers
    if (DirectTransferUiPolicy.canCancel(value, permissions)) {
        IconButton(onClick = { if (selectionMode) onSelection() else coordinator.cancel(value.offer.transferId) }, modifier = Modifier.size(44.dp)) {
            Icon(CocoIcons.Close, stringResource(R.string.direct_cancel), tint = LocalCocoColors.current.secondaryText)
        }
    }
}

@Composable
internal fun DirectTransferStatusActions(
    value: DirectFilePresentation,
    selectionMode: Boolean,
    onSelection: () -> Unit,
    permissions: DirectTransferActionPermissions = DirectTransferUiPolicy.cardPermissions(value),
) {
    val context = LocalContext.current
    val coordinator = (context.applicationContext as MessengerApp).directTransfers
    val colors = LocalCocoColors.current
    var restartConfirm by remember(value.offer.transferId) { mutableStateOf(false) }
    var openFailed by remember(value.offer.transferId) { mutableStateOf(false) }
    fun invoke(action: () -> Unit) { if (selectionMode) onSelection() else action() }
    val status = DirectTransferUiPolicy.status(value)
    val units = listOf(stringResource(R.string.direct_unit_b), stringResource(R.string.direct_unit_kb), stringResource(R.string.direct_unit_mb), stringResource(R.string.direct_unit_gb))
    val locale = context.resources.configuration.locales[0]
    val rate = if (status == DirectTransferStatus.TRANSFERRING && value.speedBytesPerSecond > 0) {
        val seconds = DirectTransferFormat.secondsRemaining(value.offset, value.offer.size, value.speedBytesPerSecond)
        stringResource(R.string.direct_rate_eta, DirectTransferFormat.size(value.speedBytesPerSecond, locale, units),
            seconds?.let(DirectTransferFormat::clock) ?: stringResource(R.string.direct_eta_long))
    } else stringResource(directTransferStatusResource(status))
    Text(rate, style = MaterialTheme.typography.labelMedium, color = colors.secondaryText,
        minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)

    if (DirectTransferUiPolicy.canOfferActions(value, permissions)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { invoke { coordinator.decline(value.offer.transferId) } }, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Text(stringResource(R.string.direct_decline), color = colors.secondaryText)
            }
            TextButton(onClick = { invoke { coordinator.accept(value.offer.transferId) } }, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Text(stringResource(R.string.direct_accept), color = colors.primaryText)
            }
        }
    } else {
        val manualAction = DirectTransferUiPolicy.manualAction(value)
        if (manualAction != null) {
            TextButton(onClick = { invoke {
                if (manualAction == DirectTransferManualAction.CONTINUE) coordinator.manualContinue(value.offer.transferId)
                else coordinator.manualPause(value.offer.transferId)
            } }, modifier = Modifier.heightIn(min = 44.dp)) {
                Icon(if (manualAction == DirectTransferManualAction.CONTINUE) CocoIcons.Play else CocoIcons.Pause, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(directTransferManualActionResource(value, manualAction)), color = colors.primaryText)
            }
        }
        if (DirectTransferUiPolicy.canRetrySynchronization(value)) {
            TextButton(onClick = { invoke { coordinator.retryManualSync(value.offer.transferId) } }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(R.string.direct_retry_sync), color = colors.primaryText)
            }
        } else if (DirectTransferUiPolicy.canRestart(value, permissions) || DirectTransferUiPolicy.canRetry(value, permissions)) {
            val restart = DirectTransferUiPolicy.canRestart(value, permissions)
            TextButton(onClick = { invoke {
                if (restart) restartConfirm = true else coordinator.retry(value.offer.transferId)
            } }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(if (restart) R.string.direct_restart else R.string.direct_resume), color = colors.primaryText)
            }
        } else if (DirectTransferUiPolicy.canOpen(value, permissions)) {
            TextButton(onClick = { invoke {
                // Same validated completedFile and FileProvider path used by the document card.
                val item = coordinator.completedFile(value.offer.transferId)
                openFailed = item == null || runCatching {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", item.first, item.second.filename)
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.second.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData = ClipData.newUri(context.contentResolver, item.second.filename, uri)
                    context.startActivity(Intent.createChooser(intent, item.second.filename))
                }.isFailure
            } }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(R.string.direct_open), color = colors.primaryText)
            }
        }
    }
    if (openFailed) Text(stringResource(R.string.direct_open_failed), style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
    BlockRootDrawerWhile(restartConfirm)
    if (restartConfirm) AlertDialog(onDismissRequest = { restartConfirm = false },
        title = { Text(stringResource(R.string.direct_restart)) }, text = { Text(stringResource(R.string.direct_restart_note)) },
        confirmButton = { TextButton(onClick = {
            restartConfirm = false
            invoke { coordinator.retry(value.offer.transferId, restartFromZero = true) }
        }) { Text(stringResource(R.string.direct_restart)) } },
        dismissButton = { TextButton(onClick = { restartConfirm = false }) { Text(stringResource(R.string.cancel)) } })
}

internal fun directTransferStatusResource(status: DirectTransferStatus): Int = when (status) {
    DirectTransferStatus.COMPLETED -> R.string.direct_completed
    DirectTransferStatus.CANCELLED -> R.string.direct_cancelled
    DirectTransferStatus.REJECTED -> R.string.direct_rejected
    DirectTransferStatus.EXPIRED -> R.string.direct_expired
    DirectTransferStatus.HISTORY -> R.string.direct_history_only
    DirectTransferStatus.OTHER_DEVICE -> R.string.direct_other_device
    DirectTransferStatus.VERIFYING -> R.string.direct_verifying
    DirectTransferStatus.PAUSE_SAVING -> R.string.direct_pause_saving
    DirectTransferStatus.CONTINUE_SAVING -> R.string.direct_continue_saving
    DirectTransferStatus.PAUSE_SAVE_FAILED -> R.string.direct_pause_save_failed
    DirectTransferStatus.CONTINUE_SAVE_FAILED -> R.string.direct_continue_save_failed
    DirectTransferStatus.HELD_BOTH -> R.string.direct_held_both
    DirectTransferStatus.HELD_LOCAL -> R.string.direct_held_local
    DirectTransferStatus.HELD_PEER -> R.string.direct_held_peer
    DirectTransferStatus.SYNC_LIMIT -> R.string.direct_sync_limit
    DirectTransferStatus.CONFIRMING -> R.string.direct_confirming_continue
    DirectTransferStatus.TRANSFERRING -> R.string.direct_transferring
    DirectTransferStatus.CONNECTING -> R.string.direct_connecting
    DirectTransferStatus.QUEUED -> R.string.direct_queued
    DirectTransferStatus.SERVER_CAPACITY -> R.string.direct_server_capacity
    DirectTransferStatus.NO_ROUTE -> R.string.direct_no_route
    DirectTransferStatus.PREFIX_MISMATCH -> R.string.direct_prefix_mismatch
    DirectTransferStatus.DISK_FULL -> R.string.direct_disk_full
    DirectTransferStatus.SOURCE_MISSING -> R.string.direct_source_missing
    DirectTransferStatus.UNSUPPORTED -> R.string.direct_unsupported
    DirectTransferStatus.INTEGRITY_FAILED -> R.string.direct_integrity_failed
    DirectTransferStatus.SECURITY_ERROR -> R.string.direct_security_error
    DirectTransferStatus.CALL_PRIORITY -> R.string.direct_call_priority
    DirectTransferStatus.UNAVAILABLE -> R.string.direct_transfer_unavailable
    DirectTransferStatus.BACKGROUND_INTERRUPTED -> R.string.direct_background_interrupted
    DirectTransferStatus.PAUSED -> R.string.direct_paused
    DirectTransferStatus.OFFERED -> R.string.direct_offer_note
    DirectTransferStatus.WAITING -> R.string.direct_waiting
}

/** Same allowlisted, localized error text in chat and manager; never show raw errors. */
internal fun directTransferErrorResource(reason: String): Int = when (reason) {
    "destination_unavailable" -> R.string.direct_destination_unavailable
    "unsupported" -> R.string.direct_unsupported
    "disk_full" -> R.string.direct_disk_full
    "too_large" -> R.string.direct_too_large
    "too_many" -> R.string.direct_too_many
    "other_device" -> R.string.direct_other_device
    "security_error" -> R.string.direct_security_error
    "transfer_unavailable", "access_unavailable" -> R.string.direct_transfer_unavailable
    "manual_pause_save_failed" -> R.string.direct_pause_save_failed
    "manual_continue_save_failed" -> R.string.direct_continue_save_failed
    "manual_sync_limit" -> R.string.direct_sync_limit
    "manual_sync_unavailable" -> R.string.direct_sync_unavailable
    "manual_sync_cooldown" -> R.string.direct_sync_cooldown
    "background_notification_required" -> R.string.direct_background_notification_required
    "background_unavailable" -> R.string.direct_background_unavailable
    else -> R.string.direct_prepare_failed
}

/** Same label for the same requested durable intent in compact cards and the manager. */
internal fun directTransferManualActionResource(value: DirectFilePresentation, action: DirectTransferManualAction): Int =
    when (value.reason) {
        "manual_pause_save_failed", "manual_continue_save_failed" -> R.string.retry
        else -> if (action == DirectTransferManualAction.CONTINUE) R.string.direct_manual_continue else R.string.direct_manual_pause
    }
