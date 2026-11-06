package com.messenger.app.conference

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.ConferenceRoomStyle
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.components.UserAvatar
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.data.api.ServerEndpoint
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.util.flow
import io.livekit.android.room.Room
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun ConferenceEntryPanel(modifier: Modifier = Modifier, onStarted: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as MessengerApp
    val controller = app.conferenceController
    var create by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var capacity by remember { mutableIntStateOf(30) }
    var link by remember { mutableStateOf("") }
    val state by controller.state.collectAsState()
    val authState by app.authRepository.authState.collectAsState()
    val epoch = app.authRepository.currentSessionEpoch()
    val origin = ServerEndpoint.current()
    var owned by remember(epoch, origin, authState) { mutableStateOf(false) }
    val permissions = rememberConferencePermissions()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { create = true }, enabled = !state.active, modifier = Modifier.weight(1f)) { Text("Создать комнату") }
            OutlinedButton(onClick = { paste = true }, enabled = !state.active, modifier = Modifier.weight(1f)) { Text("По ссылке") }
        }
        TextButton(onClick = { owned = true }, enabled = !state.active, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Icon(painterResource(R.drawable.ic_conference_people), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("Мои комнаты")
        }
    }
    if (owned) OwnedConferenceRoomsDialog(controller, origin, epoch) { owned = false }
    if (create) AlertDialog(onDismissRequest = { create = false; title = "" }, title = { Text("Новая конференция") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = ConferenceInvitation.limitTitle(it) }, label = { Text("Название") }, singleLine = true)
            ConferenceCapacitySlider(capacity, { capacity = it })
            Text("Камера и микрофон при входе выключены. Звук не передаётся, пока вы не включите микрофон. Приглашайте через доверенный канал.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = ConferenceInvitation.validTitle(title.trim()) && capacity in 2..30, onClick = {
        val name = title; val limit = capacity; create = false; title = ""
        permissions(false) { onStarted(); controller.create(name, limit) }
    }) { Text("Создать") } }, dismissButton = { TextButton(onClick = { create = false; title = "" }) { Text("Отмена") } })
    if (paste) AlertDialog(onDismissRequest = { paste = false; link = "" }, title = { Text("Войти по приглашению") }, text = {
        OutlinedTextField(link, { link = it.take(2048) }, label = { Text("Ссылка coco://…") }, maxLines = 4)
    }, confirmButton = { TextButton(onClick = { controller.offerInvite(link); link = ""; paste = false; onStarted() }, enabled = link.isNotBlank()) { Text("Проверить") } }, dismissButton = { TextButton(onClick = { paste = false; link = "" }) { Text("Отмена") } })
}

/** Owner cleanup needs no room key; never imply that missing invitation keys can be restored. */
@Composable private fun OwnedConferenceRoomsDialog(controller: ConferenceController, origin: String, epoch: Int, onClose: () -> Unit) {
    val c = LocalCocoColors.current
    var rooms by remember { mutableStateOf(emptyList<ConferenceDto>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var ending by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<ConferenceDto?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(origin, epoch, attempt) {
        loading = true; error = null
        try { rooms = kotlinx.coroutines.withTimeout(5_000) { controller.ownedRooms(origin, epoch) } }
        catch (_: UnsupportedOperationException) { error = "Этот сервер пока не поддерживает список ваших комнат." }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) { error = "Сервер не ответил. Повторите попытку." }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Не удалось загрузить комнаты. Проверьте соединение и повторите." }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { if (ending == null) onClose() }, title = { Text("Мои комнаты") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Завершите комнаты, которые больше не нужны. Для повторного входа нужна сохранённая ссылка приглашения.", style = MaterialTheme.typography.bodySmall, color = c.mutedText)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (error == null && rooms.isEmpty()) Text("Открытых комнат нет")
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.mutedText) }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(rooms.size, key = { rooms[it].id }) { index ->
                    val room = rooms[index]
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(room.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Участников: ${room.participants.size}", style = MaterialTheme.typography.labelSmall, color = c.mutedText)
                        }
                        IconButton(onClick = { confirm = room }, enabled = ending == null) {
                            Icon(CocoIcons.EndCall, "Завершить комнату ${room.title}", tint = c.danger)
                        }
                    }
                }
            }
            if (ending != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = onClose, enabled = ending == null) { Text("Закрыть") } },
        dismissButton = { if (!loading && error != null) TextButton(onClick = { attempt++ }, enabled = ending == null) { Text("Повторить") } })
    confirm?.let { selected ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Завершить комнату?") },
            text = { Text("«${selected.title}» закроется для всех. Старое приглашение перестанет работать.") },
            confirmButton = { TextButton(onClick = {
                confirm = null; ending = selected.id
                scope.launch {
                    try {
                        kotlinx.coroutines.withTimeout(5_000) { controller.endOwnedRoom(selected.id, origin, epoch) }
                        rooms = rooms.filterNot { it.id == selected.id }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) { error = "Сервер не подтвердил завершение. Обновите список перед повтором." }
                      catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                      catch (_: Exception) { error = "Не удалось завершить комнату. Повторите попытку." }
                    finally { ending = null }
                }
            }) { Text("Завершить", color = c.danger) } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } })
    }
}

