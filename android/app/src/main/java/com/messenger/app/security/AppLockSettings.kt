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
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_lock_title), modifier = Modifier.weight(1f), color = LocalCocoColors.current.primaryText)
            Switch(state.enabled, enabled = !state.authenticating, onCheckedChange = controller::setEnabled)
        }
        Text(stringResource(R.string.app_lock_detail), style = MaterialTheme.typography.bodySmall, color = LocalCocoColors.current.mutedText)
        if (state.enabled) {
            val options = listOf(0L to stringResource(R.string.app_lock_immediate), 60_000L to stringResource(R.string.app_lock_minute), 300_000L to stringResource(R.string.app_lock_five_minutes))
            com.messenger.app.ui.settings.SettingChoiceRow(stringResource(R.string.app_lock_after_background), options.firstOrNull { it.first == state.timeoutMs }?.second ?: options.first().second, options, !state.authenticating, controller::setTimeout)
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
