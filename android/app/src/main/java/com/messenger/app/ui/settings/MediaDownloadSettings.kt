package com.messenger.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.data.attachment.*
import com.messenger.app.ui.theme.LocalCocoColors

@Composable
fun MediaDownloadSettings() {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val preferences = remember(context) { MediaDownloadPreferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.media_download_detail), style = MaterialTheme.typography.bodySmall, color = LocalCocoColors.current.mutedText)
        for (network in listOf(DownloadNetwork.WIFI, DownloadNetwork.CELLULAR)) {
            Text(stringResource(if (network == DownloadNetwork.WIFI) R.string.media_auto_wifi else R.string.media_auto_mobile), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            for (kind in DownloadKind.entries) {
                val current = remember(revision, network, kind) { preferences.limitMb(network, kind) }
                val off = stringResource(R.string.media_download_off)
                val options = listOf(0, 1, 5, 10, 25, 50, 100).map { it to if (it == 0) off else FileSizeFormatter.format(it * 1024L * 1024L, locale) }
                SettingChoiceRow(stringResource(when(kind) { DownloadKind.PHOTO -> R.string.storage_photos; DownloadKind.VIDEO -> R.string.media_download_video; DownloadKind.VOICE -> R.string.storage_voice; DownloadKind.FILE -> R.string.storage_files }),
                    if (current == 0) off else FileSizeFormatter.format(current * 1024L * 1024L, locale), options) { preferences.setLimitMb(network, kind, it); revision++ }
            }
        }
    }
}