/** Requests the microphone at explicit join; camera permission only when camera is enabled. */
private class ConferencePermissionRequest(val action: () -> Unit, val required: List<String>, val epoch: Int, val origin: String)

@Composable
internal fun rememberConferencePermissions(): (Boolean, () -> Unit) -> Unit {
    val context = LocalContext.current
    val auth = (context.applicationContext as MessengerApp).authRepository
    var pending by remember { mutableStateOf<ConferencePermissionRequest?>(null) }
    var denied by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun current(request: ConferencePermissionRequest): Boolean = auth.currentSessionEpoch() == request.epoch && ServerEndpoint.current() == request.origin
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = pending; pending = null
        if (request != null && current(request)) {
            if (request.required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) request.action()
            else denied = request.epoch to request.origin
        }
    }
    if (denied?.let { it.first == auth.currentSessionEpoch() && it.second == ServerEndpoint.current() } == true) AlertDialog(
        onDismissRequest = { denied = null }, text = { Text("Android требует доступ к микрофону для конференции. Звук не передаётся, пока вы не включите микрофон. Разрешение можно изменить в настройках приложения.") },
        confirmButton = { TextButton(onClick = {
            denied = null
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
        }) { Text("Настройки") } }, dismissButton = { TextButton(onClick = { denied = null }) { Text("Отмена") } })
    return { camera, action ->
        val required = buildList { add(Manifest.permission.RECORD_AUDIO); if (camera) add(Manifest.permission.CAMERA) }
        val request = ConferencePermissionRequest(action, required, auth.currentSessionEpoch(), ServerEndpoint.current())
        val needed = buildList { addAll(required); if (android.os.Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT) }
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) { if (current(request)) action() } else { pending = request; launcher.launch(needed.toTypedArray()) }
    }
}

