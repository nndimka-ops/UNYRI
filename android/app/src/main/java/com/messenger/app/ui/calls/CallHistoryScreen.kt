package com.messenger.app.ui.calls

import androidx.compose.material.icons.filled.Menu

import com.messenger.app.ui.theme.LocalGlassBottomInset

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.*
import com.messenger.app.domain.model.CallMediaKind
import com.messenger.app.data.call.CallAudioPolicy
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallHistoryScreen(
    modifier: Modifier = Modifier,
    onOpenMenu: () -> Unit = {},
    chatSummaries: kotlinx.coroutines.flow.StateFlow<List<com.messenger.app.domain.model.ChatSummary>>,
    onOpenChat: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val callState by app.callController.state.collectAsState()
    var pendingCall by remember { mutableStateOf<Triple<String, String, CallMediaKind>?>(null) }
    var permissionError by remember { mutableStateOf<Int?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = pendingCall
        pendingCall = null
        if (request != null) {
            val denied = CallAudioPolicy.requiredCallPermissions(request.third).filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }.toSet()
            if (denied.isEmpty()) app.callController.startCall(request.first, request.second, request.third)
            else permissionError = com.messenger.app.call.CallPermissionMessages.forDeniedCall(request.third, denied)
        }
    }
    fun requestCall(peer: String, name: String, kind: CallMediaKind) {
        if (CallAudioPolicy.requiredCallPermissions(kind).all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            app.callController.startCall(peer, name, kind)
        } else {
            pendingCall = Triple(peer, name, kind)
            permissionLauncher.launch(CallAudioPolicy.allCallPermissions(kind, android.os.Build.VERSION.SDK_INT).toTypedArray())
        }
    }
    permissionError?.let { resource ->
        AlertDialog(onDismissRequest = { permissionError = null }, text = { Text(stringResource(resource)) }, confirmButton = { TextButton(onClick = { permissionError = null }) { Text(stringResource(android.R.string.ok)) } })
    }
    val calls by app.callHistory.calls.collectAsState()
    // The account-scoped view model already holds the last chat snapshot.
    // Starting a cold Room flow with emptyList here flashes fallback call labels.
    val chats by chatSummaries.collectAsState()
    val chatsByPeer = remember(chats) { chats.mapNotNull { chat -> chat.otherUser?.id?.let { it to chat } }.toMap() }
    val auth by app.authRepository.authState.collectAsState()
    val owner = (auth as? AuthSessionState.LoggedIn)?.user?.id.orEmpty()
    val colors = LocalCocoColors.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val historyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(calls, owner, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { historyListState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                calls.firstOrNull { it.id == item.key && it.unread }?.id
            }.toSet() }.collect { ids -> if (ids.isNotEmpty()) {
                try { app.callHistory.markViewed(ids) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { android.util.Log.w("CallHistory", "Read acknowledgement could not be saved; retained for retry") }
            } }
        }
    }
    var selection by remember(owner) { mutableStateOf(setOf<String>()) }
    var menuId by remember(owner) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(owner) { mutableStateOf(false) }
    var deleting by remember(owner) { mutableStateOf(false) }
    com.messenger.app.ui.BlockRootDrawerWhile(selection.isNotEmpty() || menuId != null || confirmDelete || permissionError != null)
    fun toggle(id: String) { selection = if (id in selection) selection - id else selection + id }
    BackHandler(selection.isNotEmpty() && !deleting) { selection = emptySet() }
    LaunchedEffect(calls) { selection = selection.intersect(calls.map { it.id }.toSet()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadingOlder by remember { mutableStateOf(false) }
    LaunchedEffect(app, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    val next = app.callHistory.refresh()
                    if (!loaded) { cursor = next; loaded = true }
                    error = null
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { error = context.getString(R.string.calls_refresh_error) }
                delay(10_000)
            }
        }
    }
    fun deleteSelected(everyone: Boolean) {
        val ids = selection.toList()
        val epoch = app.authRepository.currentSessionEpoch()
        confirmDelete = false
        deleting = true
        scope.launch {
            try {
                for (id in ids) {
                    if (epoch != app.authRepository.currentSessionEpoch()) throw CancellationException()
                    app.callHistory.deleteHistory(id, everyone)
                    selection = selection - id
                }
                error = null
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { error = context.getString(R.string.calls_delete_error) }
            finally { deleting = false }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.calls_delete_title)) },
        text = { Text(stringResource(R.string.calls_delete_explanation, selection.size)) },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = { deleteSelected(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete_for_me)) }
                TextButton(onClick = { deleteSelected(true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete_for_everyone), color = colors.danger) }
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            }
        })
    com.messenger.app.ui.theme.CocoScreenSurface(modifier.fillMaxSize(), color = colors.background) {
        LazyColumn(state = historyListState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + LocalGlassBottomInset.current)) {
            item {
                com.messenger.app.ui.components.CocoPageHeader(
                    title = if (selection.isEmpty()) stringResource(R.string.calls_title) else stringResource(R.string.calls_selected, selection.size),
                    start = {
                        if (selection.isEmpty()) IconButton(onClick = onOpenMenu) {
                            Icon(androidx.compose.material.icons.Icons.Filled.Menu, stringResource(R.string.navigation_menu), tint = colors.primaryText)
                        }
                    },
                    end = {
                        if (selection.isNotEmpty()) {
                            TextButton(onClick = { confirmDelete = true }, enabled = !deleting) { Text(stringResource(R.string.delete), color = colors.danger) }
                            TextButton(onClick = { selection = emptySet() }, enabled = !deleting) { Text(stringResource(R.string.done)) }
                        }
                    },
                )
            }
            error?.let { item { Text(it, color = colors.danger, style = MaterialTheme.typography.bodySmall) } }
            if (calls.isEmpty()) item {
                Surface(Modifier.fillMaxWidth().padding(vertical=16.dp),shape=RoundedCornerShape(20.dp),color=colors.surface) {
                    Row(Modifier.padding(20.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        Icon(com.messenger.app.ui.theme.CocoIcons.AudioCall,null,tint=colors.primaryText,modifier=Modifier.size(28.dp))
                        Text(if (loaded) stringResource(R.string.calls_empty_title) else stringResource(R.string.calls_loading),
                            color=colors.secondaryText,style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(calls, key = { it.id }) { call ->
                val peer = if (call.callerUserId == owner) call.calleeUserId else call.callerUserId
                val chat = chatsByPeer[peer]
                val identity = com.messenger.app.data.call.callPeerIdentity(call, owner, chat?.otherUser)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().combinedClickable(enabled = !deleting, onLongClick = { menuId = call.id }, onClick = {
                    if (selection.isNotEmpty()) toggle(call.id)
                    else {
                    if (chat != null) onOpenChat(chat.chatId)
                    else scope.launch {
                        app.chatRepository.createOrOpenDirectChat(peer).onSuccess { onOpenChat(it.chatId) }
                            .onFailure { error = context.getString(R.string.calls_open_error) }
                    }
                    }
                })) { CallHistoryCard(call, owner, identity.name, listRow = true, avatar = {
                    com.messenger.app.ui.components.UserAvatar(userId = peer, avatarVersion = identity.avatarVersion, hasAvatar = identity.hasAvatar,
                        name = identity.name.orEmpty(), color = colors.surfaceVariant, contentColor = colors.primaryText, size = 44.dp)
                }) }
                DropdownMenu(expanded = menuId == call.id, onDismissRequest = { menuId = null },containerColor=colors.surface,shape=RoundedCornerShape(20.dp)) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_select)) }, onClick = { toggle(call.id); menuId = null })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete),color=colors.danger) }, onClick = { selection = setOf(call.id); menuId = null; confirmDelete = true })
                }
                }
                if (selection.isNotEmpty()) Checkbox(checked = call.id in selection, onCheckedChange = { toggle(call.id) }, enabled = !deleting)
                else IconButton(onClick = { requestCall(peer, identity.name.orEmpty(), if (call.mediaKind == "video") CallMediaKind.VIDEO else CallMediaKind.AUDIO) },
                    enabled = callState is com.messenger.app.domain.call.CallUiState.Idle && pendingCall == null) {
                    Icon(if (call.mediaKind == "video") com.messenger.app.ui.theme.CocoIcons.VideoCall else com.messenger.app.ui.theme.CocoIcons.AudioCall, stringResource(R.string.call_again), tint = colors.accent)
                }
                }
                HorizontalDivider(Modifier.padding(start = 56.dp), color = colors.divider)
            }
            if (cursor != null) item(key = "older-calls") {
                LaunchedEffect(cursor) {
                    if (loadingOlder) return@LaunchedEffect
                    loadingOlder = true
                        try { cursor = app.callHistory.refresh(before = cursor); error = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = context.getString(R.string.calls_refresh_error) }
                        finally { loadingOlder = false }
                }
                if (loadingOlder) androidx.compose.material3.CircularProgressIndicator()
            }
        }
    }
}
