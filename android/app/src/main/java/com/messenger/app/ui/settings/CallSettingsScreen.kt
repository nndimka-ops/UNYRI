package com.messenger.app.ui.settings

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.data.call.CallDataSaving
import com.messenger.app.data.call.CallPeerToPeer
import com.messenger.app.data.call.CallQualityPreferences
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.LocalGlassBottomInset

/** Device-local call controls; the call engine reads the same store on its existing policy path. */
@Composable
fun CallSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(CallQualityPreferences.STORE, Context.MODE_PRIVATE) }
    var saving by remember(prefs) { mutableStateOf(CallDataSaving.resolved(prefs.getString(CallDataSaving.KEY, null),
        prefs.getString(CallQualityPreferences.VIDEO, null), prefs.getString(CallQualityPreferences.AUDIO, null))) }
    var peer by remember(prefs) { mutableStateOf(CallPeerToPeer.resolved(prefs.getString(CallPeerToPeer.KEY, null))) }
    SettingsScaffold(stringResource(R.string.call_settings_title), onBack) {
        Column(Modifier.fillMaxSize().testTag("call-settings-screen").verticalScroll(rememberScrollState())
            .padding(bottom = LocalGlassBottomInset.current + 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CallSettingsGroup(stringResource(R.string.call_settings_saving_title), stringResource(R.string.call_settings_saving_note)) {
                CallDataSaving.entries.forEach { mode ->
                    CallSettingsChoice(stringResource(when (mode) {
                        CallDataSaving.NEVER -> R.string.call_settings_saving_never
                        CallDataSaving.CELLULAR -> R.string.call_settings_saving_cellular
                        CallDataSaving.ALWAYS -> R.string.call_settings_saving_always
                    }), stringResource(when (mode) {
                        CallDataSaving.NEVER -> R.string.call_settings_saving_never_detail
                        CallDataSaving.CELLULAR -> R.string.call_settings_saving_cellular_detail
                        CallDataSaving.ALWAYS -> R.string.call_settings_saving_always_detail
                    }), saving == mode, "call-data-saving-${mode.wire}") {
                        if (saving != mode) {
                            prefs.edit().putString(CallDataSaving.KEY, mode.wire).apply()
                            saving = mode
                        }
                    }
                }
            }
            CallSettingsGroup(stringResource(R.string.call_p2p_title), stringResource(R.string.call_settings_peer_note)) {
                CallPeerToPeer.entries.forEach { mode ->
                    CallSettingsChoice(stringResource(when (mode) {
                        CallPeerToPeer.EVERYONE -> R.string.call_p2p_everyone
                        CallPeerToPeer.CONTACTS -> R.string.call_p2p_contacts
                        CallPeerToPeer.NOBODY -> R.string.call_p2p_nobody
                    }), stringResource(when (mode) {
                        CallPeerToPeer.EVERYONE -> R.string.call_settings_peer_everyone_detail
                        CallPeerToPeer.CONTACTS -> R.string.call_settings_peer_contacts_detail
                        CallPeerToPeer.NOBODY -> R.string.call_settings_peer_nobody_detail
                    }), peer == mode, "call-peer-${mode.wire}") {
                        if (peer != mode) {
                            prefs.edit().putString(CallPeerToPeer.KEY, mode.wire).apply()
                            peer = mode
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallSettingsGroup(title: String, footer: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalCocoColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.secondaryText,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineSubtle)) {
            Column(Modifier.fillMaxWidth().selectableGroup(), content = content)
        }
        Text(footer, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun CallSettingsChoice(title: String, detail: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag(tag)
        .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.primaryText)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
        }
        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.categoryMint,
            unselectedColor = colors.mutedText))
    }
}
