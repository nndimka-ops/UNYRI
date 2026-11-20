package com.messenger.app.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.messenger.app.BuildConfig
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.ui.theme.LocalCocoColors

/** The settings host owns scrolling and navigation; this body performs no background work. */
@Composable
fun HelpApplicationContent(onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val prefs = remember(context) { context.getSharedPreferences("coco_diagnostics", Context.MODE_PRIVATE) }
    var sharing by remember(prefs) { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var online by remember(prefs) { mutableStateOf(prefs.getBoolean("performance_online", false)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            sharing = prefs.getBoolean("enabled", false)
            online = prefs.getBoolean("performance_online", false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val colors = LocalCocoColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineSubtle)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("UNYRI", style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.app_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                if (BuildConfig.DEBUG || BuildConfig.ALLOW_LOCAL_HTTP) Text(stringResource(R.string.alpha_test),
                    style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surface) {
            Column {
                HelpAction(R.string.feedback_title, R.string.report_problem_detail) { onSelect("feedback") }
                HorizontalDivider(color = colors.outlineSubtle)
                HelpAction(R.string.performance_mark, R.string.report_recent_problem_detail, enabled = sharing) {
                    app.performanceDiagnostics.markProblem()
                }
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surface) {
            Column {
                HelpToggle(R.string.settings_diagnostics, R.string.settings_diagnostics_detail, sharing) {
                    prefs.edit().putBoolean("enabled", it).apply()
                }
                HorizontalDivider(color = colors.outlineSubtle)
                HelpToggle(R.string.performance_online, R.string.performance_online_detail, online) {
                    prefs.edit().putBoolean("performance_online", it).apply()
                }
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surface) {
            Column {
                HelpAction(R.string.settings_about_project, null) { onSelect("about") }
                for (id in listOf("terms", "privacy", "support")) {
                    HorizontalDivider(color = colors.outlineSubtle)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onSelect(id) }
                        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(cocoLegalTitle(id), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.mutedText)
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpAction(title: Int, detail: Int?, enabled: Boolean = true, action: () -> Unit) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = action)
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.primaryText else colors.mutedText)
            if (detail != null) Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.mutedText)
    }
}

@Composable
private fun HelpToggle(title: Int, detail: Int, checked: Boolean, changed: (Boolean) -> Unit) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = checked, role = Role.Switch, onValueChange = changed)
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
