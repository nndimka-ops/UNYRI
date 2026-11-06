package com.messenger.app.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.ui.components.UserAvatar
import com.messenger.app.ui.theme.*

internal enum class SearchScope(val label: Int) {
    ALL(R.string.chats_filter_all), CHATS(R.string.nav_chats),
    MESSAGES(R.string.search_scope_messages), PEOPLE(R.string.search_scope_people);
    val searchesMessages get() = this == ALL || this == MESSAGES
    val searchesPeople get() = this == PEOPLE
}

/** Same scope identity as iOS: violet local all/messages, blue chats/people. */
internal fun searchScopeTint(scope: SearchScope, colors: CocoColors): Color = when (scope) {
    SearchScope.ALL, SearchScope.MESSAGES -> colors.categoryViolet
    SearchScope.CHATS, SearchScope.PEOPLE -> colors.categoryBlue
}

private fun searchScopeIcon(scope: SearchScope) = when (scope) {
    SearchScope.ALL -> CocoIcons.Search
    SearchScope.CHATS -> CocoIcons.Chat
    SearchScope.MESSAGES -> CocoIcons.File
    SearchScope.PEOPLE -> Icons.Filled.Person
}

@Composable
private fun SearchIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    IconButton(onClick = onClick, interactionSource = interaction,
        modifier = cocoPressFeedback(interaction).size(48.dp).background(tint.copy(alpha = .08f), CircleShape)) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/** Inline content: the parent owns navigation and retains the normal chat-list position. */
