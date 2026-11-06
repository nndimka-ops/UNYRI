package com.messenger.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.push.NotificationChannels
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.ui.theme.LocalCocoColors

/** Used inside the settings page's existing scroll view; no nested scroll or overlay. */
@Composable
fun NotificationSettingsContent() {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val preferences by app.notificationSettings.settings.collectAsState()
    val colors = LocalCocoColors.current
    val view = LocalView.current
    var systemEnabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    fun refreshPermission() { systemEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshPermission() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun systemSettings(channel: String? = null) {
        CocoHaptics.select(view)
        val intent = if (channel == null) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermission() }
    Column(Modifier.fillMaxWidth().testTag("notification-settings"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.notification_settings_device_only), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
        NotificationSettingsCard {
            NotificationSettingsToggle(R.string.notification_settings_enabled, R.string.notification_settings_enabled_detail,
                preferences.enabled, true, "notifications-enabled") {
                CocoHaptics.select(view); app.notificationSettings.update { current -> current.copy(enabled = it) }
            }
        }
        NotificationSettingsCard {
            NotificationSettingsToggle(R.string.notification_settings_messages, R.string.notification_settings_messages_detail,
                preferences.messages, preferences.enabled, "notifications-messages") {
                CocoHaptics.select(view); app.notificationSettings.update { current -> current.copy(messages = it) }
            }
            HorizontalDivider(color = colors.outlineSubtle)
            NotificationSettingsToggle(R.string.notification_settings_calls, R.string.notification_settings_calls_detail,
                preferences.calls, preferences.enabled, "notifications-calls") {
                CocoHaptics.select(view); app.notificationSettings.update { current -> current.copy(calls = it) }
            }
            HorizontalDivider(color = colors.outlineSubtle)
            NotificationSettingsToggle(R.string.notification_settings_sound, R.string.notification_settings_sound_detail,
                preferences.messageSound, preferences.messageAlertsEnabled, "notifications-sound") {
                CocoHaptics.select(view); app.notificationSettings.update { current -> current.copy(messageSound = it) }
            }
            HorizontalDivider(color = colors.outlineSubtle)
            NotificationSettingsToggle(R.string.notification_settings_in_app, R.string.notification_settings_in_app_detail,
                preferences.inAppBanners, preferences.messageAlertsEnabled, "notifications-in-app") {
                CocoHaptics.select(view); app.notificationSettings.update { current -> current.copy(inAppBanners = it) }
            }
        }
        NotificationSettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(if (systemEnabled) R.string.notification_settings_system_on else R.string.notification_settings_system_off),
                    style = MaterialTheme.typography.titleSmall, color = if (systemEnabled) colors.categoryMint else colors.categoryAmber)
                Text(stringResource(R.string.notification_settings_system_detail), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                if (!systemEnabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    SettingsPrimaryButton(modifier = Modifier.fillMaxWidth().testTag("notifications-request-permission"), onClick = {
                        CocoHaptics.select(view); permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text(stringResource(R.string.notification_settings_allow)) }
                }
                SettingsSecondaryButton(modifier = Modifier.fillMaxWidth().testTag("notifications-system-settings"), onClick = { systemSettings() }) {
                    Text(stringResource(R.string.notification_settings_system_open))
                }
                SettingsSecondaryButton(modifier = Modifier.fillMaxWidth(), onClick = { NotificationChannels.ensure(context); systemSettings(NotificationChannels.CHANNEL_MESSAGES) }) {
                    Text(stringResource(R.string.notification_settings_message_channel))
                }
                SettingsSecondaryButton(modifier = Modifier.fillMaxWidth(), onClick = { systemSettings(com.messenger.app.call.IncomingCallChannelSpec.CHANNEL_ID) }) {
                    Text(stringResource(R.string.notification_settings_call_channel))
                }
            }
        }
        NotificationSettingsCard {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.notification_settings_local), style = MaterialTheme.typography.titleMedium,
                    color = colors.primaryText, modifier = Modifier.padding(top = 16.dp))
                Text(stringResource(R.string.notification_settings_local_detail), style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText, modifier = Modifier.padding(top = 8.dp))
                LocalConnectionSettings()
            }
        }
    }
}

@Composable
private fun NotificationSettingsCard(content: @Composable () -> Unit) {
    val colors = LocalCocoColors.current
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineSubtle)) { Column { content() } }
}

@Composable
private fun NotificationSettingsToggle(title: Int, detail: Int, checked: Boolean, enabled: Boolean, tag: String, changed: (Boolean) -> Unit) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = changed).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = if (enabled) colors.primaryText else colors.mutedText)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.categoryMint, checkedThumbColor = colors.onAccent))
    }
}