@Composable
fun ConferenceOverlay(controller: ConferenceController, deviceLocked: Boolean = false, showMinimizedStrip: Boolean = true, onMinimizedHeight: (androidx.compose.ui.unit.Dp) -> Unit = {}) {
    val state by controller.state.collectAsState()
    val pending by controller.pendingInvite.collectAsState()
    val passwordRequired by controller.invitePasswordRequired.collectAsState()
    val context = LocalContext.current
    val colors = LocalCocoColors.current
    val slotBusy by controller.captureReservation.collectAsState()
    val auth by (context.applicationContext as MessengerApp).authRepository.authState.collectAsState()
    val loggedIn = auth is com.messenger.app.domain.model.AuthSessionState.LoggedIn
    val permissions = rememberConferencePermissions()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val owner = controller.beginScreenVisibility()
        val observer = LifecycleEventObserver { _, _ -> controller.setScreenVisible(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        lifecycle.addObserver(observer)
        controller.setScreenVisible(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); controller.endScreenVisibility(owner) }
    }
    pending?.takeUnless { deviceLocked }?.let { invitation ->
        var password by remember(invitation, passwordRequired) { mutableStateOf("") }
        val sameServer = invitation.server == ServerEndpoint.current()
        AlertDialog(onDismissRequest = controller::dismissInvite, containerColor = ConferenceRoomStyle.panel(colors),
            icon = { Icon(painterResource(R.drawable.ic_conference_people), null, tint = colors.accent) }, title = { Text("Войти в комнату") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!sameServer) Text("Сервер: ${runCatching { java.net.URI(invitation.server).authority }.getOrDefault(invitation.server)}", style = MaterialTheme.typography.bodySmall)
                if (passwordRequired) {
                    OutlinedTextField(password, { password = it }, label = { Text("Пароль комнаты") }, keyboardOptions = conferencePasswordKeyboardOptions, singleLine = true,
                        shape = RoundedCornerShape(14.dp), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("conference_invitation_password"))
                }
                if (sameServer && loggedIn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(CocoIcons.Security, null, Modifier.size(19.dp), tint = colors.accent)
                        Text("Сквозное шифрование", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Камера и микрофон при входе выключены.", style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
                } else Text("Для входа выберите этот сервер и авторизуйтесь в настройках. Приглашение не меняет сервер автоматически.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { Button(enabled = sameServer && loggedIn && !slotBusy && (!passwordRequired || password.isNotEmpty()),
            modifier = Modifier.heightIn(min = 48.dp).testTag("conference_invitation_join"), onClick = { val supplied = password.takeIf { passwordRequired }; permissions(false) { controller.join(invitation, supplied) }; password = "" }) { Text("Войти") } },
            dismissButton = { TextButton(onClick = controller::dismissInvite, modifier = Modifier.heightIn(min = 48.dp)) { Text("Отмена") } })
    }
    if (state.active && loggedIn && controller.belongsToCurrentSession() && !deviceLocked) {
        val activity = context as? MainActivity
        DisposableEffect(activity) {
            val previous = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            onDispose { if (previous != null) activity.requestedOrientation = previous }
        }
        if (state.minimized) {
            if (showMinimizedStrip) ConferenceReturnStrip(controller, state, onMinimizedHeight)
            LaunchedEffect(Unit) { controller.suspendPresentation() }
        } else ConferenceRoomScreen(controller, state)
    } else if (deviceLocked) {
        LaunchedEffect(Unit) { controller.setVisible(emptySet()) }
    }
    state.error?.takeUnless { deviceLocked }?.let { message -> ConferenceErrorDialog(message, retryAt = state.joinRetryAt, onDismiss = controller::dismissError) }
}

@Composable internal fun ConferenceInvitationUnavailableDialog(onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, text = { Text(stringResource(R.string.conference_invite_requires_original)) },
        confirmButton = { TextButton(onClick = onClose) { Text("OK") } })
}

@Composable internal fun ConferenceErrorDialog(message: Int, retryAt: Long? = null, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(message))
        retryAt?.let { Text(stringResource(R.string.conference_join_retry_time, android.text.format.DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current).format(java.util.Date(it * 1000)))) }
    } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}

@Composable
private fun ConferenceRoomScreen(controller: ConferenceController, state: ConferenceState) = CompactConferenceRoom(controller, state)