@Composable
fun SearchUsersScreen(
    viewModel: SearchViewModel,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenMessage: (com.messenger.app.domain.model.MessageSearchResult) -> Unit = { onOpenChat(it.chatId) },
    onClose: (() -> Unit)? = null,
    embedded: Boolean = false,
    people: Boolean = false,
    onOpenMenu: (() -> Unit)? = null,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val messageModel = androidx.lifecycle.viewmodel.compose.viewModel<MessageSearchViewModel>(
        key = "unifiedSearchMessages:${app.authRepository.currentSessionEpoch()}", factory = MessageSearchViewModelFactory(app.messageSearchRepository))
    val chats by app.chatRepository.chats.collectAsState(initial = emptyList())
    val connection by app.messageRepository.socketState.collectAsState()
    val messageState by messageModel.uiState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val opening by viewModel.openingUserId.collectAsState()
    val openError by viewModel.openError.collectAsState()
    val openChatId by viewModel.openChatId.collectAsState()
    val colors = LocalCocoColors.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val searchApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val searchAuth by searchApp.authRepository.authState.collectAsState()
    val searchOwner = com.messenger.app.data.api.ServerEndpoint.current() + "|" + searchApp.authRepository.currentSessionEpoch() + "|" + (searchAuth as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id
    val presence = searchApp.presence
    val peers by presence.peers.collectAsState()
    val presenceOwner = remember { "directory:" + java.util.UUID.randomUUID() }
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(if (people) SearchScope.PEOPLE else SearchScope.ALL) }
    var previousOwner by remember { mutableStateOf(searchOwner) }
    LaunchedEffect(searchOwner) {
        if (previousOwner != searchOwner) { viewModel.close(); query = ""; scope = if (people) SearchScope.PEOPLE else SearchScope.ALL; previousOwner = searchOwner }
    }
    val recent by viewModel.recentQueries.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    var didFocus by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val term = query.trim().removePrefix("@")
    val savedTitle = stringResource(R.string.saved_messages)
    val unknownTitle = stringResource(R.string.chats_unknown_user)
    fun chatTitle(chat: com.messenger.app.domain.model.ChatSummary?) = chat?.otherUser?.displayName ?: if (chat?.type == "saved") savedTitle else unknownTitle
    val chatById = remember(chats) { chats.associateBy { it.chatId } }
    val localChats = remember(chats, term, savedTitle) {
        chats.filter { chat -> term.isNotEmpty() &&
            ((chat.otherUser?.displayName ?: if (chat.type == "saved") savedTitle else "").contains(term, true) ||
                chat.otherUser?.username?.contains(term, true) == true) }
    }
    fun recordSearch() {
        viewModel.rememberQuery(query)
        keyboard?.hide(); focusManager.clearFocus()
    }
    fun close() { keyboard?.hide(); focusManager.clearFocus(); viewModel.close(); onClose?.invoke() }
    LaunchedEffect(query, scope, connection) {
        // Message text never leaves the phone just because it was entered into search.
        if (scope.searchesPeople) viewModel.search(query, directory = true) else viewModel.close()
    }
    LaunchedEffect(query, scope) { messageModel.onQueryChange(if (scope.searchesMessages) query else "") }
    LaunchedEffect(Unit) { if (!didFocus) { didFocus = true; if (!people) focus.requestFocus() } }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) { keyboard?.hide(); focusManager.clearFocus() } }
    LaunchedEffect(openChatId) { openChatId?.let { viewModel.consumeOpenChatId(); recordSearch(); onOpenChat(it) } }
    DisposableEffect(viewModel) { onDispose { viewModel.pause(); messageModel.onQueryChange("") } }
    com.messenger.app.ui.theme.CocoScreenSurface(modifier = modifier.fillMaxSize(), color = colors.background) {
        Column((if (embedded) Modifier else Modifier.safeDrawingPadding()).imePadding().fillMaxSize()) {
            val island = com.messenger.app.ui.call.LocalCallIsland.current
            if (island != null) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                com.messenger.app.ui.call.CallIslandControl(island.durationMs, island.isVideo, island.onReturn, island.status)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onOpenMenu != null) SearchIconButton(Icons.Filled.Menu, stringResource(R.string.navigation_menu), colors.primaryText) { keyboard?.hide(); focusManager.clearFocus(); onOpenMenu() }
                else if (onClose != null) com.messenger.app.ui.components.CocoBackButton(onClick = ::close)
                TextField(value = query, onValueChange = { query = it.take(256) }, singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.chats_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(CocoIcons.Search, contentDescription = null, tint = colors.accent, modifier = Modifier.size(21.dp)) },
                    trailingIcon = if (query.isNotEmpty()) { { SearchIconButton(CocoIcons.Close, stringResource(R.string.search_clear), colors.secondaryText) { query = ""; focus.requestFocus() } } } else null,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { CocoHaptics.tap(hapticView); recordSearch() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.primaryText),
                    colors = TextFieldDefaults.colors(focusedContainerColor = colors.inputSurface,
                        unfocusedContainerColor = colors.inputSurface, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, cursorColor = colors.accent))
                if (onOpenMenu != null && onClose != null) SearchIconButton(CocoIcons.Close, stringResource(R.string.chat_controls_close), colors.secondaryText, ::close)
            }
            if (!scope.searchesPeople) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchScope.entries.filter { it != SearchScope.PEOPLE }) { option ->
                    val tint = searchScopeTint(option, colors)
                    FilterChip(selected = scope == option, onClick = { CocoHaptics.select(hapticView); scope = option },
                        label = { Text(stringResource(option.label), style = MaterialTheme.typography.labelLarge) },
                        leadingIcon = { Icon(searchScopeIcon(option), null, Modifier.size(16.dp)) }, shape = CircleShape,
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = scope == option,
                            borderColor = colors.divider.copy(alpha=.35f), selectedBorderColor = tint.copy(alpha=.32f)),
                        colors = FilterChipDefaults.filterChipColors(containerColor = colors.surface,
                            labelColor = colors.secondaryText, iconColor = tint, selectedContainerColor = tint.copy(alpha=.14f),
                            selectedLabelColor = tint, selectedLeadingIconColor = tint))
                }
            }
            }
            if (opening != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (openError) SearchErrorState(stringResource(R.string.search_open_failed), viewModel::dismissOpenError, R.string.chat_controls_close)
            LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("search-results"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + LocalGlassBottomInset.current),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (term.isEmpty() && !scope.searchesPeople) {
                    item { SearchStateMessage(stringResource(if (scope == SearchScope.PEOPLE) R.string.inline_search_people_hint else R.string.unified_search_hint)) }
                    if (recent.isNotEmpty()) {
                        item { Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.search_recent), Modifier.weight(1f), color = colors.secondaryText)
                            TextButton(onClick = { CocoHaptics.tap(hapticView); viewModel.clearRecentQueries() }) { Text(stringResource(R.string.search_clear)) }
                        } }
                        items(recent, key = { "recent:$it" }) { previous ->
                            Surface(onClick = { CocoHaptics.tap(hapticView); query = previous }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(CocoIcons.Search, null, tint = colors.secondaryText, modifier = Modifier.size(20.dp))
                                    Text(previous, Modifier.weight(1f), color = colors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.mutedText, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                } else {
                    if (scope == SearchScope.ALL || scope == SearchScope.CHATS) {
                        item { SearchSectionTitle(stringResource(R.string.nav_chats)) }
                        if (localChats.isEmpty()) item { Text(stringResource(R.string.search_no_results), color = colors.mutedText) }
                        items(localChats, key = { "chat:" + it.chatId }) { chat ->
                            chat.otherUser?.let { user -> SearchUserRow(user, enabled = opening == null) { recordSearch(); onOpenChat(chat.chatId) } }
                                ?: TextButton(onClick = { recordSearch(); onOpenChat(chat.chatId) }, modifier = Modifier.fillMaxWidth()) { Text(chatTitle(chat)) }
                        }
                    }
                    if (scope.searchesMessages) {
                        item { SearchSectionTitle(stringResource(R.string.search_scope_messages)) }
                        val found = (messageState as? MessageSearchUiState.Results)?.results.orEmpty().filter { chatById.containsKey(it.chatId) }
                        when (messageState) {
                            MessageSearchUiState.Loading -> item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                            MessageSearchUiState.Error -> item { SearchErrorState(stringResource(R.string.unified_search_local_error), messageModel::retry) }
                            MessageSearchUiState.NoResults -> item { Text(stringResource(R.string.search_no_results), color = colors.mutedText) }
                            is MessageSearchUiState.Results -> if (found.isEmpty()) item { Text(stringResource(R.string.search_no_results), color = colors.mutedText) }
                            else -> Unit
                        }
                        items(found, key = { "message:" + it.messageId }) { result ->
                            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = colors.inputSurface) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(chatTitle(chatById[result.chatId]), color = colors.accentSoft, style = MaterialTheme.typography.labelLarge)
                                    SearchResultRow(result, query) { CocoHaptics.tap(hapticView); recordSearch(); onOpenMessage(it) }
                                }
                            }
                        }
                    }
                    if (scope == SearchScope.ALL) item {
                        TextButton(onClick = { CocoHaptics.select(hapticView); scope = SearchScope.PEOPLE }) { Text(stringResource(R.string.search_people_action)) }
                    }
                    if (scope.searchesPeople) {
                        item { SearchSectionTitle(stringResource(R.string.search_scope_people)) }
                        item { SearchStateMessage(stringResource(R.string.inline_search_people_hint)) }
                        when (val state = uiState) {
                            SearchUiState.Idle -> item { Text(stringResource(R.string.search_users_idle), color = colors.mutedText) }
                            SearchUiState.Loading -> item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                            is SearchUiState.Error -> item { SearchErrorState(stringResource(R.string.unified_search_network), { viewModel.search(query, directory = true) }) }
                            is SearchUiState.Loaded -> {
                                if (state.cached) item { Text(stringResource(R.string.directory_cached_hint), style = MaterialTheme.typography.bodySmall, color = colors.mutedText) }
                                items(state.items, key = { "user:" + it.id }) { user ->
                                    DisposableEffect(presence, searchOwner, user.id) {
                                        presence.subscribe(presenceOwner + user.id, listOf(user.id))
                                        onDispose { presence.unsubscribe(presenceOwner + user.id) }
                                    }
                                    SearchUserRow(user, enabled = opening == null, status = peers[user.id], showsPresence = true) {
                                    val existing = chats.firstOrNull { it.otherUser?.id == user.id }
                                    if (existing != null) { recordSearch(); onOpenChat(existing.chatId) } else viewModel.message(user)
                                } }
                                if (state.items.isEmpty()) item { Text(stringResource(R.string.search_no_results), color = colors.mutedText) }
                                if (state.loadingMore) item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                                else if (state.pageError) item { SearchErrorState(stringResource(R.string.unified_search_network), viewModel::loadMore) }
                                else if (state.canLoadMore) item { TextButton(onClick = { CocoHaptics.tap(hapticView); viewModel.loadMore() }) { Text(stringResource(R.string.load_more)) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SearchSectionTitle(title: String) {
    Row(Modifier.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = LocalCocoColors.current.primaryText, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SearchStateMessage(text: String) {
    val colors = LocalCocoColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(CocoIcons.Search, null, tint = colors.accent, modifier = Modifier.size(44.dp).background(colors.surface, CircleShape).padding(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SearchErrorState(message: String, onRetry: () -> Unit, actionLabel: Int = R.string.retry) {
    val colors = LocalCocoColors.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    Surface(
        shape = CocoShapes.radiusCard,
        color = colors.danger.copy(alpha = .07f),
        border = BorderStroke(.5.dp, colors.danger.copy(alpha = .3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(CocoSpacing.lg),
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(CocoSpacing.md))
            TextButton(onClick = { CocoHaptics.tap(hapticView); onRetry() }) { Text(stringResource(actionLabel), color = colors.accentSoft) }
        }
    }
}

/** One full-row target with human-readable identity, no duplicated action button. */
@Composable
private fun SearchUserRow(user: PublicUser, enabled: Boolean = true, status: com.messenger.app.data.websocket.PeerPresence? = null, showsPresence: Boolean = false, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    Surface(
        shape = CocoShapes.radiusChatRow,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
        onClick = { CocoHaptics.tap(hapticView); onClick() },
        enabled = enabled,
    ) {
        Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 68.dp).padding(horizontal = CocoSpacing.md, vertical = 10.dp),
        ) {
            UserAvatar(
                userId = user.id,
                avatarVersion = user.avatarVersion,
                hasAvatar = user.hasAvatar,
                name = user.displayName,
                color = colors.accent,
                contentColor = colors.onAccent,
                size = 44.dp,
            )
            Spacer(modifier = Modifier.width(CocoSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.primaryText,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = "@${user.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                val presenceText = com.messenger.app.ui.chats.presenceLabel(status)
                if (showsPresence) Text(
                    text = presenceText.ifEmpty { stringResource(if (status == null) R.string.presence_unknown else R.string.presence_offline) },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status?.online == true) Color(0xFF63D7A5) else colors.mutedText,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(CocoSpacing.sm))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.mutedText, modifier = Modifier.size(18.dp))
        }
        HorizontalDivider(Modifier.padding(start = 68.dp, end = 12.dp), thickness = .5.dp, color = colors.divider.copy(alpha = .4f))
        }
    }
}
