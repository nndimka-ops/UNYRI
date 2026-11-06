package com.messenger.app.conference

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal val conferencePasswordKeyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
)

@Composable fun ConferenceRoomsScreen(onOpenMenu: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as MessengerApp
    val scope = rememberCoroutineScope()
    val epoch = app.authRepository.currentSessionEpoch()
    val origin = com.messenger.app.data.api.ServerEndpoint.current()
    val directory = remember(epoch, origin) { ConferenceRoomsController(app.conferenceApi, app.conferenceKeyMailbox, app.authRepository, app.conferenceController, scope, origin) }
    val state by directory.state.collectAsState()
    val active by app.conferenceController.state.collectAsState()
    val slotBusy by app.conferenceController.captureReservation.collectAsState()
    val permissions = rememberConferencePermissions()
    val c = LocalCocoColors.current; val view = LocalView.current
    var form by rememberSaveable(epoch, origin) { mutableStateOf<String?>(null) }
    var selected by remember(epoch, origin) { mutableStateOf<DirectoryRoom?>(null) }
    var end by remember(epoch, origin) { mutableStateOf<ConferenceDto?>(null) }
    com.messenger.app.ui.BlockRootDrawerWhile(form != null || selected != null || state.waitingRoom != null || end != null || state.error != null)
    fun allowNewRoomAction(): Boolean {
        if (app.conferenceController.reservesCallSlot || !app.callController.canBeginConference()) {
            app.conferenceController.reportMediaBusy()
            return false
        }
        return true
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(directory, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) directory.pause()
            if (event == Lifecycle.Event.ON_START) { val room = app.conferenceController.state.value; if (!room.active || room.minimized) directory.start() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); directory.dispose() }
    }
    LaunchedEffect(active.active, active.minimized) { if (active.active && !active.minimized) directory.pause() else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) directory.start() }
    BackHandler(form != null || selected != null || state.waitingRoom != null) { form = null; selected = null; directory.cancelJoin() }
    when {
        form == "create" -> ConferenceRoomEditor(title = "Создать комнату", accessV2 = state.capabilities?.roomAccessV2 == true, approvalAvailable = state.capabilities?.roomJoinApproval == true,
            onCancel = { form = null; directory.select(true) }, onCreate = { options -> permissions(false) { if (directory.isCurrentScreen() && form == "create") { form = null; directory.select(true); app.conferenceController.create(options) } } }, modifier = modifier)
        form == "invite" -> ConferenceJoinByInvitation(onCancel = { form = null }, onJoin = { link ->
            val invite = runCatching { ConferenceInvite.parseForServer(link, origin) }.getOrNull()
            if (invite == null) app.conferenceController.offerInvite(link) else { form = null; app.conferenceController.offerInvite(link) }
        }, modifier = modifier)
        selected != null -> ConferenceCatalogAdmission(selected!!, state, onCancel = { directory.cancelJoin(); selected = null }, onJoin = { password ->
            CocoHaptics.select(view); if (allowNewRoomAction()) directory.requestJoin(selected!!, password) { value -> permissions(false) { directory.admit(value) } }
        }, modifier = modifier)
        else -> Column(modifier.fillMaxSize().navigationBarsPadding().testTag("conference_rooms_destination")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenMenu, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Menu, "Открыть меню") }
                Text("Комнаты", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { CocoHaptics.select(view); directory.refresh() }, enabled = !state.loading, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Refresh, "Обновить") }
                FilledTonalIconButton(onClick = { CocoHaptics.select(view); if (allowNewRoomAction()) form = "create" }, enabled = state.capabilities != null,
                    modifier = Modifier.size(48.dp).testTag("conference_directory_create")) { Icon(Icons.Default.Add, "Создать комнату", Modifier.size(23.dp)) }
            }
            ConferenceRoomTabs(state.mine, state.capabilities?.roomDirectory == true) { mine -> CocoHaptics.select(view); directory.select(mine) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.query, directory::search, Modifier.weight(1f).testTag("conference_directory_search"), placeholder = { Text("Найти комнату") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), leadingIcon = { Icon(CocoIcons.Search, null, Modifier.size(21.dp)) })
                OutlinedIconButton(onClick = { CocoHaptics.select(view); if (allowNewRoomAction()) form = "invite" }, modifier = Modifier.size(48.dp).testTag("conference_directory_invitation")) {
                    Icon(painterResource(R.drawable.ic_conference_link), "Войти по приглашению", Modifier.size(21.dp))
                }
            }
            if (active.active && app.conferenceController.belongsToCurrentSession()) OutlinedButton(onClick = { app.conferenceController.minimize(false) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)) { Text("Вернуться: ${active.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (active.active) Text(stringResource(R.string.media_busy_room), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = c.mutedText, style = MaterialTheme.typography.bodySmall)
            if (!active.active && slotBusy) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("conference_leaving_progress"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                Text("Завершаем выход…", color = c.mutedText, style = MaterialTheme.typography.bodySmall)
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.accent)
            if (state.capabilities != null && state.capabilities?.roomDirectory != true) Text("На этом сервере пока доступны только комнаты по приглашению. Для каталога нужно обновление сервера.", Modifier.padding(16.dp), color = c.mutedText, style = MaterialTheme.typography.bodySmall)
            val owned = state.owned.filter { it.title.contains(state.query, true) || it.description.contains(state.query, true) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.mine) item { Text("До 3 активных комнат · каждая на 24 часа", color = c.mutedText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
                if (state.mine) items(owned, key = { it.id }) { room ->
                    val current = active.roomId == room.id && app.conferenceController.belongsToCurrentSession()
                    val saved = app.conferenceController.savedOwnerInvite(room) != null
                    // Keep actions tappable so a blocked attempt explains why before requesting permissions.
                    ConferenceOwnedRoomCard(room, current, saved, slotBusy = false, onOpen = {
                        CocoHaptics.select(view)
                        if (!current && !allowNewRoomAction()) return@ConferenceOwnedRoomCard
                        when {
                            current -> app.conferenceController.minimize(false)
                            saved -> permissions(false) { if (directory.isCurrentScreen()) app.conferenceController.rejoinOwnedRoom(room) }
                            else -> form = "invite"
                        }
                    }, onEnd = { end = room })
                } else items(state.rooms, key = { it.id }) { room ->
                    val mode = ConferenceAccess.parse(room.accessMode)
                    val full = room.participantCount >= room.maxParticipants
                    val current = active.active && active.roomId == room.id && app.conferenceController.belongsToCurrentSession()
                    val mayJoin = mode?.invitation == false && !room.admissionLocked && !full && state.capabilities?.roomKeyHandoff == true
                    val openRoom: () -> Unit = {
                        CocoHaptics.select(view)
                        if (current) app.conferenceController.minimize(false)
                        else if (mayJoin && allowNewRoomAction()) selected = room
                    }
                    ConferenceRoomCard(room.title, room.description, room.ownerDisplayName, room.participantCount, room.maxParticipants, room.accessMode, room.admissionLocked, room.expiresAt,
                        onClick = openRoom,
                        enabled = current || mayJoin,
                        action = { if (current) TextButton(onClick = openRoom) { Text("Вернуться") }
                            else if (mode?.invitation == true) Text("Нужно приглашение", color = c.mutedText, style = MaterialTheme.typography.labelMedium) else if (mode == null) Text("Нужно обновить приложение", color = c.mutedText, style = MaterialTheme.typography.labelMedium)
                            else if (state.capabilities?.roomKeyHandoff != true) Text("Нужно обновление сервера", color = c.mutedText, style = MaterialTheme.typography.labelMedium)
                            else TextButton(enabled = mayJoin, onClick = openRoom, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (room.admissionLocked) "Вход закрыт" else if (full) "Заполнена" else "Войти") } })
                }
                if (!state.loading && (if (state.mine) owned.isEmpty() else state.rooms.isEmpty())) item {
                    ConferenceRoomsEmpty(state.mine, state.query.isNotBlank(), state.capabilities != null,
                        onCreate = { CocoHaptics.select(view); if (allowNewRoomAction()) form = "create" }, onInvitation = { CocoHaptics.select(view); if (allowNewRoomAction()) form = "invite" })
                }
                if (!state.mine && state.cursor != null) item { TextButton(onClick = { directory.refresh(next = true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Показать ещё") } }
            }
        }
    }
    state.error?.let { message -> ConferenceErrorDialog(message, retryAt = state.joinRetryAt) { directory.dismissError(); if (message == R.string.conference_settings_changed || message == R.string.conference_error_closed || message == R.string.conference_error_removed) { selected = null; directory.refresh() } } }
    end?.let { room -> AlertDialog(onDismissRequest = { end = null }, title = { Text("Завершить комнату?") }, text = { Text("${room.title}: все участники отключатся. Старые приглашения перестанут работать.") }, confirmButton = { TextButton(onClick = { end = null; CocoHaptics.select(view); directory.end(room) }) { Text("Завершить", color = c.danger) } }, dismissButton = { TextButton(onClick = { end = null }) { Text("Отмена") } }) }
}

@Composable internal fun ConferenceRoomTabs(mine: Boolean, serverAvailable: Boolean, onSelect: (Boolean) -> Unit) {
    val c = LocalCocoColors.current
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp), color = c.surface) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(false to "На сервере", true to "Мои").forEach { (value, label) ->
                TextButton(onClick = { onSelect(value) }, enabled = value || serverAvailable,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                        .testTag(if (value) "conference_tab_mine" else "conference_tab_server"),
                    colors = ButtonDefaults.textButtonColors(containerColor = if (mine == value) c.elevated else androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = if (mine == value) c.primaryText else c.mutedText)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable internal fun ConferenceRoomsEmpty(mine: Boolean, searching: Boolean, canCreate: Boolean, onCreate: () -> Unit, onInvitation: () -> Unit) {
    val c = LocalCocoColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp).testTag("conference_rooms_empty"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.ic_conference_people), null, Modifier.size(36.dp), tint = c.mutedText)
        Text(if (searching) "Ничего не найдено" else if (mine) "Ваши комнаты" else "Пока нет открытых комнат", style = MaterialTheme.typography.titleMedium)
        Text(if (searching) "Попробуйте другое название." else if (mine) "Создайте комнату для разговора и приглашайте людей."
            else "Начните свою встречу или войдите по приглашению.", color = c.mutedText, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (!searching) Button(onClick = onCreate, enabled = canCreate, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
            Icon(Icons.Default.Add, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Создать комнату")
        }
        TextButton(onClick = onInvitation, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_conference_link), null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Есть приглашение")
        }
    }
}

@Composable internal fun ConferenceOwnedRoomCard(room: ConferenceDto, current: Boolean, savedKey: Boolean, slotBusy: Boolean, onOpen: () -> Unit, onEnd: () -> Unit) {
    val c = LocalCocoColors.current
    var menu by remember(room.id) { mutableStateOf(false) }
    val label = when { current -> "Вернуться"; savedKey -> "Войти снова"; else -> "По приглашению" }
    ConferenceRoomCard(room.title, room.description, "Вы", room.participants.size, room.maxParticipants, room.accessMode, room.admissionLocked, room.expiresAt,
        enabled = current || !slotBusy, onClick = onOpen,
        hint = if (!current && !savedKey) "На этом устройстве нет ключа комнаты. Для входа нужно прежнее приглашение." else null,
        trailing = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("conference_owned_menu_${room.id}")) { Icon(CocoIcons.MoreVert, "Действия с комнатой ${room.title}", Modifier.size(21.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Завершить для всех", color = c.danger) }, leadingIcon = { Icon(CocoIcons.EndCall, null, tint = c.danger) },
                        onClick = { menu = false; onEnd() }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }, action = {
            Button(onClick = onOpen, enabled = current || !slotBusy, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("conference_owned_join_${room.id}")) { Text(label, maxLines = 2) }
        })
}

@Composable internal fun ConferenceRoomCard(title: String, description: String, owner: String, count: Int, capacity: Int, mode: String, locked: Boolean, expires: String,
    onClick: () -> Unit, action: @Composable () -> Unit, hint: String? = null, enabled: Boolean = true, trailing: @Composable () -> Unit = {}) {
    val c = LocalCocoColors.current
    Surface(shape = RoundedCornerShape(20.dp), color = c.surface, contentColor = c.primaryText, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(enabled = enabled, onClick = onClick).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                trailing()
            }
            if (description.isNotBlank()) Text(description, color = c.mutedText, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$owner · $count / $capacity", color = c.mutedText, style = MaterialTheme.typography.bodySmall)
            hint?.let { Text(it, color = c.mutedText, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(painterResource(if (locked || ConferenceAccess.parse(mode)?.password == true) R.drawable.ic_conference_lock else if (ConferenceAccess.parse(mode)?.invitation == true) R.drawable.ic_conference_link else R.drawable.ic_conference_people), null, modifier = Modifier.size(15.dp), tint = c.mutedText)
                Text(if (locked) "Вход временно закрыт" else accessLabel(ConferenceAccess.parse(mode)), style = MaterialTheme.typography.labelMedium, color = c.mutedText)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("До ${expiryLabel(expires)} · автоотключение", Modifier.weight(1f), color = c.mutedText, style = MaterialTheme.typography.labelSmall)
                Box(Modifier.widthIn(max = 180.dp), contentAlignment = Alignment.CenterEnd) { action() }
            }
        }
    }
}
private fun expiryLabel(value: String): String = runCatching {
    val zone = ZoneId.systemDefault()
    val end = Instant.parse(value).atZone(zone)
    val pattern = if (end.toLocalDate() == java.time.LocalDate.now(zone)) "HH:mm" else "dd.MM HH:mm"
    DateTimeFormatter.ofPattern(pattern).format(end)
}.getOrDefault("—")
internal fun accessLabel(value: ConferenceAccess?): String = when (value) { ConferenceAccess.OPEN -> "Открытая"; ConferenceAccess.PASSWORD -> "По паролю"; ConferenceAccess.INVITE -> "По приглашению"; ConferenceAccess.INVITE_PASSWORD -> "Приглашение и пароль"; null -> "Неизвестный режим доступа" }

@Composable private fun ConferenceCatalogAdmission(room: DirectoryRoom, state: ConferenceRoomsState, onCancel: () -> Unit, onJoin: (String?) -> Unit, modifier: Modifier) {
    var password by remember(room.id) { mutableStateOf("") }
    val mode = ConferenceAccess.parse(room.accessMode)
    ConferenceFormFrame(room.title, onCancel, modifier, showCancel = state.waitingRoom == null, footer = {
        if (state.waitingRoom == null) Button(onClick = { onJoin(password.takeIf { mode?.password == true }); password = "" }, enabled = mode != null && (!mode.password || password.isNotEmpty()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("conference_catalog_join")) { Text("Войти") }
        else OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("conference_key_cancel")) { Text("Отменить ожидание") }
    }) {
        ConferenceFormSection("О комнате") {
            if (room.description.isNotBlank()) Text(room.description, style = MaterialTheme.typography.bodyMedium)
            Text("${room.ownerDisplayName} · ${room.participantCount} / ${room.maxParticipants}", style = MaterialTheme.typography.bodySmall, color = LocalCocoColors.current.mutedText)
            Text(accessLabel(mode), style = MaterialTheme.typography.labelMedium)
        }
        ConferenceFormSection(if (state.waitingRoom != null) "Защищённое подключение" else "Вход") {
            if (state.waitingRoom != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.approvalWaiting) stringResource(R.string.conference_join_waiting_creator) else "Ожидаем защищённый ключ от участника…", Modifier.testTag("conference_key_wait"))
                Text("Камера и микрофон ещё не подключены. До 60 секунд.", style = MaterialTheme.typography.bodySmall, color = LocalCocoColors.current.mutedText)
            } else {
                Text("Камера и микрофон будут выключены при входе.", style = MaterialTheme.typography.bodyMedium)
                if (!room.keyAvailable) Text("Никто пока не готов передать ключ. Можно подождать или попробовать позже.", style = MaterialTheme.typography.bodySmall, color = LocalCocoColors.current.mutedText)
                if (mode?.password == true) OutlinedTextField(password, { password = it }, label = { Text("Пароль комнаты") }, keyboardOptions = conferencePasswordKeyboardOptions,
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("conference_catalog_password"))
            }
        }
    }
}

@Composable internal fun ConferenceFormFrame(title: String, onCancel: () -> Unit, modifier: Modifier = Modifier, showCancel: Boolean = true,
    footer: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalCocoColors.current
    Surface(modifier.fillMaxSize(), color = c.background, contentColor = c.primaryText) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                com.messenger.app.ui.components.CocoBackButton(onClick = onCancel)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            Surface(color = c.background, contentColor = c.primaryText) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.4f
                    if (!showCancel) footer()
                    else if (stacked) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        footer()
                        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Отмена") }
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Отмена") }
                        Box(Modifier.weight(1.8f)) { footer() }
                    }
                }
            }
        }
    }
}

