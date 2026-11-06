package com.messenger.app.ui.chats

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import com.messenger.app.ui.theme.glassPanel
import com.messenger.app.ui.theme.glassSource
import com.messenger.app.ui.theme.rememberGlassBackdrop
import com.messenger.app.ui.theme.LocalGlassBackdrop
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import com.messenger.app.ui.theme.cocoConversationBackground
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.boundsInWindow
import com.messenger.app.ui.components.messageSelectionBodyCenter
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.systemBars
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import com.messenger.app.ActiveChatTracker
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.R
import com.messenger.app.data.attachment.AttachmentFormat
import com.messenger.app.data.attachment.VoiceFormat
import com.messenger.app.data.attachment.VoiceRecorder
import com.messenger.app.data.call.CallAudioPolicy
import com.messenger.app.domain.model.Attachment
import com.messenger.app.domain.model.AttachmentDownloadState
import com.messenger.app.domain.model.AttachmentMediaType
import com.messenger.app.domain.model.AttachmentUploadState
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.CocoTextRoles
import com.messenger.app.ui.theme.LocalCocoColors
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
private fun VoiceDraftTray(viewModel: ChatDetailViewModel, item: org.json.JSONObject, sending: Boolean, onDiscard: () -> Unit, onSend: () -> Unit) {

    val hapticView = androidx.compose.ui.platform.LocalView.current
    val playback by viewModel.voicePlaybackState.collectAsState()
    val id = item.getString("id")
    val active = playback.playingAttachmentId == id || playback.pausedAttachmentId == id
    val duration = item.getLong("duration")
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(18.dp)).background(CapsuleChatStyle.entry).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            CapsuleComposerButton(CocoIcons.Delete, stringResource(R.string.cancel), enabled = !sending, onClick = { CocoHaptics.tap(hapticView); onDiscard() })
            CapsuleComposerButton(if (playback.playingAttachmentId == id) CocoIcons.Pause else CocoIcons.Play,
                stringResource(if (playback.playingAttachmentId == id) R.string.pause else R.string.play),
                enabled = !sending, onClick = { CocoHaptics.tap(hapticView); viewModel.toggleVoicePlayback(id, File(item.getString("path"))) })
            VoiceWaveform(item.getString("path"), if (active) VoiceFormat.playedFraction(playback.positionMs.toLong(), duration) else 0f, CapsuleChatStyle.accent, CapsuleChatStyle.waveQuiet,
                Modifier.weight(1f).height(18.dp).pointerInput(active) { if (active) detectHorizontalDragGestures { change, _ -> change.consume(); viewModel.seekVoice(change.position.x / size.width.coerceAtLeast(1)) } })
            TextButton(onClick = { CocoHaptics.select(hapticView); viewModel.cycleVoiceSpeed() }, enabled = active && !sending,
                contentPadding = PaddingValues(0.dp), modifier = Modifier.width(40.dp).height(40.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(VoiceFormat.durationText(if (active) playback.positionMs.toLong() else duration), color = CapsuleChatStyle.muted, fontSize = 10.sp)
                    if (active) Text("${playback.speed}×", color = CapsuleChatStyle.accent, fontSize = 10.sp)
                }
            }
        }
        CapsuleComposerButton(ChatControls.send, stringResource(R.string.send), filled = true, enabled = !sending, onClick = { CocoHaptics.tap(hapticView); onSend() })
    }
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
private val QUICK_REACTIONS = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDD25", "＋")

/** Deletes abandoned camera-capture temp files older than one hour - they
 *  are app-owned FileProvider sources, never user photos (Bug A §4). */
