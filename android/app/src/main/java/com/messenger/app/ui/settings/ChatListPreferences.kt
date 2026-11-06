package com.messenger.app.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.messenger.app.R

@Composable
fun rememberChatFiltersEnabled(): State<Boolean> {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("coco-appearance", Context.MODE_PRIVATE) }
    val enabled = remember(prefs) { mutableStateOf(prefs.getBoolean("chat-filters", true)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "chat-filters") enabled.value = prefs.getBoolean(key, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

@Composable
fun ChatFiltersSetting() {
    val enabled by rememberChatFiltersEnabled()
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = {
        context.getSharedPreferences("coco-appearance", Context.MODE_PRIVATE).edit().putBoolean("chat-filters", it).apply()
    }).padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.chat_filters_setting), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.chat_filters_setting_detail), style = MaterialTheme.typography.bodySmall,
                color = com.messenger.app.ui.theme.LocalCocoColors.current.secondaryText)
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}
