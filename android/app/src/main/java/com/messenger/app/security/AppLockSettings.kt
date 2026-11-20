package com.messenger.app.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.LocalCocoColors

@Composable
fun AppLockSettings() {
    var context = LocalContext.current
    while (context is android.content.ContextWrapper && context !is com.messenger.app.MainActivity) context = context.baseContext
    val activity = context as? com.messenger.app.MainActivity ?: return
    val controller = activity.appLockController
    val state by controller.state.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        com.messenger.app.ui.profile.SettingsToggleRow(
            title = stringResource(R.string.app_lock_title), subtitle = stringResource(R.string.app_lock_detail),
            checked = state.enabled, enabled = !state.authenticating, onCheckedChange = controller::setEnabled)
        com.messenger.app.ui.profile.SettingsToggleRow(
            title = stringResource(R.string.privacy_prevent_screenshots), subtitle = null,
            checked = state.preventScreenshots, onCheckedChange = controller::setPreventScreenshots)
        if (state.enabled) {
            val options = listOf(0L to stringResource(R.string.app_lock_immediate), 60_000L to stringResource(R.string.app_lock_minute), 300_000L to stringResource(R.string.app_lock_five_minutes))
            Box(Modifier.padding(horizontal = 14.dp)) {
                com.messenger.app.ui.settings.SettingChoiceRow(stringResource(R.string.app_lock_after_background), options.firstOrNull { it.first == state.timeoutMs }?.second ?: options.first().second, options, !state.authenticating, controller::setTimeout)
            }
        }
        if (state.error) Text(stringResource(R.string.app_lock_error), color = LocalCocoColors.current.danger, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AppLockShield(controller: AppLockController) {
    val state by controller.state.collectAsState()
    val colors = LocalCocoColors.current
    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.app_lock_locked), style = MaterialTheme.typography.titleLarge, color = colors.primaryText)
            com.messenger.app.ui.settings.SettingsPrimaryButton(onClick = { controller.unlock() }, enabled = !state.authenticating) { Text(stringResource(R.string.app_lock_unlock)) }
            if (state.error) Text(stringResource(R.string.app_lock_error), color = colors.danger)
        }
    }
}
