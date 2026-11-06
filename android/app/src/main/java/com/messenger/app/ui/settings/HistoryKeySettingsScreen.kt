package com.messenger.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.history.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** No saveable key/text state. Dismissal destroys the unconfirmed draft. The
 * enclosing dialog uses SecureOn to keep key material out of screenshots/recents. */
@Composable
fun HistoryKeySettingsScreen(onBack: () -> Unit) {
    val colors=com.messenger.app.ui.theme.LocalCocoColors.current
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val session = remember { app.historySettingsSession() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<HistoryKeySettingsSession.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var complete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf<ByteArray?>(null) }
    var draftRevision by remember { mutableStateOf<Long?>(null) }
    var confirmation by remember { mutableStateOf("") }
    var entered by remember { mutableStateOf("") }
    var deleteRevision by remember { mutableStateOf<Long?>(null) }
    var restoreConfirmation by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var replacementConfirm by remember { mutableStateOf(false) }
    var transferMode by remember { mutableStateOf<Boolean?>(null) }
    var showTransferPreparation by remember { mutableStateOf(false) }
    var autoEnabled by remember { mutableStateOf(false) }
    var wifiOnly by remember { mutableStateOf(true) }
    var clipboardLabel by remember { mutableStateOf<String?>(null) }
    val clipboard = remember { context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager }
    fun clearClipboard() {
        runCatching {
            if (clipboardLabel != null && clipboard.primaryClipDescription?.label?.toString() == clipboardLabel) {
                if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
        clipboardLabel = null
    }
    fun clearDraft() { draft?.fill(0); draft = null; draftRevision = null; confirmation = ""; clearClipboard() }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        complete = false
        operation = scope.launch {
            try { block(); status = session.status() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = historyOperationErrorText(failure) }
            finally { busy = false }
        }
    }
    DisposableEffect(Unit) { onDispose { draft?.fill(0); clearClipboard() } }
    LaunchedEffect(Unit) { action {} }
    LaunchedEffect(status?.remote?.server_id) {
        if(status!=null) { autoEnabled=HistoryAutoBackup.enabled(context,session.scopeId());wifiOnly=HistoryAutoBackup.wifi(context,session.scopeId()) }
    }
    transferMode?.let { receiving ->
        val call by app.callController.state.collectAsState()
        HistoryTransferScreen(session,receiving,call !is com.messenger.app.domain.call.CallUiState.Idle,
            onOpenBackupSettings={transferMode=null;action{}}) { transferMode=null;action{} }
        return
    }
    LaunchedEffect(draft) {
        if (draft != null) { delay(10 * 60 * 1000L); clearDraft(); error = R.string.history_key_expired }
    }
    SettingsScaffold(stringResource(R.string.history_settings_title), onBack) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.history_feature_limit), style = MaterialTheme.typography.bodyMedium)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.history_working))
                SettingsSecondaryButton(onClick = { operation?.cancel() }) { Text(stringResource(R.string.cancel)) }
            }
            if (complete) Text(stringResource(R.string.history_completed),color=colors.success)
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            status?.let { current ->
                val step = HistorySetupStep.next(current.keySaved, draft != null, current.remote.active != null,
                    current.remote.staged != null, current.remote.desired_enabled)
                HistorySetupHint(step)
                if (step == HistorySetupStep.prepareKey) {
                    SettingsPrimaryButton(enabled = !busy, onClick = {
                        clearDraft(); draft = HistoryBackupCrypto.generateRecoveryKey(); draftRevision = current.remote.revision
                    }) { Text(stringResource(R.string.history_create_key)) }
                } else if (step == HistorySetupStep.allowUploads) {
                    SettingsPrimaryButton(enabled = !busy, onClick = { action { session.enable(current.remote.revision) } }) {
                        Text(stringResource(R.string.history_enable_copying))
                    }
                } else if (step == HistorySetupStep.createBackup) {
                    SettingsPrimaryButton(enabled = !busy, onClick = { action {
                        session.backup(onConfirmed = { HistoryAutoBackup.success(context, session.scopeId()) }); complete = true
                    } }) { Text(stringResource(R.string.history_backup_now)) }
                }
                draft?.let { secret ->
                    val formatted = HistoryBackupCrypto.formatRecoveryKey(secret)
                    Text(stringResource(R.string.history_save_key_instruction))
                    Text(formatted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                    SettingsSecondaryButton(enabled = !busy, onClick = {
                        val label = "UNYRI recovery " + java.util.UUID.randomUUID()
                        val clip = ClipData.newPlainText(label, formatted)
                        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        if (runCatching { clipboard.setPrimaryClip(clip) }.isSuccess) {
                            clipboardLabel = label
                            scope.launch { delay(60_000); if (clipboardLabel == label) clearClipboard() }
                        } else error = R.string.history_operation_failed
                    }) { Text(stringResource(R.string.history_copy_key)) }
                    OutlinedTextField(value = confirmation, onValueChange = { confirmation = it.take(8) },
                        label = { Text(stringResource(R.string.history_last_group)) }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    SettingsPrimaryButton(enabled = !busy && confirmation.uppercase(java.util.Locale.ROOT) == formatted.takeLast(8), onClick = {
                        val candidate = secret.copyOf(); val revision = draftRevision ?: return@SettingsPrimaryButton
                        action {
                            try { session.saveNewKey(candidate, revision); clearDraft() } finally { candidate.fill(0) }
                        }
                    }) { Text(stringResource(R.string.history_confirm_key)) }
                    SettingsDialogButton(enabled = !busy, onClick = { clearDraft() }) { Text(stringResource(R.string.cancel)) }
                }
                if ((!current.keySaved || replacing) && current.remote.active != null) {
                    Text(stringResource(R.string.history_enter_existing))
                    OutlinedTextField(value = entered, onValueChange = { entered = it.take(128) },
                        label = { Text(stringResource(R.string.history_recovery_key)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    SettingsPrimaryButton(enabled = !busy && entered.isNotBlank(), onClick = {
                        val candidate = runCatching { HistoryBackupCrypto.parseRecoveryKey(entered) }.getOrNull()
                        if (candidate == null) error = R.string.history_invalid_key
                        else if(replacing) { candidate.fill(0); replacementConfirm=true }
                        else action { try { session.saveExistingKey(candidate); entered = "" } finally { candidate.fill(0) } }
                    }) { Text(stringResource(R.string.history_verify_key)) }
                }
                Text(stringResource(if (current.remote.active == null) R.string.history_no_copy else R.string.history_copy_exists), style = MaterialTheme.typography.titleMedium)
                current.remote.active?.verified_at?.let { timestamp ->
                    val date = remember(timestamp) { runCatching {
                        java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                            .withLocale(java.util.Locale.getDefault()).withZone(java.time.ZoneId.systemDefault())
                            .format(java.time.Instant.parse(timestamp))
                    }.getOrNull() }
                    if (date != null) Text(stringResource(R.string.history_last_confirmed) + ": " + date)
                }
                if (current.remote.active == null && current.remote.staged != null) Text(stringResource(R.string.history_pending_copy))
                Text(stringResource(if (current.keySaved) R.string.history_key_saved else R.string.history_key_not_saved),
                    color=if(current.keySaved)colors.success else colors.warning)
                Text(stringResource(R.string.history_key_explanation), style = MaterialTheme.typography.bodyMedium)
                if (current.keySaved) {
                    HistoryBackupToggle(stringResource(R.string.history_auto_daily),autoEnabled,
                        enabled=!busy && (autoEnabled || current.remote.desired_enabled),onCheckedChange={enabled ->
                        if(enabled) action {session.verifyAutoKey();HistoryAutoBackup.configure(context,session.scopeId(),true,wifiOnly);autoEnabled=true}
                        else {HistoryAutoBackup.configure(context,session.scopeId(),false,wifiOnly);autoEnabled=false}
                    })
                    if (!current.remote.desired_enabled) Text(stringResource(R.string.history_setup_auto_hint), style = MaterialTheme.typography.bodySmall)
                    HistoryBackupToggle(stringResource(R.string.history_auto_wifi),wifiOnly,enabled=!busy,onCheckedChange={value ->
                        HistoryAutoBackup.configure(context,session.scopeId(),autoEnabled,value);wifiOnly=value
                    })
                    Text(stringResource(R.string.history_auto_notice),style=MaterialTheme.typography.bodySmall)
                    if (current.remote.active != null) SettingsSecondaryButton(enabled=!busy,destructive=true,onClick={replacing=!replacing;entered=""}){Text(stringResource(R.string.history_replace_key))}
                    if (step != HistorySetupStep.createBackup) SettingsPrimaryButton(enabled = !busy && current.remote.desired_enabled, onClick = { action { session.backup(onConfirmed={HistoryAutoBackup.success(context,session.scopeId())});complete = true } }) { Text(stringResource(R.string.history_backup_now)) }
                    if (current.remote.active != null) SettingsSecondaryButton(enabled = !busy, onClick = { restoreConfirmation = true }) { Text(stringResource(R.string.history_restore_now)) }
                }
                Text(stringResource(R.string.history_transfer_entry_title),style=MaterialTheme.typography.titleMedium)
                var showInstructions by remember { mutableStateOf(false) }
                TextButton(onClick = { showInstructions = !showInstructions }) { Text(stringResource(R.string.history_transfer_help)) }
                if (showInstructions) {
                    Text(stringResource(R.string.history_transfer_steps),style=MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.history_transfer_requirements),style=MaterialTheme.typography.bodySmall)
                }
                SettingsSecondaryButton(enabled=!busy,onClick={
                    if(current.keySaved && current.remote.desired_enabled) transferMode=false
                    else showTransferPreparation=true
                }){Text(stringResource(R.string.history_qr_send))}
                SettingsPrimaryButton(enabled=!busy,onClick={transferMode=true}){Text(stringResource(R.string.history_qr_receive))}
                if(showTransferPreparation && (!current.keySaved || !current.remote.desired_enabled)) {
                    Text(stringResource(R.string.history_transfer_setup_title),style=MaterialTheme.typography.titleMedium)
                    if(!current.keySaved) {
                        Text(stringResource(if(current.remote.active==null && current.remote.staged==null) R.string.history_transfer_setup_new else if(current.remote.active==null) R.string.history_transfer_setup_staged else R.string.history_transfer_setup_existing))
                    } else {
                        Text(stringResource(R.string.history_transfer_setup_upload))
                        SettingsPrimaryButton(enabled=!busy && draft==null,onClick={action{session.enable(current.remote.revision)}}){Text(stringResource(R.string.history_enable_copying))}
                    }
                }
                if (current.remote.desired_enabled) {
                    SettingsSecondaryButton(enabled = !busy && draft == null, onClick = { action { session.pause(current.remote.revision);HistoryAutoBackup.configure(context,session.scopeId(),false,wifiOnly);autoEnabled=false } }) {
                        Text(stringResource(R.string.history_pause_copying))
                    }
                } else {
                    Text(stringResource(R.string.history_copying_paused))
                    SettingsSecondaryButton(enabled = !busy && draft == null, onClick = { action { session.enable(current.remote.revision) } }) {
                        Text(stringResource(R.string.history_enable_copying))
                    }
                }
                if (current.remote.active != null || current.remote.staged != null) {
                    SettingsSecondaryButton(enabled = !busy, destructive = true, onClick = { deleteRevision = current.remote.revision }) {
                        Text(stringResource(R.string.history_delete_copy))
                    }
                }
            }
            SettingsSecondaryButton(enabled = !busy && draft == null, onClick = { action {} }) { Text(stringResource(R.string.retry)) }
            Spacer(Modifier.height(24.dp))
        }
    }
    deleteRevision?.let { revision ->
        AlertDialog(onDismissRequest = { deleteRevision = null },
            title = { Text(stringResource(R.string.history_delete_copy)) },
            text = { Text(stringResource(R.string.history_delete_warning)) },
            confirmButton = { SettingsDialogButton(destructive = true, onClick = {
                deleteRevision = null; action { session.delete(revision) }
            }) { Text(stringResource(R.string.history_delete_copy)) } },
            dismissButton = { SettingsDialogButton(onClick = { deleteRevision = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (restoreConfirmation) AlertDialog(onDismissRequest = { restoreConfirmation = false },
        title = { Text(stringResource(R.string.history_restore_now)) },
        text = { Text(stringResource(R.string.history_restore_warning)) },
        confirmButton = { SettingsDialogButton(onClick = { restoreConfirmation = false; action { session.restore(); complete = true } }) { Text(stringResource(R.string.history_restore_now)) } },
        dismissButton = { SettingsDialogButton(onClick = { restoreConfirmation = false }) { Text(stringResource(R.string.cancel)) } })
    if(replacementConfirm) AlertDialog(onDismissRequest={replacementConfirm=false},title={Text(stringResource(R.string.history_replace_key))},text={Text(stringResource(R.string.history_replace_warning))},
        confirmButton={SettingsDialogButton(destructive=true,onClick={replacementConfirm=false;val candidate=runCatching{HistoryBackupCrypto.parseRecoveryKey(entered)}.getOrNull();if(candidate==null)error=R.string.history_invalid_key else action{try{session.replaceExistingKey(candidate);entered="";replacing=false}finally{candidate.fill(0)}}}){Text(stringResource(R.string.history_verify_key))}},
        dismissButton={SettingsDialogButton(onClick={replacementConfirm=false}){Text(stringResource(R.string.cancel))}})
}

/** Shared by backup settings and QR transfer; only fixed local strings reach UI. */
internal fun historyOperationErrorText(error: Throwable): Int = when(error) {
    is HistoryTransferValidationFailure -> when(error.reason) {
        HistoryTransferValidationFailure.Reason.WRONG_SERVER -> R.string.history_transfer_wrong_server
        HistoryTransferValidationFailure.Reason.WRONG_ACCOUNT -> R.string.history_transfer_wrong_account
        HistoryTransferValidationFailure.Reason.EXPIRED -> R.string.history_transfer_expired
    }
    is com.messenger.app.data.api.HistoryBackupUnsupportedServer -> R.string.history_server_upgrade
    is javax.crypto.AEADBadTagException -> R.string.history_wrong_key
    is HistorySourceNotReady -> R.string.history_source_not_ready
    is HistorySourceChanged -> R.string.history_transfer_request_changed
    is HistoryMediaUnavailable -> R.string.history_media_unavailable
    is HistoryInsufficientSpace -> R.string.history_insufficient_space
    is HistoryRecoveryKeyMissing -> R.string.history_transfer_key_missing
    is HistoryUploadsDisabled -> R.string.history_transfer_uploads_disabled
    is com.messenger.app.data.api.AuthApiException.Api -> when(error.httpStatus) {
        401,403 -> R.string.history_transfer_session_expired
        404,410 -> R.string.history_transfer_request_missing
        409 -> R.string.history_transfer_request_changed
        429 -> R.string.history_transfer_rate_limited
        else -> R.string.history_operation_failed
    }
    is com.messenger.app.data.api.AuthApiException.Network -> if(error.httpStatus==429)R.string.history_transfer_rate_limited else R.string.history_transfer_network
    else -> R.string.history_operation_failed
}

/** A single next action is shown before the key editor and the secondary tools. */
@Composable
private fun HistorySetupHint(step: HistorySetupStep) {
    val copy = when (step) {
        HistorySetupStep.prepareKey -> R.string.history_setup_prepare_title to R.string.history_setup_prepare_detail
        HistorySetupStep.confirmKey -> R.string.history_setup_confirm_title to R.string.history_setup_confirm_detail
        HistorySetupStep.enterExistingKey -> R.string.history_setup_existing_title to R.string.history_setup_existing_detail
        HistorySetupStep.unfinishedUpload -> R.string.history_setup_unfinished_title to R.string.history_setup_unfinished_detail
        HistorySetupStep.allowUploads -> R.string.history_setup_allow_title to R.string.history_setup_allow_detail
        HistorySetupStep.createBackup -> R.string.history_setup_create_title to R.string.history_setup_create_detail
        HistorySetupStep.ready -> R.string.history_setup_ready_title to R.string.history_setup_ready_detail
    }
    Text(stringResource(copy.first), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(copy.second), style = MaterialTheme.typography.bodyMedium)
}

/** Labels can wrap at large text sizes without pushing the actual switch offscreen. */
@Composable
internal fun HistoryBackupToggle(label:String,checked:Boolean,enabled:Boolean,onCheckedChange:(Boolean)->Unit,
    modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f).padding(end=12.dp),style=MaterialTheme.typography.bodyLarge)
        Switch(checked=checked,onCheckedChange=onCheckedChange,enabled=enabled,
            modifier=Modifier.semantics {contentDescription=label})
    }
}