private fun cleanupStaleCameraFiles(dir: File) {
    val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
    dir.listFiles()?.forEach { file ->
        if (file.isFile && file.lastModified() < cutoff) file.delete()
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ChatScreen(viewModel: ChatDetailViewModel, onBack: () -> Unit, onStartCall: (String, String, com.messenger.app.domain.model.CallMediaKind) -> Unit = { _, _, _ -> }, onVerifyIdentity: (String) -> Unit = {}, onSearchChat: (String) -> Unit = {}, onOpenMedia: () -> Unit = {}, targetMessageId: String? = null, readReceiptsAllowed: Boolean = true, scrollBookmarks: MutableMap<String, ChatScrollBookmark>? = null) {
    // A single scope includes Dialog/Popup branches emitted outside the main
    // timeline Surface. No legacy purple palette can leak into chat menus.
    val appPalette = LocalCocoColors.current
    val palette = remember(appPalette) { capsuleChatPalette(appPalette) }
    val parentColorScheme = MaterialTheme.colorScheme
    val chatColorScheme = remember(parentColorScheme, palette) {
        parentColorScheme.copy(primary = palette.accent, onPrimary = palette.onAccent,
            background = palette.background, onBackground = palette.primaryText,
            surface = palette.surface, onSurface = palette.primaryText,
            surfaceVariant = palette.surfaceVariant, onSurfaceVariant = palette.secondaryText,
            outline = palette.divider)
    }
    CompositionLocalProvider(LocalCocoColors provides palette) {
        MaterialTheme(colorScheme = chatColorScheme) {
            CapsuleChatContent(viewModel, onBack, onStartCall, onVerifyIdentity, onSearchChat,
                onOpenMedia, targetMessageId, readReceiptsAllowed, scrollBookmarks)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
private fun CapsuleChatContent(viewModel: ChatDetailViewModel, onBack: () -> Unit, onStartCall: (String, String, com.messenger.app.domain.model.CallMediaKind) -> Unit = { _, _, _ -> }, onVerifyIdentity: (String) -> Unit = {}, onSearchChat: (String) -> Unit = {}, onOpenMedia: () -> Unit = {}, targetMessageId: String? = null, readReceiptsAllowed: Boolean = true, scrollBookmarks: MutableMap<String, ChatScrollBookmark>? = null) {
    val appearance = com.messenger.app.ui.theme.LocalChatAppearanceStyle.current
    val uiState by viewModel.uiState.collectAsState()
    val socketState by viewModel.socketState.collectAsState()
    LaunchedEffect(socketState) {
        if (socketState == com.messenger.app.domain.repository.SocketConnectionState.CONNECTED) {
            if (viewModel.actionError.value == ChatError.NETWORK) viewModel.clearActionError()
            if (viewModel.attachmentError.value == ChatError.NETWORK) viewModel.clearAttachmentError()
        }
    }
    // Progress belongs to its visible card, as with attachment row progress below.
    val messageStructure = remember(viewModel) { viewModel.messages.structuralDirectFileMessages() }
    val initialMessageStructure = remember(viewModel) { directFileMessageStructure(viewModel.messages.value) }
    val messageSnapshot by messageStructure.collectAsState(initial = initialMessageStructure)
    val historyContext by viewModel.historyContext.collectAsState()
    val historyCutoff = historyContext?.beforeSequence ?: viewModel.historyBeforeSequence
    var showDecryptionFailures by remember(viewModel.chatId) { mutableStateOf(false) }
    val messages = remember(messageSnapshot, historyCutoff, showDecryptionFailures) {
        com.messenger.app.domain.model.collapseUnavailableHistory(messageSnapshot.orEmpty(), historyCutoff, collapseRecentFailures = !showDecryptionFailures)
    }
    // Transfer percentages belong to the visible attachment row, not the screen.
    val attachmentStructure = remember(viewModel) {
        viewModel.attachments.map { snapshot ->
            snapshot?.map { if (it.progress == 0) it else it.copy(progress = 0) }
        }.distinctUntilChanged()
    }
    val initialAttachments = remember(viewModel) { viewModel.attachments.value }
    val attachmentSnapshot by attachmentStructure.collectAsState(initial = initialAttachments)
    val attachments = attachmentSnapshot.orEmpty()
    val quickReaction = com.messenger.app.ui.settings.rememberQuickReaction()
    val autoDownloadContext = LocalContext.current
    // Wake on actual network and preference changes, never on a frame/timer.
    val autoDownloads = remember(autoDownloadContext) { com.messenger.app.data.attachment.MediaDownloadPreferences(autoDownloadContext) }
    var autoDownloadRevision by remember { mutableStateOf(0) }
    DisposableEffect(autoDownloadContext) {
        val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> autoDownloadRevision++ }
        autoDownloads.preferences.registerOnSharedPreferenceChangeListener(prefsListener)
        val manager = autoDownloadContext.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { android.os.Handler(android.os.Looper.getMainLooper()).post { autoDownloadRevision++ } }
            override fun onLost(network: android.net.Network) { android.os.Handler(android.os.Looper.getMainLooper()).post { autoDownloadRevision++ } }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { autoDownloads.preferences.unregisterOnSharedPreferenceChangeListener(prefsListener); runCatching { manager.unregisterNetworkCallback(callback) } }
    }
    LaunchedEffect(attachments, autoDownloadRevision) {
        attachments.filter {
            it.downloadState == AttachmentDownloadState.NOT_DOWNLOADED &&
                autoDownloads.allows(if (it.mediaType == AttachmentMediaType.VOICE) "voice" else if (it.mediaType == AttachmentMediaType.IMAGE) "image" else "file", it.mimeType, it.size)
        }.take(3).forEach { viewModel.autoDownloadAttachment(it) }
    }
    val attachmentError by viewModel.attachmentError.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val failedActions by viewModel.failedActions.collectAsState()
    val replyTarget by viewModel.replyTarget.collectAsState()
    val editTarget by viewModel.editTarget.collectAsState()
    val forwardableChats by viewModel.forwardableChats.collectAsState()
    val playbackIdentity = remember(viewModel) {
        viewModel.voicePlaybackState.map { it.copy(positionMs = 0) }.distinctUntilChanged()
    }
    val initialPlayback = remember(viewModel) { viewModel.voicePlaybackState.value.copy(positionMs = 0) }
    val voicePlayback by playbackIdentity.collectAsState(initial = initialPlayback)
    val scheduledDates by viewModel.scheduledDates.collectAsState()
    var showChatSafety by remember { mutableStateOf(false) }
    var showPeerCard by remember { mutableStateOf(false) }
    var chatSafetyAction by remember { mutableStateOf<String?>(null) }
    var showSchedule by remember { mutableStateOf(false) }
    val currentUserId = viewModel.currentUserId
    if (showChatSafety) (uiState as? ChatDetailUiState.Loaded)?.chat?.let { current ->
        ChatSafetyDialog(chat = current, initialAction = chatSafetyAction, onDismiss = { showChatSafety = false; chatSafetyAction = null },
            update = { viewModel.updateChatPreference(it, current.otherUser?.id) },
            report = { reason, detail -> viewModel.submitReport(reason, detail) })
    }
    if (showPeerCard) (uiState as? ChatDetailUiState.Loaded)?.chat?.otherUser?.let { peer ->
        AlertDialog(onDismissRequest = { showPeerCard = false },
            title = { Text(peer.displayName.ifBlank { peer.username }) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("@${peer.username}")
                peer.bio?.takeIf { it.isNotBlank() }?.let { Text(it) }
            } },
            confirmButton = { TextButton(onClick = { showPeerCard = false }) { Text(stringResource(R.string.dismiss)) } })
    }
    val colors = LocalCocoColors.current
    var headerHeight by remember { mutableStateOf(0) }
    var composerHeight by remember { mutableStateOf(0) }
    var normalComposerHeight by remember { mutableStateOf(0) }
    var composerFocused by remember { mutableStateOf(false) }
    var submittingText by remember { mutableStateOf(false) }
    var input by rememberSaveable(viewModel.chatId, currentUserId) { mutableStateOf("") }
    var composerRestored by remember(viewModel.chatId) { mutableStateOf(false) }
    var ordinaryDraft by rememberSaveable(viewModel.chatId, currentUserId) { mutableStateOf("") }
    LaunchedEffect(viewModel.chatId) {
        ordinaryDraft = viewModel.chatValue("draft").orEmpty()
        if (input.isEmpty()) input = ordinaryDraft
        else { ordinaryDraft = input; viewModel.saveChatValue("draft", input) }
        composerRestored = true
    }
    val context = LocalContext.current
    val activityPreferences = remember(context) { context.getSharedPreferences("chat-privacy", Context.MODE_PRIVATE) }
    var activityEnabled by remember { mutableStateOf(activityPreferences.getBoolean("activity", false)) }
    DisposableEffect(activityPreferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key -> if (key == "activity") activityEnabled = prefs.getBoolean("activity", false) }
        activityPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { activityPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val peerActivity by viewModel.peerActivity.collectAsState()
    val currentPeerActivity = peerActivity[viewModel.chatId]
    var activityLabel by remember(viewModel.chatId) { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel.chatId, currentPeerActivity) {
        val remaining = currentPeerActivity?.let { 8000L - (android.os.SystemClock.elapsedRealtime() - it.second) } ?: 0L
        activityLabel = currentPeerActivity?.first?.takeIf { remaining > 0 }
        if (remaining > 0) {
            delay(remaining)
            activityLabel = null
        }
    }
    val callHistory = remember(context) { (context.applicationContext as com.messenger.app.MessengerApp).callHistory }
    val allCalls by callHistory.calls.collectAsState()
    val peerId = (uiState as? ChatDetailUiState.Loaded)?.chat?.otherUser?.id
    val liveDrafts = remember(context) { (context.applicationContext as com.messenger.app.MessengerApp).liveDrafts }
    val peerDraft by liveDrafts.peerText.collectAsState()
    val latestDraft by rememberUpdatedState(if (editTarget == null && composerFocused) input else "")
    val chatCalls = remember(allCalls, peerId, viewModel.chatId) {
        allCalls.filter { call -> com.messenger.app.data.call.CallActivityPolicy.belongsToChat(call, viewModel.chatId, peerId) }
    }
    val timelineCache = remember(viewModel) { com.messenger.app.ui.calls.ChatTimelineCache() }
    val timeline = remember(messages, chatCalls, timelineCache) { timelineCache.update(messages, chatCalls) }
    val initialDataReady = messageSnapshot != null && attachmentSnapshot != null && uiState is ChatDetailUiState.Loaded
    val initialPosition = remember(viewModel.chatId, initialDataReady) {
        val target = targetMessageId?.let { id -> timeline.indexOfFirst { it.messages.any { message -> message.clientMessageId == id } } }
        if (target != null && target >= 0) target to 0
        else scrollBookmarks?.get(viewModel.chatId)?.resolve(timeline.map { it.key })
            ?: ((timeline.size - 1).coerceAtLeast(0) to 0)
    }
    val listState = androidx.compose.runtime.key(viewModel.chatId, initialDataReady) {
        rememberLazyListState(initialFirstVisibleItemIndex = initialPosition.first,
            initialFirstVisibleItemScrollOffset = initialPosition.second)
    }
    val currentTimeline by rememberUpdatedState(timeline)
    var olderCalls by remember(peerId) { mutableStateOf<String?>(null) }
    var historyLoaded by remember(peerId) { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var callActionsFor by remember { mutableStateOf<com.messenger.app.data.api.CallDto?>(null) }
    var actionsBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var actionsPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val menuCaptureView = androidx.compose.ui.platform.LocalView.current
    val menuViewport = remember { arrayOf(androidx.compose.ui.geometry.Rect.Zero) }
    var menuCaptureJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(actionsFor, callActionsFor) { if (actionsFor == null && callActionsFor == null) actionsPreview = null }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var pendingBulkDelete by remember { mutableStateOf<Pair<Boolean, List<String>>?>(null) }
    androidx.activity.compose.BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    var pins by remember { mutableStateOf(setOf<String>()) }
    var showPins by remember { mutableStateOf(false) }
    LaunchedEffect(readReceiptsAllowed) { if (!readReceiptsAllowed) showPins = false }
    var reminders by remember { mutableStateOf(org.json.JSONObject()) }
    var reminderFor by remember { mutableStateOf<Message?>(null) }
    var showReminders by remember { mutableStateOf(false) }
    var reminderPermissionMissing by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.chatId) { reminders = viewModel.chatValue("reminders")?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject() }
    fun scheduleReminder(item: Message, at: Long) {
        if (com.messenger.app.push.ChatReminderReceiver.schedule(context, currentUserId.orEmpty(), viewModel.chatId, item.clientMessageId, at)) {
            reminders = org.json.JSONObject(reminders.toString()).put(item.clientMessageId, at)
            viewModel.saveChatValue("reminders", reminders.toString()); reminderFor = null
        } else { reminderPermissionMissing = true }
    }
    var showBulkForward by remember { mutableStateOf(false) }
    var quoteJump by remember { mutableStateOf<String?>(null) }
    var returnPosition by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel.chatId) { pins = viewModel.chatValue("pins").orEmpty().split("|").filter { it.isNotEmpty() }.toSet() }
    fun togglePin(message: Message) {
        pins = if (message.clientMessageId in pins) pins - message.clientMessageId else pins + message.clientMessageId
        viewModel.saveChatValue("pins", pins.joinToString("|"))
    }
    LaunchedEffect(quoteJump, timeline) {
        val id = quoteJump ?: return@LaunchedEffect
        val index = timeline.indexOfFirst { it.messages.any { message -> message.clientMessageId == id } }
        if (index >= 0) { listState.animateScrollToItem(index, scrollOffset = -headerHeight); quoteJump = null }
    }
    var forwardDialogFor by remember { mutableStateOf<Message?>(null) }
    var forwardPrepareFailed by remember { mutableStateOf(false) }
    LaunchedEffect(showBulkForward, forwardDialogFor) {
        if (showBulkForward || forwardDialogFor != null) {
            forwardPrepareFailed = false
            if (!viewModel.ensureSavedChatForForwarding()) forwardPrepareFailed = true
        }
    }
    var viewedImageFile by remember { mutableStateOf<File?>(null) }
    var viewedMediaMime by remember { mutableStateOf("image/jpeg") }
    var pendingPhotoUris by rememberSaveable(viewModel.chatId, currentUserId) { mutableStateOf<List<Uri>>(emptyList()) }
    var mediaCaption by rememberSaveable(viewModel.chatId) { mutableStateOf("") }
    var pendingFileUris by rememberSaveable(viewModel.chatId, currentUserId) { mutableStateOf<List<Uri>>(emptyList()) }
    var sendingComposerMedia by remember(viewModel.chatId, currentUserId) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val mediaPreparation = (context.applicationContext as com.messenger.app.MessengerApp).mediaPreparation
    val verificationPeer = (uiState as? ChatDetailUiState.Loaded)?.chat?.otherUser?.id
    val verificationFlow = remember(verificationPeer) {
        if (verificationPeer == null) kotlinx.coroutines.flow.flowOf(emptyList<com.messenger.app.data.local.signal.PeerIdentityEntity>())
        else (context.applicationContext as com.messenger.app.MessengerApp).identityVerification.observeForUser(verificationPeer)
    }
    val verificationDevices by verificationFlow.collectAsState(initial = emptyList())
    val ownVerificationFlow = remember(currentUserId) {
        if (currentUserId == null) kotlinx.coroutines.flow.flowOf(emptyList<com.messenger.app.data.local.signal.PeerIdentityEntity>())
        else (context.applicationContext as com.messenger.app.MessengerApp).identityVerification.observeForUser(currentUserId)
    }
    val ownVerificationDevices by ownVerificationFlow.collectAsState(initial = emptyList())
    val ownIdentityNeedsReview = ownVerificationDevices.any { it.status == com.messenger.app.data.signal.IDENTITY_CHANGED }
    val identityNeedsReview = verificationDevices.any { it.status == com.messenger.app.data.signal.IDENTITY_CHANGED }
    val preparations by mediaPreparation.state.collectAsState()
    val preparation = preparations[viewModel.chatId] ?: com.messenger.app.data.attachment.MediaPreparationCoordinator.State()
    var interruptedImport by remember(viewModel.chatId) { mutableStateOf(false) }


    // N.2R §104/138: let the notification policy know which chat is on
    // screen so a message to THIS chat never fires a local/FCM notification
    // (including right after the user backgrounds away from it).
    val trackingOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel.chatId, trackingOwner, readReceiptsAllowed) {
        fun updateVisible() {
            if (readReceiptsAllowed && trackingOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                ActiveChatTracker.onChatOpened(viewModel.chatId)
                androidx.core.app.NotificationManagerCompat.from(context).cancel("chat:${viewModel.chatId}", 0)
            } else ActiveChatTracker.onChatClosed(viewModel.chatId)
        }
        val observer = LifecycleEventObserver { _, _ -> updateVisible() }
        trackingOwner.lifecycle.addObserver(observer)
        updateVisible()
        onDispose { trackingOwner.lifecycle.removeObserver(observer); ActiveChatTracker.onChatClosed(viewModel.chatId) }
    }

    LaunchedEffect(editTarget) {
        editTarget?.let { input = it.text }
    }

    val attachmentsByMessage = remember(attachments) { attachments.associateBy { it.clientMessageId } }
    // Only already-loaded, locally decrypted targets can supply a quote thumbnail.
    // No lookup/download is started by a row being measured or scrolled.
    val replyAttachmentsByServerId = remember(messages, attachmentsByMessage) {
        buildMap {
            for (original in messages) if (!original.isDeletedForEveryone && original.serverId != null &&
                original.status != MessageStatus.DECRYPT_FAILED && original.status != MessageStatus.IDENTITY_CHANGED) {
                (attachmentsByMessage[original.clientMessageId] ?: original.queuedAttachment)?.let { put(original.serverId, it) }
            }
        }
    }

    var pendingCall by remember { mutableStateOf<Triple<String, String, com.messenger.app.domain.model.CallMediaKind>?>(null) }
    var callPermissionError by remember { mutableStateOf<Int?>(null) }
    fun deleteCallRecord(id: String, everyone: Boolean) {
        coroutineScope.launch {
            try {
                callHistory.deleteHistory(id, everyone)
                selectedIds = selectedIds - "call:$id"
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { callPermissionError = R.string.call_history_delete_failed }
        }
    }
    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Этап J п.46: RECORD_AUDIO denied (including permanently, "don't
        // ask again") must NOT start a doomed call - show a clear message
        // instead of proceeding into a call whose foreground service would
        // otherwise crash the app trying to start with type=microphone
        // without the permission actually granted.
        val denied = result.entries.filter { !it.value }.map { it.key }.toSet()
        val kind = pendingCall?.third ?: com.messenger.app.domain.model.CallMediaKind.AUDIO
        // Этап J.1-7: an optional BLUETOOTH_CONNECT denial never blocks the
        // call - only required (mic/camera) denials do.
        val blocking = CallAudioPolicy.blockingDenials(kind, denied)
        if (blocking.isEmpty()) {
            pendingCall?.let { (id, name, k) -> onStartCall(id, name, k) }
        } else {
            // Этап J.1-4 п.14: CAMERA denial must say "camera", not microphone.
            callPermissionError = com.messenger.app.call.CallPermissionMessages.forDeniedCall(kind, blocking)
        }
        pendingCall = null
    }
    fun requestCall(otherId: String, otherName: String, kind: com.messenger.app.domain.model.CallMediaKind) {
        if ((uiState as? ChatDetailUiState.Loaded)?.chat?.isBlocked == true) { showChatSafety = true; return }
        val required = CallAudioPolicy.requiredCallPermissions(kind)
        val alreadyGranted = required.all {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (alreadyGranted) {
            onStartCall(otherId, otherName, kind)
        } else {
            pendingCall = Triple(otherId, otherName, kind)
            callPermissionLauncher.launch(CallAudioPolicy.allCallPermissions(kind, Build.VERSION.SDK_INT).toTypedArray())
        }
    }

    LaunchedEffect(viewModel.chatId, preparation.revision) {
        if (!preparation.active && viewModel.chatValue("mediaImportPending") == "1") {
            interruptedImport = true
            viewModel.saveChatValueNow("mediaImportPending", null)
        }
        if (!preparation.active) {
            val entries=runCatching { org.json.JSONArray(viewModel.chatValue("fileDrafts")?:"[]") }.getOrDefault(org.json.JSONArray())
            val files=(0 until entries.length()).map { Uri.parse(entries.getString(it)) }.toMutableList()
            viewModel.chatValue("fileDraftUri")?.let {raw->val uri=Uri.parse(raw);if(uri !in files)files+=uri}
            pendingFileUris=files.filter {it.path?.let {path->File(path).isFile}==true}
        }
        if (mediaCaption.isEmpty()) mediaCaption = viewModel.chatValue("mediaCaption").orEmpty()
        val stored = viewModel.chatValue("mediaDrafts")
        runCatching { org.json.JSONArray(stored ?: "[]") }.getOrNull()?.let { entries ->
            pendingPhotoUris = (0 until entries.length()).map { Uri.parse(entries.getString(it)) }.filter { it.path?.let { path -> File(path).isFile } == true }
        }
    }
    fun saveMediaDrafts(uris: List<Uri>) {
        viewModel.saveChatValue("mediaDrafts", org.json.JSONArray(uris.map { it.toString() }).toString())
        if (uris.isEmpty() && pendingFileUris.isEmpty()) { viewModel.saveChatValue("mediaAlbum", null); mediaCaption = ""; viewModel.saveChatValue("mediaCaption", null) }
    }
    suspend fun saveFileDrafts(uris:List<Uri>) {
        viewModel.saveChatValueNow("fileDrafts",org.json.JSONArray(uris.map {it.toString()}).toString())
        viewModel.saveChatValueNow("fileDraftUri",null)
        if(uris.isEmpty()&&pendingPhotoUris.isEmpty()) viewModel.saveChatValueNow("mediaAlbum",null)
    }
    val galleryQualityPreferences = remember(context) { context.getSharedPreferences("media-quality", Context.MODE_PRIVATE) }
    fun preferredGalleryQuality() = galleryPreferredSendQuality(
        galleryQualityPreferences.getString(GALLERY_SEND_MODE_PREFERENCE, null), galleryQualityPreferences.getString("send", "image"))
    var galleryOriginals by rememberSaveable(viewModel.chatId) { mutableStateOf(preferredGalleryQuality() == "file") }
    val preparePickedOriginals = rememberDirectOriginalSelection(viewModel.chatId, viewModel.sessionEpoch,
        destination = (uiState as? ChatDetailUiState.Loaded)?.chat, accountUserId = currentUserId,
        captionRetained = input.isNotBlank(), onDirectCommitted = { committed ->
            val ownedDrafts = committed.filter { it in pendingPhotoUris || it in pendingFileUris }
            if (ownedDrafts.isNotEmpty()) {
                pendingPhotoUris = pendingPhotoUris.filterNot { it in ownedDrafts }; saveMediaDrafts(pendingPhotoUris)
                pendingFileUris = pendingFileUris.filterNot { it in ownedDrafts }
                coroutineScope.launch { saveFileDrafts(pendingFileUris); viewModel.discardStagedMedia(ownedDrafts) }
            }
        }) { uris, asFile, sourceLimit, cleanup ->
        mediaPreparation.start(viewModel.chatId, uris.map { it.toString() }, asFile = asFile, expectedEpoch = viewModel.sessionEpoch, sourceLimitBytes = sourceLimit, cleanup = cleanup)
    }
    LaunchedEffect(pendingPhotoUris.isEmpty()) { if (pendingPhotoUris.isEmpty()) galleryOriginals = preferredGalleryQuality() == "file" }
    // Keep the selected list in UI state first, so the user can inspect and
    // remove photos before any encrypted upload starts.
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val room = 10 - pendingPhotoUris.size - pendingFileUris.size
            if (uris.size > room) {
                android.widget.Toast.makeText(context, R.string.gallery_limit, android.widget.Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            interruptedImport = false
            preparePickedOriginals(uris, galleryOriginals, false, {})
        }
    }
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if(uris.isNotEmpty()) {
            if(uris.size+pendingPhotoUris.size+pendingFileUris.size>10) {
                android.widget.Toast.makeText(context,R.string.gallery_limit,android.widget.Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            interruptedImport = false
            preparePickedOriginals(uris, true, true, {})
        }
    }

    // Bug A: "Camera" opens the real camera capture flow into an app-owned
    // FileProvider temp Uri, then feeds the SAME photo pipeline as gallery.
    // Code-review 4/5: pending Uri+File survive Activity recreation and the
    // temp file is deleted right after ingest (not only by the hourly sweep).
    var attachmentSheetOpen by remember { mutableStateOf(false) }
    var gallerySelection by rememberSaveable { mutableStateOf<List<Uri>>(emptyList()) }
    var galleryCaptureFiles by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var cameraSendDraft by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var sendingCamera by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { cameraSendDraft = viewModel.cameraRecoveryDraft() }
    fun sendCamera(uri: Uri, source: File?) {
        if (sendingCamera) return
        sendingCamera = true
        coroutineScope.launch {
            try { viewModel.sendCameraCapture(uri, source)
                cameraSendDraft = viewModel.cameraRecoveryDraft()
            } finally { sendingCamera = false }
        }
    }
    val cameraCapture = com.messenger.app.capture.rememberOwnedDualCameraCapture(destination = viewModel.chatId, sessionEpoch = viewModel.sessionEpoch,
        canRequest = { !sendingCamera && cameraSendDraft == null },
        onUnavailable = { callPermissionError = R.string.capture_in_use },
        onPermissionDenied = viewModel::reportCameraDenied,
        onResultUnavailable = { callPermissionError = R.string.error_attachment_send },
        resultWasConsumed = viewModel::cameraLaunchWasConsumed,
        beforeLaunch = { uri, file, nonce -> viewModel.registerDualCameraLaunch(uri, file, nonce) },
        onResult = { result ->
            sendingCamera = true
            try {
                viewModel.completeDualCameraLaunch(result)
            } finally {
                sendingCamera = false
                cameraSendDraft = runCatching { viewModel.cameraRecoveryDraft() }.getOrNull()
            }
        })
    fun requestCamera() = cameraCapture.request()

    // All three sources keep the same capacity, focus and session-owned import
    // path. Selecting an entry, rather than opening the menu, dismisses the IME.
    fun openComposerAttachment(action: () -> Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (!preparation.active && !sendingComposerMedia && pendingPhotoUris.size + pendingFileUris.size < 10) {
            CocoHaptics.tap(menuCaptureView)
            action()
        }
    }

    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0L) }
    var recordingPaused by remember { mutableStateOf(false) }
    var recordingLocked by remember { mutableStateOf(true) }
    var recordingDrag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var voiceDraft by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var resumeVoiceBase by remember { mutableStateOf<org.json.JSONObject?>(null) }
    val latestVoiceBase by rememberUpdatedState(resumeVoiceBase)
    var sendingVoice by remember { mutableStateOf(false) }
    val voiceSendPreviews = remember(viewModel) { androidx.compose.runtime.mutableStateMapOf<String, Attachment>() }
    LaunchedEffect(attachmentsByMessage) {
        voiceSendPreviews.keys.toList().filter { attachmentsByMessage.containsKey(it) }.forEach { voiceSendPreviews.remove(it) }
    }
    var stagingVoice by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.chatId) {
        voiceDraft = viewModel.chatValue("voiceDraft")?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    }
    fun stageVoice(file: File, duration: Long) {
        val base = resumeVoiceBase
        stagingVoice = true
        coroutineScope.launch(kotlinx.coroutines.NonCancellable) {
            try {
                voiceDraft = viewModel.stageVoiceDraft(file, duration, base)
                resumeVoiceBase = null
            } catch (_: Exception) { callPermissionError = R.string.error_attachment_send }
            finally { stagingVoice = false }
        }
    }
    val latestStageVoice by rememberUpdatedState<(File, Long) -> Unit>(::stageVoice)
    val voiceRecorder = remember { VoiceRecorder(context) }
    val captureApp = context.applicationContext as com.messenger.app.MessengerApp
    val captureCoordinator = captureApp.nonCallCaptures
    val voiceSession = remember(viewModel) { captureApp.captureSession() }
    var voiceLease by remember { mutableStateOf<com.messenger.app.capture.NonCallCaptureCoordinator.Lease?>(null) }
    var voicePermissionLease by remember { mutableStateOf<String?>(null) }
    var voiceStarting by remember { mutableStateOf(false) }
    fun stopVoiceCapture(cancel: Boolean = false): Pair<File, Long>? {
        val ticket = voiceLease
        var stopped = false
        return try {
            val result = if (cancel) { voiceRecorder.cancel(); null } else voiceRecorder.stop()
            stopped = true
            result
        } catch (_: Exception) { callPermissionError = R.string.error_attachment_send; null }
        finally {
            voiceStarting = false; voicePermissionLease = null
            if (stopped) {
                ticket?.let(captureCoordinator::release)
                if (voiceLease === ticket) voiceLease = null
            }
        }
    }
    fun reserveVoice(): com.messenger.app.capture.NonCallCaptureCoordinator.Lease? {
        val ticket = captureCoordinator.acquire(com.messenger.app.capture.NonCallCaptureKind.VOICE, voiceSession) {
            isRecording = false
            stopVoiceCapture()?.let { latestStageVoice(it.first, it.second) }
        }
        if (ticket == null) callPermissionError = R.string.capture_in_use else voiceLease = ticket
        return ticket
    }
    val voiceLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(voiceLifecycleOwner, voiceRecorder) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (isRecording || voiceStarting)) {
                isRecording = false
                stopVoiceCapture()?.let { (file, duration) ->
                    isRecording = false
                    latestStageVoice(file, duration)
                }
            }
        }
        voiceLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { voiceLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val activeCallState by captureApp.callController.state.collectAsState()
    val conferenceCaptureReserved by captureApp.conferenceController.captureReservation.collectAsState()
    fun startVoiceRecording(resume: Boolean = false, reserved: com.messenger.app.capture.NonCallCaptureCoordinator.Lease? = null) {
        if (isRecording || sendingVoice || voiceStarting) {
            reserved?.let(captureCoordinator::release)
            if (voiceLease === reserved) voiceLease = null
            return
        }
        if ((!resume && voiceDraft != null) || stagingVoice) {
            reserved?.let(captureCoordinator::release)
            if (voiceLease === reserved) voiceLease = null
            callPermissionError = R.string.chat_voice_draft_exists; return
        }
        val ticket = reserved ?: reserveVoice() ?: return
        voiceStarting = true
        coroutineScope.launch {
            var started = false
            try {
                if (!captureCoordinator.awaitReady(ticket) || voiceSession != captureApp.captureSession()) return@launch
                focusManager.clearFocus(force = true); keyboardController?.hide()
                viewModel.stopVoice()
                voiceRecorder.start()
                started = true
                CocoHaptics.hold(menuCaptureView)
                if (resume) { resumeVoiceBase = voiceDraft; viewModel.stopVoice() }
                recordingSeconds = 0; recordingPaused = false; isRecording = true
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
              catch (_: Exception) { callPermissionError = R.string.error_attachment_send; isRecording = false }
            finally {
                voiceStarting = false
                if (!started) { captureCoordinator.release(ticket); if (voiceLease === ticket) voiceLease = null }
            }
        }
    }
    LaunchedEffect(activeCallState, conferenceCaptureReserved) {
        if (conferenceCaptureReserved || !captureApp.callController.canBeginNonCallCapture()) {
            voiceLease?.let(captureCoordinator::interrupt)
        }
    }
    // Release the microphone and delete any unfinished temp file when this
    // chat leaves composition (navigate away / account switch). Idempotent:
    // stop() already nulls the recorder + output file, so a finalized/sent
    // recording is never touched; a no-op when nothing is recording.
    DisposableEffect(voiceRecorder) {
        onDispose { stopVoiceCapture()?.let { (file, duration) -> viewModel.preserveInterruptedVoice(file, duration, latestVoiceBase) } }
    }
    // 1 Hz recording timer (Phase 5 §14): one tick per second, never a chat-
    // wide recomposition storm.
    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000)
            recordingSeconds = voiceRecorder.recordedDurationMs / 1000
            if (!voiceRecorder.isPaused) { if (activityEnabled) viewModel.sendActivity("recording") }
        }
    }
    val recordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ticket = voiceLease?.takeIf { it.id == voicePermissionLease }
        voicePermissionLease = null
        if (granted && ticket != null && captureCoordinator.valid(ticket) && voiceSession == captureApp.captureSession()) {
            recordingLocked = true
            startVoiceRecording(reserved = ticket)
        } else if (ticket != null) stopVoiceCapture()
    }
    fun requestVoicePermission() {
        if (voiceLease != null || voiceStarting || isRecording) return
        val ticket = reserveVoice() ?: return
        voicePermissionLease = ticket.id
        try { recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        catch (_: Exception) { stopVoiceCapture(); callPermissionError = R.string.error_attachment_send }
    }

    val lastMessageId = timeline.lastOrNull()?.messages?.lastOrNull()?.clientMessageId
    var hasScrolledOnce by remember(viewModel.chatId) { mutableStateOf(false) }
    val tailTracker = remember(viewModel.chatId, initialDataReady) {
        ChatTailTracker(lastMessageId, timeline.lastOrNull()?.key,
            messages.mapTo(mutableSetOf()) { it.clientMessageId }.apply { timeline.forEach { add(it.key) } })
    }
    LaunchedEffect(viewModel.chatId, listState, initialDataReady, hasScrolledOnce) {
        if (!initialDataReady || !hasScrolledOnce) return@LaunchedEffect
        // Read layout while it exists, not during onDispose: LazyColumn may
        // already have reset canScrollForward when the screen is removed.
        snapshotFlow {
            if (listState.layoutInfo.visibleItemsInfo.isEmpty()) null
            else currentTimeline.getOrNull(listState.firstVisibleItemIndex)?.key?.let { key ->
                ChatScrollBookmark(key, listState.firstVisibleItemScrollOffset, !listState.canScrollForward)
            }
        }.collect { bookmark ->
            if (bookmark != null) {
                scrollBookmarks?.set(viewModel.chatId, bookmark)
                if ((scrollBookmarks?.size ?: 0) > 100) scrollBookmarks?.keys?.firstOrNull()?.let { scrollBookmarks?.remove(it) }
            }
        }
    }
    // Number of NEW incoming messages that arrived while the user was scrolled
    // up reading older messages (drives the "New messages" pill).
    var newMessagesPending by remember(viewModel.chatId) { mutableStateOf(0) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var jumpedToTarget by remember(viewModel.chatId) { mutableStateOf<String?>(null) }

    // Code-review fix 1: gate read receipts on the Activity actually being
    // resumed - a composed ChatScreen must NOT mark messages Read while the
    // app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> {
                    isResumed = false
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val realtimePresence = (context.applicationContext as com.messenger.app.MessengerApp).presence
    val presenceValues by realtimePresence.peers.collectAsState()
    val presenceHistory by realtimePresence.lastSeen.collectAsState()
    val presenceOwner = remember(viewModel) { "chat:" + java.util.UUID.randomUUID() }
    val presenceBlocked = (uiState as? ChatDetailUiState.Loaded)?.chat?.isBlocked == true
    DisposableEffect(realtimePresence, presenceOwner, peerId, isResumed, presenceBlocked) {
        if (isResumed && !presenceBlocked && peerId != null) realtimePresence.subscribe(presenceOwner, listOf(peerId))
        onDispose { realtimePresence.unsubscribe(presenceOwner) }
    }
    val peerPresence = if (isResumed && !presenceBlocked) presenceValues[peerId]?.let { realtimePresence.current(peerId) } else null
    val peerOnline = peerPresence?.online == true
    // Reading the historical flow triggers updates, while the scoped accessor
    // rejects a cached timestamp belonging to another account/server.
    val lastConfirmedSeenAt = presenceHistory[peerId]?.let { realtimePresence.lastSeenAt(peerId) }
    val peerPresenceText = presenceLabel(peerPresence, if (!presenceBlocked) lastConfirmedSeenAt else null)

    DisposableEffect(peerId, isResumed, readReceiptsAllowed) {
        if (isResumed && readReceiptsAllowed && peerId != null) liveDrafts.open(viewModel.chatId, peerId)
        onDispose { liveDrafts.close(viewModel.chatId) }
    }
    LaunchedEffect(input, editTarget, composerFocused) {
        if (isResumed && readReceiptsAllowed && peerId != null) liveDrafts.update(latestDraft)
    }
    LaunchedEffect(peerId, isResumed, readReceiptsAllowed) {
        if (isResumed && readReceiptsAllowed && peerId != null) while (true) {
            liveDrafts.update(latestDraft)
            kotlinx.coroutines.delay(1000)
        }
    }
    LaunchedEffect(peerId, isResumed) {
        val peer = peerId ?: return@LaunchedEffect
        if (!isResumed) return@LaunchedEffect
        while (true) {
            try {
                val next = callHistory.refresh(peer)
                if (!historyLoaded) { olderCalls = next; historyLoaded = true }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { /* Existing history remains available offline. */ }
            delay(10_000)
        }
    }

    // Jump-to-message (M.12) - code-review fix 8: jump ONCE per target, then
    // fade the highlight after ~1.5s; never re-pull on every Room update.
    LaunchedEffect(targetMessageId, timeline, initialDataReady) {
        if (!initialDataReady) return@LaunchedEffect
        val target = targetMessageId ?: return@LaunchedEffect
        if (jumpedToTarget == target) return@LaunchedEffect
        val index = timeline.indexOfFirst { it.messages.any { message -> message.clientMessageId == target } }
        if (index >= 0) {
            jumpedToTarget = target
            listState.scrollToItem(index)
            highlightedMessageId = target
        }
    }
    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(1500)
            highlightedMessageId = null
        }
    }

    // Code-review fix 2: auto-scroll only when already near the bottom.
    val followThreshold = with(LocalDensity.current) { 120.dp.roundToPx() }
    val isNearBottom by remember(listState, followThreshold) { derivedStateOf {
        val layout = listState.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull()
        ChatViewportPolicy.isNearBottom(
            layout.totalItemsCount, last?.index ?: -1,
            (last?.offset ?: 0) + (last?.size ?: 0),
            layout.viewportEndOffset - layout.afterContentPadding, followThreshold,
        )
    } }
    val showJumpToLatest by remember(listState, initialDataReady, timeline, followThreshold) { derivedStateOf {
        val layout = listState.layoutInfo
        val visible = layout.visibleItemsInfo
        val last = visible.lastOrNull()
        ChatViewportPolicy.showJumpToLatest(
            ready = initialDataReady,
            timelineCount = timeline.size,
            measuredCount = layout.totalItemsCount,
            viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset,
            visibleRowsMatch = visible.isNotEmpty() && visible.all { row -> timeline.getOrNull(row.index)?.key == row.key },
            canScrollForward = listState.canScrollForward,
            nearBottom = ChatViewportPolicy.isNearBottom(layout.totalItemsCount, last?.index ?: -1,
                (last?.offset ?: 0) + (last?.size ?: 0),
                layout.viewportEndOffset - layout.afterContentPadding, followThreshold),
        )
    } }
    LaunchedEffect(initialDataReady, lastMessageId, timeline.lastOrNull()?.key, messages.size, timeline.size) {
        if (!initialDataReady || timeline.isEmpty()) return@LaunchedEffect
        // Jump instantly on the FIRST load (e.g. opening a chat with a large
        // history) - animating the scroll across thousands of items just
        // wastes time before the screen is usable. Only NEW messages
        // arriving while already near the bottom get the animated scroll.
        if (!hasScrolledOnce) {
            // Code-review fix 8: when this screen was opened from a search
            // jump (targetMessageId set), the jump effect above owns the
            // first scroll - landing on the target. Racing it to the bottom
            // here makes the tap look ignored (observed on the Xiaomi).
            // The LazyListState was created at the final anchor before the
            // first measure; no intermediate top-of-history frame is shown.
            hasScrolledOnce = true
        } else if (!tailTracker.update(lastMessageId, timeline.lastOrNull()?.key,
            messages.mapTo(mutableSetOf()) { it.clientMessageId }.apply { timeline.forEach { add(it.key) } })) {
            // Re-entering an effect or refreshing the same cached rows is not
            // a new message. Never override the restored reading position.
            return@LaunchedEffect
        } else if (isNearBottom || timeline.lastOrNull()?.message?.senderId == currentUserId) {
            androidx.compose.runtime.withFrameNanos { }
            listState.scrollToItem(timeline.size - 1)
            newMessagesPending = 0
        } else {
            // User is reading older messages: never force-scroll. Surface the
            // compact "New messages" affordance instead.
            newMessagesPending++
        }
    }

    // When the user scrolls back to the bottom (or auto-follow resumes), the
    // pending-new-messages affordance is no longer needed.
    LaunchedEffect(isNearBottom) {
        if (isNearBottom) newMessagesPending = 0
    }

    // P0.2 BUG D: when the keyboard opens the message viewport shrinks - keep
    // the latest message visible above the composer (only when at the bottom).
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeVisible by remember(composerFocused, imeInsets, density) {
        derivedStateOf { composerFocused && imeInsets.getBottom(density) > 0 }
    }
    // Keyboard, attachment tray and fullscreen-viewer insets resize the timeline.
    // Restored files may arrive before the first list measure, so wait for it
    // before deciding whether the user was following the tail. A reader above
    // the tail keeps their position; a deliberate drag always takes ownership.
    LaunchedEffect(imeVisible, pendingPhotoUris.size, pendingFileUris.size, viewedImageFile != null, listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        val wasAtBottom = isNearBottom
        if (!wasAtBottom) return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            val followComposer = launch {
            snapshotFlow {
                val layout = listState.layoutInfo
                layout.viewportEndOffset - layout.afterContentPadding
            }.collect { contentBottom ->
                if (listState.isScrollInProgress) return@collect
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()
                // Move only the portion hidden by the IME. Repositioning the
                // entire final row every frame also jumps on tall attachments.
                if (last != null && last.index == layout.totalItemsCount - 1) {
                    val overlap = (last.offset + last.size - contentBottom).coerceAtLeast(0)
                    if (overlap > 0) listState.scrollBy(overlap.toFloat())
                } else if (layout.totalItemsCount > 0) {
                    // A large resize can move the tail outside the composed window.
                    listState.scrollToItem(layout.totalItemsCount - 1)
                }
            }
            }
            // A deliberate drag takes ownership until the next chrome change.
            listState.interactionSource.interactions.first { it is androidx.compose.foundation.interaction.DragInteraction.Start }
            followComposer.cancel()
        }
    }

    LaunchedEffect(listState, hasScrolledOnce) {
        if (!hasScrolledOnce) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (index <= 2) viewModel.loadOlderMessages()
        }
    }

    val nearHistoryStart by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex <= 2 } }
    LaunchedEffect(peerId, hasScrolledOnce, nearHistoryStart, olderCalls) {
        val cursor = olderCalls
        if (hasScrolledOnce && nearHistoryStart && cursor != null) {
            try { olderCalls = callHistory.refresh(peerId, cursor) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retry when the user returns to the beginning. */ }
        }
    }

    // Read only content actually intersecting the viewport, never the loaded tail.
    // Observe viewport changes without invalidating the whole chat composition.
    LaunchedEffect(timeline, currentUserId, listState, historyCutoff, isResumed, readReceiptsAllowed, hasScrolledOnce) {
        if (!isResumed || !readReceiptsAllowed || !hasScrolledOnce) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.asSequence()
                .filter { it.offset < layout.viewportEndOffset - composerHeight && it.offset + it.size > layout.viewportStartOffset + headerHeight }
                .flatMap { timeline.getOrNull(it.index)?.messages.orEmpty() }
                .filter { it.senderId != currentUserId || (it.status == com.messenger.app.domain.model.MessageStatus.DECRYPT_FAILED && (it.sequence ?: Long.MAX_VALUE) in 1..historyCutoff) }
                .mapNotNull { it.sequence }.maxOrNull()
        }.collect { sequence -> sequence?.let { viewModel.markRead(it) } }
    }

    LaunchedEffect(timeline, isResumed, listState) {
        if (!isResumed) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.filter {
            it.offset < listState.layoutInfo.viewportEndOffset - composerHeight && it.offset + it.size > listState.layoutInfo.viewportStartOffset + headerHeight
        }.mapNotNull { timeline.getOrNull(it.index)?.call?.takeIf { call -> call.unread }?.id }.toSet() }
            .collect { ids -> if (ids.isNotEmpty()) {
                try { callHistory.markViewed(ids) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { android.util.Log.w("CallHistory", "Read acknowledgement could not be saved; retained for retry") }
            } }
    }

    val selectingMessages = selectedIds.isNotEmpty()
    fun selectionGroup(message: Message) = chatMessageActionGroup(timeline, message)
    fun toggleSelectionGroup(message: Message) {
        selectedIds = toggleChatMessageGroup(selectedIds, selectionGroup(message))
    }
    // The shorter selection bar must not shrink the tail's scroll range.
    // Keep the last measured composer inset without changing either panel's look.
    // Keep the floor for the first exit frame too, before normal onSizeChanged
    // replaces the old (shorter) selection measurement. Normal resizes update both.
    val timelineComposerInset = maxOf(composerHeight, normalComposerHeight)

    @Composable
    fun albumCell(message: Message, index: Int) {
        // Layout updates this holder without recomposing the row during scroll.
        val menuBounds = remember(message.clientMessageId) { arrayOf(androidx.compose.ui.geometry.Rect.Zero) }
        val storedAttachment = if (attachmentsByMessage.containsKey(message.clientMessageId)) {
            val rowAttachmentFlow = remember(viewModel, message.clientMessageId) {
                viewModel.attachments.map { snapshot ->
                    snapshot?.firstOrNull { it.clientMessageId == message.clientMessageId }
                }.distinctUntilChanged()
            }
            val current by rowAttachmentFlow.collectAsState(initial = attachmentsByMessage[message.clientMessageId])
            current
        } else null
        val rowDirectFile = if (message.directFile != null) {
            val flow = remember(viewModel, message) { viewModel.directFileCard(message) }
            val initial = remember(viewModel, message) { viewModel.currentDirectFileCard(message) }
            val current by flow.collectAsState(initial = initial)
            current
        } else null
        val rowMessage = if (message.directFile == null) message else message.copy(directFile = rowDirectFile)
        val rowAttachment = queuedMediaPresentation(message, storedAttachment ?: voiceSendPreviews[message.clientMessageId])
        val attachmentId = rowAttachment?.attachmentId
        val activeVoice = attachmentId != null && (attachmentId == voicePlayback.playingAttachmentId || attachmentId == voicePlayback.pausedAttachmentId)
        val voicePosition = if (activeVoice) {
            val progress by viewModel.voicePlaybackState.collectAsState()
            progress.positionMs
        } else 0
        if (message.status == com.messenger.app.domain.model.MessageStatus.DECRYPT_FAILED && !showDecryptionFailures) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.decryption_failures_notice), color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showDecryptionFailures = true }) { Text(stringResource(R.string.decryption_failures_show)) }
            }
        } else MessageBubble(
            onBoundsChanged = { menuBounds[0] = it },
            scheduledAt = scheduledDates[message.clientMessageId],
            message = rowMessage,
            attachment = rowAttachment,
            isOwnMessage = message.senderId == currentUserId,
            replyAuthor = if (message.replyPreview?.senderId == currentUserId) stringResource(R.string.reply_author_you) else (uiState as? ChatDetailUiState.Loaded)?.chat?.otherUser?.let { it.displayName ?: it.username }.orEmpty(),
            replyAttachment = message.replyPreview?.targetServerId?.let(replyAttachmentsByServerId::get),
            firstInGroup = index == 0 || timeline[index - 1].message?.senderId != message.senderId,
            selectionMode = selectedIds.isNotEmpty(),
            selected = message.clientMessageId in selectedIds,
            highlighted = highlightedMessageId == message.clientMessageId,
            playingAttachmentId = voicePlayback.playingAttachmentId?.takeIf {
                it == rowAttachment?.attachmentId
            },
            playingPositionMs = voicePosition,
            onRetry = { CocoHaptics.tap(menuCaptureView); viewModel.retry(message.clientMessageId) },
            onCancelPending = {
                CocoHaptics.tap(menuCaptureView)
                viewModel.cancelUnsentMessages(chatUnsentMessageActionIds(timeline, message, currentUserId))
            },
            onDownload = { attachment -> CocoHaptics.tap(menuCaptureView); viewModel.downloadAttachment(attachment) },
            onTogglePlay = { attachment, file -> CocoHaptics.tap(menuCaptureView); viewModel.toggleVoicePlayback(attachment.attachmentId, file) },
            onOpenFile = { file, mimeType -> CocoHaptics.tap(menuCaptureView); viewedMediaMime = mimeType; viewedImageFile = file },
            onOpenImage = { file -> CocoHaptics.tap(menuCaptureView); viewedMediaMime = attachments.firstOrNull { it.localPath == file.path }?.mimeType ?: "image/jpeg"; viewedImageFile = file },
            onLongPress = {
                if (selectedIds.isEmpty()) {
                    menuCaptureJob?.cancel()
                    menuCaptureJob = coroutineScope.launch {
                        val viewport = menuViewport[0]
                        val visible = androidx.compose.ui.geometry.Rect(
                            viewport.left, viewport.top + headerHeight,
                            viewport.right, viewport.bottom - composerHeight,
                        )
                        val bounds = menuBounds[0].intersect(visible)
                        val preview = captureMessageMenuPreview(menuCaptureView, bounds)
                        actionsBounds = preview?.bounds ?: bounds
                        actionsPreview = preview?.bitmap
                        actionsFor = message
                    }
                } else toggleSelectionGroup(message)
            },
            onSelectionTap = { if (selectedIds.isNotEmpty()) { CocoHaptics.select(menuCaptureView); toggleSelectionGroup(message) } },
            onToggleReaction = { emoji -> CocoHaptics.select(menuCaptureView); viewModel.toggleReaction(message, emoji) },
            onQuickReaction = if (quickReaction.isNotEmpty() && com.messenger.app.ui.settings.QuickReactionPolicy.eligible(message, selectedIds.isNotEmpty()))
                ({ CocoHaptics.select(menuCaptureView); viewModel.setQuickReaction(message, quickReaction) }) else null,
            onScrollToReply = { targetServerId ->
                CocoHaptics.tap(menuCaptureView)
                returnPosition = timeline.getOrNull(listState.firstVisibleItemIndex)?.key
                coroutineScope.launch {
                    val id = viewModel.locateMessage(targetServerId)
                    if (id != null) { quoteJump = id; highlightedMessageId = id }
                    else callPermissionError = R.string.chat_original_unavailable
                }
            },
        )
    }

    fun compactCandidate(message: Message?, album: Boolean): Boolean {
        if (message == null || album || message.isDeletedForEveryone ||
            message.status == MessageStatus.DECRYPT_FAILED || message.status == MessageStatus.IDENTITY_CHANGED) return false
        message.localMediaPresentation?.let { return !it.visual }
        val attachment = attachmentsByMessage[message.clientMessageId] ?: message.queuedAttachment
        return if (attachment != null) attachment.mediaType == AttachmentMediaType.VOICE ||
            (attachment.mediaType == AttachmentMediaType.FILE && !attachment.mimeType.startsWith("video/"))
        else message.text.isNotBlank()
    }
    val timelineDays = remember(timeline) {
        timeline.map { row ->
            val stamp = row.message?.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: row.call?.let { Instant.ofEpochMilli(com.messenger.app.data.call.callTimestamp(it)) }
            stamp?.atZone(ZoneId.systemDefault())?.toLocalDate()
        }
    }
    val chatGlass = rememberGlassBackdrop(blurEnabled = false)
    val parentContentColorScheme = MaterialTheme.colorScheme
    val contentColorScheme = remember(parentContentColorScheme, colors) {
        parentContentColorScheme.copy(primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.background, surface = colors.surface, onSurface = colors.primaryText,
            surfaceVariant = colors.surfaceVariant, onSurfaceVariant = colors.secondaryText)
    }
    MaterialTheme(colorScheme = contentColorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
            val glass = chatGlass
            CompositionLocalProvider(LocalCocoColors provides colors, LocalGlassBackdrop provides glass, LocalChatConnected provides (socketState == com.messenger.app.domain.repository.SocketConnectionState.CONNECTED), LocalVoiceActions provides VoiceActions(voicePlayback, viewModel::seekVoice, viewModel::cycleVoiceSpeed)) {
            Box(modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(if (composerFocused) Modifier.imePadding() else Modifier)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize().glassSource(glass)
                        .onGloballyPositioned { menuViewport[0] = it.boundsInWindow() }
                        .cocoConversationBackground(colors),
                ) {
                if (initialDataReady && headerHeight > 0 && composerHeight > 0) LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("chat-timeline")
                    .padding(horizontal = CapsuleChatStyle.side.dp),
                    verticalArrangement = Arrangement.Top,
                    contentPadding = PaddingValues(top = with(density) { headerHeight.toDp() }, bottom = with(density) { timelineComposerInset.toDp() } + 12.dp)) {
                    itemsIndexed(timeline, key = { _, item -> item.key }, contentType = { _, item ->
                        when {
                            item.message == null -> "call"
                            item.albumMembers.isNotEmpty() -> "album"
                            else -> "message"
                        }
                    }) { index, item ->
                        val message = item.message
                        val next = timeline.getOrNull(index + 1)
                        val sameDay = index < timeline.lastIndex && timelineDays[index] != null && timelineDays[index] == timelineDays[index + 1]
                        val compact = compactMessageGroup(message?.senderId, next?.message?.senderId, sameDay,
                            compactCandidate(message, item.albumMembers.isNotEmpty()),
                            compactCandidate(next?.message, next?.albumMembers?.isNotEmpty() == true))
                        // The inset belongs to the preceding keyed row. Prepending older history
                        // cannot change the existing message's top inset and shift its visible body.
                        val bottomGap = if (index == timeline.lastIndex) 0.dp else if (compact) appearance.groupGap.dp else appearance.gap.dp
                        Column(Modifier.fillMaxWidth().padding(bottom = bottomGap)) {
                        val day = timelineDays[index]
                        if (day != null && (index == 0 || day != timelineDays[index - 1])) {
                            val today = java.time.LocalDate.now()
                            val label = when (day) {
                                today -> stringResource(R.string.capsule_today)
                                today.minusDays(1) -> stringResource(R.string.capsule_yesterday)
                                else -> day.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
                            }
                            Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
                                Text(label, color = Color(0xFF949494), fontSize = 11.sp, lineHeight = 14.sp)
                            }
                        }
                        if (message == null) {
                            item.call?.let { call ->
                                val earlier = remember(call.endedAt, historyContext) {
                                    val date = historyContext?.beforeDate
                                    runCatching { call.endedAt != null && date != null &&
                                        java.time.Instant.parse(call.endedAt).isBefore(java.time.Instant.parse(date)) }.getOrDefault(false)
                                }
                                val callKey = "call:${call.id}"
                                val callSelectionLabel = stringResource(if (call.mediaKind == "video") R.string.call_history_video else R.string.call_history_audio) + " · " + formatTime(call.createdAt)
                                val callBounds = remember(call.id) { arrayOf(androidx.compose.ui.geometry.Rect.Zero) }
                                Box(Modifier.fillMaxWidth()
                                    .combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = {
                                        if (selectedIds.isNotEmpty()) selectedIds = if (callKey in selectedIds) selectedIds - callKey else selectedIds + callKey
                                    }, onLongClick = {
                                        if (selectedIds.isNotEmpty()) selectedIds = selectedIds + callKey
                                        else {
                                            menuCaptureJob?.cancel()
                                            menuCaptureJob = coroutineScope.launch {
                                                val viewport = menuViewport[0]
                                                val visible = androidx.compose.ui.geometry.Rect(viewport.left, viewport.top + headerHeight, viewport.right, viewport.bottom - composerHeight)
                                                val bounds = callBounds[0].intersect(visible)
                                                val preview = captureMessageMenuPreview(menuCaptureView, bounds)
                                                actionsBounds = preview?.bounds ?: bounds
                                                actionsPreview = preview?.bitmap
                                                callActionsFor = call
                                            }
                                        }
                                    })) {
                                    com.messenger.app.ui.calls.CallHistoryCard(call, currentUserId.orEmpty(), earlierHistory = earlier,
                                        selected = callKey in selectedIds,
                                        cardModifier = Modifier.onGloballyPositioned { callBounds[0] = it.boundsInWindow() },
                                        selectionMark = if (selectedIds.isEmpty()) null else { {
                                            ChatSelectionMark(callKey in selectedIds, { selectedIds = if (callKey in selectedIds) selectedIds - callKey else selectedIds + callKey },
                                                Modifier.semantics { contentDescription = callSelectionLabel })
                                        } })
                                }
                            }
                        } else {
                        if (item.messages.size > 1) {
                            CapsuleChatAlbum(item.messages, message.senderId == currentUserId, attachmentsByMessage,
                                selectionMark = if (selectedIds.isEmpty()) null else ({
                                    val groupLabel = stringResource(R.string.chat_attachment_group, item.messages.size)
                                    ChatSelectionMark(item.messages.all { it.clientMessageId in selectedIds },
                                        { CocoHaptics.select(menuCaptureView); toggleSelectionGroup(message) },
                                        Modifier.semantics { contentDescription = groupLabel })
                                }),
                                replyAuthor=if(message.replyPreview?.senderId==currentUserId)stringResource(R.string.reply_author_you) else (uiState as? ChatDetailUiState.Loaded)?.chat?.otherUser?.displayName.orEmpty(),
                                replyAttachment=item.messages.firstNotNullOfOrNull {it.replyPreview}?.targetServerId?.let(replyAttachmentsByServerId::get),
                                onCaptionTap={member->if(selectedIds.isNotEmpty()) {
                                    CocoHaptics.select(menuCaptureView)
                                    toggleSelectionGroup(member)
                                }},
                                onScrollToReply={ target->
                                    if(selectedIds.isNotEmpty()) {
                                        val member=item.messages.firstOrNull {it.replyPreview?.targetServerId==target}?:message
                                        CocoHaptics.select(menuCaptureView)
                                        toggleSelectionGroup(member)
                                    } else coroutineScope.launch {
                                        val id=viewModel.locateMessage(target)
                                        if(id!=null){quoteJump=id;highlightedMessageId=id}else callPermissionError=R.string.chat_original_unavailable
                                    }
                                },
                                onCaptionLongPress = { member, bounds ->
                                    if(selectedIds.isNotEmpty()) toggleSelectionGroup(member)
                                    else {
                                        menuCaptureJob?.cancel()
                                        menuCaptureJob=coroutineScope.launch {
                                            val preview=captureMessageMenuPreview(menuCaptureView,bounds)
                                            actionsBounds=preview?.bounds?:bounds;actionsPreview=preview?.bitmap;actionsFor=member
                                        }
                                    }
                                },onReaction={ member,emoji->
                                    CocoHaptics.select(menuCaptureView)
                                    if(selectedIds.isNotEmpty())toggleSelectionGroup(member)
                                    else viewModel.toggleReaction(member,emoji)
                                }) { member -> albumCell(member, index) }
                        } else albumCell(message, index)
                        }
                        }
                    }
                }
                Box(modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = with(density) { composerHeight.toDp() }).padding(CocoSpacing.md)) {
                    NewMessagesPill(
                        count = newMessagesPending,
                        visible = showJumpToLatest,
                        onClick = {
                            coroutineScope.launch { if (timeline.isNotEmpty()) listState.animateScrollToItem(timeline.size - 1) }
                            newMessagesPending = 0
                        },
                    )
                }
                }

                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().capsuleChromeScrim(top=true).onSizeChanged { headerHeight = it.height }.onGloballyPositioned { ActiveChatTracker.updateHeader(viewModel.chatId, it.positionInRoot().y + it.size.height) }.statusBarsPadding()) {
                if (selectedIds.isNotEmpty()) {
                    // Preserve the timeline's top inset when the selection toolbar replaces the peer header.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(CapsuleChatStyle.headerHeight.dp), verticalAlignment = Alignment.CenterVertically) {
                        val island = Modifier.size(44.dp).clip(CircleShape).background(colors.deepPanel.copy(alpha = 0.82f))
                            .border(0.5.dp, colors.primaryText.copy(alpha = 0.08f), CircleShape)
                        com.messenger.app.ui.components.CocoBackButton(onClick = { CocoHaptics.tap(menuCaptureView); selectedIds = emptySet() }, label = stringResource(R.string.cancel))
                        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Row(Modifier.clip(RoundedCornerShape(24.dp)).background(colors.deepPanel.copy(alpha = 0.82f))
                                .border(0.5.dp, colors.primaryText.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.chat_selection_label), color = colors.primaryText, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Box(Modifier.heightIn(min = 30.dp).widthIn(min = 30.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.85f)).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(selectedIds.size.toString(), color = colors.onAccent, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        var options by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { options = true }, modifier = island) { Icon(CocoIcons.MoreVert, stringResource(R.string.more_options), tint = colors.primaryText) }
                            DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.chat_select_loaded)) }, onClick = {
                                    options = false
                                    selectedIds = messages.filter { !it.isDeletedForEveryone }.map { it.clientMessageId }.toSet() + chatCalls.map { "call:${it.id}" }
                                })
                            }
                        }
                    }
                } else {
                Row(modifier = Modifier.fillMaxWidth().height(CapsuleChatStyle.headerHeight.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.messenger.app.ui.components.CocoBackButton(onClick = onBack)
                    Spacer(Modifier.width(8.dp))
                    when (val state = uiState) {
                        is ChatDetailUiState.Loaded -> {
                            val island = com.messenger.app.ui.call.LocalCallIsland.current
                            val otherName = state.chat.headerTitle(stringResource(R.string.saved_messages), stringResource(R.string.chat_default_title))
                            var menuOpen by remember { mutableStateOf(false) }
                            // Small letter avatar in the header (N.2: the name
                            // block gets an identity anchor, not just text).
                            Row(modifier = Modifier.weight(1f).padding(end = 8.dp).heightIn(min = 48.dp).clickable(role = Role.Button) { CocoHaptics.tap(menuCaptureView); if (state.chat.otherUser != null) showPeerCard = true else menuOpen = true }, verticalAlignment = Alignment.CenterVertically) {
                            if (island == null) Box {
                                if (state.chat.type == "saved") Box(
                                    Modifier.size(32.dp).background(Brush.linearGradient(listOf(Color(0xFF929292), Color(0xFF515151))), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(CocoIcons.Bookmark, contentDescription = otherName, tint = colors.onAccent, modifier = Modifier.size(20.dp)) }
                                else com.messenger.app.ui.components.UserAvatar(
                                userId = state.chat.otherUser?.id.orEmpty(),
                                avatarVersion = state.chat.otherUser?.avatarVersion ?: 0,
                                hasAvatar = state.chat.otherUser?.hasAvatar == true,
                                name = otherName, color = colors.accent, contentColor = CapsuleChatStyle.text, size = CapsuleChatStyle.avatarSize.dp, fallbackBrush = Brush.linearGradient(listOf(Color(0xFF929292), Color(0xFF515151))),
                            )
                                if (peerOnline && socketState == com.messenger.app.domain.repository.SocketConnectionState.CONNECTED) Box(Modifier.align(Alignment.BottomEnd).size(10.dp).background(colors.deepPanel, CircleShape).padding(2.dp).background(colors.success, CircleShape))
                            }
                            Column(modifier = Modifier.weight(1f).padding(start = 9.dp)) {
                                Text(text = otherName, color=CapsuleChatChrome.topInk,fontSize = 17.sp, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (island != null) {
                                    com.messenger.app.ui.call.CallIslandControl(island.durationMs, island.isVideo, island.onReturn, island.status)
                                } else {
                                    val subtitle = when (socketState) {
                                        com.messenger.app.domain.repository.SocketConnectionState.CONNECTING -> stringResource(R.string.call_connecting)
                                        com.messenger.app.domain.repository.SocketConnectionState.CONNECTED -> if (actionError == ChatError.NETWORK || attachmentError == ChatError.NETWORK) stringResource(R.string.waiting_for_network) else when (activityLabel) { "typing" -> stringResource(R.string.chat_typing); "recording" -> stringResource(R.string.chat_recording); "uploading" -> stringResource(R.string.chat_uploading); else -> peerPresenceText.ifEmpty { state.chat.otherUser?.username?.let { "@$it" }.orEmpty() } }
                                        else -> stringResource(R.string.waiting_for_network)
                                    }
                                    com.messenger.app.ui.theme.CocoStatusText(text = subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = if (peerOnline && socketState == com.messenger.app.domain.repository.SocketConnectionState.CONNECTED && activityLabel.isNullOrEmpty() && actionError != ChatError.NETWORK && attachmentError != ChatError.NETWORK) CapsuleChatStyle.muted else colors.secondaryText)
                                }
                            }
                            }
                            Row(Modifier.border(1.dp, CapsuleChatStyle.border, RoundedCornerShape(22.dp)).padding(horizontal = 4.dp,vertical=1.dp), verticalAlignment = Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                            val otherId = state.chat.otherUser?.id
                            if (otherId != null) {
                                IconButton(onClick = { CocoHaptics.tap(menuCaptureView); requestCall(otherId, otherName, com.messenger.app.domain.model.CallMediaKind.AUDIO) }, modifier = Modifier.size(34.dp, 36.dp)) {
                                    Icon(ChatControls.phone, stringResource(R.string.call_audio), tint = CapsuleChatChrome.topInk, modifier = Modifier.size(CapsuleChatChrome.topIcon.dp))
                                }
                            }
                            Box {
                                IconButton(onClick = { CocoHaptics.tap(menuCaptureView); menuOpen = true }, modifier = Modifier.size(34.dp, 36.dp)) {
                                    Icon(CapsuleChatChrome.settings, stringResource(R.string.more_options), tint = CapsuleChatChrome.topInk, modifier = Modifier.size(CapsuleChatChrome.topIcon.dp))
                                }
                                if (menuOpen) ChatHeaderMenu(onDismiss = { menuOpen = false }, actions = buildList {
                                    add(ChatHeaderAction(stringResource(R.string.chats_search), CocoIcons.Search, colors.categoryBlue, colors.categoryBlue.copy(alpha = .12f)) { menuOpen = false; onSearchChat(viewModel.chatId) })
                                    add(ChatHeaderAction(stringResource(R.string.media_title), CocoIcons.Photo, colors.categoryMint, colors.categoryMint.copy(alpha = .12f)) { menuOpen = false; onOpenMedia() })
                                    add(ChatHeaderAction(stringResource(R.string.chat_pinned_messages), CocoIcons.Pin, colors.categoryAmber, colors.categoryAmber.copy(alpha = .12f)) { menuOpen = false; showPins = true })
                                    add(ChatHeaderAction(stringResource(R.string.chat_activity_short), CocoIcons.Eye, colors.categoryBlue, colors.categoryBlue.copy(alpha = .12f), group = 1, checked = activityEnabled) {
                                        activityEnabled = !activityEnabled; activityPreferences.edit().putBoolean("activity", activityEnabled).apply(); menuOpen = false
                                        if (!activityEnabled) viewModel.sendActivity("idle")
                                    })
                                    add(ChatHeaderAction(stringResource(R.string.chat_schedule), CocoIcons.Send, colors.categoryBlue, colors.categoryBlue.copy(alpha = .12f), group = 1, enabled = input.isNotBlank() && editTarget == null && replyTarget == null) { menuOpen = false; showSchedule = true })
                                    add(ChatHeaderAction(stringResource(R.string.chat_reminders), CocoIcons.Bookmark, colors.categoryAmber, colors.categoryAmber.copy(alpha = .12f), group = 1) { menuOpen = false; showReminders = true })
                                    if (otherId != null) add(ChatHeaderAction(stringResource(R.string.verify_title), CocoIcons.Security, colors.categoryMint, colors.categoryMint.copy(alpha = .12f), group = 2) { menuOpen = false; onVerifyIdentity(otherId) })
                                    if (peerId != null) {
                                        add(ChatHeaderAction(stringResource(if (state.chat.isMuted) R.string.chat_unmute else R.string.chat_mute), CocoIcons.Speaker, colors.categoryAmber, colors.categoryAmber.copy(alpha = .12f), group = 2) { menuOpen = false; chatSafetyAction = "notifications"; showChatSafety = true })
                                        add(ChatHeaderAction(stringResource(if (state.chat.isBlocked) R.string.chat_unblock else R.string.chat_block), CocoIcons.EyeOff, colors.categoryRose, colors.categoryRose.copy(alpha = .12f), group = 3) { menuOpen = false; chatSafetyAction = "block"; showChatSafety = true })
                                        add(ChatHeaderAction(stringResource(R.string.chat_report), CocoIcons.Security, colors.categoryRose, colors.categoryRose.copy(alpha = .12f), group = 3) { menuOpen = false; chatSafetyAction = "report"; showChatSafety = true })
                                    }
                                })
                            }
                        }
                        }
                        is ChatDetailUiState.Error -> Text(text = stringResource(state.error.resId), color = MaterialTheme.colorScheme.error)
                        ChatDetailUiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    }
                }

                }
                pins.lastOrNull()?.let { pinnedId ->
                    val pinned = messages.firstOrNull { it.clientMessageId == pinnedId }
                    Row(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(12.dp)).clickable {
                        coroutineScope.launch {
                            val id = viewModel.locateMessage(pinnedId)
                            if (id != null) { quoteJump = id; highlightedMessageId = id }
                            else callPermissionError = R.string.chat_original_unavailable
                        }
                    }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(CocoIcons.Bookmark, contentDescription = stringResource(R.string.chat_pinned_messages), tint = colors.accent, modifier = Modifier.size(20.dp))
                        Text(pinnedMessageText(pinned, attachmentsByMessage[pinnedId]), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), color = colors.primaryText)
                        IconButton(onClick = { showPins = true }, modifier = Modifier.size(36.dp)) { Icon(CocoIcons.MoreVert, contentDescription = stringResource(R.string.chat_pinned_messages), tint = colors.secondaryText) }
                    }
                }
                failedActions.firstOrNull()?.let { failed ->
                    FailedMessageActionNotice(failed, onRetry = { viewModel.retry(failed.clientMessageId) },
                        onCancel = { viewModel.deleteForMe(failed.clientMessageId) })
                }
                attachmentError?.takeUnless { it == ChatError.NETWORK }?.let { error ->
                    Row(
                        modifier = Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(14.dp)).padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = stringResource(error.resId), color = colors.secondaryText, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::clearAttachmentError) { Text(stringResource(R.string.dismiss)) }
                    }
                }

                actionError?.takeUnless { it == ChatError.NETWORK }?.let { error ->
                    Row(
                        modifier = Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(14.dp)).padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = stringResource(error.resId), color = colors.secondaryText, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::clearActionError) { Text(stringResource(R.string.dismiss)) }
                    }
                }

                callPermissionError?.let { resId ->
                    Row(
                        modifier = Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(14.dp)).padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = stringResource(resId), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { callPermissionError = null }) { Text(stringResource(R.string.dismiss)) }
                    }
                }

                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .alpha(if (attachmentSheetOpen) 0f else 1f)
                    .capsuleChromeScrim(top=false).onSizeChanged {
                    composerHeight = it.height
                    if (!selectingMessages) normalComposerHeight = it.height
                }.navigationBarsPadding()) {
                if (peerDraft.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).glassPanel(RoundedCornerShape(16.dp)).padding(12.dp)) {
                    Text(stringResource(R.string.live_draft_peer), style = MaterialTheme.typography.labelSmall, color = colors.accentSoft)
                    Text(peerDraft, maxLines = 3, overflow = TextOverflow.Ellipsis, color = colors.secondaryText)
                }
                val otherDisplayName = (uiState as? ChatDetailUiState.Loaded)?.let { it.chat.otherUser?.displayName ?: it.chat.otherUser?.username }


                if (selectedIds.isNotEmpty()) {
                    val selectedMessages = messages.filter { it.clientMessageId in selectedIds }
                    val canDeleteEveryone = selectedMessages.all { it.senderId == currentUserId && !it.isDeletedForEveryone } &&
                        selectedMessages.size + selectedIds.count { it.startsWith("call:") } == selectedIds.size
                    var selectionMenu by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        if (selectionMenu) {
                            var alsoDelete by remember { mutableStateOf(false) }
                            val ids = remember { selectedIds.toList() }
                            Dialog(onDismissRequest = { selectionMenu = false }) {
                                ChatDialogBlur()
                                Surface(shape = RoundedCornerShape(20.dp), color = colors.surface, contentColor = colors.primaryText) {
                                    Column(Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
                                        Text(stringResource(R.string.chat_delete_items), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.media_selected_count, ids.size), color = colors.secondaryText, modifier = Modifier.padding(vertical = 12.dp))
                                        if (canDeleteEveryone) Row(Modifier.fillMaxWidth().toggleable(value = alsoDelete, role = Role.Checkbox, onValueChange = { alsoDelete = it }), verticalAlignment = Alignment.CenterVertically) {
                                            androidx.compose.material3.Checkbox(checked = alsoDelete, onCheckedChange = null)
                                            Text(stringResource(R.string.delete_also_peer), modifier = Modifier.padding(start = 8.dp))
                                        } else Text(stringResource(R.string.delete_only_you), color = colors.secondaryText)
                                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = { selectionMenu = false }) { Text(stringResource(R.string.cancel)) }
                                            TextButton(modifier = Modifier.weight(1f).heightIn(min = 48.dp).background(colors.danger.copy(alpha = 0.10f), RoundedCornerShape(12.dp)), onClick = {
                                                val everyone = alsoDelete && canDeleteEveryone
                                                selectionMenu = false
                                                ids.filter { it.startsWith("call:") }.forEach { deleteCallRecord(it.removePrefix("call:"), everyone) }
                                                viewModel.deleteSelected(ids.filterNot { it.startsWith("call:") }, everyone) { id -> selectedIds = selectedIds - id }
                                            }) { Text(stringResource(R.string.delete), color = colors.danger) }
                                        }
                                    }
                                }
                            }
                        }
                        ChatSelectionActionsBar(
                            canForward = selectedIds.none { it.startsWith("call:") } && selectedMessages.size == selectedIds.size && selectedMessages.none { it.directFile != null },
                            onForward = { showBulkForward = true },
                            onDelete = { pendingBulkDelete = null; selectionMenu = true },
                        )
                    }
                }
                returnPosition?.let { position ->
                    TextButton(onClick = { coroutineScope.launch { val index = timeline.indexOfFirst { it.key == position }; if (index >= 0) listState.animateScrollToItem(index); returnPosition = null } }) { Text(stringResource(R.string.chat_return_position)) }
                }
                voiceDraft?.takeIf { !isRecording }?.let { item ->
                    VoiceDraftTray(viewModel, item, sendingVoice || stagingVoice,
                        onDiscard = {
                            coroutineScope.launch {
                                viewModel.saveChatValueNow("voiceDraft", null)
                                viewModel.stopVoice(); voiceDraft = null
                            }
                        }, onSend = {
                            viewModel.stopVoice()
                            sendingVoice = true
                            val previewId = item.getString("id")
                            val previewFile = File(item.getString("path"))
                            voiceSendPreviews[previewId] = Attachment(previewId, previewId, AttachmentMediaType.VOICE,
                                "audio/mp4", previewFile.name, previewFile.length(), null, null, item.getLong("duration"), null,
                                previewFile.path, AttachmentDownloadState.DOWNLOADED, AttachmentUploadState.PENDING, 0)
                            coroutineScope.launch {
                                try {
                                    if (viewModel.sendVoiceDraft(File(item.getString("path")), item.getLong("duration"), item.getString("id"))) {
                                        viewModel.saveChatValueNow("voiceDraft", null); voiceDraft = null
                                    } else { voiceSendPreviews.remove(previewId) }
                                } finally { sendingVoice = false }
                            }
                        })
                }
                if (stagingVoice && voiceDraft == null && !isRecording) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(64.dp)
                        .clip(RoundedCornerShape(27.dp)).background(androidx.compose.ui.graphics.SolidColor(CapsuleChatStyle.entry)).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = CapsuleChatStyle.accent, strokeWidth = 2.dp)
                        Text(stringResource(R.string.media_preparing), color = colors.secondaryText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (preparation.active) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.media_preparing), Modifier.weight(1f).padding(8.dp))
                        TextButton(onClick = { CocoHaptics.tap(menuCaptureView); mediaPreparation.cancel(viewModel.chatId) }) { Text(stringResource(R.string.cancel)) }
                    }
                }
                if ((identityNeedsReview && verificationPeer != null) || ownIdentityNeedsReview) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(stringResource(R.string.chat_identity_review), style = MaterialTheme.typography.bodySmall)
                        if (identityNeedsReview && verificationPeer != null) TextButton(onClick = { onVerifyIdentity(verificationPeer) }) { Text(stringResource(R.string.verify_title)) }
                        if (ownIdentityNeedsReview && currentUserId != null) TextButton(onClick = { onVerifyIdentity(currentUserId) }) { Text(stringResource(R.string.verify_own_devices)) }
                    }
                }
                if (preparation.failed || interruptedImport) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (preparation.failure == com.messenger.app.data.attachment.MediaPreparationCoordinator.Failure.SOURCE_LIMIT)
                            R.string.direct_normal_limit_unavailable else R.string.media_preparation_interrupted), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { interruptedImport = false; mediaPreparation.dismissError(viewModel.chatId) }) { Text(stringResource(R.string.dismiss)) }
                    }
                }

                if (messages.any { it.senderId == currentUserId && it.status == MessageStatus.PENDING && it.pendingReason == "recipient_devices_unavailable" }) {
                    Text(stringResource(R.string.chat_waiting_recipient_keys), color = colors.secondaryText,
                        fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
                }
                if ((uiState as? ChatDetailUiState.Loaded)?.chat?.isBlocked == true) {
                    TextButton(onClick = { showChatSafety = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chat_blocked_notice)) }
                } else if ((voiceDraft == null || isRecording) && !stagingVoice && selectedIds.isEmpty()) ComposerBar(
                    editing = editTarget != null,
                    attachmentCount = if (editTarget == null && !sendingComposerMedia) pendingPhotoUris.size + pendingFileUris.size else 0,
                    attachmentsReady = !preparation.active,
                    attachments = if (editTarget == null && !sendingComposerMedia && (pendingPhotoUris.isNotEmpty() || pendingFileUris.isNotEmpty())) ({
                        ComposerAttachmentTray(pendingPhotoUris, null, files=pendingFileUris,onRemovePhoto = { uri ->
                            pendingPhotoUris = pendingPhotoUris.filterNot { it == uri }; saveMediaDrafts(pendingPhotoUris)
                            coroutineScope.launch { viewModel.discardStagedMedia(listOf(uri)) }
                        }, onRemoveFile = {}, onRemoveDocument = { uri ->
                            coroutineScope.launch {
                                val id = uri.path?.let { File(it).parentFile?.name }.orEmpty()
                                val remaining=pendingFileUris.filterNot {it==uri}
                                saveFileDrafts(remaining)
                                if (viewModel.discardPreparedFile(id, uri,clearLegacy=false)) pendingFileUris = remaining
                            }
                        })
                        if (pendingPhotoUris.isNotEmpty()) Row(Modifier.fillMaxWidth().toggleable(value = galleryOriginals,
                            role = Role.Checkbox, onValueChange = {
                                galleryOriginals = it
                                val mode = gallerySendQuality(galleryQualityPreferences.getString("send", "image"), it)
                                galleryQualityPreferences.edit().putString("send", mode).putString(GALLERY_SEND_MODE_PREFERENCE, mode).apply()
                                CocoHaptics.select(menuCaptureView)
                            })
                            .padding(start = 9.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = galleryOriginals, onCheckedChange = null, modifier = Modifier.size(36.dp))
                            Text(stringResource(R.string.capsule_gallery_originals), fontSize = 11.sp, color = colors.secondaryText)
                        }
                    }) else null,
                    replyHeader = if (replyTarget != null || editTarget != null) ({
                        val target = editTarget ?: replyTarget!!
                        ReplyComposerBanner(
                            name = if (target.senderId == currentUserId) stringResource(R.string.reply_author_you) else otherDisplayName.orEmpty().ifBlank { stringResource(R.string.reply) },
                            targetText = target.text,
                            caption = target.caption,
                            attachment = attachmentsByMessage[target.clientMessageId],
                            isEdit = editTarget != null,
                            onDismiss = { CocoHaptics.tap(menuCaptureView); if (editTarget != null) { viewModel.cancelEdit(); input = ordinaryDraft } else viewModel.cancelReply() },
                        )
                    }) else null,
                    keyboardVisible = imeVisible,
                    onFocusChanged = { composerFocused = it },
                    onHideKeyboard = { focusManager.clearFocus(force = true); keyboardController?.hide() },
                    input = input,
                    submitting = submittingText,
                    onInputChange = {
                        input = it
                        if (activityEnabled) viewModel.sendActivity(if (it.isEmpty()) "idle" else "typing")
                        if (editTarget == null && composerRestored) { ordinaryDraft = it; viewModel.saveChatValue("draft", it) }
                    },
                    isRecording = isRecording,
                    recordingContent = {
                        RecordingBanner(recordingSeconds, recordingPaused, locked = recordingLocked, recorder = voiceRecorder, drag = recordingDrag,
                            onPause = { CocoHaptics.tap(menuCaptureView); voiceRecorder.togglePause(); recordingPaused = voiceRecorder.isPaused },
                            onCancel = { CocoHaptics.tap(menuCaptureView); stopVoiceCapture(cancel = true); resumeVoiceBase = null; isRecording = false; recordingSeconds = 0 },
                            onSend = { CocoHaptics.tap(menuCaptureView); isRecording = false; stopVoiceCapture()?.let { stageVoice(it.first, it.second) } })
                    },
                    onVoiceHold = {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            recordingLocked = false; startVoiceRecording()
                        } else { requestVoicePermission() }
                    },
                    onVoiceRelease = { if (isRecording && !recordingLocked) { CocoHaptics.tap(menuCaptureView); isRecording = false; stopVoiceCapture()?.let { stageVoice(it.first, it.second) } } else if (voiceStarting && !recordingLocked) stopVoiceCapture() },
                    onVoiceCancel = { if (isRecording) CocoHaptics.tap(menuCaptureView); stopVoiceCapture(cancel = true); resumeVoiceBase = null; isRecording = false; recordingSeconds = 0 },
                    onVoiceLock = { if (isRecording && !recordingLocked) CocoHaptics.select(menuCaptureView); recordingLocked = true },
                    onVoiceProgress = { x, y -> recordingDrag = androidx.compose.ui.geometry.Offset(x, y) },
                    attachmentActionsEnabled = !preparation.active && !sendingComposerMedia && pendingPhotoUris.size + pendingFileUris.size < 10,
                    onAttach = { openComposerAttachment { attachmentSheetOpen = true } },
                    onAttachCamera = { openComposerAttachment { requestCamera() } },
                    onAttachFiles = { openComposerAttachment { filePickerLauncher.launch(arrayOf("*/*")) } },
                    onMicToggle = {
                        recordingLocked = true
                        if (!isRecording) {
                            val hasPermission =
                                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                startVoiceRecording()
                            } else {
                                requestVoicePermission()
                            }
                        }
                    },
                    onSend = mediaSend@{
                        if (!submittingText && !sendingComposerMedia && editTarget == null && (pendingPhotoUris.isNotEmpty() || pendingFileUris.isNotEmpty())) {
                            CocoHaptics.tap(menuCaptureView)
                            val caption = input
                            val photos = pendingPhotoUris
                            val files = pendingFileUris
                            var captionAdmitted = false
                            val quality = gallerySendQuality(context.getSharedPreferences("media-quality", Context.MODE_PRIVATE).getString("send", "image"), galleryOriginals)
                            val directDrafts = ((if (quality == "file") photos else emptyList()) + files).distinct().filter { uri ->
                                uri.scheme == "file" && uri.path?.let { File(it).length() > 100L * 1024 * 1024 } == true
                            }
                            if (directDrafts.isNotEmpty()) {
                                // Preserve caption and ordinary drafts. The common confirmation owns
                                // direct consent and removes only originals copied into a durable offer.
                                preparePickedOriginals(directDrafts, true, true, {})
                                return@mediaSend
                            }
                            sendingComposerMedia = true
                            mediaCaption = caption; viewModel.saveChatValue("mediaCaption", caption)
                            input = ""; ordinaryDraft = ""; viewModel.saveChatValue("draft", "")
                            fun finishMedia(success: Boolean) {
                                sendingComposerMedia = false
                                if (!success && !captionAdmitted && input.isEmpty()) {
                                    input = caption; ordinaryDraft = caption; viewModel.saveChatValue("draft", caption)
                                }
                                if (captionAdmitted || success) { mediaCaption = ""; viewModel.saveChatValue("mediaCaption", null) }
                            }
                            viewModel.sendComposerAttachments(photos,files,caption,quality,
                                onPhotoQueued = { sent ->
                                    captionAdmitted=true;mediaCaption=""
                                    pendingPhotoUris = pendingPhotoUris.filterNot { it == sent }; saveMediaDrafts(pendingPhotoUris)
                                },onFileQueued = { sent ->
                                    captionAdmitted=true;mediaCaption=""
                                    pendingFileUris=pendingFileUris.filterNot {it==sent};saveFileDrafts(pendingFileUris)
                                },onComplete=::finishMedia)
                        } else if (!submittingText) {
                            CocoHaptics.tap(menuCaptureView)
                            val text = input
                            submittingText = true
                            coroutineScope.launch {
                                try {
                                    if (viewModel.sendComposerText(text) && input == text) {
                                        if (editTarget != null) input = ordinaryDraft
                                        else { input = ""; ordinaryDraft = ""; viewModel.saveChatValue("draft", "") }
                                    }
                                } finally { submittingText = false }
                            }
                        }
                    },
                )

                }
                if (attachmentSheetOpen) {
                    AttachmentSheet(
                        backdrop = chatGlass,
                        selection = gallerySelection,
                        caption = input,
                        onCaptionChange = {
                            input = it
                            if (editTarget == null && composerRestored) { ordinaryDraft = it; viewModel.saveChatValue("draft", it) }
                        },
                        selectionLimit = maxOf(1, 10 - pendingPhotoUris.size - pendingFileUris.size),
                        onSelection = { gallerySelection = it },
                        onSelected = { uris ->
                            val mode = preferredGalleryQuality()
                            galleryOriginals = mode == "file"
                            galleryQualityPreferences.edit().putString("send", mode).apply()
                            attachmentSheetOpen = false
                            interruptedImport = false
                            val captures = galleryCaptureFiles
                            if (preparePickedOriginals(uris, mode == "file", false, { captures.forEach { File(it).delete() } })) {
                                gallerySelection = emptyList(); galleryCaptureFiles = emptyList()
                            }
                        },
                        onDismiss = { attachmentSheetOpen = false },
                        onCamera = {
                            attachmentSheetOpen = false
                            requestCamera()
                        },
                        onVideo = {
                            attachmentSheetOpen = false
                            cameraCapture.request(initialVideo = true)
                        },
                        onGallery = {
                            attachmentSheetOpen = false
                            photoPickerLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                        },
                        onFile = {
                            attachmentSheetOpen = false
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
        }

        }

        actionsFor?.let { message ->
            // Freeze the exact visible set while this menu/confirmation is open.
            val group = remember(message.clientMessageId) { selectionGroup(message) }
            val groupIds = group.map { it.clientMessageId }
            LaunchedEffect(messages) {
                if (messages.none { it.clientMessageId == message.clientMessageId }) actionsFor = null
            }
            MessageActionsSheet(
                anchor = actionsBounds,
                preview = actionsPreview,
                message = message,
                isPinned = message.clientMessageId in pins,
                isOwnMessage = message.senderId == currentUserId,
                actionGroupCount = group.size,
                actionGroupHasRemote = group.any { it.serverId != null },
                hasAttachment = attachmentsByMessage.containsKey(message.clientMessageId),
                onDismiss = { actionsFor = null },
                onReply = { viewModel.startReply(message); actionsFor = null },
                onSelect = { focusManager.clearFocus(); keyboardController?.hide(); selectedIds = selectedIds + groupIds; actionsFor = null },
                onPin = { togglePin(message); actionsFor = null },
                onRemind = { reminderFor = message; actionsFor = null },
                onCopy = { clipboardManager.setText(AnnotatedString(message.conferenceInvitation?.invite ?: if (attachmentsByMessage.containsKey(message.clientMessageId)) message.caption.orEmpty() else message.text)); actionsFor = null },
                onEdit = { viewModel.startEdit(message); actionsFor = null },
                onForward = {
                    if (group.size > 1) { selectedIds = groupIds.toSet(); showBulkForward = true }
                    else forwardDialogFor = message
                    actionsFor = null
                },
                onSave = { viewModel.saveMessage(message.clientMessageId); actionsFor = null },
                onDeleteForMe = { viewModel.deleteSelected(groupIds, false) { id -> selectedIds = selectedIds - id }; actionsFor = null },
                onDeleteForEveryone = { viewModel.deleteSelected(groupIds, true) { id -> selectedIds = selectedIds - id }; actionsFor = null },
                onDeleteSingleForMe = if (group.size > 1) ({ viewModel.deleteForMe(message.clientMessageId); actionsFor = null }) else null,
                onDeleteSingleForEveryone = if (group.size > 1) ({ viewModel.deleteForEveryone(message.clientMessageId); actionsFor = null }) else null,
                onReact = { emoji -> viewModel.toggleReaction(message, emoji) },
            )
        }

        callActionsFor?.let { call ->
            LaunchedEffect(allCalls) {
                if (allCalls.none { it.id == call.id }) callActionsFor = null
            }
            // Presentation adapter only: calls never enter the encrypted message store.
            val item = Message("call:${call.id}", call.id, viewModel.chatId, call.callerUserId,
                stringResource(if (call.mediaKind == "video") R.string.call_history_video else R.string.call_history_audio),
                null, call.createdAt, MessageStatus.SENT)
            MessageActionsSheet(anchor = actionsBounds, preview = actionsPreview, message = item,
                isOwnMessage = call.callerUserId == currentUserId, hasAttachment = false, isPinned = false, isCallRecord = true,
                onDismiss = { callActionsFor = null }, onReply = {}, onPin = {}, onRemind = {}, onCopy = {}, onEdit = {}, onForward = {}, onSave = {}, onReact = {},
                onSelect = { selectedIds = selectedIds + item.clientMessageId; callActionsFor = null; focusManager.clearFocus(); keyboardController?.hide() },
                onDeleteForMe = { callActionsFor = null; deleteCallRecord(call.id, false) },
                onDeleteForEveryone = { callActionsFor = null; deleteCallRecord(call.id, true) })
        }

        if (showSchedule) {
            AlertDialog(onDismissRequest = { showSchedule = false }, title = { Text(stringResource(R.string.chat_schedule)) }, text = { Text(stringResource(R.string.chat_schedule_notice)) }, confirmButton = {
                TextButton(onClick = {
                    val now = java.util.Calendar.getInstance()
                    android.app.DatePickerDialog(context, { _, year, month, day ->
                        android.app.TimePickerDialog(context, { _, hour, minute ->
                            val due = java.util.Calendar.getInstance().apply { set(year, month, day, hour, minute, 0) }.timeInMillis
                            val text = input
                            coroutineScope.launch { if (viewModel.scheduleText(text, due)) { if (input == text) { input = ""; ordinaryDraft = ""; viewModel.saveChatValue("draft", "") }; showSchedule = false } }
                        }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).show()
                    }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH)).show()
                }) { Text(stringResource(R.string.chat_choose_time)) }
            }, dismissButton = { TextButton(onClick = { showSchedule = false }) { Text(stringResource(R.string.cancel)) } })
        }
        reminderFor?.let { item ->
            AlertDialog(onDismissRequest = { reminderFor = null }, title = { Text(stringResource(R.string.chat_remind)) }, text = {
                Column {
                    TextButton(onClick = { scheduleReminder(item, System.currentTimeMillis() + 3600000) }) { Text(stringResource(R.string.chat_in_hour)) }
                    TextButton(onClick = {
                        val now = java.util.Calendar.getInstance()
                        android.app.DatePickerDialog(context, { _, year, month, day ->
                            android.app.TimePickerDialog(context, { _, hour, minute ->
                                val selected = java.util.Calendar.getInstance().apply { set(year, month, day, hour, minute, 0) }
                                if (selected.timeInMillis > System.currentTimeMillis()) scheduleReminder(item, selected.timeInMillis)
                            }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).show()
                        }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH)).show()
                    }) { Text(stringResource(R.string.chat_choose_time)) }
                    if (reminderPermissionMissing) Text(stringResource(R.string.chat_enable_notifications))
                }
            }, confirmButton = { TextButton(onClick = { reminderFor = null }) { Text(stringResource(R.string.cancel)) } })
        }
        if (showReminders) {
            AlertDialog(onDismissRequest = { showReminders = false }, title = { Text(stringResource(R.string.chat_reminders)) }, text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    if (reminders.length() == 0) item { Text(stringResource(R.string.reminders_empty_hint)) }
                    if (reminders.length() == 0) item { Text(stringResource(R.string.reminders_empty_hint)) }
                    items(reminders.keys().asSequence().toList(), key = { it }) { id ->
                        val original = messages.firstOrNull { it.clientMessageId == id && !it.isDeletedForEveryone }
                        Column {
                            TextButton(onClick = { coroutineScope.launch { quoteJump = viewModel.locateMessage(id); showReminders = false } }) { Text(original?.text ?: stringResource(R.string.chat_original_unavailable), maxLines = 2) }
                            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(reminders.optLong(id))))
                            TextButton(onClick = {
                                com.messenger.app.push.ChatReminderReceiver.cancel(context, currentUserId.orEmpty(), viewModel.chatId, id)
                                reminders = org.json.JSONObject(reminders.toString()).apply { remove(id) }
                                viewModel.saveChatValue("reminders", reminders.toString())
                            }) { Text(stringResource(R.string.chat_done)) }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showReminders = false }) { Text(stringResource(R.string.dismiss)) } })
        }
        if (showPins) {
            Dialog(onDismissRequest = { showPins = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                com.messenger.app.ui.theme.CocoScreenSurface(Modifier.fillMaxSize(), color = colors.background) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        com.messenger.app.ui.components.CocoPageHeader(
                            title = stringResource(R.string.chat_pinned_messages),
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            titleStyle = MaterialTheme.typography.titleMedium,
                            titleColor = colors.primaryText,
                            start = { com.messenger.app.ui.components.CocoBackButton(onClick = { showPins = false }) },
                        )
                        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(pins.toList().asReversed(), key = { it }) { pinnedId ->
                                val message = messages.firstOrNull { it.clientMessageId == pinnedId }
                                val attachment = attachmentsByMessage[pinnedId]
                                Surface(onClick = {
                                    showPins = false
                                    coroutineScope.launch {
                                        val id = viewModel.locateMessage(pinnedId)
                                        if (id != null) { quoteJump = id; highlightedMessageId = id }
                                        else callPermissionError = R.string.chat_original_unavailable
                                    }
                                }, shape = CocoShapes.radiusCard, color = colors.surface, modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        PinnedAttachmentPreview(attachment)
                                        Column(Modifier.weight(1f)) {
                                            Text(pinnedMessageText(message, attachment), color = colors.primaryText, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                            message?.let { Text(formatTime(it.createdAt), style = MaterialTheme.typography.labelSmall, color = colors.secondaryText) }
                                        }
                                        IconButton(onClick = {
                                            pins = pins - pinnedId
                                            viewModel.saveChatValue("pins", pins.joinToString("|"))
                                        }) { Icon(CocoIcons.Close, stringResource(R.string.chat_unpin), tint = colors.secondaryText) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showBulkForward) {
            ForwardChatPickerDialog(chats = forwardableChats, prepareFailed = forwardPrepareFailed, onDismiss = { showBulkForward = false }, onPick = { destination ->
                viewModel.forwardMessages(messages.filter { it.clientMessageId in selectedIds }.map { it.clientMessageId }, destination) { sent -> selectedIds = selectedIds - sent }
                showBulkForward = false
            })
        }
        forwardDialogFor?.let { message ->
            ForwardChatPickerDialog(
                chats = forwardableChats,
                prepareFailed = forwardPrepareFailed,
                onDismiss = { forwardDialogFor = null },
                onPick = { destinationChatId -> viewModel.forwardMessage(message.clientMessageId, destinationChatId); forwardDialogFor = null },
            )
        }

        viewedImageFile?.let { file ->
            val openedAttachment = attachments.firstOrNull { it.localPath == file.path }
            val media = messages.filterNot { it.isDeletedForEveryone }.mapNotNull { attachmentsByMessage[it.clientMessageId] }
                .filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") }
                .map { MediaViewerItem(it.localPath?.let(::File), it.mimeType, it.filename ?: "UNYRI", it.attachmentId, it.thumbnailBase64) }
                .distinctBy { it.id }
            MediaViewerDialog(
                items = if (media.any { it.file == file }) media else listOf(MediaViewerItem(
                    file, viewedMediaMime, openedAttachment?.filename ?: file.name,
                    openedAttachment?.attachmentId ?: file.path, openedAttachment?.thumbnailBase64,
                )),
                initialFile = file, download = viewModel::downloadMediaForViewer,
                onForward = if (media.any { it.file == file }) { { id: String ->
                    messages.firstOrNull { attachmentsByMessage[it.clientMessageId]?.attachmentId == id }?.let { message ->
                        viewedImageFile = null; forwardDialogFor = message
                    }
                } } else null, onDismiss = { viewedImageFile = null },
            )
        }

        cameraSendDraft?.let { draft ->
            AlertDialog(onDismissRequest = {},
                title = { Text(stringResource(R.string.attachment_send_failed)) },
                confirmButton = { TextButton(enabled = !sendingCamera, onClick = {
                    sendCamera(Uri.parse(draft.getString("uri")), draft.optString("source").takeIf { it.isNotBlank() }?.let(::File))
                }) { Text(stringResource(R.string.retry)) } },
                dismissButton = { TextButton(enabled = !sendingCamera, onClick = {
                    coroutineScope.launch {
                        viewModel.saveChatValueNow("cameraSendDraft", null)
                        viewModel.saveChatValueNow("cameraCaptureIntent", null)
                        viewModel.discardStagedMedia(listOf(Uri.parse(draft.getString("uri"))))
                        draft.optString("source").takeIf { it.isNotBlank() }?.let { File(it).delete() }
                        cameraSendDraft = null
                    }
                }) { Text(stringResource(R.string.cancel)) } })
        }

    }
}

@Composable
private fun pinnedMessageText(message: Message?, attachment: Attachment?): String {
    if (message == null) return stringResource(R.string.chat_original_unavailable)
    if (message.isDeletedForEveryone) return stringResource(R.string.deleted_message_preview)
    if (message.status == MessageStatus.DECRYPT_FAILED || message.status == MessageStatus.IDENTITY_CHANGED) return stringResource(R.string.msg_status_decrypt_failed)
    message.caption?.takeIf { it.isNotBlank() }?.let { return it }
    return when {
        attachment?.mediaType == AttachmentMediaType.IMAGE -> stringResource(R.string.photo_preview)
        attachment?.mimeType?.startsWith("video/") == true -> stringResource(R.string.call_caption_video)
        attachment?.mediaType == AttachmentMediaType.VOICE -> stringResource(R.string.voice_message_preview)
        attachment != null -> attachment.filename ?: stringResource(R.string.file_preview)
        else -> message.text
    }
}

@Composable
private fun PinnedAttachmentPreview(attachment: Attachment?) {
    if (attachment == null) return
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, attachment.thumbnailBase64, attachment.localPath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (attachment.mediaType == AttachmentMediaType.IMAGE && attachment.localPath != null) decodeSampledBitmap(attachment.localPath, 128)
                else (attachment.thumbnailBase64 ?: attachment.localPath?.takeIf { attachment.mimeType.startsWith("image/") }?.let {
                    com.messenger.app.data.attachment.ImageProcessor.originalThumbnail(File(it))
                })?.takeIf { it.length <= 24000 }?.let {
                    val bytes = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
                }
            }.getOrNull()
        }
    }
    Box(Modifier.padding(end = 12.dp).size(52.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        val preview = bitmap
        if (preview != null) Image(preview.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(CocoIcons.File, contentDescription = null, tint = LocalCocoColors.current.accent)
    }
}

/** Compact "New messages" affordance (Runtime Correction D): shown only when
 *  the user has scrolled up and new incoming messages arrived since. Tap
 *  smooth-scrolls to the newest. Glass Flow; tween-based so reduced motion is
 *  respected automatically. */
@Composable
private fun NewMessagesPill(count: Int, visible: Boolean, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { it / 2 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 2 },
    ) {
        ChatNavigationButton(count = count, onClick = onClick)
    }
}

@Composable
internal fun ComposerAttachmentTray(photos: List<Uri>, file: Uri?, onRemovePhoto: (Uri) -> Unit, onRemoveFile: () -> Unit,
    files:List<Uri> = emptyList(),onRemoveDocument:(Uri)->Unit = {onRemoveFile()}) {
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalView.current
    val documents=(listOfNotNull(file)+files).distinct()
    val entries = (photos + documents).distinct()
    LazyRow(Modifier.fillMaxWidth().testTag("composer-attachments").padding(horizontal = 9.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(entries, key = { _, uri -> uri.toString() }) { index, uri ->
            val document = uri in documents
            val documentBytes by androidx.compose.runtime.produceState<Long?>(null, uri, document) {
                if (document) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    uri.path?.let { path -> File(path).takeIf { it.isFile }?.length() }
                }
            }
            val type = remember(uri) {
                context.contentResolver.getType(uri) ?: android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.')?.lowercase(java.util.Locale.ROOT))
                    ?: "application/octet-stream"
            }
            val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, uri, document) {
                if (!document) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (type.startsWith("video/")) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try { retriever.setDataSource(context, uri); if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240) else null }
                        catch (_: Exception) { null } finally { retriever.release() }
                    } else if (uri.scheme == "file" && uri.path != null) {
                        com.messenger.app.data.attachment.ImageProcessor.chatPreview(File(requireNotNull(uri.path)), 240)
                    } else decodeSampledBitmap(context, uri, 240)
                }
            }
            val documentHeight = maxOf(72f, 52f * LocalDensity.current.fontScale).dp
            Box(Modifier.width(if (document) 184.dp else 58.dp).height(if (document) documentHeight else 52.dp)
                .clip(RoundedCornerShape(if (document) 12.dp else 8.dp)).background(CapsuleChatStyle.entry)) {
                if (document) Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 44.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(CocoIcons.File, null, tint = CapsuleChatStyle.accent, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(uri.lastPathSegment.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = CapsuleChatStyle.text, fontSize = 12.sp, lineHeight = 15.sp)
                        documentBytes?.let { bytes ->
                            Text(android.text.format.Formatter.formatShortFileSize(context, bytes), maxLines = 1,
                                color = CapsuleChatStyle.muted, fontSize = 10.sp)
                        }
                    }
                } else {
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (type.startsWith("video/")) Icon(CocoIcons.Play, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(22.dp))
                }
                IconButton(onClick = { CocoHaptics.select(haptics); if (document) onRemoveDocument(uri) else onRemovePhoto(uri) },
                    modifier = Modifier.align(Alignment.TopEnd).size(if (document) 48.dp else 36.dp).testTag("composer-remove-$index")) {
                    Icon(CocoIcons.Close, stringResource(R.string.gallery_deselect), tint = Color.White,
                        modifier = Modifier.size(22.dp).background(Color.Black.copy(alpha = .65f), CircleShape).padding(5.dp))
                }
            }
        }
    }
}

