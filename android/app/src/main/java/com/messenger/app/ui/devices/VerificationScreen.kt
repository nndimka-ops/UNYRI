package com.messenger.app.ui.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.messenger.app.ui.settings.SettingsDialogButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.data.local.signal.PeerIdentityEntity
import com.messenger.app.data.signal.IDENTITY_CHANGED
import com.messenger.app.data.signal.IDENTITY_VERIFIED
import com.messenger.app.ui.theme.LocalCocoColors

@Composable
fun VerificationScreen(viewModel: VerificationViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var showAll by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    MaterialTheme {
        com.messenger.app.ui.theme.CocoScreenSurface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.safeDrawingPadding().padding(16.dp).fillMaxSize()) {
                com.messenger.app.ui.components.CocoPageHeader(
                    title = stringResource(R.string.verify_title),
                    start = { com.messenger.app.ui.components.CocoBackButton(onClick = onBack) },
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        Text(stringResource(R.string.verify_explanation), style = MaterialTheme.typography.bodyMedium)
                        if (showAll) Text(stringResource(R.string.verify_hint), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { showAll = !showAll }) { Text(stringResource(if (showAll) R.string.verify_only_changes else R.string.verify_all_devices)) }
                    }
                    item {
                        if (uiState.loading) {
                            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.verify_loading))
                        } else if (uiState.loadFailed) {
                            Text(stringResource(R.string.verify_load_failed))
                            TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.verify_retry_load)) }
                        } else if (uiState.peers.isEmpty()) {
                            Text(stringResource(R.string.verify_no_devices))
                        } else if (!showAll && uiState.peers.none { it.status == IDENTITY_CHANGED }) {
                            Text(stringResource(R.string.verify_no_changes))
                        }
                    }
                    items(uiState.peers.filter { showAll || it.status == IDENTITY_CHANGED }.sortedBy { if (it.status == IDENTITY_CHANGED) 0 else 1 }, key = { it.signalDeviceId }) { peer -> PeerRow(peer, viewModel) }
                    item { uiState.actionError?.let { Text(stringResource(R.string.verify_action_failed), color = MaterialTheme.colorScheme.error) } }
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerIdentityEntity, viewModel: VerificationViewModel) {
    val colors=LocalCocoColors.current
    var confirm by androidx.compose.runtime.remember(peer.signalDeviceId) { androidx.compose.runtime.mutableStateOf<PeerIdentityEntity?>(null) }
    confirm?.let { snapshot ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.verify_confirm_title)) },
            text = { Text(stringResource(R.string.verify_confirm_message),Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { confirm = null; viewModel.acceptNewKey(snapshot) }) { Text(stringResource(R.string.verify_confirm_action),color=colors.warning) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.back)) } })
    }
    var expanded by androidx.compose.runtime.remember(peer.signalDeviceId) { androidx.compose.runtime.mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),shape=RoundedCornerShape(20.dp),
        colors=CardDefaults.cardColors(containerColor=colors.surface,contentColor=colors.primaryText)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.verify_device, peer.signalDeviceId), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(stringResource(if (peer.status == IDENTITY_VERIFIED) R.string.verify_verified else if (peer.status == IDENTITY_CHANGED) R.string.verify_changed_status else R.string.verify_unverified_status), style = MaterialTheme.typography.bodySmall,
                    color=when(peer.status){IDENTITY_VERIFIED->colors.success;IDENTITY_CHANGED->colors.warning;else->colors.secondaryText})
            }
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.verify_code_details)) }
            if (expanded) peer.safetyNumber?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Spacer(modifier = Modifier.height(4.dp))
            when (peer.status) {
                IDENTITY_CHANGED -> TextButton(onClick = { confirm = peer }) { Text(stringResource(R.string.security_accept_new_key),color=colors.warning) }
                IDENTITY_VERIFIED -> Text(stringResource(R.string.verify_verified), style = MaterialTheme.typography.bodySmall)
                else -> TextButton(onClick = { viewModel.markVerified(peer.signalDeviceId) }) { Text(stringResource(R.string.verify_mark_verified)) }
            }
        }
    }
}
