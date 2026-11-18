package com.messenger.app.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.identity.*
import kotlinx.coroutines.launch

@Composable
internal fun IdentityCard() {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val identity by app.identityRepository.identity.collectAsState()
    val current = identity ?: return
    var username by remember(current) { mutableStateOf(current.username.orEmpty()) }
    var policy by remember(current) { mutableStateOf(current.discoverability) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var qr by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.login2_number) + ": " + IdentityPolicy.formattedNumber(current.number), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("UNYRI", current.number))
        }) { Text(stringResource(R.string.copy)) }
        OutlinedTextField(username, { username = it; message = null }, label = { Text(stringResource(R.string.login2_username)) },
            supportingText = { Text(stringResource(R.string.login2_username_rules)) }, singleLine = true, enabled = !busy,
            isError = username.isNotEmpty() && IdentityPolicy.username(username) == null, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.login2_discoverability))
        Discoverability.entries.forEachIndexed { index, value ->
            val label = listOf(R.string.login2_everyone, R.string.login2_contacts, R.string.login2_nobody)[index]
            Row {
                RadioButton(policy == value, { policy = value }, enabled = !busy)
                TextButton(onClick = { policy = value }, enabled = !busy) { Text(stringResource(label)) }
            }
        }
        message?.let { Text(stringResource(it)) }
        Button(enabled = !busy && (username.isEmpty() || IdentityPolicy.username(username) != null) &&
            (username != current.username.orEmpty() || policy != current.discoverability), onClick = {
            busy = true; message = null
            scope.launch {
                try {
                    app.identityRepository.update(current, username.takeIf { it.isNotEmpty() }?.let(IdentityPolicy::username), policy)
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (error: Exception) {
                    val api = error as? AuthApiException.Api
                    message = when {
                        api != null && IdentityPolicy.unavailable(api.httpStatus, api.code) -> R.string.login2_unavailable
                        api?.httpStatus == 409 -> R.string.login2_conflict
                        api?.httpStatus == 429 -> R.string.login2_cooldown
                        else -> R.string.error_generic
                    }
                    if (api?.httpStatus == 409 && api.code == "conflict") runCatching { app.identityRepository.refresh(true) }
                } finally { busy = false }
            }
        }) { Text(stringResource(R.string.save)) }
        TextButton(onClick = { qr = true }) { Text(stringResource(R.string.sidebar_qr_title)) }
    }
    if (qr) com.messenger.app.ui.ProfileQrSheet("UNYRI", current.username, {}, { qr = false },
        current.number, current.publicOrigin)
}

/** Covers registered sessions until recovery is confirmed; a lost response can be retried after restart. */
@Composable
internal fun Login2Onboarding() {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val auth = app.authRepository
    val pending by auth.signupRecoveryPending.collectAsState()
    val identity by app.identityRepository.identity.collectAsState()
    val state by auth.authState.collectAsState()
    val user = (state as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user ?: return
    val prefs = remember { context.getSharedPreferences("identity-notices", Context.MODE_PRIVATE) }
    val noticeKey = com.messenger.app.data.api.ServerEndpoint.current() + "|" + user.id
    var acknowledged by remember(noticeKey) { mutableStateOf(prefs.getBoolean(noticeKey, false)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pending, user.id) {
        if (pending) {
            try { if (app.identityRepository.refresh() == null) auth.confirmSignupRecovery() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { /* A transient failure must not skip key confirmation. */ }
        }
    }
    var key by remember(noticeKey) { mutableStateOf("") }
    var password by remember(noticeKey) { mutableStateOf("") }
    var answers by remember(noticeKey) { mutableStateOf(listOf("", "", "")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var readingConfirmation by remember { mutableStateOf(false) }
    var secretGeneration by remember { mutableIntStateOf(0) }
    val clipboardTag = remember(noticeKey) { java.util.UUID.randomUUID().toString() }
    fun clearSecrets(clearClipboard: Boolean = true) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clearClipboard && clipboard.primaryClipDescription?.extras?.getString("com.messenger.app.recovery_clip") == clipboardTag) {
            if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        key = ""; password = ""; answers = listOf("", "", ""); readingConfirmation = false
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // An explicit copy remains usable in the password manager while UI secrets disappear.
                secretGeneration++; clearSecrets(clearClipboard = false)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); clearSecrets() }
    }
    if (!pending && (identity == null || acknowledged)) return
    androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (pending) R.string.recovery_title else R.string.login2_number), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.login2_notice))
                identity?.let { Text(stringResource(R.string.login2_number) + ": " + IdentityPolicy.formattedNumber(it.number)) }
                if (pending) {
                    if (key.isEmpty()) {
                        Text(stringResource(R.string.login2_recovery_help))
                        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.auth_password)) },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), enabled = !busy)
                        Button(enabled = !busy && password.isNotEmpty(), onClick = {
                            busy = true; error = false
                            val operation = secretGeneration
                            val supplied = password
                            scope.launch {
                                try {
                                    if (app.identityRepository.refresh(true) == null) auth.confirmSignupRecovery()
                                    else if (operation == secretGeneration) {
                                        val issued = app.identityRepository.recovery(supplied)
                                        if (operation == secretGeneration) key = issued
                                    }
                                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                catch (_: Exception) { error = true }
                                finally { busy = false; password = "" }
                            }
                        }) { Text(stringResource(R.string.login2_recovery_create)) }
                    } else if (!readingConfirmation) {
                        Text(key, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("UNYRI", key).apply {
                                description.extras = android.os.PersistableBundle().apply {
                                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                                    putString("com.messenger.app.recovery_clip", clipboardTag)
                                }
                            })
                        }) { Text(stringResource(R.string.copy)) }
                        Button(onClick = { readingConfirmation = true }) { Text(stringResource(R.string.login2_continue)) }
                    } else {
                        IdentityPolicy.confirmationIndices.forEachIndexed { index, group ->
                            OutlinedTextField(answers[index], { value -> answers = answers.toMutableList().also { it[index] = value } },
                                label = { Text(stringResource(R.string.login2_recovery_group, group + 1)) }, singleLine = true)
                        }
                        Button(enabled = IdentityPolicy.confirms(key, answers) && !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    auth.confirmSignupRecovery(); clearSecrets()
                                    prefs.edit().putBoolean(noticeKey, true).apply(); acknowledged = true
                                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                catch (_: Exception) { error = true }
                                finally { busy = false }
                            }
                        }) { Text(stringResource(R.string.login2_continue)) }
                        TextButton(onClick = { readingConfirmation = false }) { Text(stringResource(R.string.sidebar_qr_title)) }
                    }
                } else {
                    IdentityCard()
                    Button(onClick = { prefs.edit().putBoolean(noticeKey, true).apply(); acknowledged = true }) { Text(stringResource(R.string.login2_continue)) }
                }
                if (error) Text(stringResource(R.string.error_generic))
                if (pending) TextButton(onClick = { scope.launch { auth.logout() } }, enabled = !busy) { Text(stringResource(R.string.profile_logout)) }
            }
        }
    }
}