@Composable
internal fun ComposerBar(
    editing: Boolean = false,
    attachmentCount: Int = 0,
    attachmentsReady: Boolean = true,
    attachments: (@Composable () -> Unit)? = null,
    replyHeader: (@Composable () -> Unit)? = null,
    keyboardVisible: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onHideKeyboard: () -> Unit,
    input: String,
    submitting: Boolean,
    onInputChange: (String) -> Unit,
    isRecording: Boolean,
    onAttach: () -> Unit,
    onAttachCamera: () -> Unit = {},
    onAttachFiles: () -> Unit = {},
    attachmentActionsEnabled: Boolean = true,
    onMicToggle: () -> Unit,
    recordingContent: @Composable () -> Unit = {},
    onVoiceHold: () -> Unit = {},
    onVoiceRelease: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    onVoiceLock: () -> Unit = {},
    onVoiceProgress: (Float, Float) -> Unit = { _, _ -> },
    onSend: () -> Unit,
) {

    val colors = LocalCocoColors.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val composerSurface = com.messenger.app.ui.components.rememberCocoComposerSurfaceStyle(
        savedField = CapsuleChatChrome.field, background = CapsuleChatStyle.background)
    var emojiOpen by remember { mutableStateOf(false) }
    var attachmentMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(attachmentActionsEnabled, isRecording) {
        if (!attachmentActionsEnabled || isRecording) attachmentMenuOpen = false
    }
    var composerTop by remember { mutableStateOf<Float?>(null) }
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(input, androidx.compose.ui.text.TextRange(input.length))) }
    if (field.text != input) field = field.copy(text = input, selection = androidx.compose.ui.text.TextRange(input.length), composition = null)
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        .onGloballyPositioned { composerTop = it.localToScreen(androidx.compose.ui.geometry.Offset.Zero).y }
        .voiceHoldGesture(input.isBlank() && !isRecording && !editing && attachmentCount == 0,
            onVoiceHold, onVoiceRelease, onVoiceCancel, onVoiceLock, onVoiceProgress, hitWidth = 48.dp)) {
        Box(Modifier.voicePhaseReveal(isRecording)) {
            if (isRecording) recordingContent()
            else Box(Modifier.fillMaxWidth().testTag("composer-input-field")) {
                // Keep the native popup anchored above the entire field, not the Plus glyph.
                com.messenger.app.ui.components.CocoComposerSurface(savedField = CapsuleChatChrome.field,
                    background = CapsuleChatStyle.background, surfaceStyle = composerSurface) {
                Column(Modifier.fillMaxWidth().animateContentSize(tween(160))) {
                    attachments?.invoke()
                    replyHeader?.invoke()
                    Row(Modifier.heightIn(min = 48.dp).testTag("composer-input-row"), verticalAlignment = Alignment.Bottom) {
                        ComposerAttachmentToggle(enabled = attachmentActionsEnabled) {
                            emojiOpen = false
                            attachmentMenuOpen = true
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = field, onValueChange = { field = it; onInputChange(it.text) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp).onFocusChanged { onFocusChanged(it.isFocused) },
                            minLines = 1, maxLines = 6,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = CapsuleChatChrome.bottomInk,
                                fontSize = CapsuleChatStyle.entryFont.sp, lineHeight = 22.4.sp,
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(CapsuleChatStyle.accent),
                            decorationBox = { editor ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (field.text.isEmpty()) Text(stringResource(R.string.message_placeholder), color = CapsuleChatStyle.muted, fontSize = CapsuleChatStyle.entryFont.sp)
                                    editor()
                                }
                            },
                        )
                        Box {
                            com.messenger.app.ui.components.CocoComposerIconButton(ChatControls.smile, stringResource(R.string.chat_emoji),
                                tint = CapsuleChatChrome.bottomInk, modifier = Modifier.testTag("composer-emoji")) {
                                CocoHaptics.tap(hapticView); attachmentMenuOpen = false; onHideKeyboard(); emojiOpen = !emojiOpen
                            }
                            if (emojiOpen) EmojiPickerSheet(onDismiss = { emojiOpen = false }, initiallyExpanded = false, bottomAnchorY = composerTop) { emoji ->
                                val start = field.selection.min.coerceIn(0, input.length)
                                val end = field.selection.max.coerceIn(start, input.length)
                                val text = input.replaceRange(start, end, emoji)
                                field = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(start + emoji.length))
                                onInputChange(text)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (attachmentCount > 0) Text("$attachmentCount", style = MaterialTheme.typography.labelMedium,
                                color = CapsuleChatChrome.bottomInk, maxLines = 1,
                                modifier = Modifier.wrapContentWidth(unbounded = true).testTag("composer-attachment-count"))
                            // Keep the unchanged 48dp Send/Mic target last; the count never overlays its glyph.
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                if (input.isBlank() && !editing && attachmentCount == 0) {
                                    com.messenger.app.ui.components.CocoComposerIconButton(ChatControls.mic, stringResource(R.string.voice_record),
                                        tint = CapsuleChatChrome.bottomInk,
                                        modifier = Modifier.testTag("composer-record"), onClick = onMicToggle)
                                } else {
                                    com.messenger.app.ui.components.CocoComposerIconButton(ChatControls.send, stringResource(R.string.send),
                                        tint = CapsuleChatChrome.micInk, fill = CapsuleChatChrome.micColor,
                                        enabled = !submitting && (if (attachmentCount > 0) attachmentsReady else input.isNotBlank()),
                                        modifier = Modifier.testTag("composer-send"), onClick = onSend)
                                }
                            }
                        }
                    }
                }
                }
                ComposerAttachmentMenu(expanded = attachmentMenuOpen, onDismiss = { attachmentMenuOpen = false },
                    enabled = attachmentActionsEnabled, surfaceStyle = composerSurface,
                    onCamera = { attachmentMenuOpen = false; onAttachCamera() },
                    onGallery = { attachmentMenuOpen = false; onAttach() },
                    onFiles = { attachmentMenuOpen = false; onAttachFiles() })
            }
        }
    }
}

