package com.messenger.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.BuildConfig
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.api.ServerEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Recovery is optional and scoped to the currently selected HTTPS server/account. */
@Composable
fun AccountRecoveryPanel(signedIn: Boolean, initialUsername: String = "", onClose: () -> Unit) {
    val context = LocalContext.current
    val colors=com.messenger.app.ui.theme.LocalCocoColors.current
    val app = context.applicationContext as MessengerApp
    val scope = rememberCoroutineScope()
    val epoch = remember { app.authRepository.currentSessionEpoch() }
    val base = remember { ServerEndpoint.resolve(BuildConfig.API_BASE_URL) }
    val client = remember { okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build() }
    var generation by remember { mutableIntStateOf(0) }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var username by remember { mutableStateOf(initialUsername) }
    var enteredCode by remember { mutableStateOf("") }
    var issuedCode by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                generation++; password = ""; repeated = ""; enteredCode = ""; issuedCode = ""
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    suspend fun request(path: String, body: JSONObject? = null): JSONObject {
        val operation = generation
        check(base.startsWith("https://") && base == ServerEndpoint.resolve(BuildConfig.API_BASE_URL))
        check(epoch == app.authRepository.currentSessionEpoch())
        val token = if (signedIn) app.authRepository.currentAccessToken() ?: error("Signed out") else null
        check(epoch == app.authRepository.currentSessionEpoch() && operation == generation)
        val response = withContext(Dispatchers.IO) {
            val builder = Request.Builder().url("$base/auth/recovery/$path")
            token?.let { builder.header("Authorization", "Bearer $it") }
            if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
            client.newCall(builder.build()).execute().use {
                check(it.isSuccessful)
                val data = it.body?.string().orEmpty()
                if (data.isBlank()) JSONObject() else JSONObject(data)
            }
        }
        check(epoch == app.authRepository.currentSessionEpoch() && operation == generation &&
            base == ServerEndpoint.resolve(BuildConfig.API_BASE_URL))
        return response
    }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; result = null
        val operation = generation
        scope.launch {
            try { action() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { if (operation == generation) result = R.string.recovery_failed }
            finally { busy = false; password = ""; repeated = ""; enteredCode = "" }
        }
    }
    LaunchedEffect(Unit) {
        if (signedIn) run { enabled = request("status").getBoolean("enabled") }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        com.messenger.app.ui.theme.CocoScreenSurface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                com.messenger.app.ui.components.CocoPageHeader(
                    title = stringResource(R.string.recovery_title),
                    titleStyle = MaterialTheme.typography.headlineSmall,
                )
                Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=colors.surface) {
                    Text(base,modifier=Modifier.padding(14.dp),style = MaterialTheme.typography.bodySmall,color=colors.secondaryText)
                }
                Text(stringResource(R.string.recovery_detail),color=colors.secondaryText,style=MaterialTheme.typography.bodyMedium)
                if (signedIn) {
                    enabled?.let { Text(stringResource(if (it) R.string.recovery_enabled else R.string.recovery_disabled)) }
                    OutlinedTextField(password, { password = it.take(256) }, enabled = !busy,
                        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=authFieldColors(),
                        label = { Text(stringResource(R.string.recovery_current_password)) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    com.messenger.app.ui.settings.SettingsPrimaryButton(modifier = Modifier.fillMaxWidth(),destructive=enabled==true,onClick = { run {
                        issuedCode = request("code", JSONObject().put("password", password)).getString("code")
                        enabled = true
                    } }, enabled = !busy && password.isNotBlank()) { Text(stringResource(R.string.recovery_generate)) }
                    if (enabled == true) com.messenger.app.ui.settings.SettingsSecondaryButton(modifier=Modifier.fillMaxWidth(),destructive = true, onClick = { run {
                        request("disable", JSONObject().put("password", password)); enabled = false; issuedCode = ""
                        result = R.string.recovery_disabled
                    } }, enabled = !busy && password.isNotBlank()) { Text(stringResource(R.string.recovery_disable)) }
                    if (issuedCode.isNotEmpty()) {
                        Text(stringResource(R.string.recovery_save_warning),color=colors.warning)
                        Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=colors.surface) {
                            Text(issuedCode,modifier=Modifier.padding(16.dp),style = MaterialTheme.typography.titleMedium,
                                color=colors.primaryText,fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        com.messenger.app.ui.settings.SettingsDialogButton(onClick = {
                            val clip = android.content.ClipData.newPlainText("UNYRI recovery code", issuedCode)
                            clip.description.extras = android.os.PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                        }) { Text(stringResource(R.string.copy)) }
                    }
                } else {
                    OutlinedTextField(username, { username = it.take(64) }, enabled = !busy,
                        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=authFieldColors(),
                        label = { Text(stringResource(R.string.recovery_username)) }, singleLine = true)
                    OutlinedTextField(enteredCode, { enteredCode = it.take(128) }, enabled = !busy,
                        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=authFieldColors(),
                        label = { Text(stringResource(R.string.recovery_code)) }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(password, { password = it.take(256) }, enabled = !busy,
                        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=authFieldColors(),
                        label = { Text(stringResource(R.string.recovery_new_password)) }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(repeated, { repeated = it.take(256) }, enabled = !busy,
                        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=authFieldColors(),
                        label = { Text(stringResource(R.string.recovery_repeat_password)) }, visualTransformation = PasswordVisualTransformation())
                    com.messenger.app.ui.settings.SettingsPrimaryButton(modifier = Modifier.fillMaxWidth(), onClick = { run {
                        request("reset", JSONObject().put("username", username.trim().removePrefix("@"))
                            .put("code", enteredCode).put("new_password", password))
                        result = R.string.recovery_success
                    } }, enabled = !busy && username.isNotBlank() && enteredCode.isNotBlank() &&
                        password.length >= 8 && password == repeated) { Text(stringResource(R.string.recovery_reset)) }
                }
                if (busy) CircularProgressIndicator(color=colors.accent)
                result?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium,
                    color=when(it){R.string.recovery_failed->colors.danger;R.string.recovery_success->colors.success;else->colors.primaryText}) }
                com.messenger.app.ui.settings.SettingsDialogButton(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.chat_controls_close)) }
            }
        }
    }
}
