package com.messenger.app.ui.chats

import com.messenger.app.ui.security.rejectObscuredTouches

import androidx.compose.material.icons.filled.Menu

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.messenger.app.ui.theme.LocalGlassBottomInset
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import java.time.ZoneId
import com.messenger.app.R
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.LastMessagePreview
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors

/**
 * COCO FLOW Chats screen (Этап N.1 §13/§14): a dense, modern chat list.
 * Saved Messages opens from a bookmark in the compact header.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ChatsScreen(
    viewModel: ChatsViewModel,
    showSavedMessages: Boolean = true,
    onOpenMenu: () -> Unit = {},
    onOpenChat: (String) -> Unit,
    onOpenRowChat: (String) -> Unit = onOpenChat,
    searchViewModel: com.messenger.app.ui.search.SearchViewModel,
    searching: Boolean,
    onSearchChange: (Boolean) -> Unit,
    onOpenSearchMessage: (com.messenger.app.domain.model.MessageSearchResult) -> Unit,
    modifier: Modifier = Modifier,
    searchPeople: Boolean = false,
    searchRequestKey: Int = 0,
    showingArchive: Boolean = false,
    onCloseArchive: () -> Unit = {},
) {
    val motion = rememberChatMotionSettings()
    val openMotion = LocalChatOpenMotion.current
    val motionClock = viewModel.listMotionClock
    var openSwipeId by remember { mutableStateOf<String?>(null) }
    var leavingIds by remember { mutableStateOf(emptySet<String>()) }
    var collapsedIds by remember { mutableStateOf(emptySet<String>()) }
    var rowSignals by remember { mutableStateOf(emptyMap<String, ChatRowMotionSignal>()) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.DisposableEffect(viewModel) { onDispose {
        motionClock.stamp(false, android.os.SystemClock.uptimeMillis())
        viewModel.cancelPendingOpen()
    } }

    val hydratedChats by viewModel.chats.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    var chats by remember(viewModel) { mutableStateOf(viewModel.displayedChats ?: hydratedChats) }
    LaunchedEffect(hydratedChats, openMotion?.holdUntilMillis) {
        val remaining = (openMotion?.holdUntilMillis ?: 0L) - android.os.SystemClock.uptimeMillis()
        if (remaining > 0) kotlinx.coroutines.delay(remaining)
        chats = hydratedChats
        viewModel.displayedChats = hydratedChats
    }
    LaunchedEffect(openMotion?.returnToken, motion.active) {
        val token = openMotion?.returnToken ?: return@LaunchedEffect
        if (!openMotion.consumeReturnGlow(token)) return@LaunchedEffect
        motionClock.returnedAt = android.os.SystemClock.uptimeMillis()
        openMotion.returnedChatId?.let { id ->
            kotlinx.coroutines.delay(50)
            if (motion.active) rowSignals = rowSignals + (id to ChatRowMotionSignal(System.nanoTime(), id, glow = true))
        }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val colors = LocalCocoColors.current
    val callRows by app.callHistory.calls.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val authState by app.authRepository.authState.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val owner = (authState as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id
    val context = androidx.compose.ui.platform.LocalContext.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val pinOwner = com.messenger.app.data.api.ServerEndpoint.current().trimEnd('/') + "|" + owner.orEmpty()
    val sessionEpoch = app.authRepository.currentSessionEpoch()
    val pinPrefs = remember(context) { context.getSharedPreferences("chat_pins", android.content.Context.MODE_PRIVATE) }
    var pinned by remember(pinOwner) { mutableStateOf(pinPrefs.getStringSet(pinOwner, emptySet())!!.toSet()) }
    var selecting by remember(pinOwner, sessionEpoch) { mutableStateOf(false) }
    var selection by remember(pinOwner, sessionEpoch) { mutableStateOf(emptySet<String>()) }
    var archived by remember(pinOwner, sessionEpoch) { mutableStateOf(emptySet<String>()) }
    var selectionEpoch by remember(pinOwner, sessionEpoch) { androidx.compose.runtime.mutableIntStateOf(-1) }
    var batchBusy by remember(pinOwner, sessionEpoch) { mutableStateOf(false) }
    var batchFailed by remember(pinOwner, sessionEpoch) { mutableStateOf(false) }
    var batchDelete by remember(pinOwner, sessionEpoch) { mutableStateOf<Set<String>?>(null) }
    var batchDeleteEveryone by remember(pinOwner, sessionEpoch) { mutableStateOf(false) }
    var batchDeleteRequests by remember(pinOwner, sessionEpoch) { mutableStateOf(emptyMap<String, String>()) }
    val onNavigateToSearch = { selecting = false; selection = emptySet(); viewModel.cancelPendingOpen(); onSearchChange(true) }
    val pinScope = rememberCoroutineScope()
    var pinBusy by remember(pinOwner) { mutableStateOf(false) }
    var pinError by remember(pinOwner) { mutableStateOf(false) }
    val pinApi = remember { com.messenger.app.data.api.ChatsApi(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }, com.messenger.app.BuildConfig.API_BASE_URL) }
    val pinMutex = remember(pinOwner) { kotlinx.coroutines.sync.Mutex() }
    fun setPinned(ids: Set<String>, enabled: Boolean) {
        if (owner == null || pinBusy) return
        val epoch = app.authRepository.currentSessionEpoch()
        pinBusy = true; pinError = false
        pinScope.launch {
            try {
                val result = app.chatRepository.setPins(ids, enabled)
                if (epoch == app.authRepository.currentSessionEpoch()) {
                    pinned = result
                    pinPrefs.edit().putStringSet(pinOwner, result).apply()
                }
            } catch (e: Exception) { if (epoch == app.authRepository.currentSessionEpoch()) pinError = true }
            finally { pinBusy = false }
        }
    }
    val pinConnection by app.messageRepository.socketState.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val managementRevision by app.messageRepository.managementRevision.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val knownChatIds = remember(chats) { chats.map { it.chatId }.toSet() }
    LaunchedEffect(pinOwner, pinConnection, knownChatIds, managementRevision) {
        if (owner == null) return@LaunchedEffect
        val epoch = app.authRepository.currentSessionEpoch()
        pinned = app.chatRepository.localPins()
        pinMutex.lock()
        try {
            val token = app.authRepository.currentAccessToken() ?: return@LaunchedEffect
            check(epoch == app.authRepository.currentSessionEpoch())
            val clears = pinApi.personalClears(token)
            app.callHistory.applyPersonalClears(clears, epoch)
            check(epoch == app.authRepository.currentSessionEpoch())
            for (clear in clears) {
                check(epoch == app.authRepository.currentSessionEpoch())
                check(app.messageRepository.applyPersonalClear(clear.chat_id, clear.through_sequence))
            }
            var result = app.chatRepository.refreshPins()
            if (!pinPrefs.getBoolean(pinOwner + "|synced", false)) {
                if (pinned.isNotEmpty() && chats.isEmpty()) return@LaunchedEffect
                val migrate = pinned.intersect(chats.map { it.chatId }.toSet()) - result
                if (migrate.isNotEmpty()) result = app.chatRepository.setPins(migrate, true)
                check(epoch == app.authRepository.currentSessionEpoch())
                pinPrefs.edit().putBoolean(pinOwner + "|synced", true).apply()
            }
            if (epoch == app.authRepository.currentSessionEpoch()) { pinned = result; pinPrefs.edit().putStringSet(pinOwner, result).apply() }
        } catch (_: Exception) { /* Keep the cached order offline. */ }
        finally { pinMutex.unlock() }
    }
    LaunchedEffect(pinOwner, sessionEpoch, pinConnection, knownChatIds, managementRevision) {
        if (owner == null) return@LaunchedEffect
        val epoch = app.authRepository.currentSessionEpoch()
        val origin = com.messenger.app.data.api.ServerEndpoint.current()
        fun current() = epoch == app.authRepository.currentSessionEpoch() && origin == com.messenger.app.data.api.ServerEndpoint.current()
        try {
            val local = app.chatRepository.localArchives()
            if (current()) archived = local
            val remote = app.chatRepository.refreshArchives()
            if (current()) archived = remote
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the account-local archive on older servers or offline. */ }
    }
    val listContext = "$showingArchive|$sessionEpoch"
    var previousListContext by rememberSaveable { mutableStateOf(listContext) }
    LaunchedEffect(listContext) {
        selecting = false; selection = emptySet(); batchFailed = false; batchDelete = null
        if (previousListContext != listContext) listState.scrollToItem(0)
        previousListContext = listContext
    }
    androidx.activity.compose.BackHandler(enabled = showingArchive && !searching && !selecting) { if (!batchBusy) onCloseArchive() }
    fun toggleSelection(id: String) { selection = if (id in selection) selection - id else selection + id }
    androidx.activity.compose.BackHandler(enabled = selecting && !searching) { if (!batchBusy) { selecting = false; selection = emptySet(); batchFailed = false } }
    LaunchedEffect(chats) { selection = selection.intersect(chats.map { it.chatId }.toSet()) }
    val latestCalls = remember(callRows, owner) { ChatCallActivity.latestByPeer(callRows, owner) }
    val scope = rememberCoroutineScope()
    var actionChat by remember { mutableStateOf<ChatSummary?>(null) }
    var actionOwner by remember { mutableStateOf<String?>(null) }
    var actionEpoch by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    fun activeMenuOwner(): String = com.messenger.app.data.api.ServerEndpoint.current().trimEnd('/') + "|" +
        ((app.authRepository.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id.orEmpty())
    var action by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf(false) }
    var reportText by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("other") }
    fun runSelectionAction(operation: String, ids: Set<String> = selection, everyone: Boolean = false) {
        if (batchBusy || pinBusy || ids.isEmpty() || pinOwner != activeMenuOwner() || selectionEpoch != app.authRepository.currentSessionEpoch()) return
        val epoch = app.authRepository.currentSessionEpoch()
        val targets = chats.filter { it.chatId in ids }.associateBy { it.chatId }
        val pinEnabled = !ids.all { it in pinned }
        val muteEnabled = !ids.all { targets[it]?.isMuted == true }
        val requests = batchDeleteRequests
        batchBusy = true; batchFailed = false
        scope.launch {
            fun current() = pinOwner == activeMenuOwner() && epoch == app.authRepository.currentSessionEpoch()
            try {
                val result = runChatSelectionBatch(ids, ::current) { id ->
                    check(targets[id] != null)
                    when (operation) {
                        "pin" -> app.chatRepository.setPins(setOf(id), pinEnabled)
                        "archive" -> {
                            check(targets[id]?.type == "direct")
                            val result = app.chatRepository.setArchived(setOf(id), !showingArchive)
                            if (current()) archived = result
                        }
                        "mute" -> (if (muteEnabled) app.chatRepository.setMute(id) else app.chatRepository.clearMute(id)).getOrThrow()
                        "delete" -> {
                            check(targets[id]?.type == "direct")
                            app.chatClearQueue.submit(id, everyone, checkNotNull(requests[id]))
                        }
                    }
                }
                if (current()) {
                    if (operation == "pin") pinned = app.chatRepository.localPins()
                    selection = selection - result.succeeded
                    if (operation == "delete") batchDeleteRequests = batchDeleteRequests - result.succeeded
                    selecting = selection.isNotEmpty()
                    batchFailed = result.failed.isNotEmpty()
                    batchDelete = null
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) { batchFailed = true; batchDelete = null } }
            finally { if (current()) batchBusy = false }
        }
    }
    batchDelete?.let { ids ->
        AlertDialog(onDismissRequest = { if (!batchBusy) batchDelete = null },
            title = { Text(stringResource(R.string.chats_batch_delete_title, ids.size)) },
            text = { Column {
                Text(stringResource(if (batchDeleteEveryone) R.string.chats_delete_both_detail else R.string.chats_delete_me_detail))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = batchDeleteEveryone, enabled = !batchBusy, onCheckedChange = { value ->
                        batchDeleteEveryone = value
                        batchDeleteRequests = batchDeleteRequests + ids.associateWith { java.util.UUID.randomUUID().toString() }
                    })
                    Text(stringResource(R.string.chats_delete_both))
                }
                if (batchBusy) CircularProgressIndicator(Modifier.size(24.dp))
            } },
            confirmButton = { TextButton(enabled = !batchBusy, onClick = {
                com.messenger.app.ui.theme.CocoHaptics.tap(hapticView)
                runSelectionAction("delete", ids, batchDeleteEveryone)
            }) { Text(stringResource(if (batchDeleteEveryone) R.string.chats_delete_both else R.string.chats_delete_me), color = colors.danger) } },
            dismissButton = { TextButton(enabled = !batchBusy, onClick = { batchDelete = null }) { Text(stringResource(R.string.chat_controls_close)) } })
    }
    LaunchedEffect(pinOwner, sessionEpoch) { actionChat = null; actionOwner = null; action = null; reportText = ""; actionError = false; actionBusy = false }
    LaunchedEffect(owner) { if (owner != null) runCatching { app.callHistory.refresh() } }
    fun submit(operation: String) {
        val chat = actionChat ?: return
        if (actionBusy || actionOwner != activeMenuOwner() || actionEpoch != app.authRepository.currentSessionEpoch()) return
        actionBusy = true; actionError = false
        val epoch = app.authRepository.currentSessionEpoch()
        scope.launch {
            try {
                val result = when (operation) {
                    "archive" -> runCatching {
                        val result = app.chatRepository.setArchived(setOf(chat.chatId), chat.chatId !in archived)
                        if (epoch == app.authRepository.currentSessionEpoch() && actionOwner == activeMenuOwner()) archived = result
                        Unit
                    }
                    "mute" -> if (chat.isMuted) app.chatRepository.clearMute(chat.chatId) else app.chatRepository.setMute(chat.chatId)
                    "block" -> if (chat.isBlocked) app.chatRepository.unblockUser(chat.chatId) else chat.otherUser?.id?.let { app.chatRepository.blockUser(chat.chatId, it) } ?: Result.failure(IllegalStateException())
                    else -> app.chatRepository.reportChat(chat.chatId, reason, chat.otherUser?.id, reportText)
                }
                if (epoch != app.authRepository.currentSessionEpoch()) return@launch
                if (result.isSuccess) { actionChat = null; action = null; reportText = "" }
                else actionError = true
            } finally { if (epoch == app.authRepository.currentSessionEpoch()) actionBusy = false }
        }
    }
    var deleteTarget by remember(pinOwner, sessionEpoch) { mutableStateOf<ChatSummary?>(null) }
    var deleteForEveryone by remember(deleteTarget?.chatId, sessionEpoch) { mutableStateOf(false) }
    val deleteRequestId = remember(deleteTarget?.chatId, deleteForEveryone, sessionEpoch) { java.util.UUID.randomUUID().toString() }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { if (!actionBusy) deleteTarget = null },
            title = { Text(stringResource(R.string.chats_delete)) },
            text = { Column {
                Text(stringResource(if (deleteForEveryone) R.string.chats_delete_both_detail else R.string.chats_delete_me_detail))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = deleteForEveryone, enabled = !actionBusy, onCheckedChange = { deleteForEveryone = it })
                    Text(stringResource(R.string.chats_delete_both))
                }
                if (actionError) Text(stringResource(R.string.chat_settings_failed))
            } },
            confirmButton = { TextButton(enabled = !actionBusy, onClick = confirmDelete@{
                if (actionOwner != activeMenuOwner() || actionEpoch != app.authRepository.currentSessionEpoch()) return@confirmDelete
                actionBusy = true; actionError = false
                val epoch = app.authRepository.currentSessionEpoch()
                scope.launch {
                    try {
                        check(epoch == app.authRepository.currentSessionEpoch())
                        app.chatClearQueue.submit(target.chatId, deleteForEveryone, deleteRequestId)
                        check(epoch == app.authRepository.currentSessionEpoch())
                        deleteTarget = null; actionChat = null
                    } catch (_: Exception) { if (epoch == app.authRepository.currentSessionEpoch()) actionError = true }
                    finally { if (epoch == app.authRepository.currentSessionEpoch()) actionBusy = false }
                }
            }) { Text(stringResource(if (deleteForEveryone) R.string.chats_delete_both else R.string.chats_delete_me),color=colors.danger) } },
            dismissButton = { TextButton(enabled = !actionBusy, onClick = { deleteTarget = null }) { Text(stringResource(R.string.chat_controls_close)) } })
    }
    val menuClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    actionChat?.let { selected ->
        val publicCopy = selected.otherUser?.let { com.messenger.app.ui.publicHandle(it) }?.takeIf { it.startsWith("@") }?.drop(1)
        val cyan = colors.categoryBlue; val mint = colors.categoryMint; val amber = colors.categoryAmber
        val coral = colors.danger; val violet = colors.categoryViolet
        val name = selected.otherUser?.visibleName ?: selected.otherUser?.username ?: stringResource(R.string.chat_controls)
        if (action == null) ContactChatMenu(title = name, avatar = {
            com.messenger.app.ui.components.UserAvatar(userId = selected.otherUser?.id.orEmpty(), avatarVersion = selected.otherUser?.avatarVersion ?: 0,
                hasAvatar = selected.otherUser?.hasAvatar == true, name = name, color = violet.copy(alpha = .15f), contentColor = violet, size = 46.dp)
        }, enabled = !actionBusy, canAct = { actionOwner == activeMenuOwner() && actionEpoch == app.authRepository.currentSessionEpoch() },
            onDismiss = { if (!actionBusy) actionChat = null }, error = if (actionError) stringResource(R.string.chat_settings_failed) else null,
            quick = listOf(
                ContactChatAction(stringResource(R.string.chat_open), CocoIcons.Chat, cyan) { actionChat = null; onOpenChat(selected.chatId) },
                ContactChatAction(stringResource(if (selected.chatId in pinned) R.string.chats_unpin else R.string.chats_pin), CocoIcons.Pin, amber, !pinBusy) { setPinned(setOf(selected.chatId), selected.chatId !in pinned); actionChat = null },
                ContactChatAction(stringResource(if (selected.isMuted) R.string.chat_menu_unmute_short else R.string.chat_menu_mute_short), if (selected.isMuted) CocoIcons.Speaker else CocoIcons.MutedBell, mint) { submit("mute") }
            ), rows = buildList {
                if (selected.type == "direct") add(ContactChatAction(
                    stringResource(if (selected.chatId in archived) R.string.chats_restore else R.string.chats_archive_action),
                    if (selected.chatId in archived) CocoIcons.RestoreChat else CocoIcons.Archive, colors.primaryText
                ) { submit("archive") })
                publicCopy?.let { username ->
                    add(ContactChatAction(stringResource(R.string.chat_copy_username), CocoIcons.Copy, cyan) { menuClipboard.setText(androidx.compose.ui.text.AnnotatedString("@$username")); actionChat = null })
                }
                add(ContactChatAction(stringResource(R.string.chat_report), CocoIcons.ReportFlag, amber) { action = "report"; reportText = ""; reason = "other" })
            }, danger = buildList {
                add(ContactChatAction(stringResource(if (selected.isBlocked) R.string.chat_unblock else R.string.chat_block), CocoIcons.Security, if (selected.isBlocked) mint else coral, sensitive = true) { if (selected.isBlocked) submit("block") else action = "block" })
                if (selected.type == "direct") add(ContactChatAction(stringResource(R.string.chats_delete), CocoIcons.Delete, coral) { deleteTarget = selected; actionChat = null })
            }) else AlertDialog(onDismissRequest = { if (!actionBusy) { actionChat = null; action = null } },
            title = { Text(stringResource(if (action == "report") R.string.chat_report else if (action == "block") R.string.chat_block else R.string.chat_controls)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (action == null) {
                    TextButton(onClick = { submit("mute") }, enabled = !actionBusy) { Text(stringResource(if (selected.isMuted) R.string.chat_unmute else R.string.chat_mute)) }
                    TextButton(modifier = Modifier.rejectObscuredTouches(), onClick = { if (selected.isBlocked) submit("block") else action = "block" }, enabled = !actionBusy) { Text(stringResource(if (selected.isBlocked) R.string.chat_unblock else R.string.chat_block)) }
                    TextButton(onClick = { action = "report"; reportText = ""; reason = "other" }, enabled = !actionBusy) { Text(stringResource(R.string.chat_report)) }
                } else if (action == "block") { Text(stringResource(R.string.chat_block_detail)) }
                else {
                    Text(stringResource(R.string.chat_report_disclosure))
                    listOf("spam" to R.string.chat_reason_spam, "harassment" to R.string.chat_reason_harassment, "scam" to R.string.chat_reason_scam, "other" to R.string.chat_reason_other).forEach { (value, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = reason == value, onClick = { reason = value }, enabled = !actionBusy)
                            Text(stringResource(label))
                        }
                    }
                    OutlinedTextField(reportText, { reportText = it.take(4000) }, label = { Text(stringResource(R.string.chat_report_description)) }, enabled = !actionBusy, maxLines = 3)
                }
                if (actionBusy) CircularProgressIndicator()
                if (actionError) Text(stringResource(R.string.chat_settings_failed))
            } },
            confirmButton = { if (action != null) TextButton(modifier = Modifier.rejectObscuredTouches(), onClick = { submit(action ?: return@TextButton) }, enabled = !actionBusy) { Text(stringResource(if (action == "block") R.string.chat_block else R.string.chat_report_send),color=if(action=="block")colors.danger else colors.accent) } },
            dismissButton = { TextButton(onClick = { actionChat = null; action = null }, enabled = !actionBusy) { Text(stringResource(R.string.chat_controls_close)) } })
    }
    val connection by app.messageRepository.socketState.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val activity by app.messageRepository.peerActivity.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    var activityNow by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(activity, searching, motion.active) {
        if (searching || !motion.active) return@LaunchedEffect
        while (true) {
            activityNow = android.os.SystemClock.elapsedRealtime()
            val nextExpiry = activity.values.map { it.second + 8000 - activityNow }.filter { it > 0 }.minOrNull() ?: break
            kotlinx.coroutines.delay(nextExpiry)
        }
    }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    val showFilters by com.messenger.app.ui.settings.rememberChatFiltersEnabled()
    var filterMenu by remember { mutableStateOf(false) }
    LaunchedEffect(showFilters) { if (!showFilters) unreadOnly = false }
    val filterUnread = showFilters && unreadOnly
    var showError by remember { mutableStateOf(false) }
    val modalCoverage = ChatListModalCoverage(contactMenu = actionChat != null, deleteConfirmation = deleteTarget != null,
        batchDeleteConfirmation = batchDelete != null, filterPopup = filterMenu, errorDialog = showError)
    val visibleMotion = if (modalCoverage.covered) motion.copy(active = false) else motion
    com.messenger.app.ui.BlockRootDrawerWhile(selecting || modalCoverage.covered || openSwipeId != null)
    val presentation = viewModel.listPresentation
    val live = visibleMotion.active && !searching && !selecting &&
        openSwipeId == null && !listState.isScrollInProgress
    val latestLive by androidx.compose.runtime.rememberUpdatedState(live)
    val computedOrder = remember(chats, filterUnread, latestCalls, pinned, archived, showingArchive, collapsedIds) {
        presentation.ordered(chats, pinned, archived, showingArchive, filterUnread, latestCalls, collapsedIds)
    }
    val computedSnapshot = remember(computedOrder, pinned, filterUnread, showingArchive, chats) {
        ChatListSnapshot(computedOrder, pinned.toSet(), "$filterUnread|$showingArchive", chats)
    }
    var frame by remember(viewModel) { mutableStateOf(viewModel.displayedFrame ?: viewModel.listUpdates.apply(computedSnapshot, false, android.os.SystemClock.uptimeMillis())) }
    LaunchedEffect(computedSnapshot, searching, openMotion?.holdUntilMillis) {
        if (searching) return@LaunchedEffect
        val remaining = (openMotion?.holdUntilMillis ?: 0L) - android.os.SystemClock.uptimeMillis()
        if (remaining > 0) kotlinx.coroutines.delay(remaining)
        if (frame.snapshot != computedSnapshot) {
            val next = viewModel.listUpdates.apply(computedSnapshot, latestLive, android.os.SystemClock.uptimeMillis())
            frame = next
            viewModel.displayedFrame = next
        }
        if (viewModel.displayedFrame == null) viewModel.displayedFrame = frame
    }
    val order = frame.snapshot.order
    val conversations = order.rows
    val displayedPins = frame.snapshot.pins
    LaunchedEffect(live) { motionClock.stamp(live, android.os.SystemClock.uptimeMillis()) }
    LaunchedEffect(searching, selecting, actionChat, filterMenu, listState.isScrollInProgress) {
        if (searching || selecting || actionChat != null || filterMenu || listState.isScrollInProgress) openSwipeId = null
    }
    val scrollMemory = remember(viewModel) { viewModel.inlineSearchScroll }
    LaunchedEffect(searching) {
        if (searching) scrollMemory.capture(listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String,
            listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        else scrollMemory.restore(conversations.map { it.chatId })?.let { (index, offset) -> listState.scrollToItem(index, offset) }
    }
    val decision = frame.decision
    LaunchedEffect(frame) {
        if (frame.signals().isNotEmpty()) {
            androidx.compose.runtime.withFrameNanos { }
            val visible = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
            val signals = frame.signals().filter { it.chatId in visible }
            rowSignals = rowSignals + signals.associateBy { it.chatId }
            signals.forEach { signal -> scope.launch {
                kotlinx.coroutines.delay((1600 * motion.durationScale).toLong())
                if (rowSignals[signal.chatId]?.token == signal.token) rowSignals = rowSignals - signal.chatId
            } }
        }
    }
    fun commitSwipe(kind: String, chat: ChatSummary) {
        if (pinOwner != activeMenuOwner() || batchBusy || actionBusy || pinBusy) return
        val epoch = app.authRepository.currentSessionEpoch()
        if (kind == "pin") { setPinned(setOf(chat.chatId), chat.chatId !in pinned); return }
        if (kind == "archive" && chat.type != "direct") return
        if (kind == "archive") leavingIds = leavingIds + chat.chatId
        scope.launch {
            try {
                if (kind == "archive") {
                    if (!motion.reduceMotion) kotlinx.coroutines.delay((260 * motion.durationScale).toLong())
                    collapsedIds = collapsedIds + chat.chatId
                    val result = app.chatRepository.setArchived(setOf(chat.chatId), chat.chatId !in archived)
                    if (epoch == app.authRepository.currentSessionEpoch()) archived = result
                } else {
                    (if (chat.isMuted) app.chatRepository.clearMute(chat.chatId) else app.chatRepository.setMute(chat.chatId)).getOrThrow()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == app.authRepository.currentSessionEpoch()) pinError = true }
            finally { if (epoch == app.authRepository.currentSessionEpoch()) {
                leavingIds = leavingIds - chat.chatId
                collapsedIds = collapsedIds - chat.chatId
            } }
        }
    }
    val presence = app.presence
    // Collapse lease/timestamp renewals before dispatching anything to Compose.
    val peerStates by presence.online.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED, initialValue = emptyMap())
    val presenceOwner = remember(pinOwner) { "chats:" + java.util.UUID.randomUUID() }
    val rootLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var presenceResumed by remember(rootLifecycle) { mutableStateOf(rootLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    androidx.compose.runtime.DisposableEffect(rootLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            presenceResumed = rootLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        rootLifecycle.addObserver(observer)
        onDispose { rootLifecycle.removeObserver(observer) }
    }
    LaunchedEffect(presence, presenceOwner, conversations, searching, presenceResumed) {
        if (!presenceResumed || searching) return@LaunchedEffect
        val subscriptionOwner = presenceOwner + ":" + java.util.UUID.randomUUID()
        val peersByChat = conversations.filterNot { it.isBlocked }.mapNotNull { chat ->
            chat.otherUser?.id?.let { chat.chatId to it }
        }.toMap()
        try {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
                .distinctUntilChanged()
                .collectLatest { visible ->
                    kotlinx.coroutines.delay(100) // coalesce fast scrolling into one subscription
                    presence.subscribe(subscriptionOwner, visible.mapNotNull(peersByChat::get).distinct().take(64))
                }
        } finally { presence.unsubscribe(subscriptionOwner) }
    }

    if (showError) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showError = false },
        title = { Text(stringResource(R.string.chat_update_failed)) },
        text = { Text((uiState as? ChatsUiState.Error)?.message.orEmpty()) },
        confirmButton = { TextButton(onClick = { showError = false; viewModel.refresh() }) { Text(stringResource(R.string.retry)) } },
    )
    com.messenger.app.ui.theme.CocoScreenSurface(modifier = modifier.fillMaxSize(), color = ChatsPalette.background) {
        Box(Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().then(if (searching) Modifier
            .graphicsLayer { alpha = 0f }.clearAndSetSemantics { } else Modifier)) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp)) {
            val centerWidth = (maxWidth - (if (showSavedMessages) 136.dp else 96.dp) * 2).coerceAtLeast(96.dp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (showingArchive) com.messenger.app.ui.components.CocoBackButton(onClick = onCloseArchive,
                    label = stringResource(R.string.chats_archive_back))
                else androidx.compose.material3.IconButton(onClick = onOpenMenu) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Menu, stringResource(R.string.navigation_menu), tint = colors.primaryText)
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // One search (owner, 14 Nov): the chat list's button, as on iOS; the drawer has none.
                    CompactChatHeaderButton(CocoIcons.Search, stringResource(R.string.chats_search), colors.primaryText, tag = "chats-search") {
                        com.messenger.app.ui.theme.CocoHaptics.select(hapticView); onNavigateToSearch()
                    }
                    Box {
                        CompactChatHeaderButton(CocoIcons.ChatFilter, stringResource(R.string.chats_actions), colors.primaryText) {
                            com.messenger.app.ui.theme.CocoHaptics.select(hapticView); filterMenu = true
                        }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            if (showFilters) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.chats_filter_all)) }, onClick = {
                                com.messenger.app.ui.theme.CocoHaptics.select(hapticView); unreadOnly = false; filterMenu = false
                            }, trailingIcon = { if (!unreadOnly) Icon(CocoIcons.Check, null, tint = colors.accent, modifier = Modifier.size(18.dp)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.chats_filter_unread)) }, onClick = {
                                com.messenger.app.ui.theme.CocoHaptics.select(hapticView); unreadOnly = true; filterMenu = false
                            }, trailingIcon = { if (unreadOnly) Icon(CocoIcons.Check, null, tint = colors.accent, modifier = Modifier.size(18.dp)) })
                            HorizontalDivider()
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.chats_select)) }, leadingIcon = { Icon(CocoIcons.Select, null) },
                                enabled = !batchBusy && conversations.isNotEmpty(), onClick = {
                                    com.messenger.app.ui.theme.CocoHaptics.select(hapticView)
                                    filterMenu = false; selecting = true; selection = emptySet(); batchFailed = false
                                    selectionEpoch = app.authRepository.currentSessionEpoch()
                                })
                        }
                    }
                    if (showSavedMessages) CompactChatHeaderButton(CocoIcons.Bookmark, stringResource(R.string.saved_messages), ChatControls.amber) {
                        viewModel.openSavedMessages(onOpenChat)
                    }
                }
            }
            // 14 Nov (owner: "когда ты уже отцентруешь"): the status sits on the true centre of the
            // screen, not between one button on the left and two or three on the right.
            Box(Modifier.align(Alignment.Center).widthIn(max = centerWidth), contentAlignment = Alignment.Center) {
                    val island = com.messenger.app.ui.call.LocalCallIsland.current
                    if (island != null) {
                        com.messenger.app.ui.call.CallIslandControl(island.durationMs, island.isVideo, island.onReturn, island.status)
                    } else if (showingArchive) {
                        Text(stringResource(R.string.chats_archive_title), color = colors.primaryText, style = MaterialTheme.typography.titleMedium)
                    } else com.messenger.app.ui.components.ChatConnectionHeader(app.messageRepository,
                        loading = uiState is ChatsUiState.Loading && chats.isEmpty(), error = uiState is ChatsUiState.Error,
                        onRetry = { viewModel.refresh(); app.messageRepository.retryConnectionSync() }, contentReady = chats.isNotEmpty(),
                        visible = modalCoverage.headerVisible)
                }
            }
            HorizontalDivider(thickness = .5.dp, color = ChatsPalette.border.copy(alpha = .45f))

            if (pinError) Text(stringResource(R.string.chat_settings_failed), Modifier.padding(horizontal = 16.dp))
            if (pinBusy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            if (selecting) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chats_selected_count, selection.size), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = !batchBusy && conversations.isNotEmpty(), onClick = {
                        com.messenger.app.ui.theme.CocoHaptics.select(hapticView)
                        val visible = conversations.map { it.chatId }.toSet()
                        selection = if (selection.containsAll(visible)) selection - visible else selection + visible
                    }) { Text(stringResource(if (conversations.isNotEmpty() && selection.containsAll(conversations.map { it.chatId })) R.string.chats_deselect_all else R.string.chats_select_all)) }
                    IconButton(enabled = !batchBusy, onClick = { selecting = false; selection = emptySet(); batchFailed = false }) { Icon(CocoIcons.Close, stringResource(R.string.chat_controls_close)) }
                }
                if (batchFailed) Text(stringResource(R.string.chats_batch_failed), color = colors.danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = CocoSpacing.xxl + LocalGlassBottomInset.current)) {
                if (conversations.isEmpty()) {
                    item(key = "empty") {
                        if (uiState is ChatsUiState.Loading) {
                            Box(Modifier.fillMaxWidth().padding(CocoSpacing.xxxl), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = colors.accent, strokeWidth = 2.dp)
                            }
                        } else if (showingArchive && !filterUnread) {
                            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(CocoIcons.Archive, null, tint = colors.secondaryText, modifier = Modifier.size(36.dp))
                                Text(stringResource(R.string.chats_archive_empty), color = colors.primaryText, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.chats_archive_detail), color = colors.secondaryText, textAlign = TextAlign.Center)
                            }
                        } else if (filterUnread) {
                            Text(stringResource(R.string.chats_no_unread), color = colors.secondaryText, modifier = Modifier.padding(24.dp))
                        } else if (uiState !is ChatsUiState.Error) {
                            EmptyChatsState(onStartChat = onNavigateToSearch)
                        }
                    }
                }
                items(conversations, key = { it.chatId }) { chat ->
                    val actions = buildList {
                        add(ChatSwipeAction("pin", stringResource(if (chat.chatId in displayedPins) R.string.chats_unpin else R.string.chats_pin), chatSwipeIcon("pin", chat.chatId in displayedPins, false), colors.categoryAmber, chatSwipeIcon("pin", chat.chatId in displayedPins, true)))
                        add(ChatSwipeAction("mute", stringResource(if (chat.isMuted) R.string.chat_menu_unmute_short else R.string.chat_menu_mute_short), chatSwipeIcon("mute", !chat.isMuted, false), colors.categoryMint, chatSwipeIcon("mute", !chat.isMuted, true)))
                        if (chat.type == "direct") add(ChatSwipeAction("archive", stringResource(if (showingArchive) R.string.chats_restore_short else R.string.chats_archive_title), chatSwipeIcon("archive", showingArchive, false), colors.categoryBlue, chatSwipeIcon("archive", showingArchive, true)))
                    }
                    ChatArchiveDeparture(leaving = chat.chatId in leavingIds, calm = visibleMotion.calm,
                        modifier = Modifier.animateItem(fadeInSpec = null,
                            placementSpec = if (!visibleMotion.calm && decision.animateOrder) chatSpring(.42f, .86f) else null,
                            fadeOutSpec = null)) {
                    ChatListRowMotion(rowSignals[chat.chatId], motionClock, visibleMotion.calm,
                        visibleMotion.active && !searching, motion.durationScale) {
                    ChatSwipeRow(actions = actions, isOpen = openSwipeId == chat.chatId,
                        enabled = !selecting && !batchBusy && !searching && actionChat == null,
                        calm = visibleMotion.calm, touchExploration = motion.touchExploration, durationScale = motion.durationScale,
                        onBegin = { openSwipeId = chat.chatId }, onClose = { if (openSwipeId == chat.chatId) openSwipeId = null },
                        onCommit = { commitSwipe(it, chat) }) {
                    ChatRow(chat = chat, callOwner = owner.orEmpty(), selecting = selecting, selected = chat.chatId in selection, pinned = chat.chatId in displayedPins,
                        onClick = {
                            if (openSwipeId != null) openSwipeId = null
                            else if (selecting) { if (!batchBusy) { com.messenger.app.ui.theme.CocoHaptics.select(hapticView); toggleSelection(chat.chatId) } }
                            else { com.messenger.app.ui.theme.CocoHaptics.tap(hapticView); onOpenRowChat(chat.chatId) }
                        },
                        onLongClick = {
                            openSwipeId = null
                            if (selecting) { if (!batchBusy) toggleSelection(chat.chatId) }
                            else if (pinOwner == activeMenuOwner()) { actionOwner = pinOwner; actionEpoch = app.authRepository.currentSessionEpoch(); actionChat = chat; action = null; actionError = false }
                        }, latestCall = latestCalls[chat.otherUser?.id],
                        activityKind = if (motion.active) activity[chat.chatId]?.takeIf { activityNow - it.second < 8000 }?.first else null,
                        presenceState = if (!motion.active || chat.isBlocked) ChatPresenceState.Unknown else chat.otherUser?.id?.let { peerStates[it] }?.let {
                            if (it) ChatPresenceState.Online else ChatPresenceState.Offline
                        } ?: ChatPresenceState.Unknown,
                        calm = visibleMotion.calm, durationScale = motion.durationScale, animateCount = true,
                        actions = actions, onAction = { commitSwipe(it, chat) }, modifier = Modifier)
                    }
                    }
                    }
                }
            }
            if (selecting) {
                val selectedChats = chats.filter { it.chatId in selection }
                val allPinned = selection.isNotEmpty() && selection.all { it in pinned }
                val allMuted = selectedChats.isNotEmpty() && selectedChats.all { it.isMuted }
                Surface(color = colors.deepPanel, tonalElevation = 0.dp) {
                    Column(Modifier.fillMaxWidth().padding(bottom = LocalGlassBottomInset.current)) {
                        if (batchBusy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            SelectionActionButton(CocoIcons.Pin, stringResource(if (allPinned) R.string.chats_unpin else R.string.chats_pin), colors.primaryText, selection.isNotEmpty() && !batchBusy && !pinBusy, Modifier.weight(1f)) { runSelectionAction("pin") }
                            SelectionActionButton(if (allMuted) CocoIcons.Speaker else CocoIcons.MutedBell, stringResource(if (allMuted) R.string.chat_menu_unmute_short else R.string.chat_menu_mute_short), colors.primaryText, selection.isNotEmpty() && !batchBusy && !pinBusy, Modifier.weight(1f)) { runSelectionAction("mute") }
                            SelectionActionButton(if (showingArchive) CocoIcons.RestoreChat else CocoIcons.Archive,
                                stringResource(if (showingArchive) R.string.chats_restore_short else R.string.chats_archive_title), colors.primaryText,
                                selectedChats.isNotEmpty() && selectedChats.all { it.type == "direct" } && !batchBusy && !pinBusy, Modifier.weight(1f)) { runSelectionAction("archive") }
                            SelectionActionButton(CocoIcons.Delete, stringResource(R.string.chats_delete), colors.danger, selectedChats.isNotEmpty() && selectedChats.all { it.type == "direct" } && !batchBusy && !pinBusy, Modifier.weight(1f)) {
                                batchDeleteRequests = batchDeleteRequests + selection.filterNot { it in batchDeleteRequests }.associateWith { java.util.UUID.randomUUID().toString() }
                                batchDelete = selection.toSet()
                            }
                        }
                    }
                }
            }
        }
        if (searching) androidx.compose.runtime.key(searchRequestKey) {
            com.messenger.app.ui.search.InlineChatSearch(searchViewModel, { onSearchChange(false) }, onOpenChat, onOpenSearchMessage,
                Modifier.fillMaxSize().background(ChatsPalette.background).pointerInput(Unit) { detectTapGestures { } })
        }
        }
    }
}


