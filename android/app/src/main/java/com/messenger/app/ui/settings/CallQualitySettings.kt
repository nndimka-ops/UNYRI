package com.messenger.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.data.call.CallQuality
import com.messenger.app.data.call.CallQualityPreferences

@Composable
fun CallQualitySettings() {
    val context = LocalContext.current
    val colors = com.messenger.app.ui.theme.LocalCocoColors.current
    val prefs = remember(context) { context.getSharedPreferences(CallQualityPreferences.STORE, Context.MODE_PRIVATE) }
    var video by remember { mutableStateOf(CallQuality.parse(prefs.getString(CallQualityPreferences.VIDEO, null))) }
    var audio by remember { mutableStateOf(CallQuality.parse(prefs.getString(CallQualityPreferences.AUDIO, null))) }
    var editing by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.call_quality_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    listOf(CallQualityPreferences.VIDEO to video, CallQualityPreferences.AUDIO to audio).forEach { (key, value) ->
        SettingsSecondaryButton(onClick = { editing = key }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(if (key == CallQualityPreferences.VIDEO) R.string.call_quality_video else R.string.call_quality_audio), modifier = Modifier.weight(1f).padding(end = 12.dp))
                Text(stringResource(qualityLabel(value)))
            }
        }
    }
    Text(stringResource(R.string.call_quality_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    editing?.let { key ->
        val selected = if (key == CallQualityPreferences.VIDEO) video else audio
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(stringResource(if (key == CallQualityPreferences.VIDEO) R.string.call_quality_video else R.string.call_quality_audio)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                CallQuality.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(selected == mode, role = Role.RadioButton, onClick = {
                        prefs.edit().putString(key, mode.wire).apply()
                        if (key == CallQualityPreferences.VIDEO) video = mode else audio = mode
                        editing = null
                    }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == mode, onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = if (mode == CallQuality.ECONOMY) colors.categoryMint else colors.categoryBlue))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(stringResource(qualityLabel(mode)), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(when(mode) {
                                CallQuality.AUTO -> R.string.call_quality_auto_detail
                                CallQuality.ECONOMY -> if (key == CallQualityPreferences.VIDEO) R.string.call_quality_video_economy else R.string.call_quality_audio_economy
                                CallQuality.HIGH -> if (key == CallQualityPreferences.VIDEO) R.string.call_quality_video_high else R.string.call_quality_audio_high
                            }), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } }, confirmButton = { SettingsDialogButton(onClick = { editing = null }) { Text(stringResource(R.string.dismiss)) } })
    }
}
private fun qualityLabel(mode: CallQuality) = when(mode) {
    CallQuality.AUTO -> R.string.call_quality_auto
    CallQuality.ECONOMY -> R.string.call_quality_economy
    CallQuality.HIGH -> R.string.call_quality_high
}