/** Native 48dp target; its popup belongs to the entire field. */
@Composable
internal fun ComposerAttachmentToggle(enabled: Boolean = true, onClick: () -> Unit) {
    val hapticView = androidx.compose.ui.platform.LocalView.current
    IconButton(onClick = { CocoHaptics.tap(hapticView); onClick() }, enabled = enabled,
        modifier = Modifier.size(48.dp).testTag("composer-attachment-toggle")) {
        Icon(CocoIcons.Plus, stringResource(R.string.chat_attach_title),
            tint = CapsuleChatChrome.bottomInk.copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(20.dp))
    }
}

/** Standard intrinsic-size menu. Parent Box is the entire themed input field. */
@Composable
internal fun ComposerAttachmentMenu(
    surfaceStyle: com.messenger.app.ui.components.CocoComposerSurfaceStyle,
    expanded: Boolean,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
) {
    val maximumWidth = minOf(320.dp, (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(1).dp)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = maximumWidth).testTag("composer-attachment-menu"),
        // Keep native menu focus/Back/accessibility while leaving the field as the IME target.
        // This is the platform IME-independent popup policy used by Material exposed menus.
        properties = androidx.compose.ui.window.PopupProperties(flags =
            android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM),
        shape = RoundedCornerShape(28.dp), containerColor = surfaceStyle.fill,
        tonalElevation = 0.dp, shadowElevation = 8.dp,
        border = surfaceStyle.border) {
        ComposerAttachmentMenuItem(CocoIcons.Camera, stringResource(R.string.attach_camera),
            "composer-attachment-camera", enabled, onCamera)
        ComposerAttachmentMenuItem(CocoIcons.Photo, stringResource(R.string.gallery_media),
            "composer-attachment-photos", enabled, onGallery)
        ComposerAttachmentMenuItem(CocoIcons.File, stringResource(R.string.media_files),
            "composer-attachment-files", enabled, onFiles)
    }
}

