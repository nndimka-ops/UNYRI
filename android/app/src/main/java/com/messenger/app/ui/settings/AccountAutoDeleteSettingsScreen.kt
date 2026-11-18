package com.messenger.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.R
import com.messenger.app.data.profile.AccountAutoDeleteOwner
import com.messenger.app.data.profile.AccountAutoDeleteRepository
import com.messenger.app.data.profile.AccountAutoDeleteSnapshot
import com.messenger.app.data.profile.isAutoDeleteConflict
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoScreenSurface
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.LocalGlassBottomInset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AccountAutoDeleteSettingsScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val session by app.authRepository.authState.collectAsState()
    val owner = session.let { app.accountAutoDeleteRepository.ownerKey() }
    key(owner) { AccountAutoDeleteOwned(app.accountAutoDeleteRepository, owner, onClose, modifier) }
}

private data class AutoDeleteConfirmation(val days: Int?, val revision: Long)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountAutoDeleteOwned(repository: AccountAutoDeleteRepository, owner: AccountAutoDeleteOwner?,
    onClose: () -> Unit, modifier: Modifier) {
    val colors = LocalCocoColors.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var snapshot by remember { mutableStateOf<AccountAutoDeleteSnapshot?>(null) }
    var enabled by remember { mutableStateOf(false) }
    var daysText by remember { mutableStateOf("365") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<AutoDeleteConfirmation?>(null) }
    // Intentionally not saveable: neither navigation nor process restoration retains a password.
    var password by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                password = ""; confirmation = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            password = ""; confirmation = null
        }
    }
    fun accept(value: AccountAutoDeleteSnapshot) {
        snapshot = value; enabled = value.inactiveDays != null
        daysText = (value.inactiveDays ?: 365).toString()
    }
    fun refresh() {
        if (busy) return
        busy = true; failed = false; conflict = false
        scope.launch {
            val result = repository.refresh(owner)
            if (owner == repository.ownerKey()) result.getOrNull()?.let(::accept)
            failed = result.isFailure; busy = false
        }
    }
    LaunchedEffect(owner) { refresh() }
    BackHandler { if (!busy) { if (confirmation != null) { confirmation = null; password = "" } else onClose() } }
    com.messenger.app.ui.BlockRootDrawerWhile(true)
    val days = daysText.toIntOrNull()?.takeIf { it in 1..1095 }
    val draft = if (enabled) days else null
    val changed = snapshot != null && (!enabled || days != null) && draft != snapshot?.inactiveDays
    val decreaseLabel = stringResource(R.string.auto_delete_decrease)
    val increaseLabel = stringResource(R.string.auto_delete_increase)
    CocoScreenSurface(modifier.fillMaxSize().testTag("account-auto-delete-screen"), color = colors.background) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(16.dp).padding(bottom = LocalGlassBottomInset.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            com.messenger.app.ui.components.CocoPageHeader(
                title = stringResource(R.string.auto_delete_title),
                start = { com.messenger.app.ui.components.CocoBackButton(onClick = onClose, enabled = !busy) },
            )
            Text(stringResource(R.string.auto_delete_description), color = colors.secondaryText, style = MaterialTheme.typography.bodyMedium)
            if (snapshot == null && busy) CircularProgressIndicator(Modifier.size(28.dp), color = colors.accent)
            if (failed || conflict) {
                Text(stringResource(if (conflict) R.string.auto_delete_conflict else R.string.auto_delete_failed), color = colors.danger)
                SettingsSecondaryButton(onClick = { refresh() }, enabled = !busy) { Text(stringResource(R.string.retry)) }
            }
            val current = snapshot
            if (current != null) {
                Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineSubtle)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.auto_delete_enable), color = colors.primaryText)
                                Text(stringResource(R.string.auto_delete_default_off), color = colors.mutedText, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy && confirmation == null,
                                modifier = Modifier.testTag("account-auto-delete-enabled"))
                        }
                        if (enabled) {
                            Text(stringResource(R.string.auto_delete_period), color = colors.secondaryText, style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(1, 3, 7, 365, 1095).forEach { preset ->
                                    val label = when (preset) {
                                        365 -> stringResource(R.string.auto_delete_year)
                                        1095 -> stringResource(R.string.auto_delete_three_years)
                                        else -> autoDeletePeriodLabel(preset)
                                    }
                                    FilterChip(selected = days == preset, enabled = !busy && confirmation == null,
                                        onClick = { daysText = preset.toString(); focus.clearFocus() }, label = { Text(label) })
                                }
                            }
                            Slider(value = (days ?: 1).toFloat(), onValueChange = { daysText = it.roundToInt().coerceIn(1, 1095).toString() },
                                valueRange = 1f..1095f, steps = 1093, enabled = !busy && confirmation == null,
                                modifier = Modifier.testTag("account-auto-delete-slider"))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = daysText, onValueChange = { raw -> if (raw.length <= 4 && raw.all { it in '0'..'9' }) daysText = raw },
                                    label = { Text(stringResource(R.string.auto_delete_exact_days)) }, singleLine = true,
                                    enabled = !busy && confirmation == null, isError = days == null,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f).testTag("account-auto-delete-days"))
                                IconButton(enabled = !busy && confirmation == null && (days ?: 1) > 1,
                                    modifier = Modifier.testTag("account-auto-delete-decrease").semantics { contentDescription = decreaseLabel },
                                    onClick = { daysText = ((days ?: 1) - 1).coerceAtLeast(1).toString() }) {
                                    Text("−", color = colors.primaryText)
                                }
                                IconButton(enabled = !busy && confirmation == null && (days ?: 1095) < 1095,
                                    modifier = Modifier.testTag("account-auto-delete-increase").semantics { contentDescription = increaseLabel },
                                    onClick = { daysText = ((days ?: 1) + 1).coerceAtMost(1095).toString() }) {
                                    Text("+", color = colors.primaryText)
                                }
                            }
                            Text(stringResource(R.string.auto_delete_range), color = if (days == null) colors.danger else colors.mutedText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceVariant) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.auto_delete_saved_policy, current.inactiveDays?.let { autoDeletePeriodLabel(it) }
                            ?: stringResource(R.string.auto_delete_off)), color = colors.primaryText, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.auto_delete_last_active, autoDeleteDate(current.lastActiveAt)), color = colors.mutedText, style = MaterialTheme.typography.bodySmall)
                        current.nextDeletionAt?.let {
                            Text(stringResource(R.string.auto_delete_next_deletion, autoDeleteDate(it)), color = colors.mutedText, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(R.string.auto_delete_server_clock), color = colors.mutedText, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringResource(R.string.auto_delete_scope), color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.auto_delete_copy_limits), color = colors.mutedText, style = MaterialTheme.typography.bodySmall)
                SettingsPrimaryButton(enabled = changed && !busy && owner != null && confirmation == null,
                    destructive = enabled, modifier = Modifier.fillMaxWidth().testTag("account-auto-delete-save"), onClick = {
                        focus.clearFocus(); password = ""; confirmation = AutoDeleteConfirmation(draft, current.revision)
                    }) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.save))
                }
            }
        }
    }
    confirmation?.let { reviewed ->
        AlertDialog(onDismissRequest = { if (!busy) { confirmation = null; password = "" } },
            containerColor = colors.elevated, titleContentColor = colors.primaryText, textContentColor = colors.secondaryText,
            title = { Text(stringResource(if (reviewed.days == null) R.string.auto_delete_confirm_off else R.string.auto_delete_confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (reviewed.days == null) stringResource(R.string.auto_delete_disable_detail)
                        else stringResource(R.string.auto_delete_confirm_period, autoDeletePeriodLabel(reviewed.days)))
                    if (reviewed.days != null) {
                        Text(stringResource(R.string.auto_delete_scope))
                        Text(stringResource(R.string.auto_delete_copy_limits), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = password,
                            onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= 1024) password = it },
                            enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.account_delete_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().testTag("account-auto-delete-password"))
                    }
                }
            },
            confirmButton = {
                SettingsDialogButton(destructive = reviewed.days != null,
                    enabled = !busy && owner != null && (reviewed.days == null || password.isNotEmpty()), onClick = {
                        val capturedOwner = owner ?: return@SettingsDialogButton
                        val secret = password; password = ""; confirmation = null; busy = true
                        scope.launch {
                            val result = repository.update(capturedOwner, reviewed.days, reviewed.revision, secret)
                            if (capturedOwner == repository.ownerKey()) {
                                result.getOrNull()?.let(::accept)
                                conflict = result.exceptionOrNull()?.isAutoDeleteConflict() == true
                                failed = result.isFailure && !conflict
                                if (conflict) {
                                    snapshot = null
                                    repository.state.value?.let(::accept)
                                }
                            }
                            busy = false
                        }
                    }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { SettingsDialogButton(enabled = !busy, onClick = { confirmation = null; password = "" }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun autoDeleteDate(value: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(value))
}.getOrDefault(value)

@Composable
private fun autoDeletePeriodLabel(days: Int): String = if (days == 1) stringResource(R.string.auto_delete_one_day)
    else stringResource(R.string.auto_delete_days_value, days)