@Composable
private fun SelectionActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color,
    enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button) {
        com.messenger.app.ui.theme.CocoHaptics.tap(view); onClick()
    }.heightIn(min = 56.dp).padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = tint.copy(alpha = if (enabled) 1f else .38f), modifier = Modifier.size(21.dp))
        Text(label, color = tint.copy(alpha = if (enabled) 1f else .38f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CompactChatHeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color,
    tag: String = "", onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(Modifier.size(48.dp).then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(14.dp), color = Color.Transparent,
            // The callback owns the selection tick; the quiet style supplies only visual feedback.
            modifier = com.messenger.app.ui.theme.cocoQuietPressFeedback(interaction, haptic = false)) {
            IconButton(onClick = onClick, modifier = Modifier.size(44.dp), interactionSource = interaction) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ChatRow(chat: ChatSummary, callOwner: String, selecting: Boolean = false, selected: Boolean = false, pinned: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit, latestCall: com.messenger.app.data.api.CallDto?, activityKind: String? = null,
    presenceState: ChatPresenceState = ChatPresenceState.Unknown, calm: Boolean = false, durationScale: Float = 1f, animateCount: Boolean = false,
    actions: List<ChatSwipeAction> = emptyList(), onAction: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val colors = LocalCocoColors.current
    val displayedCall = latestCall?.takeIf { ChatCallActivity.callIsLatest(chat, it) }
    val callOutcome = displayedCall?.let { com.messenger.app.data.call.callOutcome(it, callOwner) }
    val callLabel = displayedCall?.let { call -> when (callOutcome) {
        com.messenger.app.data.call.CallHistoryOutcome.INCOMING -> R.string.call_history_incoming
        com.messenger.app.data.call.CallHistoryOutcome.OUTGOING -> R.string.call_history_outgoing
        com.messenger.app.data.call.CallHistoryOutcome.MISSED -> R.string.call_history_missed
        com.messenger.app.data.call.CallHistoryOutcome.CANCELLED -> if (call.endReason == "missed") R.string.call_history_no_answer else R.string.call_history_cancelled
        com.messenger.app.data.call.CallHistoryOutcome.REJECTED -> R.string.call_history_rejected
        else -> R.string.call_history_failed
    } }
    val callPreview = if (displayedCall != null && callLabel != null) stringResource(callLabel) + " · " +
        stringResource(if (displayedCall.mediaKind == "video") R.string.call_history_video else R.string.call_history_audio) else null
    val name = chat.otherUser?.visibleName ?: stringResource(R.string.chats_unknown_user)
    val avatarTint = colors.avatarTints[Math.floorMod(chat.chatId.hashCode(), colors.avatarTints.size)]
    val locale = LocalConfiguration.current.locales[0]
    val today = java.time.LocalDate.now()
    val activityAt = if (ChatCallActivity.callIsLatest(chat, latestCall)) latestCall?.createdAt else chat.lastMessageAt
    val timestamp = remember(activityAt, locale, today) {
        activityAt?.let { chatTimestamp(it, java.time.Instant.now(), ZoneId.systemDefault(), locale) }
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (pinned || selected) 4.dp else 0.dp)
            .clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { customActions = actions.map { action -> androidx.compose.ui.semantics.CustomAccessibilityAction(action.title) { onAction(action.kind); true } } },
        shape = RoundedCornerShape(20.dp),
        color = if (selected) ChatsPalette.selected else if (pinned) ChatsPalette.card.copy(alpha = .65f) else Color.Transparent,
    ) {
        Column {
        Row(
            modifier = Modifier
                .heightIn(min = if (pinned) 68.dp else 72.dp)
                .padding(horizontal = 8.dp, vertical = if (pinned) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = { onClick() })
            Box {
            com.messenger.app.ui.components.UserAvatar(
                userId = chat.otherUser?.id.orEmpty(),
                avatarVersion = chat.otherUser?.avatarVersion ?: 0,
                hasAvatar = chat.otherUser?.hasAvatar == true,
                name = name, color = avatarTint.copy(alpha = 0.18f), contentColor = avatarTint, size = 48.dp,
            )
            if (activityKind != null) ChatTypingRing(colors.categoryMint, calm, durationScale, Modifier.matchParentSize())
            if (presenceState == ChatPresenceState.Online || presenceState == ChatPresenceState.Offline) {
                val description = stringResource(R.string.chat_peer_online)
                ChatPresenceDot(presenceState, calm, colors.success, colors.background, durationScale,
                    Modifier.align(Alignment.BottomEnd).semantics { if (presenceState == ChatPresenceState.Online) contentDescription = description })
            }
            }
            Spacer(modifier = Modifier.width(CocoSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ChatsPalette.primary,
                    fontWeight = if (chat.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(CocoSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (activityKind == null) {
                    val media = if (displayedCall != null) (if (displayedCall.mediaKind == "video") CocoIcons.VideoCall else CocoIcons.AudioCall) to (if (callOutcome == com.messenger.app.data.call.CallHistoryOutcome.MISSED) colors.danger else colors.secondaryText) else when (chat.lastPreview) {
                        LastMessagePreview.Video -> CocoIcons.Videocam to colors.categoryViolet
                        LastMessagePreview.Photo -> CocoIcons.Image to colors.categoryBlue
                        LastMessagePreview.Voice -> CocoIcons.Mic to colors.categoryBlue
                        is LastMessagePreview.File -> CocoIcons.File to colors.categoryMint
                        else -> null
                    }
                    media?.let { (icon, tint) -> Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp)) }
                }
                Text(
                    text = when (activityKind) { "typing" -> stringResource(R.string.chat_list_typing); "recording" -> stringResource(R.string.chat_list_recording); "uploading" -> stringResource(R.string.chat_list_sending_file); else -> callPreview ?: previewText(chat) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (activityKind != null) colors.categoryMint else ChatsPalette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (activityKind != null) ChatTypingWave(colors.categoryMint, calm, durationScale)
                }
            }
            Spacer(Modifier.width(CocoSpacing.sm))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(CocoSpacing.sm)) {
                timestamp?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = if (chat.unreadCount > 0) colors.primaryText else ChatsPalette.secondary)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pinned) Icon(CocoIcons.Pin, contentDescription = stringResource(R.string.chats_pinned), modifier = Modifier.size(18.dp), tint = colors.accent)
                if (chat.isMuted) Icon(CocoIcons.MutedBell, contentDescription = stringResource(R.string.chat_notifications_muted), modifier = Modifier.size(18.dp), tint = colors.secondaryText)
                ChatUnreadOdometer(chat.unreadCount, animateCount, calm, colors.accent, colors.onAccent)
            }
        }
        }
        if (!pinned && !selected) HorizontalDivider(Modifier.padding(start = if (selecting) 110.dp else 68.dp, end = 8.dp), thickness = .5.dp, color = ChatsPalette.border.copy(alpha = .6f))
        }
    }
}

