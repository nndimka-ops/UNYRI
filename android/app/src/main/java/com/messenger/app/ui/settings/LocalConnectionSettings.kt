package com.messenger.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.SocketConnectionState

@Composable
fun LocalConnectionSettings() {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val controller = app.localMessaging
    val enabled by controller.enabled.collectAsState()
    val running by controller.running.collectAsState()
    val connection by controller.connection.collectAsState()
    val auth by app.authRepository.authState.collectAsState()
    val signedIn = auth is AuthSessionState.LoggedIn
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = enabled, enabled = signedIn,
            role = Role.Switch, onValueChange = controller::setEnabled), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.local_mode_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = null, enabled = signedIn)
        }
        Text(stringResource(R.string.local_mode_description), style = MaterialTheme.typography.bodySmall)
        if (!signedIn) Text(stringResource(R.string.local_mode_signin), style = MaterialTheme.typography.bodySmall)
        if (enabled) {
            Text(stringResource(when {
                !running -> R.string.local_mode_stopped
                connection == SocketConnectionState.CONNECTED -> R.string.local_mode_connected
                else -> R.string.local_mode_reconnecting
            }), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            SettingsSecondaryButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                val manager = context.getSystemService(PowerManager::class.java)
                val intent = if (manager.isIgnoringBatteryOptimizations(context.packageName))
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
            }) { Text(stringResource(R.string.local_mode_battery)) }
            SettingsSecondaryButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
            }) { Text(stringResource(R.string.local_mode_notifications)) }
        }
    }
}
