package com.messenger.app.conference

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.ConferenceRoomStyle
import com.messenger.app.ui.theme.LocalCocoColors
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

/** Focus covers the existing grid instead of replacing its native renderers. */
@Composable internal fun ConferenceVideoStage(focused: Boolean, grid: @Composable () -> Unit, focus: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (focused) Modifier.clearAndSetSemantics { } else Modifier)) { grid() }
        if (focused) Surface(Modifier.fillMaxSize(), color = LocalCocoColors.current.callBackground) { focus() }
    }
}

/** Own composition boundary: changing speaker/count must not rebuild the app navigation graph. */
@Composable internal fun ConferenceReturnStripHost(controller: ConferenceController) {
    val state by controller.state.collectAsState()
    if (state.active && state.minimized && controller.belongsToCurrentSession()) ConferenceReturnStrip(controller, state)
}

@Composable internal fun ConferenceReturnStrip(controller: ConferenceController, state: ConferenceState, onHeight: (androidx.compose.ui.unit.Dp) -> Unit = {}) {
    val c = LocalCocoColors.current
    com.messenger.app.ui.call.ActiveMediaReturnStrip(
        state.title.ifBlank { "Конференция" },
        if (state.reconnecting || state.connecting) "Восстановление связи…" else "${state.tiles.size + 1} · Нажмите, чтобы вернуться",
        if (state.microphone) CocoIcons.Mic else CocoIcons.MicOff, c.categoryMint,
        { controller.minimize(false) }, "conference_return_strip", onHeight = onHeight)

}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable internal fun CompactConferenceRoom(controller: ConferenceController, state: ConferenceState) {
    val c = LocalCocoColors.current
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val large = config.fontScale >= 1.5f
    val view = LocalView.current
    val size = ConferencePresentationPolicy.displayCount(state.visibleParticipantCount, ConferenceLayoutPolicy.pageSize(landscape, config.fontScale))
    val allTiles = state.presentationTiles()
    val aloneWithoutCamera = state.tiles.isEmpty() && state.own?.cameraEnabled != true
    val count = ConferencePresentationPolicy.pageCount(allTiles.size, size)
    val page = ConferencePresentationPolicy.page(allTiles.map { it.id }, state.pageAnchor, state.pageIndex, size)
    val pinned = allTiles.firstOrNull { it.id == state.pinnedId }
    // Keep this page mounted under focus. Detaching its renderers also tells
    // adaptive stream to stop video, making a return wait for a new key frame.
    val visible = if (state.gridMode == ConferenceGridMode.PAGES) allTiles.drop(page * size).take(size) else allTiles
    val owner = remember(controller, state.roomId) { controller.beginPresentation() }
    DisposableEffect(owner) { onDispose { controller.endPresentation(owner) } }
    var directory by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf(false) }
    var roomSettings by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var joinQueue by remember(controller, state.roomId) { mutableStateOf(false) }
    var participantActions by remember(controller, state.roomId) { mutableStateOf<String?>(null) }
    var removeConfirmation by remember(controller, state.roomId) { mutableStateOf<Pair<String, String>?>(null) }
    val actionTile = state.tiles.firstOrNull { it.id == participantActions }
    var videoIds by remember(owner) { mutableStateOf(emptySet<String>()) }
    val permissions = rememberConferencePermissions()
    val enabled = !state.connecting && !state.reconnecting && !state.controlsBusy
    BackHandler { if (pinned != null) controller.pin(null) else controller.minimize(true) }
    LaunchedEffect(owner, aloneWithoutCamera, videoIds, pinned?.id) {
        controller.present(owner, if (aloneWithoutCamera) emptySet() else ConferencePresentationPolicy.videoDemand(videoIds, pinned?.id))
    }
    val backdrop = remember(c) { ConferenceRoomStyle.backdrop(c) }
    Surface(Modifier.fillMaxSize(), color = c.callBackground, contentColor = c.primaryText) {
        Column(Modifier.fillMaxSize().background(backdrop).safeDrawingPadding().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.fillMaxWidth().padding(top = 4.dp), shape = RoundedCornerShape(20.dp), color = ConferenceRoomStyle.panel(c).copy(alpha = .65f),
                contentColor = c.primaryText, border = BorderStroke(.5.dp, ConferenceRoomStyle.outline(c))) {
              Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ConferenceIcon(CocoIcons.CollapseCall, "Свернуть конференцию", c.primaryText, "conference_minimize") { controller.minimize(true) }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(state.title.ifBlank { "Комната" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!state.connecting && !state.reconnecting) Icon(CocoIcons.Security, null, Modifier.size(12.dp), tint = c.accent)
                        Text(when { state.approvalWaiting -> stringResource(R.string.conference_join_waiting_creator); state.connecting -> "Подключение…"; state.reconnecting -> "Восстановление связи…"; else -> "Сквозное шифрование" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = c.mutedText)
                    }
                }
                FilledTonalIconButton(onClick = { directory = true }, Modifier.width(if (large) 72.dp else 60.dp).height(48.dp).testTag("conference_participants"),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = c.primaryText.copy(alpha = .06f), contentColor = c.primaryText)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(painterResource(R.drawable.ic_conference_people), "Все участники", Modifier.size(18.dp))
                        Text("${state.presentationTiles().size.coerceAtLeast(1)}", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
              }
            }
            if (state.host && state.joinRequests.isNotEmpty()) {
                val request = state.joinRequests.first()
                ConferenceJoinRequestCard(request, state.decidingJoinRequest != null,
                    onAccept = { controller.decideJoinRequest(request.requestId, true) },
                    onDecline = { controller.decideJoinRequest(request.requestId, false) })
                if (state.joinRequests.size > 1) TextButton(onClick = { joinQueue = true }) {
                    Text(stringResource(R.string.conference_join_requests) + ": " + state.joinRequests.size)
                }
            }
            if (state.approvalWaiting) Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.dp)
                Text(stringResource(R.string.conference_join_waiting_creator), Modifier.padding(16.dp))
                TextButton(onClick = { controller.leave() }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.conference_join_cancel)) }
            } else if (aloneWithoutCamera) ConferenceAloneStage(state.connecting, state.reconnecting, state.microphone, controller.canShareInvitation, enabled,
                onInvite = { invite = true }, modifier = Modifier.fillMaxWidth().weight(1f))
            else BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp))) {
                val fit = state.gridMode == ConferenceGridMode.FIT
                val scroll = state.gridMode == ConferenceGridMode.SCROLL
                val perScreen = if (fit) maxOf(1, visible.size) else minOf(size, maxOf(1, visible.size))
                val columns = if (fit) ConferencePresentationPolicy.fitColumns(visible.size, maxWidth.value, maxHeight.value)
                    else ConferencePresentationPolicy.columns(perScreen, landscape)
                val rows = maxOf(1, (perScreen + columns - 1) / columns)
                val height = ((maxHeight - 6.dp * (rows - 1)) / rows).coerceAtLeast(1.dp)
                val density = LocalDensity.current
                val swipeThreshold = with(density) { 48.dp.toPx() }
                var swipe by remember { mutableFloatStateOf(0f) }
                ConferenceVideoStage(pinned != null, grid = {
                key(state.gridMode, size, columns) {
                    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = if (scroll) visible.indexOfFirst { it.id == state.pageAnchor }.coerceAtLeast(0) else 0)
                    LaunchedEffect(owner, grid, visible.map { it.id }, scroll, fit, size) {
                        snapshotFlow {
                            val info = grid.layoutInfo
                            val areas = info.visibleItemsInfo.map { item ->
                                val top = maxOf(item.offset.y, info.viewportStartOffset)
                                val bottom = minOf(item.offset.y + item.size.height, info.viewportEndOffset)
                                (item.key as String) to (bottom - top).coerceAtLeast(0).toFloat() * item.size.width
                            }
                            ConferencePresentationPolicy.visibleIds(areas, if (fit) 30 else size) to
                                (info.visibleItemsInfo.firstOrNull { it.offset.y + it.size.height > info.viewportStartOffset }?.key as? String)
                        }.distinctUntilChanged().collect { (ids, anchor) ->
                            // Ignore the empty first layout of a replacement grid.
                            // Lifecycle teardown, not a transient measurement,
                            // is responsible for clearing subscriptions.
                            if (ids.isNotEmpty() || visible.isEmpty()) videoIds = ids
                            if (scroll) controller.anchor(owner, anchor)
                        }
                    }
                    val gesture = if (state.gridMode == ConferenceGridMode.PAGES && pinned == null) Modifier.pointerInput(page, count) {
                        detectHorizontalDragGestures(onDragStart = { swipe = 0f }, onDragEnd = {
                            if (kotlin.math.abs(swipe) >= swipeThreshold) controller.page(page + if (swipe < 0) 1 else -1, size)
                        }) { change, amount -> swipe += amount; change.consume() }
                    } else Modifier
                    LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().testTag("conference_grid").then(gesture), state = grid,
                        userScrollEnabled = scroll && pinned == null, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(visible, key = { _, tile -> tile.id }, span = { index, _ -> GridItemSpan(if (scroll) ConferencePresentationPolicy.span(index, size, columns) else 1) }) { _, tile ->
                            ConferenceVideoTile(controller.room, if (tile.id in videoIds) tile else tile.copy(video = null),
                                Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(18.dp)).testTag("conference_tile_${tile.id}")
                                    .combinedClickable(
                                        onClick = { controller.pin(if (pinned?.id == tile.id) null else tile.id) },
                                        onLongClick = if (tile.id != state.own?.id) ({ com.messenger.app.ui.theme.CocoHaptics.tap(view); participantActions = tile.id }) else null,
                                        onLongClickLabel = "Действия с участником"), compact = height < 58.dp)
                        }
                    }
                }
                }, focus = {
                    if (pinned != null) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { controller.pin(null) }, modifier = Modifier.heightIn(min = 44.dp).testTag("conference_back_to_grid")) {
                            Icon(CocoIcons.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("К сетке участников")
                        }
                        ConferenceVideoTile(controller.room, pinned, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp))
                            .testTag("conference_tile_${pinned.id}").combinedClickable(onClick = { controller.pin(null) },
                                onLongClick = if (pinned.id != state.own?.id) ({ participantActions = pinned.id }) else null))
                        state.own?.takeIf { it.id != pinned.id }?.let { own ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                ConferenceVideoTile(controller.room, own.copy(name = "Вы"),
                                    Modifier.width(112.dp).height(if (landscape) 72.dp else 88.dp).testTag("conference_own_pinned_tile")
                                        .clickable { controller.pin(own.id) }, corner = 14.dp)
                            }
                        }
                    }
                })
                if (visible.isEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(CocoIcons.Chat, null, Modifier.size(34.dp), tint = c.categoryViolet)
                    Text(if (state.connecting || state.reconnecting) "Подключение…" else "Пригласите участников", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text("Камера и микрофон включаются кнопками внизу", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = c.mutedText)
                }
            }
            if (state.gridMode == ConferenceGridMode.PAGES && count > 1 && pinned == null) Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller.page(page - 1, size) }, enabled = page > 0, modifier = Modifier.size(44.dp).testTag("conference_page_previous")) { Icon(painterResource(R.drawable.ic_conference_chevron_left), "Предыдущая страница", Modifier.size(22.dp)) }
                Text("${page + 1} / $count", Modifier.semantics { contentDescription = "Страница ${page + 1} из $count" }, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { controller.page(page + 1, size) }, enabled = page + 1 < count, modifier = Modifier.size(44.dp).testTag("conference_page_next")) { Icon(painterResource(R.drawable.ic_conference_chevron_right), "Следующая страница", Modifier.size(22.dp)) }
            }
            if (state.gridMode == ConferenceGridMode.PAGES && count > 1 && pinned != null) Spacer(Modifier.height(44.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = ConferenceRoomStyle.panel(c), contentColor = c.primaryText,
                border = BorderStroke(.5.dp, ConferenceRoomStyle.outline(c)), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("conference_control_dock")) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val actions: List<@Composable () -> Unit> = listOf(
                        { ConferenceDockButton(if (state.microphone) CocoIcons.Mic else CocoIcons.MicOff, "Микрофон", if (state.microphone) c.accent else c.mutedText, enabled, !landscape && !large,
                            locked = state.microphoneBlockedByHost,
                            description = if (state.microphoneBlockedByHost) stringResource(R.string.conference_microphone_host_info) else "Микрофон") { controller.toggleMicrophone() } },
                        { ConferenceDockButton(CocoIcons.Videocam, "Камера", if (state.camera) c.accent else c.mutedText, enabled, !landscape && !large, off = !state.camera) { if (state.camera) controller.toggleCamera() else permissions(true, controller::toggleCamera) } },
                        { ConferenceDockButton(CocoIcons.Speaker, "Звук", c.primaryText, enabled, !landscape && !large) { audio = true } },
                        { ConferenceDockButton(ImageVector.vectorResource(R.drawable.ic_conference_person_add), "Позвать", if (controller.canShareInvitation) c.primaryText else c.mutedText, enabled, !landscape && !large, infoReason = if (controller.canShareInvitation) null else stringResource(R.string.conference_invite_requires_original)) { invite = true } },
                        { ConferenceDockButton(CocoIcons.MoreVert, "Ещё", c.primaryText, true, !landscape && !large) { more = true } },
                        { ConferenceDockButton(CocoIcons.EndCall, "Выйти", c.danger, true, !landscape && !large, destructive = true) { controller.leave() } },
                    )
                    actions.chunked(if (large || config.screenWidthDp < 344) 3 else 6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { action -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { action() } } }
                    }
                }
            }
        }
    }
    if (directory) ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { directory = false }, containerColor = ConferenceRoomStyle.panel(c), contentColor = c.primaryText, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Участники · ${state.tiles.size + 1}", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(search, { search = it.take(80) }, placeholder = { Text("Найти участника") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(CocoIcons.Search, null, Modifier.size(20.dp)) }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("conference_participant_search"))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("conference_participant_directory")) {
                item { ParticipantRow("Вы", !state.microphone, state.camera, false) {} }
                items(state.tiles.filter { it.name.contains(search, ignoreCase = true) }, key = { it.id }) { tile ->
                    ParticipantRow(tile.name, tile.muted, tile.cameraEnabled && !tile.videoHiddenForMe, true, audioMutedForMe = tile.audioMutedForMe, onActions = {
                        directory = false; participantActions = tile.id
                    }) {
                        controller.page(allTiles.indexOfFirst { it.id == tile.id } / size, size); controller.pin(tile.id); directory = false
                    }
                }
            }
            Spacer(Modifier.height(12.dp).navigationBarsPadding())
        }
    }
    if (actionTile != null) ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { participantActions = null }, containerColor = ConferenceRoomStyle.panel(c), contentColor = c.primaryText, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(actionTile.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Только для вас", Modifier.padding(top = 8.dp, bottom = 12.dp), color = c.mutedText, style = MaterialTheme.typography.labelLarge)
            Text("Эти настройки не выключают микрофон или камеру участника у остальных.", color = c.mutedText, style = MaterialTheme.typography.bodySmall)
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(if (state.pinnedId == actionTile.id) "Вернуть в сетку" else "Закрепить на экране") },
                leadingContent = { Icon(CocoIcons.Pin, null, tint = c.primaryText) },
                modifier = Modifier.testTag("conference_participant_pin").clickable {
                    controller.pin(if (state.pinnedId == actionTile.id) null else actionTile.id); participantActions = null
                })
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Звук участника") },
                leadingContent = { if (actionTile.audioMutedForMe) Icon(painterResource(R.drawable.ic_conference_volume_off), null, tint = c.primaryText)
                    else Icon(CocoIcons.Speaker, null, tint = c.primaryText) },
                trailingContent = { Switch(checked = !actionTile.audioMutedForMe, onCheckedChange = { com.messenger.app.ui.theme.CocoHaptics.tap(view); controller.muteForMe(actionTile.id, !it) }) },
                modifier = Modifier.testTag("conference_participant_local_audio").clickable { com.messenger.app.ui.theme.CocoHaptics.tap(view); controller.muteForMe(actionTile.id, !actionTile.audioMutedForMe) })
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Видео участника") },
                leadingContent = { Icon(if (actionTile.videoHiddenForMe) CocoIcons.EyeOff else CocoIcons.Eye, null, tint = c.primaryText) },
                trailingContent = { Switch(checked = !actionTile.videoHiddenForMe, onCheckedChange = { com.messenger.app.ui.theme.CocoHaptics.tap(view); controller.hideVideoForMe(actionTile.id, !it) }) },
                modifier = Modifier.testTag("conference_participant_local_video").clickable { com.messenger.app.ui.theme.CocoHaptics.tap(view); controller.hideVideoForMe(actionTile.id, !actionTile.videoHiddenForMe) })
            if (state.host && state.participantModerationAvailable && state.members.any { it.participantIdentity == actionTile.id }) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = c.primaryText.copy(alpha = .08f))
                Text("Для всей комнаты", color = c.mutedText, style = MaterialTheme.typography.labelLarge)
                ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(if (actionTile.audioBlockedByHost) "Разрешить микрофон" else "Выключить микрофон для всех") },
                    supportingContent = { Text(if (actionTile.audioBlockedByHost) "Участник включит его самостоятельно" else "Звук этого участника не будет передаваться") },
                    leadingContent = { Icon(if (actionTile.audioBlockedByHost) CocoIcons.Mic else CocoIcons.MicOff, null, tint = c.primaryText) },
                    modifier = Modifier.testTag("conference_participant_global_audio").clickable(enabled = enabled && !state.settingsBusy) {
                        controller.restrictParticipantAudio(actionTile.id, !actionTile.audioBlockedByHost)
                    })
                ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Удалить из комнаты", color = c.danger) },
                    leadingContent = { Icon(CocoIcons.Delete, null, tint = c.danger) },
                    modifier = Modifier.testTag("conference_participant_remove").clickable(enabled = enabled && !state.settingsBusy) {
                        removeConfirmation = actionTile.id to actionTile.name; participantActions = null
                    })
                if (state.settingsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp).navigationBarsPadding())
        }
    }
    removeConfirmation?.let { (identity, name) ->
        AlertDialog(onDismissRequest = { removeConfirmation = null }, title = { Text("Удалить участника?") },
            text = { Text("$name отключится и не сможет повторно войти в эту комнату с этого аккаунта.") },
            confirmButton = { TextButton(onClick = { removeConfirmation = null; controller.removeParticipant(identity) }) { Text("Удалить", color = c.danger) } },
            dismissButton = { TextButton(onClick = { removeConfirmation = null }) { Text("Отмена") } })
    }
    if (more) ModalBottomSheet(onDismissRequest = { more = false }, containerColor = ConferenceRoomStyle.panel(c), contentColor = c.primaryText, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {
            Text("Комната", style = MaterialTheme.typography.titleLarge)
            Text("Ваш экран", Modifier.padding(top = 12.dp, bottom = 4.dp), color = c.mutedText, style = MaterialTheme.typography.labelLarge)
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Вид участников") }, supportingContent = { Text(if (state.gridMode == ConferenceGridMode.FIT) "Все на экране" else "$size на экране") }, leadingContent = { Icon(painterResource(R.drawable.ic_conference_people), null) }, modifier = Modifier.testTag("conference_layout_settings").clickable { more = false; layout = true })
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Сменить камеру") }, leadingContent = { Icon(CocoIcons.SwitchCamera, null) }, modifier = Modifier.clickable(enabled = enabled && state.camera) { controller.switchCamera(); more = false })
            if (state.host) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = c.outlineSubtle)
                Text("Управление комнатой", color = c.mutedText, style = MaterialTheme.typography.labelLarge)
                if (state.hostControlsAvailable) ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Настройки комнаты") }, supportingContent = { Text(stringResource(R.string.conference_settings_summary)) }, leadingContent = { Icon(com.messenger.app.ui.RootNavIcons.Settings, null) }, modifier = Modifier.testTag("conference_host_settings").clickable(enabled = enabled) { more = false; roomSettings = true })
                ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Завершить для всех", color = c.danger) }, supportingContent = { Text("Все участники отключатся") },
                    leadingContent = { Icon(CocoIcons.EndCall, null, tint = c.danger) }, modifier = Modifier.clickable { more = false; confirmEnd = true })
            }
            Spacer(Modifier.height(12.dp).navigationBarsPadding())
        }
    }
    if (roomSettings && state.host && state.hostControlsAvailable) ConferenceHostEditor(controller, state) { roomSettings = false }
    if (layout) ModalBottomSheet(onDismissRequest = { layout = false }, containerColor = ConferenceRoomStyle.panel(c), contentColor = c.primaryText, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp)) {
            val displayed = if (state.gridMode == ConferenceGridMode.FIT) allTiles.size.coerceAtLeast(1) else size
            Text("Вид участников", style = MaterialTheme.typography.titleLarge)
            Text("Настройка меняет только ваш экран. Остальных участников можно увидеть прокруткой.", Modifier.padding(vertical = 10.dp), color = c.mutedText, style = MaterialTheme.typography.bodyMedium)
            Text(if (state.gridMode == ConferenceGridMode.FIT) "На экране: все (${allTiles.size})" else "На экране: $size", modifier = Modifier.testTag("conference_visible_count"), style = MaterialTheme.typography.titleMedium)
            Slider(value = displayed.toFloat(), onValueChange = { controller.display(ConferenceGridMode.SCROLL, it.roundToInt()) }, valueRange = 1f..30f, steps = 28,
                modifier = Modifier.testTag("conference_visible_slider"))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { controller.display(if (state.gridMode == ConferenceGridMode.FIT) ConferenceGridMode.SCROLL else ConferenceGridMode.FIT) }.testTag("conference_fit_all"), verticalAlignment = Alignment.CenterVertically) {
                Text("Все на экране", Modifier.weight(1f)); Switch(checked = state.gridMode == ConferenceGridMode.FIT, onCheckedChange = { controller.display(if (it) ConferenceGridMode.FIT else ConferenceGridMode.SCROLL) })
            }
            Text("При большом числе людей окна будут меньше. Качество видео подстраивается под размер окна.", color = c.mutedText, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { controller.display(ConferenceGridMode.PAGES, 0) }, modifier = Modifier.heightIn(min = 44.dp)) { Text("Вернуть постраничный вид") }
            Button(onClick = { layout = false }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("conference_layout_done")) { Text("Готово") }
            Spacer(Modifier.height(12.dp).navigationBarsPadding())
        }
    }
    if (joinQueue) ConferenceJoinRequestSheet(controller, state) { joinQueue = false }
    if (audio) ModalBottomSheet(onDismissRequest = { audio = false }, containerColor = ConferenceRoomStyle.panel(c), contentColor = c.primaryText, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {
            Text("Аудиовыход", style = MaterialTheme.typography.titleLarge)
            controller.audioDevices().forEach { device -> ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(device.name) }, trailingContent = { if (controller.selectedAudioRoute() == device.name) Text("✓", Modifier.semantics { contentDescription = "Выбранный аудиовыход" }) }, leadingContent = { Icon(CocoIcons.Speaker, null) }, modifier = Modifier.clickable(enabled = enabled) { controller.selectAudioDevice(device); audio = false }) }
            Spacer(Modifier.height(12.dp).navigationBarsPadding())
        }
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("Завершить для всех?") }, text = { Text("Участники отключатся. Приглашение больше не откроет эту конференцию.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; controller.leave(true) }) { Text("Завершить", color = c.danger) } }, dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Отмена") } })
    if (invite) { if (controller.canShareInvitation) ConferenceShareDialog(controller) { invite = false } else ConferenceInvitationUnavailableDialog { invite = false } }
}

