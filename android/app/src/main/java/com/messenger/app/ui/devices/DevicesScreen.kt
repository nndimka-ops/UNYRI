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
import androidx.compose.material3.AlertDialog
import com.messenger.app.ui.settings.SettingsPrimaryButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.messenger.app.ui.settings.SettingsDialogButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.model.DeviceInfo
import com.messenger.app.domain.model.SecurityEventInfo
import com.messenger.app.ui.theme.LocalCocoColors

@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    onBack: () -> Unit,
) {
    val colors = LocalCocoColors.current
    val uiState by viewModel.uiState.collectAsState()
    var confirmOthers by remember(viewModel) { mutableStateOf(false) }

    if(confirmOthers) AlertDialog(
        onDismissRequest={confirmOthers=false},
        title={Text(stringResource(R.string.devices_revoke_all))},
        text={Text(stringResource(R.string.devices_revoke_others_confirmation),Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(destructive=true,
            enabled=(uiState as? DevicesUiState.Loaded)?.devices?.any{!it.isCurrent}==true,
            onClick={if(confirmOthers){confirmOthers=false;viewModel.revokeOthers()}}) {Text(stringResource(R.string.devices_revoke_all))}},
        dismissButton={TextButton(onClick={confirmOthers=false}){Text(stringResource(R.string.cancel))}},
    )

    MaterialTheme {
        com.messenger.app.ui.theme.CocoScreenSurface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.safeDrawingPadding().padding(16.dp).fillMaxSize()) {
                com.messenger.app.ui.components.CocoPageHeader(
                    title = stringResource(R.string.devices_title),
                    start = { com.messenger.app.ui.components.CocoBackButton(onClick = onBack) },
                )

                when (val state = uiState) {
                    DevicesUiState.Loading -> CircularProgressIndicator()
                    is DevicesUiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    is DevicesUiState.Loaded -> {
                        if (state.isCached) Text(stringResource(R.string.offline_saved_data))
                        com.messenger.app.ui.settings.SettingsSecondaryButton(destructive = true,
                            enabled = state.devices.any { !it.isCurrent },
                            onClick = { confirmOthers = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.devices_revoke_all))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.devices_active), style = MaterialTheme.typography.titleMedium, color = colors.categoryBlue)
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(state.devices,key={it.id}) { device -> DeviceRow(device, onRevoke = { viewModel.revoke(device.id) }) }
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(stringResource(R.string.devices_security_events), style = MaterialTheme.typography.titleMedium)
                            }
                            items(state.events) { event -> EventRow(event) }
                        }
                        state.actionError?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceInfo, onRevoke: () -> Unit) {
    var confirm by remember(device.id) { mutableStateOf(false) }
    val colors=LocalCocoColors.current
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),shape=RoundedCornerShape(20.dp),
        colors=CardDefaults.cardColors(containerColor=colors.surface,contentColor=colors.primaryText)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (device.isCurrent) stringResource(R.string.devices_current_name, device.displayName) else device.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (device.isCurrent) colors.success else colors.primaryText,
                    modifier = Modifier.weight(1f),
                )
                if (!device.isCurrent) {
                    TextButton(destructive = true, onClick = { confirm = true }) { Text(stringResource(R.string.revoke)) }
                }
            }
            val meta = listOfNotNull(device.osVersion, device.appVersion).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall,color=colors.secondaryText)
            device.lastActiveAt?.let { Text(stringResource(R.string.devices_last_active, readableDeviceDate(it)), style = MaterialTheme.typography.bodySmall,color=colors.secondaryText) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.devices_revoke_title)) },
            text = { Text(stringResource(R.string.devices_revoke_text, device.displayName),Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(destructive = true, onClick = { confirm = false; onRevoke() }) { Text(stringResource(R.string.revoke)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun EventRow(event: SecurityEventInfo) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(stringResource(when (event.eventType) {
            "new_device" -> R.string.security_event_new_device
            "device_revoked" -> R.string.security_event_device_revoked
            "all_other_devices_revoked" -> R.string.security_event_all_revoked
            "contact_device_added" -> R.string.security_contact_device
            "contact_identity_changed" -> R.string.security_contact_identity
            else -> R.string.security_event_other
        }) + when (event.eventType) {
            "new_device", "device_revoked" -> event.description.substringAfter(": ", "").takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            "all_other_devices_revoked" -> event.description.substringAfter(" (", "").takeIf { it.isNotBlank() }?.let { " ($it" }.orEmpty()
            else -> ""
        }, style = MaterialTheme.typography.bodyMedium)
        Text(readableDeviceDate(event.createdAt), style = MaterialTheme.typography.bodySmall)
    }
}

internal fun readableDeviceDate(raw: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
        .withLocale(java.util.Locale.getDefault()).withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.parse(raw))
}.getOrDefault("—")
