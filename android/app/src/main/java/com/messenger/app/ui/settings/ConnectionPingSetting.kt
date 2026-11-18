package com.messenger.app.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.messenger.app.R

@Composable fun rememberConnectionPingEnabled(): State<Boolean> {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("coco-appearance", Context.MODE_PRIVATE) }
    val state = remember(prefs) { mutableStateOf(prefs.getBoolean("connection-ping", false)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "connection-ping") state.value = prefs.getBoolean(key, false) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
@Composable fun ConnectionPingSetting() {
    val enabled by rememberConnectionPingEnabled()
    val context = LocalContext.current
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = {
        com.messenger.app.ui.theme.CocoHaptics.select(view)
        context.getSharedPreferences("coco-appearance", Context.MODE_PRIVATE).edit().putBoolean("connection-ping", it).apply()
    }).padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.connection_ping_setting), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.connection_ping_detail), style = MaterialTheme.typography.bodySmall, color = com.messenger.app.ui.theme.LocalCocoColors.current.secondaryText)
        }
        Switch(enabled, onCheckedChange = null)
    }
}