@Composable internal fun ConferenceAloneStage(connecting: Boolean, reconnecting: Boolean, microphone: Boolean, canInvite: Boolean, enabled: Boolean,
    onInvite: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalCocoColors.current
    val heading = when { connecting -> "Подключение к комнате"; reconnecting -> "Восстанавливаем связь"; else -> "Вы в комнате" }
    val detail = when {
        connecting || reconnecting -> "Готовим защищённое соединение…"
        microphone -> "Микрофон включён · камера выключена"
        else -> "Камера и микрофон выключены"
    }
    BoxWithConstraints(modifier.testTag("conference_alone_stage"), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 220.dp
        val avatar: @Composable () -> Unit = {
            Surface(Modifier.size(if (compact) 64.dp else 88.dp), shape = CircleShape, color = ConferenceRoomStyle.panel(c),
                border = BorderStroke(.7.dp, ConferenceRoomStyle.outline(c))) {
                Box(contentAlignment = Alignment.Center) {
                    if (connecting || reconnecting) CircularProgressIndicator(Modifier.size(if (compact) 26.dp else 32.dp), color = c.accent, strokeWidth = 2.dp)
                    else Text("Вы", style = MaterialTheme.typography.headlineSmall, color = c.primaryText)
                }
            }
        }
        val details: @Composable ColumnScope.() -> Unit = {
            Text(heading, style = MaterialTheme.typography.titleLarge, color = c.primaryText)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.mutedText)
            if (!connecting && !reconnecting) {
                Text("Остальные смогут присоединиться по приглашению.", style = MaterialTheme.typography.bodyMedium, color = c.mutedText)
                FilledTonalButton(onClick = onInvite, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("conference_alone_invite"),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = c.accent.copy(alpha = .14f), contentColor = c.accent)) {
                    Icon(painterResource(R.drawable.ic_conference_person_add), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (canInvite) "Пригласить участников" else "О приглашениях")
                }
            }
        }
        if (compact) Row(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            avatar()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = details)
        } else Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            avatar()
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), content = details)
        }
    }
}

