package com.messenger.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors

/** The single real backend server as a UI profile (Этап N.1 §25). N.1.1 will
 *  add real multi-server routing; here the hostname is always visible and the
 *  only real active server is shown - no fake switching. */
data class ServerProfileUiModel(
    val displayName: String,
    val host: String,
    val securityState: String,
)

/** Derives the current host (without scheme) from the build config URL. */
fun currentServerProfile(baseUrl: String): ServerProfileUiModel {
    val host = baseUrl
        .substringAfter("://", baseUrl)
        .substringBefore('/')
    return ServerProfileUiModel(displayName = "UNYRI Main", host = host, securityState = if (baseUrl.startsWith("https://", ignoreCase = true)) "🔒" else "⚠")
}

/** COCO FLOW Server picker (Этап N.1 §25-§27): current server + hostname,
 *  and an Add-server row that is honest about being UI-preview only. */
@Composable
fun ServerPickerScreen(onBack: () -> Unit, baseUrl: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.messenger.app.MessengerApp
    val state by app.authRepository.authState.collectAsState()
    val signedIn = state is com.messenger.app.domain.model.AuthSessionState.LoggedIn
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var address by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(baseUrl) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var pendingAddress by remember { mutableStateOf("") }
    var cardAddress by remember { mutableStateOf<String?>(null) }
    cardAddress?.let { value -> com.messenger.app.ui.admin.ServerCard(value) { cardAddress=null } }
    SettingsScaffold(title = stringResource(R.string.server_choose), onBack = onBack) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = CocoSpacing.lg)) {
            androidx.compose.material3.OutlinedTextField(value = address, onValueChange = { address = it; error = false }, label = { Text(stringResource(R.string.server_label)) }, singleLine = true, enabled = !busy && !signedIn, modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
            Text(stringResource(R.string.server_edit_help), modifier = Modifier.padding(vertical = 12.dp))
            if (error) Text(stringResource(R.string.server_edit_failed),color=LocalCocoColors.current.danger)
            SettingsPrimaryButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, destructive=signedIn,onClick = {
                if (signedIn) {
                    pendingAddress = runCatching {
                        com.messenger.app.data.api.ServerEndpoint.normalize(address, com.messenger.app.BuildConfig.ALLOW_LOCAL_HTTP)
                    }.getOrNull().orEmpty()
                    if (pendingAddress.isNotEmpty()) confirm = true else error = true
                }
                else scope.launch {
                    busy = true
                    try {
                        val origin = com.messenger.app.data.api.ServerEndpoint.normalize(address, com.messenger.app.BuildConfig.ALLOW_LOCAL_HTTP)
                        (app.authRepository as com.messenger.app.data.auth.AuthRepositoryImpl).changeServer { com.messenger.app.data.api.ServerEndpoint.save(context, origin) }
                        onBack()
                    } catch (_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(if (signedIn) R.string.settings_home_sign_out else R.string.save)) }
            SettingsSecondaryButton(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy, onClick={
                val normalized=runCatching { com.messenger.app.data.api.ServerEndpoint.normalize(address,com.messenger.app.BuildConfig.ALLOW_LOCAL_HTTP) }.getOrNull()
                if(normalized==null) error=true else cardAddress=normalized
            }){Text(stringResource(R.string.manage_card))}
            LocalConnectionSettings()
        }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false; pendingAddress = "" },
            title = { Text(stringResource(R.string.settings_home_sign_out)) },
            text = { Text(stringResource(R.string.server_signout_warning)) },
            confirmButton = {
                SettingsDialogButton(destructive = true, onClick = {
                    confirm = false
                    scope.launch {
                        busy = true
                        try {
                            val destination = pendingAddress
                            (app.authRepository as com.messenger.app.data.auth.AuthRepositoryImpl).logoutAndChangeServer {
                                com.messenger.app.data.api.ServerEndpoint.save(context, destination)
                            }
                            // Auth state owns the navigation reset; this screen is now gone.
                        } catch (_: Exception) {
                            error = true
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.settings_home_sign_out)) }
            },
            dismissButton = {
                SettingsDialogButton(onClick = { confirm = false; pendingAddress = "" }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