@Composable internal fun ConferenceFormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalCocoColors.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = c.mutedText)
        Surface(shape = RoundedCornerShape(20.dp), color = c.surface, contentColor = c.primaryText) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

private data class ConferenceEditorSnapshot(val title: String, val description: String, val listed: Boolean, val accessMode: String, val locked: Boolean, val revision: Long, val approvalRequired: Boolean)
private fun ConferenceState.editorSnapshot() = ConferenceEditorSnapshot(title, description, listed, accessMode, admissionLocked, settingsRevision, approvalRequired)

@Composable internal fun ConferenceCapacitySlider(value: Int, onValueChange: (Int) -> Unit, enabled: Boolean = true) {
    val c = LocalCocoColors.current
    val view = LocalView.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Участников", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Surface(color = c.accent.copy(alpha = .12f), shape = RoundedCornerShape(12.dp)) {
                Text(value.toString(), Modifier.widthIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                    color = c.accent, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Slider(value = value.toFloat(), onValueChange = { onValueChange(it.roundToInt().coerceIn(2, 30)) },
            onValueChangeFinished = { CocoHaptics.select(view) }, valueRange = 2f..30f, steps = 27, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("conference_room_capacity").semantics { contentDescription = "Количество участников" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("2", color = c.mutedText, style = MaterialTheme.typography.bodySmall)
            Text("30", color = c.mutedText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun ConferenceRoomEditor(title: String, accessV2: Boolean, onCancel: () -> Unit, onCreate: ((ConferenceCreateOptions) -> Unit)? = null,
    initial: ConferenceState? = null, onSave: ((ConferenceSettingsEdit) -> Unit)? = null, modifier: Modifier = Modifier, approvalAvailable: Boolean = initial?.approvalAvailable == true) {
    var baseline by remember(initial?.roomId) { mutableStateOf(initial?.editorSnapshot()) }
    var name by remember(initial?.roomId) { mutableStateOf(baseline?.title.orEmpty()) }
    var description by remember(initial?.roomId) { mutableStateOf(baseline?.description.orEmpty()) }
    var listed by remember(initial?.roomId) { mutableStateOf(baseline?.listed == true) }
    var mode by remember(initial?.roomId) { mutableStateOf(ConferenceAccess.parse(baseline?.accessMode ?: "invite")) }
    var password by remember(initial?.roomId) { mutableStateOf("") }
    var replacePassword by remember(initial?.roomId) { mutableStateOf(false) }
    var capacity by remember { mutableIntStateOf(30) }
    var locked by remember(initial?.roomId) { mutableStateOf(baseline?.locked == true) }
    var approvalRequired by remember(initial?.roomId) { mutableStateOf(baseline?.approvalRequired == true) }
    val c = LocalCocoColors.current; val view = LocalView.current
    val enabled = initial?.settingsBusy != true && initial?.connecting != true && initial?.reconnecting != true
    val needsPassword = mode?.password == true && (initial == null || replacePassword || ConferenceAccess.parse(baseline?.accessMode.orEmpty())?.password != true)
    LaunchedEffect(initial?.lastAppliedSettingsRevision, initial?.settingsRevision) {
        if (initial != null && initial.lastAppliedSettingsRevision > (baseline?.revision ?: 0) && initial.lastAppliedSettingsRevision == initial.settingsRevision) {
            baseline = initial.editorSnapshot(); replacePassword = false; password = ""
        }
    }
    val conflict = accessV2 && initial != null && baseline?.revision != initial.settingsRevision
    val dirty = baseline == null || name.trim() != baseline?.title || description != baseline?.description || listed != baseline?.listed || mode?.wire != baseline?.accessMode || locked != baseline?.locked || approvalRequired != baseline?.approvalRequired || needsPassword
    val valid = ConferenceInvitation.validTitle(name.trim()) && mode != null && runCatching { ConferenceDiscoveryPolicy.description(description); if (needsPassword) ConferenceDiscoveryPolicy.password(password) }.isSuccess && (initial != null || capacity in 2..30)
    ConferenceFormFrame(title, onCancel, modifier, footer = {
        Button(onClick = {
            val access = mode ?: return@Button
            CocoHaptics.select(view)
            if (initial == null) onCreate?.invoke(ConferenceCreateOptions(name, capacity, description, listed, access, password.takeIf { access.password }, approvalRequired))
            else onSave?.invoke(ConferenceSettingsEdit(name, description, listed, access, locked, requireNotNull(baseline).revision,
                passwordAction = if (needsPassword) "set" else if (!access.password) "remove" else "keep", password = password.takeIf { needsPassword }, approvalRequired = approvalRequired.takeIf { approvalAvailable }))
            password = ""
        }, enabled = valid && dirty && enabled && !conflict, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(if (initial == null) "conference_room_apply" else "conference_host_save")) { Text(if (initial?.settingsBusy == true) "Сохраняем…" else if (initial == null) "Создать" else "Применить") }
    }) {
        if (conflict) {
            Text("Настройки изменились на другом устройстве. Ваши изменения пока не применены. Загрузите актуальные значения, чтобы не перезаписать чужие изменения.", color = c.mutedText)
            TextButton(enabled = enabled, modifier = Modifier.testTag("conference_room_reload_settings"), onClick = {
                val latest = initial ?: return@TextButton
                baseline = latest.editorSnapshot(); name = latest.title; description = latest.description; listed = latest.listed
                mode = ConferenceAccess.parse(latest.accessMode); locked = latest.admissionLocked; approvalRequired = latest.approvalRequired; password = ""; replacePassword = false
            }) { Text("Загрузить актуальные") }
        }
        ConferenceFormSection("О комнате") {
            OutlinedTextField(name, { name = ConferenceInvitation.limitTitle(it) }, enabled = enabled, label = { Text("Название") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag(if (initial == null) "conference_room_title" else "conference_host_title"))
            if (accessV2) {
                val invalidDescription = runCatching { ConferenceDiscoveryPolicy.description(description) }.isFailure
                OutlinedTextField(description, { description = it }, enabled = enabled, label = { Text("Описание") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    isError = invalidDescription, supportingText = if (invalidDescription) ({ Text("До 240 символов, без переносов строк") }) else null,
                    modifier = Modifier.fillMaxWidth().testTag("conference_room_description"))
            }
            if (initial == null) {
                ConferenceCapacitySlider(capacity, { capacity = it }, enabled)
                Text("До 3 активных комнат · каждая на 24 часа", color = c.mutedText, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (accessV2) ConferenceFormSection("Доступ") {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Показывать на сервере", style = MaterialTheme.typography.bodyMedium)
                    Text(if (listed) "Комнату можно найти в списке" else "Комната скрыта из списка", color = c.mutedText, style = MaterialTheme.typography.bodySmall)
                }
                Switch(listed, { listed = it; CocoHaptics.select(view) }, enabled = enabled, modifier = Modifier.testTag("conference_room_listed"))
            }
            HorizontalDivider(color = c.outlineSubtle)
            Text("Кто может войти", style = MaterialTheme.typography.labelLarge, color = c.mutedText)
            Column {
                ConferenceAccess.entries.forEach { access ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .selectable(selected = mode == access, enabled = enabled, role = Role.RadioButton) { mode = access; CocoHaptics.select(view) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RadioButton(mode == access, onClick = null, enabled = enabled)
                        Text(accessLabel(access), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (mode?.password == true) {
                if (initial != null && ConferenceAccess.parse(baseline?.accessMode.orEmpty())?.password == true) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Заменить пароль", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(replacePassword, { replacePassword = it; password = "" }, enabled = enabled)
                }
                if (needsPassword) OutlinedTextField(password, { password = it }, enabled = enabled, label = { Text(if (initial == null) "Пароль комнаты" else "Новый пароль") }, keyboardOptions = conferencePasswordKeyboardOptions, singleLine = true,
                    shape = RoundedCornerShape(14.dp), isError = password.isNotEmpty() && runCatching { ConferenceDiscoveryPolicy.password(password) }.isFailure,
                    supportingText = { Text("6–128 символов. Пробелы учитываются.") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("conference_room_password"))
                else Text("Текущий пароль сохранится. Он не загружается на устройство.", color = c.mutedText, style = MaterialTheme.typography.bodySmall)
            }
        } else Text("Сервер поддерживает вход только по приглашению. Новые режимы появятся после обновления.", color = c.mutedText, style = MaterialTheme.typography.bodySmall)
        if (approvalAvailable) ConferenceFormSection(stringResource(R.string.conference_join_requests)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.conference_join_confirm), Modifier.weight(1f))
                Switch(approvalRequired, { approvalRequired = it }, enabled = enabled, modifier = Modifier.testTag("conference_approval_required"))
            }
            Text(stringResource(R.string.conference_join_explanation), color = c.mutedText, style = MaterialTheme.typography.bodySmall)
        }
        if (initial != null) ConferenceFormSection("Новые участники") {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Приостановить вход", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(locked, { locked = it }, enabled = enabled, modifier = Modifier.testTag("conference_host_lock"))
            }
            Text("Уже подключённые участники останутся в комнате. Изменения применятся после подтверждения.", color = c.mutedText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ConferenceJoinByInvitation(onCancel: () -> Unit, onJoin: (String) -> Unit, modifier: Modifier) {
    var link by remember { mutableStateOf("") }
    ConferenceFormFrame("Войти по приглашению", onCancel, modifier, footer = { Button(onClick = { onJoin(link) }, enabled = link.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Продолжить") } }) {
        Text("Вставьте полученную ссылку. Она содержит ключ комнаты — отправляйте её только доверенным людям.")
        OutlinedTextField(link, { link = it.take(2048) }, label = { Text("Приглашение UNYRI") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    }
}

/** The editor shares the already edge-to-edge activity window with the room.
 * A second WRAP_CONTENT Dialog window can measure beyond its usable height on OEMs. */
@Composable internal fun ConferenceHostEditor(controller: ConferenceController, state: ConferenceState, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize().testTag("conference_host_editor"), color = LocalCocoColors.current.background) {
        ConferenceRoomEditor("Настройки комнаты", state.roomAccessAvailable, onClose, initial = state,
            modifier = Modifier.fillMaxSize().safeDrawingPadding(), onSave = { edit ->
                if (state.roomAccessAvailable) controller.updateSettings(edit) else controller.updateSettings(edit.title, edit.locked)
            })
    }
}