@Composable private fun ParticipantRow(name: String, muted: Boolean, camera: Boolean, selectable: Boolean, audioMutedForMe: Boolean = false, onActions: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().clickable(enabled = selectable, onClick = onClick).heightIn(min = 60.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(c.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase()) }
        Text(name, Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (audioMutedForMe) Icon(painterResource(R.drawable.ic_conference_volume_off), "Звук выключен только для вас", Modifier.size(18.dp), tint = c.mutedText)
        else Icon(if (muted) CocoIcons.MicOff else CocoIcons.Mic, if (muted) "Микрофон выключен" else "Микрофон включён", Modifier.size(18.dp), tint = if (muted) c.mutedText else c.primaryText)
        Spacer(Modifier.width(12.dp))
        if (camera) Icon(CocoIcons.Videocam, "Камера включена", Modifier.size(18.dp), tint = c.primaryText)
        else Icon(painterResource(R.drawable.ic_conference_videocam_off), "Камера выключена", Modifier.size(18.dp), tint = c.mutedText)
        if (onActions != null) IconButton(onClick = onActions, modifier = Modifier.size(48.dp).testTag("conference_participant_actions_$name")) {
            Icon(CocoIcons.MoreVert, "Действия с участником $name", Modifier.size(20.dp), tint = c.primaryText)
        }
    }
}

