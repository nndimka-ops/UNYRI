package com.messenger.app.ui.selfhost

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.data.selfhost.SelfHostingApi
import com.messenger.app.data.selfhost.SelfHostingGuidePolicy
import com.messenger.app.domain.repository.SocketConnectionState
import com.messenger.app.ui.settings.SettingsPrimaryButton
import com.messenger.app.ui.settings.SettingsSecondaryButton
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SelfHostingGuide(includeConnectionCheck: Boolean = true) {
    var show by rememberSaveable { mutableStateOf(false) }
    SettingsSecondaryButton(modifier = Modifier.fillMaxWidth(), onClick = { show = !show }) { Text(stringResource(R.string.sh_tools)) }
    if (show) {
        val context = LocalContext.current
        val guideUrl = SelfHostingGuidePolicy.guideUrl(LocalConfiguration.current.locales[0].language)
        val openFailed = stringResource(R.string.sh_open_guide_failed)
        val steps = listOf(
            Triple(R.string.sh_step_computer, R.string.sh_step_computer_summary, R.string.sh_get_server),
            Triple(R.string.sh_step_install, R.string.sh_step_install_summary, R.string.sh_guide_install),
            Triple(R.string.sh_step_internet, R.string.sh_step_internet_summary, R.string.sh_guide_network),
            Triple(R.string.sh_step_phone, R.string.sh_step_phone_summary, R.string.sh_guide_join),
            Triple(R.string.sh_step_owner, R.string.sh_step_owner_summary, R.string.sh_guide_owner),
            Triple(R.string.sh_step_test, R.string.sh_step_test_summary, R.string.sh_guide_check),
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            steps.forEachIndexed { index, (title, summary, detail) ->
                SelfHostingStep(index + 1, title, summary, detail) {
                    if (index == 1) SettingsPrimaryButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(guideUrl))) }
                            .onFailure { Toast.makeText(context, openFailed, Toast.LENGTH_LONG).show() }
                    }) { Text(stringResource(R.string.sh_open_guide)) }
                    if (index == 5 && includeConnectionCheck) SelfHostingConnectionCheck()
                }
            }
        }
    }
}

@Composable
private fun SelfHostingStep(number: Int, title: Int, summary: Int, detail: Int, actions: @Composable () -> Unit) {
    val colors = LocalCocoColors.current
    var expanded by rememberSaveable(number) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineSubtle)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = colors.accent.copy(alpha = .15f)) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Text(number.toString(), color = colors.accent) }
                }
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(summary), style = MaterialTheme.typography.bodyMedium)
            SettingsSecondaryButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.sh_step_more)) }
            if (expanded) Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
            actions()
        }
    }
}

/** Manual, bounded diagnostics. No polling or idle work. */
@Composable
fun SelfHostingConnectionCheck() {
    val app = LocalContext.current.applicationContext as MessengerApp
    val origin = ServerEndpoint.current()
    val colors = LocalCocoColors.current
    val scope = rememberCoroutineScope()
    var result by remember(origin) { mutableStateOf<SelfHostingApi.ConnectionCheck?>(null) }
    var busy by remember(origin) { mutableStateOf(false) }
    var failed by remember(origin) { mutableStateOf(false) }
    val socket by app.messageRepository.socketState.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsPrimaryButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { scope.launch {
            busy = true; failed = false; result = null
            try { result = app.selfHosting.checkConnection(origin) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        } }) { Text(stringResource(if (busy) R.string.connection_checking else R.string.sh_connect)) }
        if (failed) Text(stringResource(R.string.error_generic), color = colors.danger)
        result?.let { value ->
            for ((label, ready, fix) in listOf(
                Triple(R.string.sh_dns, value.dns, R.string.sh_fix_url),
                Triple(R.string.sh_check_tls, value.tls, R.string.sh_fix_url),
                Triple(R.string.sh_api, value.api, R.string.sh_fix_url),
                Triple(R.string.sh_compatibility, value.compatible, R.string.sh_fix_version),
                Triple(R.string.sh_socket, socket == SocketConnectionState.CONNECTED, R.string.sh_probe),
                Triple(R.string.sh_check_turn_reach, null, R.string.sh_probe),
                Triple(R.string.sh_check_delivery, null, R.string.sh_probe),
            )) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(when (ready) { true -> Icons.Default.CheckCircle; false -> Icons.Default.Warning; null -> Icons.Default.Info },
                        contentDescription = null, tint = when (ready) { true -> colors.categoryMint; false -> colors.danger; null -> colors.secondaryText },
                        modifier = Modifier.size(22.dp))
                    Column {
                        Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                        val status = stringResource(when (ready) { true -> R.string.sh_ready; false -> R.string.sh_not_ready; null -> R.string.sh_not_probed })
                        Text(status, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                        if (ready != true) Text(stringResource(fix), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                    }
                }
            }
        }
    }
}
