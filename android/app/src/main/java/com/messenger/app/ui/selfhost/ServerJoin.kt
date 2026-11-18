package com.messenger.app.ui.selfhost

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.messenger.app.R
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.data.selfhost.*
import kotlinx.coroutines.launch

class ServerJoinInbox {
    var pending by mutableStateOf<ServerInvite?>(null); private set
    var signupInvite by mutableStateOf<ServerInvite?>(null); private set
    var switching by mutableStateOf(false); private set
    private var epoch = -1
    private var source = ""
    fun offer(raw: String?, sessionEpoch: Int, origin: String): Boolean {
        if (switching) return false
        val parsed = ServerInviteLink.parse(raw, com.messenger.app.BuildConfig.ALLOW_LOCAL_HTTP) ?: return false
        pending = parsed; epoch = sessionEpoch; source = origin
        return true
    }
    fun current(sessionEpoch: Int, origin: String): ServerInvite? {
        if (!switching && (epoch != sessionEpoch || source != origin)) pending = null
        return pending
    }
    fun begin(invite: ServerInvite, sessionEpoch: Int, origin: String): Boolean {
        if (switching || current(sessionEpoch, origin) !== invite) return false
        switching = true
        return true
    }
    fun dismiss() { if (!switching) pending = null }
    fun failed() { switching = false }
    fun import(invite: ServerInvite) { signupInvite = invite; pending = null; switching = false }
    fun clearSignup() { signupInvite = null }
}

@Composable
fun ServerJoinHost(canShow: Boolean, onJoined: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as com.messenger.app.MessengerApp
    val auth by app.authRepository.authState.collectAsState()
    val epoch = app.authRepository.currentSessionEpoch()
    val origin = ServerEndpoint.current()
    val pending = app.serverJoin.pending
    val scope = rememberCoroutineScope()
    LaunchedEffect(epoch, origin, pending) { app.serverJoin.current(epoch, origin) }
    if (!canShow || pending == null) return
    var busy by remember(pending) { mutableStateOf(false) }
    var failed by remember(pending) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) app.serverJoin.dismiss() },
        title = { Text(stringResource(R.string.sh_join_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(pending.origin)
            Text(stringResource(R.string.sh_join_confirm))
            if (auth is com.messenger.app.domain.model.AuthSessionState.LoggedIn && origin != pending.origin)
                Text(stringResource(R.string.server_signout_warning))
            if (failed) Text(stringResource(R.string.error_generic))
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            if (!app.serverJoin.begin(pending, epoch, origin)) return@launch
            busy = true
            try {
                // Accepted changes and their in-memory prefill finish together even
                // when signing out disposes the old navigation graph.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    if (origin != pending.origin) {
                        val repository = app.authRepository as com.messenger.app.data.auth.AuthRepositoryImpl
                        if (auth is com.messenger.app.domain.model.AuthSessionState.LoggedIn)
                            repository.logoutAndChangeServer(expectedEpoch = epoch) { ServerEndpoint.save(context, pending.origin) }
                        else repository.changeServer(expectedEpoch = epoch) { ServerEndpoint.save(context, pending.origin) }
                    }
                    app.serverJoin.import(pending)
                }
                onJoined()
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { app.serverJoin.failed(); failed = true }
            finally { busy = false }
        } }) { Text(stringResource(R.string.sh_join_action)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = app.serverJoin::dismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun JoinServerScanner() {
    val context = LocalContext.current
    val app = context.applicationContext as com.messenger.app.MessengerApp
    var failed by remember { mutableStateOf(false) }
    var scanningEpoch by remember { mutableStateOf(-1) }
    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        result.contents?.let {
            if (scanningEpoch == app.authRepository.currentSessionEpoch()) failed = !app.serverJoin.offer(it,
                scanningEpoch, ServerEndpoint.current())
        }
    }
    TextButton(onClick = {
        scanningEpoch = app.authRepository.currentSessionEpoch()
        scanner.launch(com.journeyapps.barcodescanner.ScanOptions()
            .setCaptureActivity(com.messenger.app.capture.SharedQrCaptureActivity::class.java)
            .setOrientationLocked(false).setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            .setBeepEnabled(false).setBarcodeImageEnabled(false))
    }) { Text(stringResource(R.string.sh_scan_join)) }
    if (failed) Text(stringResource(R.string.sh_link_invalid))
}
