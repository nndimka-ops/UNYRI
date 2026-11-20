package com.messenger.app.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.identity.*
import com.messenger.app.ui.settings.SettingsPrimaryButton
import com.messenger.app.ui.settings.SettingsSecondaryButton
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.launch

/** One draft is shared by the header Save and the public identity fields. */
@Stable
internal class IdentityEditorState(private val repository: IdentityRepository, initial: OwnerIdentity) {
    var current by mutableStateOf(initial)
        private set
    var username by mutableStateOf(initial.username.orEmpty())
    var policy by mutableStateOf(initial.discoverability)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<Int?>(null)
    val valid: Boolean get() = username.isEmpty() || IdentityPolicy.username(username) != null
    val dirty: Boolean get() = username != current.username.orEmpty() || policy != current.discoverability

    fun accept(value: OwnerIdentity) {
        if (username == current.username.orEmpty()) username = value.username.orEmpty()
        if (policy == current.discoverability) policy = value.discoverability
        current = value
    }

    suspend fun save(): Boolean {
        if (busy || !valid) return false
        if (!dirty) return true
        busy = true
        message = null
        return try {
            current = repository.update(current, username.takeIf { it.isNotEmpty() }?.let(IdentityPolicy::username), policy)
            username = current.username.orEmpty()
            policy = current.discoverability
            true
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (error: Exception) {
            val api = error as? AuthApiException.Api
            message = when {
                api != null && IdentityPolicy.unavailable(api.httpStatus, api.code) -> R.string.login2_unavailable
                api?.httpStatus == 409 -> R.string.login2_conflict
                api?.httpStatus == 429 -> R.string.login2_cooldown
                else -> R.string.error_generic
            }
            if (api?.httpStatus == 409 && api.code == "conflict") runCatching { repository.refresh(true) }
            false
        } finally { busy = false }
    }
}

@Composable
internal fun rememberIdentityEditor(): IdentityEditorState? {
    val app = LocalContext.current.applicationContext as MessengerApp
    val identity by app.identityRepository.identity.collectAsState()
    val current = identity ?: return null
    val editor = remember(app.identityRepository, current.userId, current.publicOrigin, app.authRepository.currentSessionEpoch()) {
        IdentityEditorState(app.identityRepository, current)
    }
    LaunchedEffect(current) { editor.accept(current) }
    return editor
}

@Composable
internal fun IdentityCard(editor: IdentityEditorState? = rememberIdentityEditor(), showSave: Boolean = true, enabled: Boolean = true) {
    val state = editor ?: return
    val context = LocalContext.current
    val colors = LocalCocoColors.current
    val current = state.current
    var qr by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val editable = enabled && !state.busy
    ProfileEditorCard {
        Text(stringResource(R.string.login2_number), style = MaterialTheme.typography.titleSmall, color = colors.secondaryText)
        Text(IdentityPolicy.formattedNumber(current.number), style = MaterialTheme.typography.titleMedium, color = colors.primaryText)
        SettingsSecondaryButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("UNYRI", current.number))
        }) { Text(stringResource(R.string.copy)) }
        OutlinedTextField(state.username, { state.username = it; state.message = null },
            label = { Text(stringResource(R.string.login2_username)) },
            supportingText = { Text(stringResource(R.string.login2_username_rules)) },
            singleLine = true, enabled = editable, isError = !state.valid,
            shape = RoundedCornerShape(16.dp), colors = profileEditorFieldColors(), modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.login2_discoverability), style = MaterialTheme.typography.titleSmall, color = colors.secondaryText)
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            Discoverability.entries.forEachIndexed { index, value ->
                val label = listOf(R.string.login2_everyone, R.string.login2_contacts, R.string.login2_nobody)[index]
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = state.policy == value, enabled = editable, role = Role.RadioButton, onClick = { state.policy = value }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.policy == value, onClick = null, enabled = editable)
                    Text(stringResource(label), Modifier.padding(start = 12.dp), color = colors.primaryText)
                }
            }
        }
        state.message?.let { Text(stringResource(it), color = colors.danger) }
        if (showSave) SettingsPrimaryButton(enabled = editable && state.valid && state.dirty,
            onClick = { scope.launch { state.save() } }) { Text(stringResource(R.string.save)) }
        SettingsSecondaryButton(onClick = { qr = true }) { Text(stringResource(R.string.sidebar_qr_title)) }
    }
    if (qr) com.messenger.app.ui.ProfileQrSheet("UNYRI", current.username, {}, { qr = false }, current.number, current.publicOrigin)
}
/**
 * One notice for accounts that existed before the private login (their login became private and
 * they received a public number). A new account never sees it, and sign-up has no recovery step:
 * owner 19 Nov (B-049, «зачем это сразу после регистрации, убери»). The recovery key is created
 * when wanted in Settings -> «Восстановление аккаунта» (AccountRecoveryPanel), never as a gate.
 */
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
    LaunchedEffect(pending, user.id) {
        if (pending) {
            // A fresh registration: no upgrade notice and no recovery gate (B-049).
            prefs.edit().putBoolean(noticeKey, true).apply(); acknowledged = true
            try { auth.confirmSignupRecovery() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { /* The flag only holds back banners; the next start clears it. */ }
        }
    }
    if (!Login2OnboardingPolicy.showsUpgradeNotice(pending, identity != null, acknowledged)) return
    androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.profile_editor_username_section), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.login2_notice))
                IdentityCard()
                Button(onClick = { prefs.edit().putBoolean(noticeKey, true).apply(); acknowledged = true }) {
                    Text(stringResource(R.string.login2_continue))
                }
            }
        }
    }
}

internal object Login2OnboardingPolicy {
    /** Only an account that existed before the private login sees the one-time notice (B-049). */
    fun showsUpgradeNotice(signupPending: Boolean, hasIdentity: Boolean, acknowledged: Boolean): Boolean =
        !signupPending && hasIdentity && !acknowledged
}