private object ChatsPalette {
    val background:Color @Composable get() = LocalCocoColors.current.background
    val card:Color @Composable get() = LocalCocoColors.current.elevated
    val selected:Color @Composable get() = LocalCocoColors.current.surfaceVariant
    val border:Color @Composable get() = LocalCocoColors.current.divider
    val primary:Color @Composable get() = LocalCocoColors.current.primaryText
    val secondary:Color @Composable get() = LocalCocoColors.current.secondaryText
}

/** Second-line text for a chat row: the LOCAL last-message preview when the
 *  cache has one, otherwise the peer's @username (identity fallback). Never
 *  raw JSON / object keys / crypto metadata. */
@Composable
private fun previewText(chat: ChatSummary): String = when (val preview = chat.lastPreview) {
    null -> stringResource(if (chat.lastMessageAt == null) R.string.chat_no_messages else R.string.chat_message_unavailable)
    is LastMessagePreview.Text -> preview.text
    LastMessagePreview.Video -> stringResource(R.string.call_caption_video)
    LastMessagePreview.Photo -> stringResource(R.string.photo_preview)
    LastMessagePreview.Voice -> stringResource(R.string.voice_message_preview)
    is LastMessagePreview.File -> com.messenger.app.domain.model.FilePreviewText.file(stringResource(R.string.file_preview), preview.filename, preview.caption)
    LastMessagePreview.Deleted -> stringResource(R.string.deleted_message_preview)
}

@Composable
internal fun Avatar(name: String, color: androidx.compose.ui.graphics.Color, contentColor: androidx.compose.ui.graphics.Color) {
    Surface(shape = CircleShape, color = color, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun EmptyChatsState(onStartChat: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCocoColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(CocoSpacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CocoShapes.large, color = colors.surfaceVariant, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = CocoIcons.Logo, contentDescription = null, tint = colors.accent, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(modifier = Modifier.size(CocoSpacing.lg))
        Text(text = stringResource(R.string.chats_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.primaryText, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.size(CocoSpacing.sm))
        FilledTonalButton(onClick = onStartChat) { Text(stringResource(R.string.chats_empty_action)) }
    }
}
