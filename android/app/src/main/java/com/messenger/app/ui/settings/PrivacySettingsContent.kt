package com.messenger.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.R
import com.messenger.app.data.profile.PrivacySnapshot
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.launch

@Composable
fun PrivacySettingsContent() {
    val app = LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val auth by app.authRepository.authState.collectAsState()
    val repository = app.privacyRepository
    val owner = repository.ownerKey()
    key(owner) { PrivacySettingsOwned(repository, owner) }
}

@Composable
private fun PrivacySettingsOwned(repository: com.messenger.app.data.profile.PrivacyRepository, owner: String?) {
    val scope = rememberCoroutineScope()
    val snapshot by repository.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var exceptions by remember { mutableStateOf<Boolean?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    fun refresh() { scope.launch { busy = true; failed = repository.refresh().isFailure; busy = false } }
    DisposableEffect(lifecycle, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(owner) { refresh() }
    fun update(change: (PrivacySnapshot) -> PrivacySnapshot) {
        if (busy) return
        scope.launch { busy = true; failed = repository.update(change).isFailure; busy = false }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        val current = snapshot
        if (failed) {
            Text(stringResource(R.string.privacy_save_failed), color = LocalCocoColors.current.danger)
            SettingsDialogButton(onClick = { refresh() }, enabled = !busy) { Text(stringResource(R.string.retry)) }
        }
        if (current == null) {
            if (!failed) CircularProgressIndicator(Modifier.size(24.dp))
        } else {
            SettingChoiceRow(stringResource(R.string.privacy_online), if (current.onlineVisibility == "everyone") stringResource(R.string.privacy_everyone) else stringResource(R.string.privacy_nobody),
                listOf("everyone" to stringResource(R.string.privacy_everyone), "nobody" to stringResource(R.string.privacy_nobody)), !busy) { value -> update { it.copy(onlineVisibility = value) } }
            SettingChoiceRow(stringResource(R.string.privacy_last_seen), if (current.lastSeenVisibility == "everyone") stringResource(R.string.privacy_everyone) else stringResource(R.string.privacy_nobody),
                listOf("everyone" to stringResource(R.string.privacy_everyone), "nobody" to stringResource(R.string.privacy_nobody)), !busy) { value -> update { it.copy(lastSeenVisibility = value) } }
            SettingActionRow(stringResource(R.string.privacy_always_allow), stringResource(R.string.privacy_people_count, current.alwaysAllow.size), !busy) { exceptions = true }
            SettingActionRow(stringResource(R.string.privacy_never_allow), stringResource(R.string.privacy_people_count, current.neverAllow.size), !busy) { exceptions = false }
            Text(stringResource(R.string.privacy_exceptions_detail), style = MaterialTheme.typography.bodySmall, color = LocalCocoColors.current.mutedText)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.privacy_read_receipts), modifier = Modifier.weight(1f), color = LocalCocoColors.current.primaryText)
                Switch(current.sendReadReceipts, enabled = !busy, onCheckedChange = { checked -> update { it.copy(sendReadReceipts = checked) } })
            }
            Text(stringResource(R.string.privacy_read_detail), style = MaterialTheme.typography.bodySmall, color = LocalCocoColors.current.mutedText)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
    val allow = exceptions
    val current = snapshot
    if (allow != null && current != null) ExceptionPicker(repository, if (allow) current.alwaysAllow else current.neverAllow,
        title = stringResource(if (allow) R.string.privacy_always_allow else R.string.privacy_never_allow),
        onDismiss = { exceptions = null }, onSave = { selected -> exceptions = null; update { if (allow) it.copy(alwaysAllow = selected) else it.copy(neverAllow = selected) } })
}

@Composable
private fun ExceptionPicker(repository: com.messenger.app.data.profile.PrivacyRepository, initial: List<String>, title: String, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initial.toSet()) }
    var people by remember { mutableStateOf(emptyList<com.messenger.app.data.api.PublicUserDto>()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        kotlinx.coroutines.delay(250); loading = true
        try { people = repository.people(query); failed = false }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            OutlinedTextField(query, { query = it.take(128) }, label = { Text(stringResource(R.string.chats_search)) }, singleLine = true)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(stringResource(R.string.privacy_save_failed))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items((selected - people.map { it.id }.toSet()).toList(), key = { "selected:$it" }) { id ->
                    Row(Modifier.fillMaxWidth().clickable { selected = selected - id }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(true, { selected = selected - id }); Text(id, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(people, key = { it.id }) { person ->
                    Row(Modifier.fillMaxWidth().clickable { selected = if (person.id in selected) selected - person.id else if (selected.size < 256) selected + person.id else selected }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(person.id in selected, { checked -> selected = if (!checked) selected - person.id else if (selected.size < 256) selected + person.id else selected })
                        Column { Text(person.displayName); Text("@${person.username}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }, confirmButton = { SettingsDialogButton(onClick = { onSave(selected.toList()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { SettingsDialogButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun SettingActionRow(title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = colors.primaryText, modifier = Modifier.weight(1f))
        Text(value, color = colors.accentSoft, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}
@Composable
internal fun <T> SettingChoiceRow(title: String, value: String, options: List<Pair<T, String>>, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var show by remember { mutableStateOf(false) }
    SettingActionRow(title, value, enabled) { show = true }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text(title) }, text = {
        Column { options.forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { show = false; onSelect(key) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = label == value, onClick = null); Text(label, Modifier.padding(start = 12.dp))
            }
        } }
    }, confirmButton = { SettingsDialogButton(onClick = { show = false }) { Text(stringResource(R.string.cancel)) } })
}