@Composable
private fun ComposerAttachmentMenuItem(icon: ImageVector, label: String, tag: String,
    enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    DropdownMenuItem(text = { Text(label, color = colors.primaryText, style = MaterialTheme.typography.bodyLarge) },
        onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = 52.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        leadingIcon = { Icon(icon, null, tint = colors.primaryText, modifier = Modifier.size(22.dp)) })
}

/** Recording state (Phase 5 §11–§14): replaces the composer with a compact
 *  glass strip — danger dot, 1 Hz timer, Cancel and Send. The recorder itself
 *  still owns mic/release semantics; this is presentation only. */
@Composable
private fun VoiceRecordingMeter(recorder: VoiceRecorder, modifier: Modifier) {
    var levels by remember(recorder) { mutableStateOf(List(12) { 0f }) }
    LaunchedEffect(recorder) {
        while (true) { delay(100); levels = levels.drop(1) + recorder.level() }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val step = size.width / levels.size
        levels.forEachIndexed { i, level ->
            val h = (size.height * kotlin.math.sqrt(level.coerceIn(0f, 1f))).coerceAtLeast(2.dp.toPx())
            drawLine(CapsuleChatStyle.accent, androidx.compose.ui.geometry.Offset((i + .5f) * step, (size.height - h) / 2), androidx.compose.ui.geometry.Offset((i + .5f) * step, (size.height + h) / 2), 2.dp.toPx())
        }
    }
}

@Composable
private fun RecordingBanner(seconds: Long, paused: Boolean, onPause: () -> Unit, onCancel: () -> Unit, onSend: () -> Unit, locked: Boolean = true, recorder: VoiceRecorder, drag: androidx.compose.ui.geometry.Offset) {

    val colors = LocalCocoColors.current
    if (!locked) {
        val offset = with(LocalDensity.current) { androidx.compose.ui.unit.IntOffset(0, -68.dp.roundToPx()) }
        androidx.compose.ui.window.Popup(alignment = Alignment.TopEnd, offset = offset,
            properties = androidx.compose.ui.window.PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Box(Modifier.size(32.dp, 52.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF363636)), contentAlignment = Alignment.Center) {
                Icon(ChatControls.lock, stringResource(R.string.voice_slide_lock), tint = CapsuleChatStyle.accent, modifier = Modifier.size(17.dp))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(CapsuleChatStyle.entry).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(7.dp).background(if (paused) colors.mutedText else colors.primaryText, CircleShape))
            Text(VoiceFormat.durationText(seconds * 1000), fontSize = 14.sp, color = colors.primaryText,
                modifier = Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.voice_pause_resume), onClick = onPause))
            VoiceRecordingMeter(recorder, Modifier.weight(1f).height(18.dp))
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp, 40.dp)) {
                Icon(CocoIcons.Delete, stringResource(R.string.cancel), tint = CapsuleChatStyle.accent, modifier = Modifier.size(19.dp))
            }
        }
        CapsuleComposerButton(ChatControls.send, stringResource(R.string.voice_done), filled = true, onClick = onSend)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(
    backdrop: com.messenger.app.ui.theme.GlassBackdrop,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onVideo: () -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit,
    onSelected: (List<Uri>) -> Unit,
    selection: List<Uri>, onSelection: (List<Uri>) -> Unit,
    caption: String, onCaptionChange: (String) -> Unit,
    selectionLimit: Int = 10,
) {
    // Keep one viewport owner: these insets belong to the Activity composition
    // before entering Dialog. A fitted wrap-content dialog may report zero IME
    // distance even while its keyboard is visible (observed on Samsung).
    val hostView = androidx.compose.ui.platform.LocalView.current
    val hostDensity = LocalDensity.current
    val hostConfiguration = LocalConfiguration.current
    val hostActivity = remember(hostView) {
        var context = hostView.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
        context as? android.app.Activity
    }
    val hostWindowHeightPx = remember(hostActivity, hostConfiguration.screenWidthDp, hostConfiguration.screenHeightDp) {
        if (Build.VERSION.SDK_INT >= 30) hostActivity?.windowManager?.currentWindowMetrics?.bounds?.height()?.toFloat() else null
    }
    val hostSafeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val hostSafeTopPx = hostSafeInsets.getTop(hostDensity)
    val hostSafeBottomPx = hostSafeInsets.getBottom(hostDensity)
    val hostImeBottomPx = WindowInsets.ime.getBottom(hostDensity)
    // WindowMetrics includes system bars regardless of target-SDK configuration
    // semantics. Pre-R screenHeightDp already excludes them, so do not subtract
    // those bars a second time in the fallback.
    val normalUsablePx = hostWindowHeightPx?.let { (it - hostSafeTopPx - hostSafeBottomPx).coerceAtLeast(0f) }
        ?: with(hostDensity) { hostConfiguration.screenHeightDp.dp.toPx() }
    val visibleUsablePx = (normalUsablePx - (hostImeBottomPx - hostSafeBottomPx).coerceAtLeast(0)).coerceAtLeast(0f)
    val requestedHeight = with(hostDensity) { AttachmentPickerStyle.panelHeight(normalUsablePx.toDp().value).dp }
    val visibleHeight = with(hostDensity) { visibleUsablePx.toDp() }

    // Native window gravity owns position. Bound the content height to the
    // visible viewport without introducing an offset or a second inset padding.
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        val dialogView = androidx.compose.ui.platform.LocalView.current
        var cameraReady by remember { mutableStateOf(false) }
        androidx.compose.runtime.SideEffect {
            // This is the dialog's own window, never the underlying Activity.
            (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                if (window.attributes.gravity != android.view.Gravity.BOTTOM) window.setGravity(android.view.Gravity.BOTTOM)
                if (window.attributes.softInputMode != android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
                    window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            }
        }
        LaunchedEffect(dialogView) {
            androidx.compose.runtime.withFrameNanos { }
            cameraReady = true
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().testTag("attachment-picker-dialog")) {
            val panelHeight = minOf(requestedHeight, visibleHeight, maxHeight)
            Surface(Modifier.fillMaxWidth().height(panelHeight),
                shape = RoundedCornerShape(topStart = AttachmentPickerStyle.radius.dp, topEnd = AttachmentPickerStyle.radius.dp),
                color = Color(AttachmentPickerStyle.background).copy(alpha = AttachmentPickerStyle.opacity / 100f),
                contentColor = Color(AttachmentPickerStyle.foreground)) {
                AttachmentGallery(onCamera, onVideo, onGallery, onFile, onSelected, selection, onSelection, cameraReady, selectionLimit,
                    caption, onCaptionChange, onDismiss)
            }
        }
    }
}