@Composable private fun ConferenceIcon(icon: ImageVector, label: String, tint: Color, tag: String? = null, onClick: () -> Unit) {
    val view = LocalView.current
    IconButton(onClick = { com.messenger.app.ui.theme.CocoHaptics.tap(view); onClick() }, modifier = Modifier.size(48.dp).then(if (tag == null) Modifier else Modifier.testTag(tag))) { Icon(icon, label, Modifier.size(21.dp), tint = tint) }
}

@Composable private fun ConferenceDockButton(icon: ImageVector, label: String, accent: Color, enabled: Boolean, showLabel: Boolean, off: Boolean = false, destructive: Boolean = false, locked: Boolean = false, description: String = label, infoReason: String? = null, onClick: () -> Unit) {
    val view = LocalView.current
    val ink = (if (destructive) Color.White else accent).copy(alpha = if (enabled) 1f else .4f)
    // One semantic/touch target includes the label; tapping its text is never a dead area.
    Column(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
        .clickable(enabled = enabled, role = Role.Button) { com.messenger.app.ui.theme.CocoHaptics.tap(view); onClick() }
        .semantics { contentDescription = description; infoReason?.let { stateDescription = it } }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).background(if (destructive) accent else accent.copy(alpha = if (off) .08f else .12f), CircleShape), contentAlignment = Alignment.Center) {
            if (off) Icon(painterResource(R.drawable.ic_conference_videocam_off), null, Modifier.size(21.dp), tint = ink)
            else Icon(icon, null, Modifier.size(21.dp), tint = ink)
            if (locked) Box(Modifier.align(Alignment.TopEnd).size(15.dp).background(LocalCocoColors.current.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_conference_lock), null, Modifier.size(11.dp).testTag("conference_microphone_host_lock"), tint = ink)
            }
        }
        if (showLabel) Text(label, fontSize = if (label == "Микрофон") 10.sp else 11.sp, maxLines = 1, color = ink, modifier = Modifier.padding(top = 3.dp))
    }
}
