package com.messenger.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.BuildConfig
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.data.auth.AuthRepositoryImpl
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.SocketConnectionState
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class ServerProfileUiModel(val displayName: String, val host: String, val securityState: String)

fun currentServerProfile(baseUrl: String): ServerProfileUiModel {
    val host = baseUrl.substringAfter("://", baseUrl).substringBefore('/')
    return ServerProfileUiModel(displayName = "UNYRI Main", host = host,
        securityState = if (baseUrl.startsWith("https://", ignoreCase = true)) "🔒" else "⚠")
}

@Composable
fun ServerPickerScreen(onBack: () -> Unit, baseUrl: String,
    onClaimOwner: (() -> Unit)? = null, onManageServer: (() -> Unit)? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val state by app.authRepository.authState.collectAsState()
    val connection by app.messageRepository.socketState.collectAsState()
    val signedIn = state is AuthSessionState.LoggedIn
    val colors = LocalCocoColors.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(baseUrl) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var cardAddress by remember { mutableStateOf<String?>(null) }
    cardAddress?.let { value -> com.messenger.app.ui.admin.ServerCard(value) { cardAddress = null } }
    SettingsScaffold(title = stringResource(R.string.settings_group_server), onBack = onBack) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(bottom = CocoSpacing.lg), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ServerSettingsCard {
                Text(stringResource(R.string.server_current_title), style = MaterialTheme.typography.titleMedium)
                Text(baseUrl, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(when {
                    !signedIn -> R.string.sh_unknown
                    connection == SocketConnectionState.CONNECTED -> R.string.local_mode_connected
                    else -> R.string.local_mode_reconnecting
                }), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                SettingsSecondaryButton(modifier = Modifier.fillMaxWidth(), onClick = { cardAddress = baseUrl }) {
                    Text(stringResource(R.string.manage_card))
                }
            }
            ServerSettingsCard {
                Text(stringResource(R.string.server_change_title), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = address, onValueChange = { address = it; error = false },
                    label = { Text(stringResource(R.string.server_label)) }, singleLine = true,
                    enabled = !busy && !signedIn, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
                Text(stringResource(R.string.server_edit_help), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                if (BuildConfig.ALLOW_LOCAL_HTTP) Text(stringResource(R.string.server_alpha_http_detail),
                    style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                if (error) Text(stringResource(R.string.server_edit_failed), color = colors.danger)
                if (signedIn) SettingsSecondaryButton(modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    destructive = true, onClick = { confirm = true }) { Text(stringResource(R.string.settings_home_sign_out)) }
                else SettingsPrimaryButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val origin = ServerEndpoint.normalize(address, BuildConfig.ALLOW_LOCAL_HTTP)
                            (app.authRepository as AuthRepositoryImpl).changeServer { ServerEndpoint.save(context, origin) }
                            onBack()
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { error = true }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.save)) }
            }
            ServerSettingsCard {
                Text(stringResource(R.string.notification_settings_local), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.notification_settings_local_detail), style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText)
                LocalConnectionSettings()
            }
            ServerSettingsCard {
                com.messenger.app.ui.selfhost.JoinServerScanner(asSettingsRow = true)
                com.messenger.app.ui.selfhost.SelfHostingGuide()
            }
            onClaimOwner?.let { action -> SettingsSecondaryButton(modifier = Modifier.fillMaxWidth(), onClick = action) {
                Text(stringResource(R.string.sh_claim_title))
            } }
            onManageServer?.let { action -> SettingsSecondaryButton(modifier = Modifier.fillMaxWidth(), onClick = action) {
                Text(stringResource(R.string.admin_title))
            } }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.settings_home_sign_out)) },
        text = { Text(stringResource(R.string.server_signout_warning)) },
        confirmButton = {
            SettingsDialogButton(destructive = true, enabled = !busy, onClick = {
                confirm = false
                scope.launch {
                    busy = true
                    try {
                        (app.authRepository as AuthRepositoryImpl).logoutAndChangeServer { ServerEndpoint.save(context, baseUrl) }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.settings_home_sign_out)) }
        }, dismissButton = { SettingsDialogButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ServerSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalCocoColors.current
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineSubtle)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