@Composable
private fun AttachRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val colors = LocalCocoColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { CocoHaptics.tap(hapticView); onClick() }.padding(horizontal = 4.dp, vertical = 12.dp),
    ) {
        Surface(shape = CircleShape, color = colors.accent.copy(alpha = 0.14f)) {
            Icon(imageVector = icon, contentDescription = null, tint = colors.accent, modifier = Modifier.padding(14.dp).size(26.dp))
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = colors.primaryText, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun MessageActionsSheet(
    anchor: androidx.compose.ui.geometry.Rect,
    preview: android.graphics.Bitmap?,
    message: Message,
    isOwnMessage: Boolean,
    hasAttachment: Boolean,
    isPinned: Boolean,
    isCallRecord: Boolean = false,
    actionGroupCount: Int = 1,
    actionGroupHasRemote: Boolean = message.serverId != null,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onSelect: () -> Unit,
    onPin: () -> Unit,
    onRemind: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onSave: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onReact: (String) -> Unit,
    onDeleteSingleForMe: (() -> Unit)? = null,
    onDeleteSingleForEveryone: (() -> Unit)? = null,
) {
    val colors = LocalCocoColors.current
    var showMore by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteSingle by remember { mutableStateOf(false) }
    var deleteEveryone by remember { mutableStateOf<Boolean?>(null) }
    val menuScroll = remember(showMore, deleting, deleteEveryone) { androidx.compose.foundation.ScrollState(0) }
    var extendedReactions by remember { mutableStateOf(false) }
    var showFullText by remember { mutableStateOf(false) }
    val rawActionText = if (hasAttachment) message.caption.orEmpty() else message.text
    val actionText = if (hasAttachment && rawActionText.isBlank()) ""
        else cocoMessagePresentationText(rawActionText, inline = true)
    val fullTextDisplay = remember(rawActionText) { com.messenger.app.data.message.MessageTextPolicy.presentationResult(rawActionText) }
    val canReadFullText = !isCallRecord && !message.isDeletedForEveryone &&
        fullTextDisplay.truncated &&
        fullTextDisplay.kind == com.messenger.app.data.message.MessageTextPolicy.PresentationKind.TEXT
    if (showFullText) {
        FullMessageTextDialog(rawActionText, onDismiss)
        return
    }
    // Pending/failed text can be edited too: the repository either updates the
    // not-yet-sent payload or stores a durable intent for after serverId.
    val canEdit = isOwnMessage && !hasAttachment && message.conferenceInvitation == null && message.directFile == null && !message.isDeletedForEveryone &&
        (message.serverId != null || message.status == MessageStatus.PENDING || message.status == MessageStatus.FAILED)
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.activity.compose.BackHandler(enabled = deleting || showMore) {
            if (deleting) { deleteEveryone = null; deleting = false }
            else showMore = false
        }
        if (extendedReactions) EmojiPickerSheet(onDismiss = { extendedReactions = false }, embedded = true) { emoji ->
            extendedReactions = false; onReact(emoji); onDismiss()
        }
        ChatDialogBlur()
        val dialogView = androidx.compose.ui.platform.LocalView.current
        var overlayOrigin by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
        if (!extendedReactions) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { overlayOrigin = it.localToScreen(androidx.compose.ui.geometry.Offset.Zero) }) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)).clickable(onClick = onDismiss))
            val safeTop = with(density) { WindowInsets.systemBars.getTop(this).toDp() } + 8.dp
            val safeBottom = maxOf(with(density) { WindowInsets.systemBars.getBottom(this).toDp() }, with(density) { WindowInsets.ime.getBottom(this).toDp() }, 48.dp) + 8.dp
            val availableHeight = (maxHeight - safeTop - safeBottom).coerceAtLeast(0.dp)
            val menuWidth = minOf(241.dp, maxWidth - 32.dp)
            val reactionWidth = minOf(241.dp, maxWidth - 32.dp)
            val compactReactions = availableHeight < 420.dp
            val reactionCellHeight = maxOf(32.dp, with(density) { 23.sp.toDp() } + 8.dp)
            val reactionColumns = if (reactionWidth >= maxOf(204.dp, reactionCellHeight * 6)) 6 else 3
            val reactionRows = (QUICK_REACTIONS.size + reactionColumns - 1) / reactionColumns
            val canReact = !isCallRecord && !deleting && message.serverId != null && !message.isDeletedForEveryone
            val requestedReactionHeight = if (canReact) (if (compactReactions) maxOf(48.dp, reactionCellHeight) else reactionCellHeight * reactionRows) + 12.dp else 0.dp
            val rowCount = if (deleting) (if (!deleteSingle && actionGroupCount > 1) 7 else 6) else if (isCallRecord) 3 else if (showMore) 2 + (if (!message.isDeletedForEveryone) 2 else 0) + (if (onDeleteSingleForMe != null) 1 else 0) else 3 + (if (!message.isDeletedForEveryone) 1 else 0) +
                (if (!message.isDeletedForEveryone && rawActionText.isNotBlank()) 1 else 0) +
                (if (!message.isDeletedForEveryone && message.status != MessageStatus.PENDING) 1 else 0) + (if (canEdit) 1 else 0) +
                (if (canReadFullText) 1 else 0)
            var budget = messageMenuBudget(availableHeight.value, rowCount, requestedReactionHeight.value, preview != null || actionText.isNotBlank())
            if (preview == null && budget.previewLimit < reactionCellHeight.value + 12f) {
                budget = messageMenuBudget(availableHeight.value, rowCount, requestedReactionHeight.value, false)
            }
            val reactionHeight = if (budget.showInlineReactions) requestedReactionHeight else 0.dp
            val menuHeight = budget.menuHeight.dp
            val previewLimit = budget.previewLimit.dp
            val sourceWidth = with(density) { anchor.width.toDp() }.coerceAtLeast(1.dp)
            val sourceHeight = with(density) { anchor.height.toDp() }.coerceAtLeast(1.dp)
            val previewScale = minOf(1f, (maxWidth - 32.dp) / sourceWidth, previewLimit / sourceHeight)
            val previewWidth = if (preview != null) sourceWidth * previewScale else menuWidth
            val previewHeight = if (previewLimit <= 0.dp) 0.dp else if (preview != null) sourceHeight * previewScale else minOf(previewLimit, reactionCellHeight + 12.dp)
            val groupWidth = maxOf(menuWidth, previewWidth, if (reactionHeight > 0.dp) reactionWidth else 0.dp)
            val groupHeight = reactionHeight + previewHeight + menuHeight + budget.fixedHeight.dp
            val desiredX = with(density) { if (isOwnMessage) (anchor.right - (overlayOrigin?.x ?: 0f)).toDp() - groupWidth else (anchor.left - (overlayOrigin?.x ?: 0f)).toDp() }
            val x = desiredX.coerceIn(16.dp, (maxWidth - groupWidth - 16.dp).coerceAtLeast(16.dp))
            val y = (with(density) { (anchor.top - (overlayOrigin?.y ?: 0f)).toDp() } - reactionHeight - 8.dp)
                .coerceIn(safeTop, (maxHeight - safeBottom - groupHeight).coerceAtLeast(safeTop))
            Column(Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val screenBottom = dialogView.resources.displayMetrics.heightPixels - (overlayOrigin?.y ?: 0f).toInt()
                val bottom = minOf(constraints.maxHeight, screenBottom) - safeBottom.roundToPx()
                val top = safeTop.roundToPx()
                val actualY = y.roundToPx().coerceIn(top, (bottom - placeable.height).coerceAtLeast(top))
                layout(placeable.width, placeable.height) { placeable.placeRelative(x.roundToPx(), actualY) }
            }.width(groupWidth).heightIn(max = availableHeight).testTag("message-menu-group").alpha(if (overlayOrigin == null) 0f else 1f)
, horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start) {
                if (budget.showClose) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(33.dp).background(Color(0xFF444444), CircleShape)) {
                        Icon(CocoIcons.Close, stringResource(R.string.cancel), tint = colors.primaryText, modifier = Modifier.size(17.dp))
                    }
                }
                if (reactionHeight > 0.dp) {
                    if (compactReactions) {
                        Row(Modifier.width(reactionWidth).height(reactionHeight).clip(RoundedCornerShape(22.dp)).background(CapsuleChatStyle.entry).border(1.dp, CapsuleChatStyle.border, RoundedCornerShape(22.dp)).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LazyRow(Modifier.weight(1f)) {
                                items(QUICK_REACTIONS.filter { it != "＋" }) { emoji ->
                                    Box(Modifier.size(maxOf(48.dp, reactionCellHeight)).semantics { selected = message.reactions.any { it.emoji == emoji && it.reactedByMe } }.combinedClickable(onClickLabel = stringResource(R.string.react_with, emoji), onClick = {
                                        CocoHaptics.select(dialogView); onReact(emoji); onDismiss()
                                    }), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 23.sp) }
                                }
                            }
                            IconButton(onClick = { extendedReactions = true }, modifier = Modifier.size(maxOf(48.dp, reactionCellHeight))) {
                                Icon(ChatControls.smile, stringResource(R.string.more_options), tint = CapsuleChatStyle.accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                    val cellWidth = reactionWidth / reactionColumns
                    Column(Modifier.width(reactionWidth).clip(RoundedCornerShape(22.dp)).background(CapsuleChatStyle.entry).border(1.dp, CapsuleChatStyle.border, RoundedCornerShape(22.dp)).padding(vertical = 6.dp).zIndex(1f)) {
                        QUICK_REACTIONS.chunked(reactionColumns).forEach { emojis ->
                            Row(Modifier.height(reactionCellHeight), verticalAlignment = Alignment.CenterVertically) {
                                emojis.forEach { emoji ->
                                    val mine = message.reactions.any { it.emoji == emoji && it.reactedByMe }
                                    Box(Modifier.width(cellWidth).height(reactionCellHeight)
                                        .semantics { selected = mine }
                                        .combinedClickable(onClickLabel = if (emoji == "＋") stringResource(R.string.more_options) else stringResource(R.string.react_with, emoji), onClick = {
                                            CocoHaptics.select(dialogView)
                                            if (emoji == "＋") extendedReactions = true
                                            else { onReact(emoji); onDismiss() }
                                        }), contentAlignment = Alignment.Center) {
                                        if (mine) Box(Modifier.size(29.dp).background(colors.accentSoft.copy(alpha = 0.20f), CircleShape))
                                        if (emoji == "＋") Icon(ChatControls.smile, stringResource(R.string.more_options), tint = CapsuleChatStyle.accent, modifier = Modifier.size(18.dp))
                                        else Text(emoji, fontSize = 23.sp)
                                    }
                                }
                            }
                        }
                    }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (previewHeight > 0.dp && preview != null) {
                    Image(preview.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds,
                        modifier = Modifier.size(previewWidth, previewHeight).testTag("message-menu-preview").clip(CocoShapes.radiusBubble))
                } else if (previewHeight > 0.dp && actionText.isNotBlank()) {
                    Surface(shape = CocoShapes.radiusBubble, color = if (isOwnMessage) colors.outgoingBubble else colors.incomingBubble) {
                        Box(Modifier.width(menuWidth).height(previewHeight).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                            Text(actionText, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isOwnMessage) colors.onOutgoingBubble else colors.primaryText)
                        }
                    }
                }
                Spacer(Modifier.height(minOf(8.dp, availableHeight)))
                Surface(shape = RoundedCornerShape(18.dp), color = CapsuleChatStyle.entry, contentColor = colors.primaryText, modifier = Modifier.width(menuWidth).border(1.dp, CapsuleChatStyle.border, RoundedCornerShape(18.dp))) {
                    Column(Modifier.heightIn(max = menuHeight).testTag("message-menu-actions").verticalScroll(menuScroll).padding(vertical = 4.dp)) {
                        if (canReact && !budget.showInlineReactions) ActionRow(stringResource(R.string.message_reaction_action), ChatControls.smile) { extendedReactions = true }
                        if (deleting) {
                            ActionRow(stringResource(R.string.back), CocoIcons.ArrowBack) {
                                deleteEveryone = null; deleting = false
                            }
                            val count = if (deleteSingle) 1 else actionGroupCount
                            Text(stringResource(if (isCallRecord) R.string.call_history_delete else if (count > 1) R.string.chat_delete_album else R.string.delete_message), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            if (count > 1) Text(stringResource(R.string.media_selected_count, count), color = colors.secondaryText, modifier = Modifier.padding(horizontal = 16.dp))
                            val scope = deleteEveryone == true
                            if ((isOwnMessage || isCallRecord) && (if (deleteSingle) message.serverId != null else actionGroupHasRemote)) {
                                Row(Modifier.fillMaxWidth().toggleable(value = scope, role = Role.Checkbox, onValueChange = { deleteEveryone = it }).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(checked = scope, onCheckedChange = null)
                                    Text(stringResource(R.string.delete_also_peer), modifier = Modifier.padding(start = 8.dp))
                                }
                            } else Text(stringResource(R.string.delete_only_you), modifier = Modifier.padding(16.dp))
                            ActionRow(stringResource(R.string.delete), CocoIcons.Delete, destructive = true) {
                                if (deleteSingle) {
                                    if (scope) onDeleteSingleForEveryone?.invoke() else onDeleteSingleForMe?.invoke()
                                } else if (scope) onDeleteForEveryone() else onDeleteForMe()
                            }
                            ActionRow(stringResource(R.string.cancel), CocoIcons.Close, onClick = onDismiss)
                        } else if (isCallRecord) {
                            ActionRow(stringResource(R.string.chat_select), CocoIcons.Select, onClick = onSelect)
                            ActionRow(stringResource(R.string.delete), CocoIcons.Delete, destructive = true) { deleting = true }
                            ActionRow(stringResource(R.string.cancel), CocoIcons.Close, onClick = onDismiss)
                        } else if (!showMore) {
                        if (!message.isDeletedForEveryone) ActionRow(stringResource(R.string.reply), CocoIcons.ArrowBack) { onReply() }
                        if (!message.isDeletedForEveryone && rawActionText.isNotBlank()) ActionRow(stringResource(R.string.copy), CocoIcons.Copy) { onCopy() }
                        if (canReadFullText) ActionRow(stringResource(R.string.coco_text_full_action), CocoIcons.File) { showFullText = true }
                        if (!message.isDeletedForEveryone && message.directFile == null && message.status != MessageStatus.PENDING) ActionRow(stringResource(R.string.forward), CocoIcons.Send) { onForward() }
                        if (canEdit) ActionRow(stringResource(R.string.edit), CocoIcons.File) { onEdit() }
                        ActionRow(stringResource(R.string.chat_select), CocoIcons.Select) { onSelect() }
                        ActionRow(stringResource(R.string.more_options)) { showMore = true }
                        ActionRow(if (actionGroupCount > 1) stringResource(R.string.chat_delete_album) else if (isOwnMessage && message.serverId == null) stringResource(R.string.cancel_sending) else stringResource(R.string.delete), CocoIcons.Delete, destructive = true) {
                            deleteSingle = false
                            if (actionGroupCount == 1 && isOwnMessage && message.serverId == null) onDeleteForMe() else deleting = true
                        }
                        } else {
                            ActionRow(stringResource(R.string.back), CocoIcons.ArrowBack) { showMore = false }
                            if (!message.isDeletedForEveryone) ActionRow(stringResource(if (isPinned) R.string.chat_unpin else R.string.chat_pin_action), CocoIcons.Pin) { onPin() }
                            ActionRow(stringResource(R.string.chat_remind)) { onRemind() }
                            if (!message.isDeletedForEveryone && message.directFile == null) ActionRow(stringResource(R.string.save_to_saved), CocoIcons.Bookmark) { onSave() }
                            if (onDeleteSingleForMe != null) ActionRow(stringResource(R.string.chat_delete_album_item), CocoIcons.Delete, destructive = true) {
                                deleteSingle = true; deleteEveryone = null; deleting = true
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, icon: ImageVector = CocoIcons.MoreVert, destructive: Boolean = false, onClick: () -> Unit) {
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { CocoHaptics.tap(hapticView); onClick() }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (destructive) colors.danger else colors.secondaryText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, style = MaterialTheme.typography.bodyMedium, color = if (destructive) colors.danger else colors.primaryText)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardChatPickerDialog(chats: List<ChatSummary>, prepareFailed: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val colors = LocalCocoColors.current
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = chats.firstOrNull { it.chatId == selectedId }
    val savedTitle = stringResource(R.string.saved_messages)
    val fallbackTitle = stringResource(R.string.chat_default_title)
    fun title(chat: ChatSummary) = if (chat.type == "saved") savedTitle else chat.otherUser?.displayName ?: chat.otherUser?.username ?: fallbackTitle
    val filtered = chats.filter { query.isBlank() || (title(it) + " " + it.otherUser?.username.orEmpty()).contains(query, ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface, contentColor = colors.primaryText) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding().padding(horizontal = 16.dp)) {
            com.messenger.app.ui.components.CocoPageHeader(
                title = stringResource(R.string.forward_to),
                modifier = Modifier.fillMaxWidth(),
                titleStyle = MaterialTheme.typography.titleLarge,
                titleColor = colors.primaryText,
                end = { IconButton(onClick = onDismiss) { Icon(CocoIcons.Close, stringResource(R.string.cancel), tint = colors.primaryText) } },
            )
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.chat_find_destination)) })
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false).padding(vertical = 8.dp)) {
                if (prepareFailed) item {
                    Text(
                        stringResource(R.string.forward_saved_prepare_failed),
                        Modifier.padding(16.dp),
                        color = colors.danger,
                    )
                }
                if (filtered.isEmpty() && !prepareFailed) item { Text(stringResource(if (query.isBlank()) R.string.forward_no_chats else R.string.search_no_results), Modifier.padding(16.dp), color = colors.secondaryText) }
                items(filtered, key = { it.chatId }) { chat ->
                    val title = title(chat)
                    Row(Modifier.fillMaxWidth().clickable { selectedId = chat.chatId }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        com.messenger.app.ui.components.UserAvatar(userId = chat.otherUser?.id.orEmpty(), avatarVersion = chat.otherUser?.avatarVersion ?: 0, hasAvatar = chat.otherUser?.hasAvatar == true, name = title, color = colors.accent, contentColor = colors.onAccent, size = 44.dp)
                        Text(title, Modifier.weight(1f).padding(start = 12.dp), color = colors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RadioButton(selected = selectedId == chat.chatId, onClick = null)
                    }
                }
            }
            Button(onClick = { selected?.let { onPick(it.chatId) } }, enabled = selected != null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(stringResource(R.string.forward))
                selected?.let { Text(" · " + title(it), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

private fun openFile(context: android.content.Context, file: File, mimeType: String) {
    com.messenger.app.data.local.MediaCacheUse.retainExternal(file)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
internal fun MessageBubble(
    modifier: Modifier = Modifier,
    onBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    message: Message,
    scheduledAt: Long? = null,
    attachment: Attachment?,
    isOwnMessage: Boolean,
    replyAuthor: String = "",
    replyAttachment: Attachment? = null,
    firstInGroup: Boolean = true,
    playingAttachmentId: String?,
    playingPositionMs: Int = 0,
    highlighted: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onRetry: () -> Unit,
    onCancelPending: () -> Unit = {},
    onDownload: (Attachment) -> Unit,
    onTogglePlay: (Attachment, File) -> Unit,
    onOpenFile: (File, String) -> Unit,
    onOpenImage: (File) -> Unit,
    onLongPress: () -> Unit,
    onSelectionTap: () -> Unit,
    onToggleReaction: (String) -> Unit,
    onQuickReaction: (() -> Unit)?,
    onScrollToReply: (String) -> Unit,
) {
    val appearance = com.messenger.app.ui.theme.LocalChatAppearanceStyle.current
    val reactionTouch = remember(message.clientMessageId) { ReactionTouchGuard() }
    val latestSelectionTap = androidx.compose.runtime.rememberUpdatedState(onSelectionTap)
    val scaledEmoji = with(LocalDensity.current) { CapsuleChatStyle.emojiSize.sp.toDp() }
    val scaledReactionLine = with(LocalDensity.current) { CapsuleChatStyle.reactionHeight.sp.toDp() }
    val reactionHeight = maxOf(CapsuleChatStyle.reactionHeight.dp, scaledEmoji + 6.dp, scaledReactionLine)
    val reactionWidth = maxOf(CapsuleChatStyle.reactionWidth.dp, scaledEmoji + 10.dp)
    val guardedQuickReaction: (() -> Unit)? = onQuickReaction?.let { send -> action@{
        if (reactionTouch.touchOnReaction) return@action
        send()
    } }
    val chatTextSize = com.messenger.app.ui.theme.LocalChatTextSize.current
    val messageTextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = chatTextSize.sp,
        fontFamily = appearance.messageFontFamily,
        lineHeight = (chatTextSize * appearance.lineHeight).sp,
        fontWeight = if (appearance.mediumText) FontWeight.Medium else FontWeight.Normal,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
    val colors = LocalCocoColors.current
    val alignment = if (isOwnMessage) Alignment.CenterEnd else Alignment.CenterStart
    val contentColor = if (isOwnMessage) CapsuleChatStyle.outText else CapsuleChatStyle.text
    val metaColor = CapsuleChatStyle.muted
    val inlineMetadata = CapsuleChatStyle.metadataMode == "inline"
    // Photo/video geometry stays separate from the approved text, voice and file shapes.
    val edgeToEdgeMedia = !message.isDeletedForEveryone &&
        (attachment?.mediaType == AttachmentMediaType.IMAGE || attachment?.mimeType?.startsWith("video/") == true)
    val documentCard = !message.isDeletedForEveryone && attachment?.mediaType == AttachmentMediaType.FILE && !edgeToEdgeMedia
    val voiceCard = !message.isDeletedForEveryone && attachment?.mediaType == AttachmentMediaType.VOICE
    val contentLink = remember(message.caption, message.text) { chatLinkAddress(message.caption ?: message.text) }
    val legacyConferenceInvite = remember(message.text, message.conferenceInvitation) {
        if (message.conferenceInvitation == null && message.text.trimStart().startsWith("coco://")) runCatching { com.messenger.app.conference.ConferenceInvite.parse(message.text) }.getOrNull() else null
    }
    val adaptiveTextMetadata = CapsuleChatStyle.metadataMode == "adaptive" && CapsuleChatStyle.showTimes &&
        !LocalChatAlbumCell.current && attachment == null && !message.isDeletedForEveryone &&
        message.status != MessageStatus.DECRYPT_FAILED && message.status != MessageStatus.IDENTITY_CHANGED &&
        message.replyPreview == null && !message.forwarded && contentLink == null && message.conferenceInvitation == null && legacyConferenceInvite == null
    val compactFileMetadata = inlineMetadata && documentCard && !LocalChatAlbumCell.current && CapsuleChatStyle.showTimes && message.caption.isNullOrBlank()
    val contentShape = when {
        voiceCard -> appearance.voiceShape(isOwnMessage)
        documentCard -> appearance.fileShape(isOwnMessage)
        else -> appearance.bodyShape(isOwnMessage)
    }
    val contentBackground = when {
        // Incoming/outgoing surfaces may be fully transparent; highlighting still needs a visible surface.
        highlighted -> CapsuleChatStyle.entry
        voiceCard -> appearance.voiceBackground(isOwnMessage)
        documentCard -> appearance.fileBackground(isOwnMessage)
        isOwnMessage -> appearance.outgoing
        else -> appearance.incoming
    }
    val textCard = attachment == null
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val separateContent = true
    val shape = CapsuleChatStyle.mediaShape
    // Adaptive max width ~78% of the screen (never one hardcoded pixel width).
    val maxBubbleWidth = ((LocalConfiguration.current.screenWidthDp - 2 * CapsuleChatStyle.side) * CapsuleChatStyle.width).dp.let {
        when {
            edgeToEdgeMedia -> minOf(it, (LocalChatMediaWidth.current ?: CapsuleChatStyle.mediaWidth).dp)
            attachment?.mediaType == AttachmentMediaType.FILE -> minOf(it, (LocalChatAlbumCellWidth.current ?: CapsuleChatStyle.fileWidth).dp)
            attachment?.mediaType == AttachmentMediaType.VOICE -> minOf(it, CapsuleChatStyle.voiceWidth.dp)
            else -> it
        }
    }

    val minimumBubbleWidth = 0.dp
    val selectionLabel = if (!selectionMode) "" else when {
        message.isDeletedForEveryone -> stringResource(R.string.deleted_message_preview)
        message.status == MessageStatus.DECRYPT_FAILED -> stringResource(R.string.msg_status_decrypt_failed)
        message.status == MessageStatus.IDENTITY_CHANGED -> stringResource(R.string.msg_status_identity_changed)
        else -> message.caption?.takeIf { it.isNotBlank() } ?: message.text.takeIf { it.isNotBlank() }
            ?: attachment?.filename?.takeIf { it.isNotBlank() } ?: stringResource(when {
                attachment?.mediaType == AttachmentMediaType.VOICE -> R.string.voice_message_preview
                attachment?.mimeType?.startsWith("video/") == true -> R.string.media_video
                attachment?.mediaType == AttachmentMediaType.IMAGE -> R.string.photo_preview
                else -> R.string.file_preview
            })
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        com.messenger.app.ui.components.SelectionOverlayLayout(
            outgoing = isOwnMessage,
            markerInside = LocalChatAlbumCell.current,
            selectionMark = if (selectionMode && !LocalChatAlbumOwnsSelectionMark.current) ({
                ChatSelectionMark(selected, { onSelectionTap() }, Modifier.requiredSize(48.dp)
                    .semantics { contentDescription = selectionLabel })
            }) else null,
        ) {
        Column(
            modifier = Modifier
                .widthIn(min = minimumBubbleWidth, max = maxBubbleWidth)
                .then(if (selectionMode) Modifier.clearAndSetSemantics {} else Modifier)
                .onGloballyPositioned { reactionTouch.bounds = it.boundsInWindow() }
                .pointerInput(reactionTouch) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            event.changes.firstOrNull { it.pressed && !it.previousPressed }?.let {
                                reactionTouch.touchOnReaction = reactionTouch.reactionArea.contains(reactionTouch.bounds.topLeft + it.position)
                            }
                        }
                    }
                }
                // Preserve the media outline while the content uses its own presentation shape.
                .then(if ((edgeToEdgeMedia || documentCard) && highlighted) Modifier.border(2.dp, colors.accentSoft, shape) else Modifier)
                .combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onSelectionTap, onLongClick = onLongPress, onDoubleClick = guardedQuickReaction)
                .padding(horizontal = if (separateContent) 0.dp else CocoSpacing.md, vertical = if (separateContent) 0.dp else CocoSpacing.sm),
        ) {
            Column(Modifier
                .align(if (isOwnMessage) Alignment.End else Alignment.Start)
                .messageSelectionBodyCenter()
                .onGloballyPositioned { onBoundsChanged(it.boundsInWindow()) }
                .then(if (selectionMode && selected) Modifier.border(1.dp, CapsuleChatStyle.text.copy(alpha = .3f),
                    if (edgeToEdgeMedia) LocalChatMediaShape.current else contentShape) else Modifier)
                .then(if (!edgeToEdgeMedia) Modifier
                    .widthIn(min = minimumBubbleWidth)
                    .then(if (attachment?.mediaType == AttachmentMediaType.VOICE) Modifier.width(CapsuleChatStyle.voiceWidth.dp)
                        else if (documentCard) Modifier.width((LocalChatAlbumCellWidth.current ?: CapsuleChatStyle.fileWidth).dp) else Modifier)
                    .background(contentBackground, contentShape)
                    .then(if (selectionMode && selected) Modifier.background(CapsuleChatStyle.text.copy(alpha = .06f), contentShape) else Modifier)
                    .padding(start = if (attachment?.mediaType == AttachmentMediaType.VOICE) 8.dp else appearance.padX.dp,
                        end = if (attachment?.mediaType == AttachmentMediaType.VOICE) 11.dp else appearance.padX.dp,
                        top = if (attachment?.mediaType == AttachmentMediaType.VOICE) appearance.voicePadY.dp else appearance.padY.dp,
                        bottom = if (attachment?.mediaType == AttachmentMediaType.VOICE) appearance.voicePadY.dp else appearance.padY.dp) else Modifier)) {
            if (scheduledAt != null && message.status == MessageStatus.PENDING) Text(stringResource(R.string.chat_schedule) + " · " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(scheduledAt)), color = metaColor, style = MaterialTheme.typography.labelSmall)
            message.replyPreview?.takeUnless { LocalChatAlbumCell.current }?.let { reply ->
                ReplyQuote(author = replyAuthor.ifBlank { stringResource(R.string.reply) }, preview = reply.preview, contentType = reply.contentType,
                    modifier = Modifier.clickable { if (selectionMode) onSelectionTap() else onScrollToReply(reply.targetServerId) }, attachment = replyAttachment)
            }
            if (message.forwarded && !LocalChatAlbumCell.current) {
                ForwardedMessageLabel()
            }
            if (message.isDeletedForEveryone) {
                Text(text = stringResource(R.string.deleted_message_preview), style = messageTextStyle, color = contentColor)
            } else if (message.directFile != null) {
                DirectFileCard(message.directFile, selectionMode, onSelectionTap, text = message.text)
            } else if (message.conferenceInvitation != null) {
                com.messenger.app.conference.ConferenceInvitationCard(message, isOwnMessage, selectionMode, onSelectionTap)
            } else if (legacyConferenceInvite != null) {
                Column {
                    Text(com.messenger.app.conference.ConferenceInvitation.FALLBACK, style = messageTextStyle, color = contentColor)
                    val invitationApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
                    TextButton(onClick = { if (selectionMode) onSelectionTap() else invitationApp.conferenceController.offerInvite(message.text) }) { Text("Открыть приглашение") }
                }
            } else if (attachment != null) {
                Box {
                androidx.compose.runtime.CompositionLocalProvider(LocalAttachmentLongPress provides onLongPress, LocalAttachmentQuickReaction provides guardedQuickReaction,
                    LocalAttachmentCancel provides { if (selectionMode) onSelectionTap() else onCancelPending() }) {
                    AttachmentContent(attachment, isOwnMessage, playingAttachmentId, playingPositionMs,
                        { if (selectionMode) onSelectionTap() else onDownload(it) },
                        { item, file -> if (selectionMode) onSelectionTap() else onTogglePlay(item, file) },
                        { file, mime -> if (selectionMode) onSelectionTap() else onOpenFile(file, mime) },
                        { if (selectionMode) onSelectionTap() else onOpenImage(it) }, contentColor, metaColor,
                        fileMetadata = if (compactFileMetadata) ({ CompactMessageMetadata(message, isOwnMessage, metaColor) }) else null)
                }
                    if (edgeToEdgeMedia && LocalChatAlbumLastMember.current && CapsuleChatStyle.showTimes) {
                        Row(Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(CapsuleChatStyle.metaGap.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (message.isEdited) Text(stringResource(R.string.edited), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            Text(formatTime(message.createdAt), color = Color.White, fontSize = CapsuleChatStyle.timeSize.sp,
                                lineHeight = (CapsuleChatStyle.timeSize * 1.3f).sp, style = MaterialTheme.typography.labelSmall)
                            if (isOwnMessage) MessageReceipt(message.status)
                        }
                    }
                }
                message.caption?.takeIf { it.isNotBlank() && !LocalChatAlbumCell.current }?.let {
                    Text(cocoMessageRichText(it), color = contentColor, style = messageTextStyle,
                        modifier = if (edgeToEdgeMedia) Modifier
                            .background(if (isOwnMessage) appearance.outgoing else appearance.incoming, shape)
                            .padding(horizontal = (10f + appearance.padX - CapsuleChatStyle.padX).coerceAtLeast(0f).dp,
                                vertical = (8f + appearance.padY - CapsuleChatStyle.padY).coerceAtLeast(0f).dp) else Modifier)
                }
            } else {
                val readable = when (message.status) {
                    MessageStatus.DECRYPT_FAILED -> stringResource(R.string.msg_status_decrypt_failed)
                    MessageStatus.IDENTITY_CHANGED -> stringResource(R.string.msg_status_identity_changed)
                    else -> message.text
                }
                val edited = if (message.isEdited) stringResource(R.string.edited) else ""
                val displayed = cocoMessageRichText(readable)
                val inlineText = androidx.compose.ui.text.buildAnnotatedString {
                    append(displayed)
                    if (inlineMetadata && CapsuleChatStyle.showTimes) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = CapsuleChatStyle.timeSize.sp, color = metaColor))
                        append("\u2002\u2002" + formatTime(message.createdAt))
                        if (edited.isNotEmpty()) append("\u00a0" + edited)
                        if (isOwnMessage) {
                            append("\u00a0\u00a0")
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = if (message.status == MessageStatus.READ) CapsuleChatStyle.ticks else metaColor))
                            append(when(message.status) {
                                MessageStatus.PENDING -> "◷"
                                MessageStatus.SENT -> "✓"
                                MessageStatus.DELIVERED, MessageStatus.READ -> "✓✓"
                                else -> ""
                            })
                            pop()
                        }
                        pop()
                    }
                }
                if (adaptiveTextMetadata) {
                    CompactMessageText(text = inlineText, color = contentColor, style = messageTextStyle,
                        isOwnMessage = isOwnMessage, belowGap = CapsuleChatStyle.timeOffset.dp) {
                        CompactMessageMetadata(message, isOwnMessage, metaColor)
                    }
                } else Text(text = inlineText, color = contentColor, style = messageTextStyle)
            }
            if (!LocalChatAlbumCell.current && !message.isDeletedForEveryone && message.status != MessageStatus.DECRYPT_FAILED && message.status != MessageStatus.IDENTITY_CHANGED) {
                contentLink?.let { address ->
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(contentColor.copy(alpha = 0.08f))
                        .clickable { if (selectionMode) onSelectionTap() else runCatching { uriHandler.openUri(address.toString()) } }.padding(10.dp)) {
                        Text(address.host, color = contentColor, style = MaterialTheme.typography.labelLarge.copy(fontFamily = appearance.messageFontFamily), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(address.rawPath?.takeIf { it.isNotBlank() } ?: "/", color = metaColor, style = MaterialTheme.typography.bodySmall.copy(fontFamily = appearance.messageFontFamily), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
                    if (!edgeToEdgeMedia && attachment != null && attachment.mediaType != AttachmentMediaType.VOICE && attachment.mediaType != AttachmentMediaType.FILE && isOwnMessage && (attachment.uploadState != AttachmentUploadState.SENT && attachment.uploadState != AttachmentUploadState.CANCELLED)) {
                        Row(Modifier.widthIn(max = 232.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (!LocalChatConnected.current) stringResource(R.string.voice_waiting_network) else uploadPendingLabel(attachment.uploadState),
                                color = metaColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = onCancelPending, modifier = Modifier.size(32.dp)) { Icon(CocoIcons.Close, stringResource(R.string.cancel_sending), tint = metaColor, modifier = Modifier.size(18.dp)) }
                        }
                    }
            }
            val reactionVisibility = remember(message.clientMessageId) {
                androidx.compose.animation.core.MutableTransitionState(CapsuleChatStyle.showReactions && message.reactions.isNotEmpty() && !message.isDeletedForEveryone)
            }
            reactionVisibility.targetState = CapsuleChatStyle.showReactions && message.reactions.isNotEmpty() && !message.isDeletedForEveryone
            val lastReactions = remember(message.clientMessageId) { arrayOf(message.reactions) }
            if (message.reactions.isNotEmpty()) lastReactions[0] = message.reactions
            // Ease both ends of the reveal; no spring, overshoot or bubble deformation.
            if (!LocalChatAlbumCell.current && !edgeToEdgeMedia && !inlineMetadata && !adaptiveTextMetadata && !compactFileMetadata) {
            // Keep time attached to the content rather than below the optional reactions.
            Row(
                modifier = Modifier.align(if (isOwnMessage) Alignment.End else Alignment.Start).padding(
                    start = if (voiceCard) 8.dp else appearance.padX.dp,
                    end = if (voiceCard) 11.dp else appearance.padX.dp,
                    top = CapsuleChatStyle.timeOffset.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (attachment != null && attachment.mediaType != AttachmentMediaType.VOICE && attachment.mediaType != AttachmentMediaType.FILE && !message.isDeletedForEveryone) {
                    IconButton(onClick = { CocoHaptics.tap(hapticView); onLongPress() }, modifier = Modifier.size(48.dp)) {
                        Icon(CocoIcons.MoreVert, contentDescription = stringResource(R.string.more_options), tint = metaColor)
                    }
                }
                if (attachment?.mediaType == AttachmentMediaType.VOICE && isOwnMessage && (attachment.uploadState != AttachmentUploadState.SENT && attachment.uploadState != AttachmentUploadState.CANCELLED)) {
                    Text(if (!LocalChatConnected.current) stringResource(R.string.voice_waiting_network) else uploadPendingLabel(attachment.uploadState),
                        color = metaColor, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 125.dp).padding(end = 6.dp))
                }
                if (CapsuleChatStyle.showTimes) {
                    Text(text = formatTime(message.createdAt), fontSize = CapsuleChatStyle.timeSize.sp,
                        lineHeight = (CapsuleChatStyle.timeSize * 1.3f).sp,
                        style = MaterialTheme.typography.labelSmall.copy(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                        color = metaColor, maxLines = 1, softWrap = false)
                    if (isOwnMessage && !message.isDeletedForEveryone) {
                        Spacer(modifier = Modifier.width(CapsuleChatStyle.metaGap.dp))
                        MessageReceipt(message.status)
                    }
                    if (message.isEdited && !message.isDeletedForEveryone) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.edited), style = MaterialTheme.typography.labelSmall, color = metaColor)
                    }
                }
            }
            }
            val reactionReveal = tween<androidx.compose.ui.unit.IntSize>(160,
                easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0.2f, 1f))
            val reactionClose = tween<androidx.compose.ui.unit.IntSize>(130,
                easing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 1f, 1f))
            if (!LocalChatAlbumCell.current) AnimatedVisibility(visibleState = reactionVisibility,
                modifier = Modifier.align(if (isOwnMessage) Alignment.End else Alignment.Start),
                enter = if (edgeToEdgeMedia) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.expandVertically(reactionReveal, expandFrom = Alignment.Top),
                exit = if (edgeToEdgeMedia) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.shrinkVertically(reactionClose, shrinkTowards = Alignment.Top)) {
                DisposableEffect(reactionTouch) { onDispose { reactionTouch.reactionArea = androidx.compose.ui.geometry.Rect.Zero } }
                FlowRow(
                    modifier = Modifier.onGloballyPositioned { reactionTouch.reactionArea = it.boundsInWindow() }
                        .then(if (!edgeToEdgeMedia) Modifier
                            .animateContentSize(reactionReveal, alignment = if (isOwnMessage) Alignment.TopEnd else Alignment.TopStart)
                            .padding(start = 3.dp, end = 0.dp, top = CapsuleChatStyle.reactionOffset.dp) else Modifier.padding(top = CapsuleChatStyle.reactionOffset.dp)),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val visibleReactions = lastReactions[0].flatMap { reaction ->
                        List(reaction.count.coerceAtLeast(0)) { reaction }
                    }
                    visibleReactions.forEach { reaction ->
                        Row(
                            modifier = Modifier
                                // A fixed dp height can round below the emoji's measured sp line
                                // (or its fallback-font metrics). Keep the approved minimum, but
                                // let Text measure its complete line instead of clipping it.
                                .widthIn(min = reactionWidth)
                                .heightIn(min = reactionHeight)
                                .background(CapsuleChatStyle.reaction, CircleShape)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                    if (selectionMode) onSelectionTap() else onToggleReaction(reaction.emoji)
                                }
                                .padding(horizontal = 0.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(reaction.emoji, fontSize = CapsuleChatStyle.emojiSize.sp, lineHeight = CapsuleChatStyle.reactionHeight.sp,
                                // Constrain the paragraph itself, not only the wrapping Row;
                                // centered fallback emoji must not use the whole message width.
                                modifier = Modifier.width(reactionWidth), maxLines = 1, softWrap = false,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall.copy(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
                        }
                    }
                }
            }
            if (isOwnMessage && (message.status == MessageStatus.FAILED || attachment?.uploadState == AttachmentUploadState.FAILED) && !edgeToEdgeMedia) {
                PendingMessageFailureNotice(
                    onRetry = { if (selectionMode) onSelectionTap() else onRetry() },
                    onCancel = { if (selectionMode) onSelectionTap() else onCancelPending() },
                    modifier = Modifier.widthIn(max = 220.dp).padding(top = 3.dp))
            }
        }
        }
        if (isOwnMessage && edgeToEdgeMedia && (message.status == MessageStatus.FAILED || attachment?.uploadState == AttachmentUploadState.FAILED) && !selectionMode) {
            PendingMessageFailureNotice(onRetry = onRetry, onCancel = onCancelPending,
                compact = true, modifier = Modifier.align(Alignment.Center))
        }
        if (selectionMode) Box(Modifier.matchParentSize().zIndex(2f).pointerInput(Unit) {
            // No semantics node on this pointer shield: it must not occlude the labeled checkbox.
            detectTapGestures(onTap = { latestSelectionTap.value() })
        })
    }
}

@Composable
internal fun ChatDialogBlur(enabled:Boolean=false) {
        if(!enabled)return
        val dialogView = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            if (Build.VERSION.SDK_INT >= 31 && window != null) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = 24 }
            }
            onDispose {
                if (Build.VERSION.SDK_INT >= 31) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
        }
}

