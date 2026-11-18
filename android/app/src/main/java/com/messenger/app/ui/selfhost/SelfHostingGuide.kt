package com.messenger.app.ui.selfhost

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.messenger.app.R
import kotlinx.coroutines.launch

@Composable
fun SelfHostingGuide() {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = !show }) { Text(stringResource(R.string.sh_tools)) }
    if (show) {
        Text(stringResource(R.string.sh_guide))
        Text(stringResource(R.string.sh_probe))
        val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
        val origin = com.messenger.app.data.api.ServerEndpoint.current()
        val scope = rememberCoroutineScope()
        var result by remember(origin) { mutableStateOf<com.messenger.app.data.selfhost.SelfHostingApi.ConnectionCheck?>(null) }
        var busy by remember(origin) { mutableStateOf(false) }
        val socket by app.messageRepository.socketState.collectAsState()
        TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { result = app.selfHosting.checkConnection(origin) }
            finally { busy = false }
        } }) { Text(stringResource(R.string.sh_connect)) }
        result?.let { value ->
            fun resultKey(ready: Boolean?) = when (ready) { true -> R.string.sh_ready; false -> R.string.sh_not_ready; null -> R.string.sh_unknown }
            for ((label, ready, fix) in listOf(
                Triple(R.string.sh_dns, value.dns, R.string.sh_fix_url),
                Triple(R.string.sh_check_tls, value.tls, R.string.sh_fix_url),
                Triple(R.string.sh_api, value.api, R.string.sh_fix_url),
                Triple(R.string.sh_compatibility, value.compatible, R.string.sh_fix_version),
                Triple(R.string.sh_socket, socket == com.messenger.app.domain.repository.SocketConnectionState.CONNECTED, R.string.sh_probe),
            )) Text(stringResource(R.string.sh_check_sentence, stringResource(label), stringResource(resultKey(ready)), stringResource(fix)))
            for (protocol in listOf("UDP", "TCP")) Text(stringResource(R.string.sh_check_sentence,
                stringResource(R.string.sh_check_turn_reach) + " ($protocol)", stringResource(R.string.sh_not_probed), stringResource(R.string.sh_probe)))
            Text(stringResource(R.string.sh_check_sentence, stringResource(R.string.sh_check_delivery), stringResource(R.string.sh_not_probed), stringResource(R.string.sh_probe)))
        }
    }
}
