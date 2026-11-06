package com.messenger.app.ui.chats

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.directtransfer.DirectFilePresentation
import com.messenger.app.ui.BlockRootDrawerWhile
import com.messenger.app.ui.transfers.*
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors

/** Compact document card. Byte updates do not change its control hit targets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DirectFileCard(value: DirectFilePresentation, selectionMode: Boolean, onSelection: () -> Unit, text: String? = null) {
    val context = LocalContext.current
    val directUnits = listOf(stringResource(R.string.direct_unit_b), stringResource(R.string.direct_unit_kb), stringResource(R.string.direct_unit_mb), stringResource(R.string.direct_unit_gb))
    val directLocale = context.resources.configuration.locales[0]
    fun directSize(bytes: Long) = com.messenger.app.data.directtransfer.DirectTransferFormat.size(bytes, directLocale, directUnits)
    val colors = LocalCocoColors.current
    val coordinator = (context.applicationContext as MessengerApp).directTransfers
    var restartConfirm by remember(value.offer.transferId) { mutableStateOf(false) }
    var openFailed by remember(value.offer.transferId) { mutableStateOf(false) }
    val progress by animateFloatAsState((value.offset.toDouble() / value.offer.size.toDouble()).coerceIn(0.0, 1.0).toFloat(), label = "directFileProgress")
    fun invoke(action: () -> Unit) { if (selectionMode) onSelection() else action() }
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val permissions = DirectTransferUiPolicy.cardPermissions(value)
    val status = DirectTransferUiPolicy.status(value)
    val canCancel = DirectTransferUiPolicy.canCancel(value, permissions)
    val canRestart = DirectTransferUiPolicy.canRestart(value, permissions)
    val canRetry = DirectTransferUiPolicy.canRetry(value, permissions)
    val canOpen = DirectTransferUiPolicy.canOpen(value, permissions)
    val manualAction = DirectTransferUiPolicy.manualAction(value)
    val canRetrySync = DirectTransferUiPolicy.canRetrySynchronization(value)
    fun retryOriginal() { if (canRestart) restartConfirm = true else if (canRetry) coordinator.retry(value.offer.transferId) }
    fun openOriginal() {
        val item = coordinator.completedFile(value.offer.transferId)
        openFailed = item == null || runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", item.first, item.second.filename)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.second.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, item.second.filename, uri)
            context.startActivity(Intent.createChooser(intent, item.second.filename))
        }.isFailure
    }
    @Composable fun identity(modifier: Modifier) {
        Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CocoIcons.File, contentDescription = null, tint = colors.primaryText, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // Text semantics retain the complete filename even when its visual lines elide.
                Text(value.offer.filename, style = MaterialTheme.typography.bodyMedium, color = colors.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(directSize(value.offer.size), style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
            }
        }
    }
    @Composable fun actions(modifier: Modifier = Modifier) {
        Row(modifier, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (manualAction != null) {
                IconButton(onClick = {
                    directTransferManualClick(value, selectionMode, onSelection, coordinator::manualPause, coordinator::manualContinue)
                }, modifier = Modifier.size(48.dp)) {
                    Icon(if (manualAction == DirectTransferManualAction.CONTINUE) CocoIcons.Play else CocoIcons.Pause,
                        stringResource(directTransferManualActionResource(value, manualAction)), tint = colors.primaryText)
                }
            }
            if (canRetrySync) {
                IconButton(onClick = { invoke { coordinator.retryManualSync(value.offer.transferId) } }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.direct_retry_sync), tint = colors.primaryText)
                }
            } else if (canRestart || canRetry) {
                val label = stringResource(if (canRestart) R.string.direct_restart else R.string.direct_resume)
                if (manualAction != null) {
                    IconButton(onClick = { invoke { retryOriginal() } }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Refresh, label, tint = colors.primaryText)
                    }
                } else {
                    TextButton(onClick = { invoke { retryOriginal() } }, modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp)) {
                        Text(label, color = colors.primaryText)
                    }
                }
            }
            if (canCancel) {
                IconButton(onClick = { invoke { coordinator.cancel(value.offer.transferId) } }, modifier = Modifier.size(48.dp)) {
                    Icon(CocoIcons.Close, stringResource(R.string.direct_cancel), tint = colors.secondaryText)
                }
            } else if (canOpen) {
                TextButton(onClick = { invoke { openOriginal() } }, modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.direct_open), color = colors.primaryText)
                }
            } else if (value.completed && value.role == "sender") {
                Icon(CocoIcons.Check, stringResource(R.string.direct_completed), tint = colors.secondaryText, modifier = Modifier.size(22.dp))
            }
        }
    }
    Column(Modifier.widthIn(min = 180.dp, max = 250.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Three 48dp controls otherwise consume144dp, leaving no filename glyphs at180dp.
        val hasThreeControls = manualAction != null && (canRetrySync || canRestart || canRetry) && canCancel
        // Text actions have their own row so neither identity nor the fixed Cancel target
        // borrows their width. A long label wraps within the remaining weighted space.
        val hasHeaderTextAction = canOpen || !canRetrySync && manualAction == null && (canRestart || canRetry)
        if (largeText || hasThreeControls || hasHeaderTextAction) {
            identity(Modifier.fillMaxWidth())
            actions(Modifier.fillMaxWidth())
        } else {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                identity(Modifier.weight(1f))
                actions()
            }
        }
        if (value.state in setOf("transferring", "verifying", "connecting")) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = colors.primaryText, trackColor = colors.secondaryText.copy(alpha = .15f))
        }
        val rate = if (status == DirectTransferStatus.TRANSFERRING && value.speedBytesPerSecond > 0) {
            val seconds = requireNotNull(com.messenger.app.data.directtransfer.DirectTransferFormat.secondsRemaining(value.offset, value.offer.size, value.speedBytesPerSecond))
            stringResource(R.string.direct_rate_eta, directSize(value.speedBytesPerSecond),
                com.messenger.app.data.directtransfer.DirectTransferFormat.clock(seconds) ?: stringResource(R.string.direct_eta_long))
        } else stringResource(directTransferStatusResource(status))
        if (status in setOf(DirectTransferStatus.TRANSFERRING, DirectTransferStatus.VERIFYING)) {
            val acknowledged = String.format(directLocale, "%d%% · %s", (value.offset * 100 / value.offer.size).toInt(), directSize(value.offset))
            if (largeText) {
                Text(acknowledged, style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
                Text(rate, style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
            } else {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(acknowledged, modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelSmall,
                        color = colors.secondaryText)
                    Text(rate, modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
                }
            }
        } else {
            Text(rate, style = MaterialTheme.typography.labelMedium, color = colors.secondaryText,
                maxLines = Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        }
        if (DirectTransferUiPolicy.canOfferActions(value, permissions)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { invoke { coordinator.decline(value.offer.transferId) } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.direct_decline), color = colors.secondaryText) }
                TextButton(onClick = { invoke { coordinator.accept(value.offer.transferId) } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.direct_accept), color = colors.primaryText) }
            }
        }
        com.messenger.app.data.directtransfer.DirectFileOffer.caption(text)?.let { caption ->
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = colors.primaryText)
        }
        if (openFailed) Text(stringResource(R.string.direct_open_failed), style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
    }
    BlockRootDrawerWhile(restartConfirm)
    if (restartConfirm) AlertDialog(onDismissRequest = { restartConfirm = false }, title = { Text(stringResource(R.string.direct_restart)) },
        text = { Text(stringResource(R.string.direct_restart_note)) }, confirmButton = { TextButton(onClick = {
            restartConfirm = false; invoke { if (canRestart) coordinator.retry(value.offer.transferId, restartFromZero = true) }
        }) { Text(stringResource(R.string.direct_restart)) } }, dismissButton = { TextButton(onClick = { restartConfirm = false }) { Text(stringResource(R.string.cancel)) } })
}