@Composable
internal fun ChatSelectionMark(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCocoColors.current
    Box(modifier.size(48.dp).toggleable(value = checked, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = Role.Checkbox, onValueChange = onCheckedChange), contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).background(if (checked) colors.accent else colors.background, CircleShape)
            .border(1.5.dp, if (checked) colors.accent else colors.secondaryText, CircleShape), contentAlignment = Alignment.Center) {
            if (checked) Icon(CocoIcons.Check, null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
        }
    }
}

private data class VoiceActions(val state: com.messenger.app.data.attachment.VoicePlaybackState = com.messenger.app.data.attachment.VoicePlaybackState(), val seek: (Float) -> Unit = {}, val speed: () -> Unit = {})
private val LocalChatConnected = androidx.compose.runtime.compositionLocalOf { true }
private val LocalAttachmentCancel = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }
private val LocalVoiceActions = androidx.compose.runtime.compositionLocalOf { VoiceActions() }

private val LocalAttachmentQuickReaction = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

private val LocalAttachmentLongPress = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

internal val LocalChatMediaWidth = androidx.compose.runtime.compositionLocalOf<Float?> { null }
internal val LocalChatMediaHeight = androidx.compose.runtime.compositionLocalOf<Float?> { null }
internal val LocalChatMediaShape = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.graphics.Shape> { CapsuleChatStyle.mediaShape }
internal val LocalChatAlbumLastMember = androidx.compose.runtime.compositionLocalOf { true }

@Composable
private fun AttachmentContent(
    attachment: Attachment,
    isMine: Boolean,
    playingAttachmentId: String?,
    playingPositionMs: Int,
    onDownload: (Attachment) -> Unit,
    onTogglePlay: (Attachment, File) -> Unit,
    onOpenFile: (File, String) -> Unit,
    onOpenImage: (File) -> Unit,
    contentColor: Color,
    metaColor: Color,
    fileMetadata: (@Composable () -> Unit)? = null,
) {
    when (attachment.mediaType) {
        AttachmentMediaType.IMAGE -> ImageAttachmentContent(attachment, isMine, onDownload, onOpenImage)
        AttachmentMediaType.VOICE -> VoiceAttachmentContent(attachment, isMine, playingAttachmentId, playingPositionMs, onDownload, onTogglePlay, contentColor)
        AttachmentMediaType.FILE -> FileAttachmentContent(attachment, isMine, onDownload, onOpenFile, contentColor, metaColor, fileMetadata)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ImageAttachmentContent(
    attachment: Attachment,
    isMine: Boolean,
    onDownload: (Attachment) -> Unit,
    onOpenImage: (File) -> Unit,
) {
    val colors = LocalCocoColors.current
    val onQuickReaction = LocalAttachmentQuickReaction.current
    val onLongPress = LocalAttachmentLongPress.current
    val imageWidth = minOf(LocalChatMediaWidth.current ?: CapsuleChatStyle.mediaWidth, LocalConfiguration.current.screenWidthDp * 0.78f - 6f).coerceAtLeast(1f)
    val ratio = if (LocalChatMediaWidth.current != null) 1f else if ((attachment.width ?: 0) > 0 && (attachment.height ?: 0) > 0)
        ((attachment.width ?: 1).toFloat() / (attachment.height ?: 1)).coerceIn(0.2f, 5f) else 1f
    val imageHeight = LocalChatMediaHeight.current ?: minOf(270f, imageWidth / ratio.coerceIn(0.2f, 5f))
    val imageModifier = Modifier.size(width = (if (LocalChatMediaHeight.current != null) imageWidth else minOf(imageWidth, imageHeight * ratio)).dp, height = imageHeight.dp).clip(LocalChatMediaShape.current)

    // Outgoing photo still uploading: bounded pending state, never a
    // "tap to download" placeholder for our own in-flight image.
    if (isMine && (attachment.uploadState != AttachmentUploadState.SENT && attachment.uploadState != AttachmentUploadState.CANCELLED) && attachment.localPath == null) {
        Box(
            modifier = imageModifier.background(colors.glassSecondary, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            PendingMediaControls(attachment)
        }
        return
    }
    val localPath = attachment.localPath
    if (attachment.downloadState == AttachmentDownloadState.DOWNLOADED && localPath != null) {
        // A camera photo can be many times larger than the bubble. Decode a
        // sampled bitmap so opening a long chat cannot allocate a full-size
        // 20+ megapixel image on the UI thread.
        val desiredPixels = maxOf(minOf(imageWidth, imageHeight * ratio), imageHeight) * LocalDensity.current.density
        val previewEdge = when { desiredPixels <= 384 -> 384; desiredPixels <= 512 -> 512; else -> 768 }
        val previewKey = "photo$previewEdge:$localPath"
        val bitmap by androidx.compose.runtime.produceState(
            com.messenger.app.data.local.ChatImageCache.peek(previewKey), previewKey) {
            // Keep the previous decoded image while a new local path is decoded.
            val decoded = com.messenger.app.data.local.ChatImageCache.load(previewKey) { com.messenger.app.data.attachment.ImageProcessor.chatPreview(File(localPath), previewEdge) }
            if (decoded != null) value = decoded
        }
        val decoded = bitmap
        if (decoded != null) {
            Box(imageModifier, contentAlignment = Alignment.Center) {
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = stringResource(R.string.photo_preview),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onLongClick = onLongPress, onDoubleClick = onQuickReaction, onClick = { onOpenImage(File(localPath)) }),
            )
            if (isMine && (attachment.uploadState != AttachmentUploadState.SENT && attachment.uploadState != AttachmentUploadState.CANCELLED)) PendingMediaControls(attachment)
            }
            return
        }
    }
    val thumbnailKey = attachment.thumbnailBase64?.let { "thumb:$it" }
    val thumbBitmap by androidx.compose.runtime.produceState(
        thumbnailKey?.let { com.messenger.app.data.local.ChatImageCache.peek(it) }, thumbnailKey) {
        if (value == null && thumbnailKey != null) {
            val decoded = com.messenger.app.data.local.ChatImageCache.load(thumbnailKey) {
                attachment.thumbnailBase64?.takeIf { it.length <= 128 * 1024 }?.let { b64 -> runCatching {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) null
                    else {
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }.getOrNull() }
            }
            if (decoded != null) {
                value = decoded
            }
        }
    }
    Box(
        modifier = imageModifier.background(MaterialTheme.colorScheme.surface).combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
            onLongClick = onLongPress, onDoubleClick = onQuickReaction, onClick = {
                if (isMine && (attachment.uploadState != AttachmentUploadState.SENT && attachment.uploadState != AttachmentUploadState.CANCELLED)) {
                    attachment.localPath?.let { onOpenImage(File(it)) }
                } else onDownload(attachment)
            }),
        contentAlignment = Alignment.Center,
    ) {
        thumbBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = stringResource(R.string.photo_thumbnail),
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (isMine && (attachment.uploadState != AttachmentUploadState.SENT && attachment.uploadState != AttachmentUploadState.CANCELLED)) PendingMediaControls(attachment)
        else when (attachment.downloadState) {
            AttachmentDownloadState.DOWNLOADING -> CircularProgressIndicator()
            AttachmentDownloadState.FAILED -> Text(stringResource(R.string.download_failed))
            AttachmentDownloadState.CORRUPT -> Text(stringResource(R.string.download_corrupt))
            else -> if (thumbBitmap == null) Text(stringResource(R.string.tap_to_download))
        }
    }
}

@Composable private fun PendingMediaControls(attachment: Attachment) {
    // Failed sends have one dedicated retry/cancel control on the owning bubble.
    if (attachment.uploadState == AttachmentUploadState.FAILED || attachment.uploadState == AttachmentUploadState.CANCELLED) return
    val label = if (!LocalChatConnected.current && attachment.uploadState != AttachmentUploadState.FAILED)
        stringResource(R.string.waiting_for_network) else uploadPendingLabel(attachment.uploadState)
    // Use the same cancellation control as documents. Phase text remains available
    // to TalkBack, without a wide label covering the photo/video or shifting tiles.
    val uploading = attachment.uploadState == AttachmentUploadState.UPLOADING && LocalChatConnected.current
    FileTransferControl(attachment.progress, waiting = !uploading, label = stringResource(R.string.cancel_sending),
        onCancel = LocalAttachmentCancel.current, mediaOverlay = true,
        statusDescription = if (uploading) "$label ${attachment.progress.coerceIn(0, 100)}%" else label)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoBatchPreviewSheet(
    uris: List<Uri>, caption: String, onCaptionChange: (String) -> Unit,
    sending: Boolean, failed: Boolean, onRemove: (Uri) -> Unit,
    onDismiss: () -> Unit, onSend: (String) -> Unit, onAdd: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val colors = LocalCocoColors.current
    var quality by remember { mutableStateOf(context.getSharedPreferences("media-quality", Context.MODE_PRIVATE).getString("send", "image") ?: "image") }
    var previewFile by remember { mutableStateOf<File?>(null) }
    fun mime(uri: Uri) = context.contentResolver.getType(uri)
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.')?.lowercase(java.util.Locale.ROOT))
        ?: "application/octet-stream"
    val viewerItems = remember(uris) { uris.mapNotNull { uri ->
        uri.path?.takeIf { uri.scheme == "file" }?.let(::File)?.takeIf { it.isFile }?.let { MediaViewerItem(it, mime(uri), it.name) }
    } }
    previewFile?.let { file -> MediaViewerDialog(viewerItems, file) { previewFile = null } }
    ModalBottomSheet(onDismissRequest = { if (!sending) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface, contentColor = colors.primaryText, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding()) {
            AttachmentPanelHeader(stringResource(R.string.media_selected_count, uris.size), { CocoHaptics.tap(hapticView); onDismiss() }, !sending)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("media-draft-content").padding(horizontal = CocoSpacing.lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onAdd != null) AttachmentPanelButton(stringResource(R.string.attach_gallery), CocoIcons.Image, CapsuleChatStyle.accent, { CocoHaptics.tap(hapticView); onAdd() }, enabled = !sending)
                Text(stringResource(R.string.media_draft_hint), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(uris, key = { _, uri -> uri.toString() }) { index, uri ->
                        val type = remember(uri) { mime(uri) }
                        val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, uri) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                if (type.startsWith("video/")) {
                                    val retriever = android.media.MediaMetadataRetriever()
                                    try { retriever.setDataSource(context, uri); if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 336, 336) else null }
                                    catch (_: Exception) { null } finally { retriever.release() }
                                } else decodeSampledBitmap(context, uri, 336)
                            }
                        }
                        Box(Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant).clickable(enabled = !sending) {
                            val file = viewerItems.firstOrNull { it.file?.path == uri.path }?.file
                            if (file != null) CocoHaptics.tap(hapticView)
                            previewFile = file
                        }) {
                            bitmap?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.photo_preview), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            if (type.startsWith("video/")) Icon(CocoIcons.Play, contentDescription = stringResource(R.string.media_video), tint = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .5f), CircleShape).padding(8.dp))
                            Text("${index + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha = .6f), CircleShape).padding(horizontal = 7.dp, vertical = 3.dp))
                            IconButton(enabled = !sending, onClick = { CocoHaptics.select(hapticView); onRemove(uri) }, modifier = Modifier.align(Alignment.TopEnd)) {
                                Icon(CocoIcons.Close, contentDescription = stringResource(R.string.media_remove_draft), tint = CapsuleChatStyle.accent,
                                    modifier = Modifier.background(CapsuleChatStyle.entry.copy(alpha = .95f), CircleShape).padding(6.dp))
                            }
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("image" to R.string.media_standard, "image_high" to R.string.media_high, "file" to R.string.media_original)) { (key, label) ->
                        FilterChip(selected = quality == key, onClick = { CocoHaptics.select(hapticView); quality = key; context.getSharedPreferences("media-quality", Context.MODE_PRIVATE).edit().putString("send", key).apply() }, label = { Text(stringResource(label)) }, enabled = !sending, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp), colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = CapsuleChatStyle.accent.copy(alpha = .16f), selectedLabelColor = CapsuleChatStyle.accent))
                    }
                }
                Text(stringResource(R.string.media_quality_note), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                OutlinedTextField(value = caption, onValueChange = { onCaptionChange(it.take(4096)) }, enabled = !sending,
                    label = { Text(stringResource(R.string.chat_caption)) }, maxLines = 4, shape = RoundedCornerShape(18.dp), colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = CapsuleChatStyle.accent, unfocusedBorderColor = colors.secondaryText.copy(alpha = .25f), focusedContainerColor = colors.surfaceVariant, unfocusedContainerColor = colors.surfaceVariant), modifier = Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.attachment_send_failed), color = colors.secondaryText, modifier = Modifier.padding(vertical = 8.dp))
                Spacer(Modifier.height(8.dp))
            }
            if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AttachmentPanelButton(stringResource(R.string.media_keep_draft), CocoIcons.Close, CapsuleChatStyle.accent, { CocoHaptics.tap(hapticView); onDismiss() }, enabled = !sending, modifier = Modifier.weight(1f))
                AttachmentPanelButton(if (failed) stringResource(R.string.retry) else stringResource(R.string.media_send_count, uris.size), CocoIcons.Send, CapsuleChatStyle.accent, { CocoHaptics.tap(hapticView); onSend(caption) }, enabled = !sending && uris.isNotEmpty(), modifier = Modifier.weight(1f), primary = true)
            }
        }
    }
}

