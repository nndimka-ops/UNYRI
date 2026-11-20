package com.messenger.app.ui.auth

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.data.identity.*
import com.messenger.app.domain.model.DisplayNamePolicy
import com.messenger.app.ui.theme.*
import java.util.Locale
import kotlinx.coroutines.launch
import com.messenger.app.ui.selfhost.*

internal fun login2Header(step: AuthStep): Int = if (step == AuthStep.SignIn)
    R.string.auth_flow_welcome else R.string.login2_signup_header

/** A finite resize only when the user changes steps. Passwords never enter saved state. */
@Composable
fun LoginScreen(viewModel: AuthViewModel, onNavigateToRegister: () -> Unit,
    currentTheme: ThemeMode, onThemeChange: (ThemeMode) -> Unit, currentLanguage: String?,
    onLanguageChange: (Locale?) -> Unit, serverHost: String, onOpenServer: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    var support by remember(serverHost) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(serverHost) {
        try { support = app.identityRepository.supports(serverHost) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { /* Keep the private-login form; no outage-triggered fallback. */ }
    }
    if (support == false) {
        LegacyLoginScreen(viewModel, onNavigateToRegister, currentTheme, onThemeChange, currentLanguage,
            onLanguageChange, serverHost, onOpenServer)
        return
    }
    var form by remember(serverHost) { mutableStateOf(AuthForm()) }
    val signup = rememberSignupPolicy(serverHost)
    val scope = rememberCoroutineScope()
    val importedInvite = app.serverJoin.signupInvite
    var invite by remember(serverHost, importedInvite) { mutableStateOf(importedInvite?.takeIf { it.origin == serverHost.trimEnd('/') }?.code.orEmpty()) }
    var login by remember(serverHost) { mutableStateOf("") }
    var password by remember(serverHost) { mutableStateOf("") }
    var repeated by remember(serverHost) { mutableStateOf("") }
    var name by remember(serverHost) { mutableStateOf("") }
    var visible by remember(serverHost) { mutableStateOf(false) }
    var accepted by remember(serverHost) { mutableStateOf(false) }
    var recovery by remember { mutableStateOf(false) }
    var legal by remember { mutableStateOf<String?>(null) }
    val state by viewModel.uiState.collectAsState()
    val failedCleanup by viewModel.cacheCleanupFailed.collectAsState()
    val loading = state is AuthUiState.Loading
    val staleCode = (state as? AuthUiState.Error)?.code
    LaunchedEffect(staleCode) {
        if (registrationPolicyStale(staleCode)) {
            form = AuthForm(AuthStep.SignUp)
            // Editing clears form feedback, but must not cancel this policy refresh.
            scope.launch { signup.load(true) }
        }
        // B-048: a login/password refusal returns to the step with those fields; the message
        // stays visible there. A refused password is cleared so it is typed anew.
        val back = form.afterRefusal(staleCode)
        if (back != form) {
            if (staleCode == "common_password") { password = ""; repeated = "" }
            form = back
        }
    }
    LaunchedEffect(importedInvite, serverHost) {
        if (importedInvite?.origin == serverHost.trimEnd('/') && signup.load()) form = AuthForm(AuthStep.SignUp)
    }
    val colors = LocalCocoColors.current
    val motion = com.messenger.app.ui.chats.rememberChatMotionSettings()
    val duration = if (!android.animation.ValueAnimator.areAnimatorsEnabled() || motion.calm) 0 else 220
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                password = ""; repeated = ""; visible = false
                if (form.step == AuthStep.DisplayName) form = AuthForm(AuthStep.SignUp)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    // Back from the name step returns to the login and password step, not to sign-in (B-048).
    androidx.activity.compose.BackHandler(form.step != AuthStep.SignIn && !loading) { form = form.back(); viewModel.clearError() }
    if (recovery) AccountRecoveryPanel(false, login) { recovery = false }
    CocoScreenSurface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuthBrandHeader()
            Text(stringResource(login2Header(form.step)), color = colors.secondaryText)
            ServerCard(serverHost, onOpenServer, !loading)
            if (failedCleanup) {
                Text(stringResource(R.string.cache_cleanup_failed))
                TextButton(onClick = viewModel::logout) { Text(stringResource(R.string.retry_cache_cleanup)) }
            } else {
                Surface(modifier = Modifier.glassPanel(RoundedCornerShape(24.dp)), shape = RoundedCornerShape(24.dp), color = androidx.compose.ui.graphics.Color.Transparent, border = BorderStroke(1.dp, colors.divider)) {
                    Column(Modifier.fillMaxWidth().animateContentSize(tween(duration)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (form.step != AuthStep.DisplayName) {
                            OutlinedTextField(login, { login = it; viewModel.clearError() }, singleLine = true,
                                enabled = !loading, label = { Text(stringResource(R.string.login2_private_login)) },
                                supportingText = { Text(stringResource(R.string.login2_private_help)) },
                                keyboardOptions = KeyboardOptions(autoCorrect = false), colors = authFieldColors(), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(password, { password = it; viewModel.clearError() }, singleLine = true, enabled = !loading,
                                label = { Text(stringResource(R.string.auth_password)) }, colors = authFieldColors(),
                                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = { IconButton(onClick = { visible = !visible }) {
                                    Icon(if (visible) CocoIcons.EyeOff else CocoIcons.Eye, stringResource(if (visible) R.string.auth_hide_password else R.string.auth_show_password))
                                } }, modifier = Modifier.fillMaxWidth())
                            if (form.step == AuthStep.SignUp) {
                                InviteCodeField(inviteFieldVisible(signup.policy), invite, !loading) { invite = it; viewModel.clearError() }
                                // B-048: a password the server would refuse is named here, while typing.
                                val common = password.isNotEmpty() && IdentityPolicy.isCommonPassword(password)
                                val strength = if (common) 0 else IdentityPolicy.strength(password)
                                LinearProgressIndicator(progress = { strength / 3f }, modifier = Modifier.fillMaxWidth())
                                if (common) Text(stringResource(R.string.auth_error_common_password), color = colors.danger,
                                    style = MaterialTheme.typography.bodySmall)
                                else Text(stringResource(listOf(R.string.auth_password_help, R.string.login2_strength_weak,
                                    R.string.login2_strength_fair, R.string.login2_strength_strong)[strength]), style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(repeated, { repeated = it }, label = { Text(stringResource(R.string.auth_confirm_password)) },
                                    enabled = !loading, singleLine = true, colors = authFieldColors(),
                                    isError = repeated.isNotEmpty() && repeated != password,
                                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(accepted, { accepted = it }, enabled = !loading)
                                    Text(com.messenger.app.ui.settings.cocoLegalAgreement(), Modifier.weight(1f))
                                }
                                for (id in listOf("terms", "privacy")) TextButton(onClick = { legal = id }) {
                                    Text(com.messenger.app.ui.settings.cocoLegalTitle(id))
                                }
                            }
                        } else {
                            OutlinedTextField(name, { name = it; viewModel.clearError() }, singleLine = true,
                                label = { Text(stringResource(R.string.auth_your_name)) }, supportingText = { Text(stringResource(R.string.auth_name_help)) },
                                isError = name.isNotEmpty() && DisplayNamePolicy.error(name, false) != null,
                                enabled = !loading, colors = authFieldColors(), modifier = Modifier.fillMaxWidth())
                            // B-048: the way back to the login and password is visible, not only the system gesture.
                            TextButton(onClick = { form = form.back(); viewModel.clearError() }, enabled = !loading,
                                modifier = Modifier.testTag("signup-name-back")) { Text(stringResource(R.string.back)) }
                        }
                    }
                }
                (state as? AuthUiState.Error)?.let { Text(stringResource(it.messageRes), color = colors.danger) }
                if (signup.failed) Text(stringResource(R.string.error_network), color = colors.danger)
                val enabled = !loading && when (form.step) {
                    AuthStep.SignIn -> login.isNotBlank() && password.isNotEmpty() && !login.trim().startsWith("@")
                    AuthStep.SignUp -> !signup.busy && !signup.failed && signup.policy?.open == true &&
                        (!inviteFieldVisible(signup.policy) || invite.isNotBlank()) && form.next(login.trim(), password, repeated, accepted).step == AuthStep.DisplayName
                    AuthStep.DisplayName -> !signup.busy && !signup.failed && DisplayNamePolicy.error(name, false) == null && accepted &&
                        IdentityPolicy.passwordAllowed(password) && !IdentityPolicy.isCommonPassword(password)
                }
                val style = authPrimaryActionStyle(enabled, RoundedCornerShape(20.dp))
                Button(onClick = {
                    when (form.step) {
                        AuthStep.SignIn -> viewModel.login(login.trim(), password)
                        AuthStep.SignUp -> form = form.next(login.trim(), password, repeated, accepted)
                        AuthStep.DisplayName -> viewModel.register(login.trim(), password, DisplayNamePolicy.sanitize(name), invite.takeIf { inviteFieldVisible(signup.policy) })
                    }
                }, enabled = enabled, colors = style.colors, shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).then(style.background)) {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text(stringResource(when (form.step) {
                        AuthStep.SignIn -> R.string.auth_flow_login
                        AuthStep.SignUp -> R.string.login2_continue
                        AuthStep.DisplayName -> R.string.auth_create_account
                    }))
                }
                if (form.step != AuthStep.SignIn && signup.policy?.open == false) Text(stringResource(R.string.sh_registration_closed), color = colors.danger)
                if (signup.failed) TextButton(onClick = { scope.launch { signup.load(true) } }) { Text(stringResource(R.string.retry)) }
                TextButton(onClick = {
                    viewModel.clearError()
                    if (form.step == AuthStep.SignIn) scope.launch { if (signup.load()) form = form.toggle() }
                    else form = form.toggle()
                }, enabled = !loading && !signup.busy) {
                    Text(stringResource(if (form.step == AuthStep.SignIn) R.string.auth_create_account else R.string.auth_have_account))
                }
                if (form.step == AuthStep.SignIn) TextButton(onClick = { recovery = true }, enabled = !loading) { Text(stringResource(R.string.auth_flow_recovery)) }
            }
            AuthFooter(currentTheme, onThemeChange, currentLanguage, onLanguageChange)
        }
    }
    legal?.let { id -> androidx.compose.ui.window.Dialog(onDismissRequest = { legal = null },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        com.messenger.app.ui.settings.SettingsScaffold(com.messenger.app.ui.settings.cocoLegalTitle(id), { legal = null }) {
            com.messenger.app.ui.settings.CocoLegalContent(id)
        }
    } }
}