/** Explicit recipient selection uses the existing Signal text-message send path. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConferenceShareDialog(controller: ConferenceController, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val colors = LocalCocoColors.current
    val view = androidx.compose.ui.platform.LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val chats by app.chatRepository.chats.collectAsState(initial = emptyList())
    val authState by app.authRepository.authState.collectAsState()
    val roomState by controller.state.collectAsState()
    val epoch = remember(controller) { app.authRepository.currentSessionEpoch() }
    val origin = remember(controller) { ServerEndpoint.current() }
    val roomId = remember(controller) { controller.state.value.roomId }
    val card = remember(controller) { controller.invitationCard() }
    val link = remember(controller) { controller.inviteLink() }
    fun currentScope(): Boolean = app.authRepository.currentSessionEpoch() == epoch &&
        ServerEndpoint.current() == origin && controller.belongsToCurrentSession() &&
        controller.state.value.roomId == roomId && controller.inviteLink() == link
    val validScope = authState is AuthSessionState.LoggedIn && roomState.roomId == roomId && currentScope()
    LaunchedEffect(validScope) { if (!validScope) onClose() }
    if (!validScope) return
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var queued by remember { mutableStateOf(setOf<String>()) }
    val drafts = remember { mutableMapOf<String, ConferenceInvitation>() }
    var search by remember { mutableStateOf("") }
    val busyState = remember { mutableStateOf(false) }
    var busy by busyState
    var refreshing by remember { mutableStateOf(true) }
    var refreshFailed by remember { mutableStateOf(false) }
    var refreshAttempt by remember { mutableIntStateOf(0) }
    var failures by remember { mutableStateOf(setOf<String>()) }
    val owner = (authState as? AuthSessionState.LoggedIn)?.user?.id
    val eligible = remember(chats, owner) { chats.filter { chat ->
        chat.type == "direct" && !chat.isBlocked && chat.otherUser?.let { it.id != owner } == true
    } }
    val eligibleIds = remember(eligible) { eligible.mapTo(mutableSetOf()) { it.chatId } }
    val currentEligibleIds by rememberUpdatedState(eligibleIds)
    val candidates = remember(eligible, search) { eligible.filter {
        val peer = requireNotNull(it.otherUser)
        peer.displayName.contains(search.trim(), true) || peer.username.contains(search.trim().removePrefix("@"), true)
    } }
    LaunchedEffect(eligibleIds) { selected = selected.intersect(eligibleIds); failures = failures.intersect(eligibleIds) }
    // Refresh known chat metadata once on opening, or on an explicit retry. The
    // repository keeps history visibility and account fences; search is local.
    LaunchedEffect(epoch, origin, refreshAttempt) {
        refreshing = true; refreshFailed = false
        try {
            val result = app.chatRepository.refreshChats()
            if (currentScope()) refreshFailed = result.isFailure
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { if (currentScope()) refreshFailed = true }
        finally { if (currentScope()) refreshing = false }
    }
    fun toggle(id: String) {
        if (!busy && currentScope() && id in currentEligibleIds && id !in queued) {
            com.messenger.app.ui.theme.CocoHaptics.select(view)
            selected = if (id in selected) selected - id else selected + id
        }
    }
    fun send() {
        val invitation = card ?: return
        if (busy || selected.isEmpty() || !currentScope()) return
        val pending = selected.toList()
        busy = true; failures = emptySet()
        scope.launch {
            var addedToQueue = false
            try {
                for (chat in pending) {
                    if (!currentScope()) return@launch
                    if (chat in queued || chat !in currentEligibleIds) continue
                    val payload = drafts.getOrPut(chat) { invitation.copy(id = java.util.UUID.randomUUID().toString()) }
                    val result = app.messageRepository.sendConferenceInvitation(chat, payload, epoch)
                    if (!currentScope()) return@launch
                    if (result.isSuccess) { queued = queued + chat; selected = selected - chat; addedToQueue = true }
                    else failures = failures + chat
                }
                if (currentScope() && selected.isEmpty() && addedToQueue) {
                    android.widget.Toast.makeText(context, "Приглашения добавлены в очередь", android.widget.Toast.LENGTH_SHORT).show()
                    onClose()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            finally { if (currentScope()) busy = false }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !busyState.value || it != SheetValue.Hidden })
    ModalBottomSheet(sheetState = sheetState, onDismissRequest = { if (!busy) onClose() },
        shape = CocoShapes.radiusSheetTop, containerColor = colors.elevated, contentColor = colors.primaryText,
        scrimColor = colors.scrim, dragHandle = { BottomSheetDefaults.DragHandle(color = colors.veryMutedText) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .88f)
            .imePadding().navigationBarsPadding().testTag("conference_invite_picker")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Пригласить", style = MaterialTheme.typography.titleLarge)
                    Text(roomState.title.ifBlank { "В комнату" }, style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose, enabled = !busy, modifier = Modifier.size(48.dp).testTag("conference_invite_close")) {
                    Icon(CocoIcons.Close, "Закрыть приглашение", tint = colors.primaryText)
                }
            }
            OutlinedTextField(search, { search = it.take(80) }, enabled = !busy, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                placeholder = { Text("Имя или логин") }, leadingIcon = { Icon(CocoIcons.Search, null, tint = colors.mutedText) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }, enabled = !busy) {
                    Icon(CocoIcons.Close, "Очистить поиск", Modifier.size(18.dp), tint = colors.mutedText)
                } }, shape = CocoShapes.radiusSearch,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.primaryText, unfocusedTextColor = colors.primaryText,
                    cursorColor = colors.accent, focusedContainerColor = colors.inputSurface, unfocusedContainerColor = colors.inputSurface,
                    focusedBorderColor = colors.outlineSelected, unfocusedBorderColor = colors.outlineSubtle),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("conference_invite_search"))
            if (refreshing && eligible.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.outlineSubtle)
            if (refreshFailed && eligible.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Не удалось обновить. Показаны сохранённые чаты.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
                TextButton(onClick = { refreshAttempt++ }, enabled = !refreshing && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Повторить") }
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("conference_invite_candidates"),
                contentPadding = PaddingValues(horizontal = 12.dp)) {
                if (candidates.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            refreshing && eligible.isEmpty() -> {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = colors.accent)
                                Text("Загружаем собеседников…", color = colors.mutedText, style = MaterialTheme.typography.bodyMedium)
                            }
                            refreshFailed && eligible.isEmpty() -> {
                                Text("Не удалось загрузить чаты", style = MaterialTheme.typography.bodyLarge)
                                Text("Проверьте соединение и повторите.", color = colors.mutedText, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { refreshAttempt++ }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("conference_invite_retry")) { Text("Повторить") }
                            }
                            search.isNotBlank() -> {
                                Text("Никого не нашли", style = MaterialTheme.typography.bodyLarge)
                                Text("Попробуйте другое имя или логин.", color = colors.mutedText, style = MaterialTheme.typography.bodySmall)
                            }
                            else -> {
                                Text("Пока некого пригласить", style = MaterialTheme.typography.bodyLarge)
                                Text("Здесь появятся ваши личные чаты. Или поделитесь ссылкой.", color = colors.mutedText, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                items(candidates.size, key = { candidates[it].chatId }) { index ->
                    val chat = candidates[index]
                    val peer = requireNotNull(chat.otherUser)
                    val name = peer.displayName.ifBlank { peer.username }
                    val chosen = chat.chatId in selected || chat.chatId in queued
                    val enabled = !busy && chat.chatId !in queued
                    Row(Modifier.fillMaxWidth().clip(CocoShapes.radiusMedium)
                        .toggleable(value = chosen, enabled = enabled, role = Role.Checkbox, onValueChange = { toggle(chat.chatId) })
                        .heightIn(min = 68.dp).padding(horizontal = 8.dp, vertical = 8.dp).testTag("conference_invite_peer_${chat.chatId}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        UserAvatar(userId = peer.id, avatarVersion = peer.avatarVersion, hasAvatar = peer.hasAvatar, name = name,
                            color = colors.categoryViolet.copy(alpha = .14f), contentColor = colors.categoryViolet, size = 44.dp,
                            modifier = Modifier.testTag("conference_invite_avatar_${peer.id}"))
                        Column(Modifier.weight(1f)) {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(when { chat.chatId in queued -> "В очереди отправки"; chat.chatId in failures -> "Не удалось отправить · повторите"; else -> "@${peer.username}" },
                                color = if (chat.chatId in failures) colors.danger else colors.mutedText, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Surface(Modifier.size(24.dp), shape = CircleShape, color = if (chosen) colors.accent else Color.Transparent,
                            border = if (chosen) null else androidx.compose.foundation.BorderStroke(1.5.dp, colors.veryMutedText)) {
                            Box(contentAlignment = Alignment.Center) { if (chosen) Icon(CocoIcons.Check, null, Modifier.size(16.dp), tint = colors.onAccent) }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = colors.outlineSubtle)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(CocoIcons.Security, null, Modifier.size(14.dp), tint = colors.mutedText)
                Text(if (card == null) "Приглашение недоступно. Вернитесь в комнату и попробуйте ещё раз."
                    else if (selected.isEmpty()) "Защищённые приглашения в чаты" else "Выбрано: ${selected.size}",
                    style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                val compactLink = LocalDensity.current.fontScale > 1.3f || maxWidth < 320.dp
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = !busy && link != null && card != null, onClick = {
                        val invitation = link ?: return@OutlinedButton
                        if (!currentScope()) return@OutlinedButton
                        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invitation), "Поделиться приглашением")) }
                        onClose()
                    }, shape = CocoShapes.radiusMedium,
                        modifier = (if (compactLink) Modifier.size(48.dp) else Modifier.heightIn(min = 48.dp)).testTag("conference_invite_share_link"),
                        contentPadding = if (compactLink) PaddingValues(0.dp) else ButtonDefaults.ContentPadding,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primaryText)) {
                        Icon(painterResource(R.drawable.ic_conference_link), if (compactLink) "Поделиться приглашением" else null, Modifier.size(18.dp))
                        if (!compactLink) { Spacer(Modifier.width(6.dp)); Text("Ссылка") }
                    }
                    Button(onClick = ::send, enabled = selected.isNotEmpty() && card != null && !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("conference_invite_send"), shape = CocoShapes.radiusMedium,
                        contentPadding = if (compactLink) PaddingValues(horizontal = 16.dp, vertical = 8.dp) else ButtonDefaults.ContentPadding,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent,
                            disabledContainerColor = colors.surfaceVariant, disabledContentColor = colors.veryMutedText)) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = colors.onAccent, strokeWidth = 2.dp)
                        else Icon(CocoIcons.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text(if (busy) "Отправляем…" else if (selected.isEmpty()) "Пригласить" else "Пригласить (${selected.size})")
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConferenceVideoTile(room: Room?, tile: ConferenceTile, modifier: Modifier, corner: androidx.compose.ui.unit.Dp = 18.dp, compact: Boolean = false) {
    val colors = LocalCocoColors.current
    val background = remember(colors) { ConferenceRoomStyle.tile(colors) }
    Box(modifier.clip(RoundedCornerShape(corner)).background(background)
        .border(if (tile.speaking) 1.5.dp else .5.dp, if (tile.speaking) colors.accent else ConferenceRoomStyle.outline(colors), RoundedCornerShape(corner))) {
        val context = LocalContext.current
        val track = tile.video
        if (room != null && track != null) {
            val front = if (track is LocalVideoTrack) {
                val options by remember(track) { track::options.flow }.collectAsState()
                options.position == CameraPosition.FRONT
            } else false
            val mirrored = ConferencePresentationPolicy.mirrorsPreview(track is LocalVideoTrack, front)
            val renderer = remember(room, track) {
                TextureViewRenderer(context).apply {
                    room.initVideoRenderer(this)
                    setMirror(mirrored)
                    keepScreenOn = true
                }
            }
            // Camera switches keep the track identity; update only its local view.
            SideEffect { renderer.setMirror(mirrored) }
            DisposableEffect(renderer, track) {
                track.addRenderer(renderer)
                onDispose { track.removeRenderer(renderer); renderer.release() }
            }
            // A reconnected publication may replace the track under the same
            // participant ID. Rebind the native view as well as its renderer.
            ConferenceRendererView(renderer)
        } else if (!compact) Surface(Modifier.align(Alignment.Center).size(64.dp), shape = CircleShape, color = colors.primaryText.copy(alpha = .07f), contentColor = colors.primaryText) {
            Box(contentAlignment = Alignment.Center) { Text(tile.name.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium) }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(if (compact) 2.dp else 8.dp).clip(RoundedCornerShape(if (compact) 6.dp else 12.dp)).background(Color.Black.copy(alpha = .5f))
            .padding(horizontal = if (compact) 3.dp else 8.dp, vertical = if (compact) 1.dp else 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(tile.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Color.White, style = MaterialTheme.typography.labelSmall)
            if (!compact && tile.audioMutedForMe) Icon(painterResource(R.drawable.ic_conference_volume_off), "Звук выключен только для вас", Modifier.size(14.dp), tint = Color.White.copy(alpha = .8f))
            else if (!compact && tile.muted) Icon(CocoIcons.MicOff, "Микрофон выключен", Modifier.size(14.dp), tint = Color.White.copy(alpha = .8f))
            if (!compact && tile.videoHiddenForMe) Icon(CocoIcons.EyeOff, "Видео скрыто только для вас", Modifier.size(14.dp), tint = Color.White.copy(alpha = .8f))
        }
    }
}

/** Shared with a native binding test; changing a publication renderer must replace its View. */
@Composable internal fun ConferenceRendererView(renderer: android.view.View) {
    key(renderer) { AndroidView(factory = { renderer }, modifier = Modifier.fillMaxSize()) }
}

@Composable
private fun ConferenceControl(label: String, description: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit, danger: Boolean = false) {
    val colors = LocalCocoColors.current
    TextButton(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        colors = ButtonDefaults.textButtonColors(contentColor = if (danger) colors.danger else colors.primaryText),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConferenceMediaControl(label: String, description: String, modifier: Modifier, enabled: Boolean,
    accent: Color, icon: ImageVector? = null, iconResource: Int? = null, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    FilledTonalButton(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = accent.copy(alpha = .14f), contentColor = accent,
            disabledContainerColor = colors.surfaceVariant, disabledContentColor = colors.veryMutedText),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp))
        else if (iconResource != null) Icon(painterResource(iconResource), null, Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