/** Camera capture review (Phase 7 §14/§15): the user reviews the photo before
 *  send; cancel deletes the app-owned temp file. */
@Composable
private fun CameraCapturePreview(uri: Uri, sending: Boolean, onSend: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    var previewLoading by remember(uri) { mutableStateOf(true) }
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodeSampledBitmap(context, uri, 2_048) }
        previewLoading = false
    }
    Dialog(
        onDismissRequest = { if (!sending) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val decoded = bitmap
            if (decoded != null) {
                Image(
                    bitmap = decoded.asImageBitmap(),
                    contentDescription = stringResource(R.string.photo_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (previewLoading) {
                CircularProgressIndicator()
            } else {
                Text(stringResource(R.string.profile_avatar_decode_failed), color = Color.White)
            }
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(CocoSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(CocoSpacing.lg),
            ) {
                TextButton(onClick = { CocoHaptics.tap(hapticView); onCancel() }, enabled = !sending) { Text(stringResource(R.string.cancel), color = Color.White) }
                Button(onClick = { CocoHaptics.tap(hapticView); onSend() }, enabled = !sending && bitmap != null) { Text(if (sending) stringResource(R.string.msg_status_sending) else stringResource(R.string.send)) }
            }
        }
    }
}

/** File confirmation (Phase 7 §17/§19): filename + formatted size before the
 *  encrypted upload starts. Cancel simply abandons the picked document. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSendConfirm(uri: Uri, sending: Boolean, failed: Boolean, onSend: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalCocoColors.current
    val metadata by androidx.compose.runtime.produceState<Pair<String?, Long>?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { queryFileMeta(context, uri) }
    }
    val name = metadata?.first
    val size = metadata?.second ?: -1L
    ModalBottomSheet(onDismissRequest = { if (!sending) onCancel() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface, contentColor = colors.primaryText) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(CocoIcons.File, contentDescription = null, tint = CapsuleChatStyle.accent, modifier = Modifier.background(CapsuleChatStyle.accent.copy(alpha = .12f), RoundedCornerShape(16.dp)).padding(12.dp).size(30.dp))
                Text(text = name ?: stringResource(R.string.file_preview), color = colors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(CocoSpacing.xs))
                Text(text = AttachmentFormat.fileSize(size), color = colors.secondaryText)
                if (size > AttachmentFormat.MAX_FILE_BYTES) {
                    Spacer(modifier = Modifier.height(CocoSpacing.xs))
                    Text(text = stringResource(R.string.error_file_too_large), color = colors.danger)
                }
                if (failed) Text(stringResource(R.string.attachment_send_failed), color = colors.danger)
                if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AttachmentPanelButton(stringResource(R.string.cancel), CocoIcons.Close, CapsuleChatStyle.accent, onCancel,
                        enabled = !sending, modifier = Modifier.weight(1f))
                    AttachmentPanelButton(if (sending) stringResource(R.string.msg_status_sending) else stringResource(if (failed) R.string.retry else R.string.send), CocoIcons.Send, CapsuleChatStyle.accent, onSend,
                        enabled = !sending && metadata != null && size <= AttachmentFormat.MAX_FILE_BYTES, modifier = Modifier.weight(1f), primary = true)
                }
            }
    }
}

private fun queryFileMeta(context: Context, uri: Uri): Pair<String?, Long> {
    com.messenger.app.data.attachment.localFileMetadata(uri.scheme, uri.path)?.let { return it }
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                name to size
            } else {
                null to -1L
            }
        } ?: (null to -1L)
    }.getOrDefault(null to -1L)
}

/** Full-screen in-app photo view. Keeping it in COCO avoids losing the chat
 * context to another app just to inspect an already decrypted attachment. */
@Composable
private fun ImageViewerDialog(file: File, onDismiss: () -> Unit) {
    // Full screen deserves a sharper image than the bubble, but it still must
    // have a bounded allocation for photos from modern cameras.
    var previewLoading by remember(file.absolutePath) { mutableStateOf(true) }
    val preview by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, file.absolutePath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodeSampledBitmap(file.absolutePath, 2_048) }
        previewLoading = false
    }
    val bitmap = preview
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.photo_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (previewLoading) {
                CircularProgressIndicator()
            } else {
                Text(stringResource(R.string.download_failed), color = Color.White)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(CocoSpacing.md),
            ) {
                Icon(CocoIcons.Close, contentDescription = stringResource(R.string.dismiss), tint = Color.White)
            }
        }
    }
}

private fun decodeSampledBitmap(path: String, maxDimension: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun decodeSampledBitmap(context: Context, uri: Uri, maxDimension: Int): android.graphics.Bitmap? {
    try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
        sample *= 2
    }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    } catch (_: Exception) { return null }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceAttachmentContent(
    attachment: Attachment,
    isMine: Boolean,
    playingAttachmentId: String?,
    playingPositionMs: Int,
    onDownload: (Attachment) -> Unit,
    onTogglePlay: (Attachment, File) -> Unit,
    contentColor: Color,
) {

    val onLongPress = LocalAttachmentLongPress.current
    val colors = LocalCocoColors.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val localPath = attachment.localPath
    val durationMs = attachment.durationMs ?: 0L
    val isPlaying = playingAttachmentId == attachment.attachmentId
    val controls = LocalVoiceActions.current
    val active = controls.state.playingAttachmentId == attachment.attachmentId || controls.state.pausedAttachmentId == attachment.attachmentId
    val downloaded = attachment.downloadState == AttachmentDownloadState.DOWNLOADED && localPath != null
    val uploading = isMine && (attachment.uploadState != AttachmentUploadState.SENT && attachment.uploadState != AttachmentUploadState.CANCELLED)
    val ink = if (isMine) CapsuleChatStyle.outText else CapsuleChatStyle.text
    val metadataHeight = maxOf(18.dp, with(LocalDensity.current) { (CapsuleChatStyle.timeSize * 1.3f).sp.toDp() })
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(CapsuleChatStyle.playSize.dp).clip(CircleShape).background(CapsuleChatStyle.accent)
            .combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                role = Role.Button, onLongClick = onLongPress, onClick = {
                    if (downloaded) onTogglePlay(attachment, File(localPath!!))
                    else if (!uploading) onDownload(attachment)
                }), contentAlignment = Alignment.Center) {
            if ((uploading && !downloaded && attachment.uploadState != AttachmentUploadState.FAILED) || attachment.downloadState == AttachmentDownloadState.DOWNLOADING) {
                CircularProgressIndicator(Modifier.size(18.dp), color = CapsuleChatStyle.buttonInk, strokeWidth = 2.dp)
            } else Icon(if (attachment.uploadState == AttachmentUploadState.FAILED || attachment.downloadState in listOf(AttachmentDownloadState.FAILED, AttachmentDownloadState.CORRUPT)) CocoIcons.Close else if (isPlaying) CocoIcons.Pause else CocoIcons.Play,
                stringResource(if (isPlaying) R.string.pause else if (downloaded) R.string.play else R.string.download), tint = CapsuleChatStyle.buttonInk, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f)) {
            VoiceWaveform(path = localPath, playedFraction = VoiceFormat.playedFraction(playingPositionMs.toLong(), durationMs),
                playedColor = CapsuleChatStyle.accent, unplayedColor = CapsuleChatStyle.waveQuiet,
                modifier = Modifier.fillMaxWidth().height(CapsuleChatStyle.waveHeight.dp)
                    .pointerInput(active) { if (active) detectTapGestures { controls.seek(it.x / size.width.coerceAtLeast(1)) } }
                    .pointerInput(active) { if (active) detectHorizontalDragGestures { change, _ -> change.consume(); controls.seek(change.position.x / size.width.coerceAtLeast(1)) } })
            Row(Modifier.fillMaxWidth().height(metadataHeight), verticalAlignment = Alignment.CenterVertically) {
                Text(VoiceFormat.durationText(if (active) playingPositionMs.toLong() else durationMs), color = ink,
                    fontSize = CapsuleChatStyle.timeSize.sp, lineHeight = (CapsuleChatStyle.timeSize * 1.3f).sp, modifier = Modifier.weight(1f))
                if (uploading) Box(Modifier.size(28.dp, metadataHeight).clickable(role = Role.Button, onClick = LocalAttachmentCancel.current), contentAlignment = Alignment.Center) {
                    Icon(CocoIcons.Close, stringResource(R.string.cancel_sending), tint = ink, modifier = Modifier.size(13.dp))
                } else Box(Modifier.size(28.dp, metadataHeight).clip(RoundedCornerShape(5.dp)).background(ink.copy(alpha = .06f))
                    .clickable(role = Role.Button) { CocoHaptics.select(hapticView); controls.speed() }, contentAlignment = Alignment.Center) {
                    Text(if (active) "${controls.state.speed}×" else "1×", color = ink,
                        fontSize = CapsuleChatStyle.timeSize.sp, lineHeight = (CapsuleChatStyle.timeSize * 1.3f).sp)
                }
            }
        }
    }
}

/** Cached real audio envelope. Playback changes only the played-color boundary. */
@Composable
private fun VoiceWaveform(
    path: String?,
    playedFraction: Float,
    playedColor: Color,
    unplayedColor: Color,
    modifier: Modifier = Modifier,
) {
    // Draw bars in one node; narrow screens do not overflow a fixed-width row.
    val amplitudes by androidx.compose.runtime.produceState<List<Float>>(emptyList(), path) {
        value = if (path == null) emptyList() else com.messenger.app.data.attachment.VoiceWaveformCache.load(path)
    }
    androidx.compose.foundation.Canvas(modifier) {
        val count = (size.width / 4.dp.toPx()).toInt().coerceIn(1, 32)
        val step = size.width / count
        val playedBars = (playedFraction * count).toInt().coerceIn(0, count)
        repeat(count) { index ->
            val amplitude = amplitudes.getOrNull(index * amplitudes.size / count) ?: 0f
            val height = (2.dp.toPx() + amplitude * (size.height - 2.dp.toPx())).coerceAtMost(size.height)
            val x = step * (index + 0.5f)
            drawLine(
                color = if (index < playedBars) playedColor else unplayedColor,
                start = androidx.compose.ui.geometry.Offset(x, (size.height - height) / 2),
                end = androidx.compose.ui.geometry.Offset(x, (size.height + height) / 2),
                strokeWidth = minOf(2.dp.toPx(), step),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun uploadPendingLabel(state: AttachmentUploadState): String = stringResource(
    when (state) {
        AttachmentUploadState.PENDING, AttachmentUploadState.ENCRYPTING -> R.string.attachment_preparing
        AttachmentUploadState.FAILED -> R.string.attachment_send_failed
        else -> R.string.attachment_uploading
    },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileAttachmentContent(attachment: Attachment, isMine: Boolean, onDownload: (Attachment) -> Unit, onOpenFile: (File, String) -> Unit, contentColor: Color, metaColor: Color, fileMetadata: (@Composable () -> Unit)? = null) {
    val appearance = com.messenger.app.ui.theme.LocalChatAppearanceStyle.current
    if (attachment.mimeType.startsWith("video/")) {
        VideoAttachmentCover(attachment, isMine, onDownload, onOpenFile)
        return
    }

    val openMenu = LocalAttachmentLongPress.current
    val quickReaction = LocalAttachmentQuickReaction.current
    val uploading = isMine && (attachment.uploadState != AttachmentUploadState.SENT && attachment.uploadState != AttachmentUploadState.CANCELLED)
    val downloading = attachment.downloadState == AttachmentDownloadState.DOWNLOADING
    val downloaded = attachment.downloadState == AttachmentDownloadState.DOWNLOADED && attachment.localPath != null
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(role = Role.Button, onLongClick = openMenu,
        onDoubleClick = quickReaction, onClick = {
            if (!uploading) {
                if (downloaded) onOpenFile(File(attachment.localPath!!), attachment.mimeType)
                else onDownload(attachment)
            }
        }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // The transfer control occupies the icon slot, keeping filename/footer width
        // unchanged while uploading and when the control disappears after completion.
        Box(Modifier.width(31.dp).height(48.dp), contentAlignment = Alignment.Center) {
            if (uploading || downloading) {
                val cancel = LocalAttachmentCancel.current
                Box(Modifier.requiredSize(48.dp)) {
                    FileTransferControl(attachment.progress, uploading && (attachment.uploadState != AttachmentUploadState.UPLOADING || !LocalChatConnected.current),
                        if (uploading) stringResource(R.string.cancel_sending) else stringResource(R.string.cancel),
                        onCancel = { if (uploading) cancel() else onDownload(attachment) })
                }
            } else Box(Modifier.size(31.dp, 35.dp).background(Color(0xFF414141), CircleShape), contentAlignment = Alignment.Center) {
                Icon(CapsuleChatStyle.file, null, tint = CapsuleChatStyle.accent, modifier = Modifier.size(21.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            val textSize = com.messenger.app.ui.theme.LocalChatTextSize.current
            CompactFileName(attachment.filename ?: stringResource(R.string.file_preview), color = contentColor,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = textSize.sp,
                    fontFamily = appearance.messageFontFamily,
                    lineHeight = (textSize * appearance.lineHeight).sp,
                    fontWeight = FontWeight.Medium,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
            if (fileMetadata != null) CompactFileMetadataLine(fileAttachmentSubtitle(attachment, isMine), metaColor, isMine, fileMetadata)
            else FileAttachmentDetails(attachment, isMine, onDownload, onOpenFile, contentColor, metaColor, showFilename = false)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoAttachmentCover(attachment: Attachment, isMine: Boolean, onDownload: (Attachment) -> Unit, onOpenFile: (File, String) -> Unit) {
    val onQuickReaction = LocalAttachmentQuickReaction.current
    val onLongPress = LocalAttachmentLongPress.current
    val previewKey = remember(attachment.thumbnailBase64, attachment.localPath) {
        attachment.thumbnailBase64?.let { "video-thumb:$it" } ?: attachment.localPath?.let { "video-frame:$it" }
    }
    val bitmap by androidx.compose.runtime.produceState(
        previewKey?.let { com.messenger.app.data.local.ChatImageCache.peek(it) }, previewKey) {
        if (previewKey != null) {
            val decoded = com.messenger.app.data.local.ChatImageCache.load(previewKey) {
                runCatching {
                    val encoded = attachment.thumbnailBase64 ?: attachment.localPath?.let {
                        com.messenger.app.data.attachment.VideoPreview.read(File(it)).thumbnail
                    }
                    encoded?.takeIf { it.length <= 24000 }?.let {
                        val bytes = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) null
                        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }.getOrNull()
            }
            if (decoded != null) {
                value = decoded
            }
        }
    }
    val pending = isMine && (attachment.uploadState != AttachmentUploadState.SENT && attachment.uploadState != AttachmentUploadState.CANCELLED)
    val width = minOf(LocalChatMediaWidth.current ?: CapsuleChatStyle.mediaWidth, LocalConfiguration.current.screenWidthDp * 0.78f - 6f).coerceAtLeast(1f)
    val ratio = if (LocalChatMediaWidth.current != null) 1f else ((attachment.width ?: 4).coerceAtLeast(1).toFloat() / (attachment.height ?: 3).coerceAtLeast(1)).coerceIn(0.2f, 5f)
    val height = LocalChatMediaHeight.current ?: minOf(270f, width / ratio)
    Box(Modifier.width((if (LocalChatMediaHeight.current != null) width else minOf(width, height * ratio)).dp).height(height.dp).clip(LocalChatMediaShape.current)
        .background(Color.Black.copy(alpha = 0.35f)).combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onLongClick = onLongPress, onDoubleClick = onQuickReaction, onClick = {
            if (!pending) {
                val path = attachment.localPath
                if (path != null) onOpenFile(File(path), attachment.mimeType) else onDownload(attachment)
            }
        }), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (pending) Box(Modifier.align(Alignment.Center)) { PendingMediaControls(attachment) }
        if (!pending) {
            Box(Modifier.size(31.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                if (attachment.downloadState == AttachmentDownloadState.DOWNLOADING) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White)
                else if (attachment.localPath != null) Icon(CocoIcons.Play, contentDescription = stringResource(R.string.open), tint = Color.White, modifier = Modifier.size(15.dp))
                else Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.download), tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
        if (attachment.durationMs != null) Text(com.messenger.app.data.attachment.FilePresentation.duration(attachment.durationMs), color = Color.White, fontSize = 10.sp, lineHeight = 13.sp,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(5.dp)).padding(3.dp))
    }
}

@Composable
private fun fileAttachmentSubtitle(attachment: Attachment, isMine: Boolean): String {
    val uploading = isMine && (attachment.uploadState != AttachmentUploadState.SENT && attachment.uploadState != AttachmentUploadState.CANCELLED)
    val downloading = attachment.downloadState == AttachmentDownloadState.DOWNLOADING
    return when {
        uploading && attachment.uploadState == AttachmentUploadState.FAILED -> stringResource(R.string.attachment_send_failed)
        uploading && !LocalChatConnected.current -> stringResource(R.string.voice_waiting_network)
        uploading -> uploadPendingLabel(attachment.uploadState) + if (attachment.uploadState == AttachmentUploadState.UPLOADING) " · ${attachment.progress.coerceIn(0, 100)}%" else ""
        downloading -> "${AttachmentFormat.fileSize(attachment.size)} · ${attachment.progress.coerceIn(0, 100)}%"
        attachment.downloadState == AttachmentDownloadState.FAILED || attachment.downloadState == AttachmentDownloadState.CORRUPT -> stringResource(R.string.retry)
        else -> com.messenger.app.data.attachment.FilePresentation.label(attachment.mimeType, attachment.filename) + " · " + AttachmentFormat.fileSize(attachment.size)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileAttachmentDetails(attachment: Attachment, isMine: Boolean, onDownload: (Attachment) -> Unit, onOpenFile: (File, String) -> Unit, contentColor: Color, metaColor: Color, showFilename: Boolean = true) {
    val subtitle = fileAttachmentSubtitle(attachment, isMine)
    Column(Modifier.heightIn(min = if (showFilename) 36.dp else 0.dp), verticalArrangement = Arrangement.Center) {
        if (showFilename) Text(text = attachment.filename ?: stringResource(R.string.file_preview), style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp, color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = subtitle, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, color = metaColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentActionButton(label: String, color: Color, onClick: () -> Unit) {
    val onQuickReaction = LocalAttachmentQuickReaction.current
    val onLongPress = LocalAttachmentLongPress.current
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = androidx.compose.ui.semantics.Role.Button, onLongClick = onLongPress, onDoubleClick = onQuickReaction, onClick = onClick)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun MessageReceipt(status: MessageStatus) {
    val colors = LocalCocoColors.current
    val label = stringResource(when (status) {
        MessageStatus.PENDING -> R.string.msg_status_sending
        MessageStatus.SENT -> R.string.msg_status_sent
        MessageStatus.DELIVERED -> R.string.msg_status_delivered
        MessageStatus.READ -> R.string.msg_status_read
        MessageStatus.FAILED -> R.string.msg_status_failed
        MessageStatus.DECRYPT_FAILED -> R.string.msg_status_decrypt_failed
        MessageStatus.IDENTITY_CHANGED -> R.string.msg_status_identity_changed
    })
    Text(statusGlyph(status), fontSize = CapsuleChatStyle.timeSize.sp, lineHeight = (CapsuleChatStyle.timeSize * 1.3f).sp, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clearAndSetSemantics { contentDescription = label },
        color = when (status) {
            MessageStatus.READ -> CapsuleChatStyle.ticks
            MessageStatus.SENT -> CapsuleChatChrome.sent
            MessageStatus.DELIVERED -> CapsuleChatChrome.delivered
            MessageStatus.FAILED, MessageStatus.DECRYPT_FAILED, MessageStatus.IDENTITY_CHANGED -> colors.danger
            else -> CapsuleChatStyle.muted
        })
}

private fun statusGlyph(status: MessageStatus): String = when (status) {
    MessageStatus.PENDING -> "◷"
    MessageStatus.SENT -> "✓"
    MessageStatus.DELIVERED -> "✓✓"
    MessageStatus.READ -> "✓✓"
    MessageStatus.FAILED, MessageStatus.DECRYPT_FAILED, MessageStatus.IDENTITY_CHANGED -> "!"
}

internal fun formatTime(createdAt: String): String = runCatching {
    Instant.parse(createdAt).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)
}.getOrDefault("")


@Composable
private fun ChatSafetyDialog(
    initialAction: String? = null,
    chat: com.messenger.app.domain.model.ChatSummary,
    onDismiss: () -> Unit,
    update: suspend (String) -> Boolean,
    report: suspend (String, String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(initialAction == "block" && !chat.isBlocked) }
    var reporting by remember { mutableStateOf(initialAction == "report") }
    var reason by remember { mutableStateOf("spam") }
    var detail by rememberSaveable(chat.chatId) { mutableStateOf("") }
    var status by remember { mutableStateOf<Int?>(null) }
    val reasons = listOf("spam" to R.string.chat_reason_spam, "harassment" to R.string.chat_reason_harassment, "scam" to R.string.chat_reason_scam, "other" to R.string.chat_reason_other)
    fun change(action: String) {
        if (busy) return
        busy = true; status = null
        scope.launch { try { status = if (update(action)) R.string.chat_settings_saved else R.string.chat_settings_failed } finally { busy = false } }
    }
    if (initialAction == null && !confirmBlock && !reporting) {
        ChatActionMenu(stringResource(R.string.chat_controls), { if(!busy) onDismiss() }) {
            ChatActionItem(stringResource(if(chat.isMuted) R.string.chat_unmute else R.string.chat_mute), CocoIcons.Speaker, !busy) { change(if(chat.isMuted) "unmute" else "mute") }
            ChatActionItem(stringResource(if(chat.isBlocked) R.string.chat_unblock else R.string.chat_block), CocoIcons.Security, !busy, destructive = !chat.isBlocked) { if(chat.isBlocked) change("unblock") else confirmBlock = true }
            ChatActionItem(stringResource(R.string.chat_report), CocoIcons.Chat, !busy) { reporting = true }
            status?.let { Text(stringResource(it), Modifier.padding(24.dp)) }
        }
        return
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(when (initialAction) { "notifications" -> if (chat.isMuted) R.string.chat_unmute else R.string.chat_mute; "block" -> if (chat.isBlocked) R.string.chat_unblock else R.string.chat_block; "report" -> R.string.chat_report; else -> R.string.chat_controls })) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (initialAction == null || initialAction == "notifications") {
            TextButton(enabled = !busy, onClick = { change(if (chat.isMuted) "unmute" else "mute") }) { Text(stringResource(if (chat.isMuted) R.string.chat_unmute else R.string.chat_mute)) }
            Text(stringResource(R.string.chat_mute_detail), style = MaterialTheme.typography.bodySmall)
            }
            if (initialAction == null || initialAction == "block") {
            TextButton(enabled = !busy, onClick = { if (chat.isBlocked) change("unblock") else confirmBlock = true }) { Text(stringResource(if (chat.isBlocked) R.string.chat_unblock else R.string.chat_block)) }
            if (confirmBlock) {
                Text(stringResource(R.string.chat_block_detail))
                Row { TextButton(enabled = !busy, onClick = { confirmBlock = false; change("block") }) { Text(stringResource(R.string.chat_block)) }
                    TextButton(onClick = { confirmBlock = false }) { Text(stringResource(R.string.cancel)) } }
            }
            }
            if (initialAction == null || initialAction == "report") {
            if (initialAction == null) TextButton(enabled = !busy, onClick = { reporting = !reporting; status = null }) { Text(stringResource(R.string.chat_report)) }
            if (reporting) {
                Text(stringResource(R.string.chat_report_disclosure), style = MaterialTheme.typography.bodySmall)
                reasons.forEach { (value, label) -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !busy) { reason = value }) {
                    androidx.compose.material3.RadioButton(selected = reason == value, enabled = !busy, onClick = { reason = value }); Text(stringResource(label))
                } }
                Text(stringResource(R.string.manage_evidence), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = detail, onValueChange = { detail = it.take(4000) }, enabled = !busy, label = { Text(stringResource(R.string.chat_report_description)) }, minLines = 2, maxLines = 5)
                TextButton(enabled = !busy, onClick = {
                    busy = true; status = null
                    scope.launch { try {
                        if (report(reason, detail)) { reporting = false; detail = ""; status = R.string.chat_report_sent }
                        else status = R.string.chat_settings_failed
                    } finally { busy = false } }
                }) { Text(stringResource(R.string.send)) }
            }
            }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.chat_controls_close)) } })
}
