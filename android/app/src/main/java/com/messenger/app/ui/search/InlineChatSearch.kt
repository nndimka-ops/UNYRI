package com.messenger.app.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.domain.model.*
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.glassPanel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Only conversation hits navigate. The host retains the mounted chat list underneath. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun InlineChatSearch(viewModel: SearchViewModel, onClose: () -> Unit, onOpenChat: (String) -> Unit,
    onOpenMessage: (MessageSearchResult) -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as MessengerApp
    val colors = LocalCocoColors.current
    val legacyIdentity by app.identityRepository.legacy.collectAsState()
    val owner = app.privacyRepository.ownerKey() + "|" + app.authRepository.currentSessionEpoch()
    val coroutineScope = rememberCoroutineScope()
    val contacts = remember(owner) { app.contactsRepository.store }
    val history = remember(owner) { (app.chatRepository.searchHistory ?: SearchHistory()).also { it.bind(owner) } }
    val saved = stringResource(R.string.saved_messages)
    val unknown = stringResource(R.string.chats_unknown_user)
    val model = remember(owner, saved, unknown) { InlineSearchModel(coroutineScope) { term ->
        val chats = app.chatRepository.chats.first()
        if (contacts.phase.value == com.messenger.app.data.profile.ContactsStore.Phase.LOADING)
            contacts.phase.first { it != com.messenger.app.data.profile.ContactsStore.Phase.LOADING }
        val people = chats.map { chat -> SearchPerson(chat.otherUser?.id ?: chat.chatId,
            chat.otherUser?.visibleName ?: if (chat.type == "saved") saved else unknown,
            chat.otherUser?.username.orEmpty(), chat.otherUser, chat.chatId) } +
            contacts.entries.value.map { SearchPerson(it.id, it.user.visibleName, it.user.username, it.user) }
        var localFailed = false
        val hits = try { app.messageSearchRepository.globalSearch(term, InlineSearchPolicy.MESSAGE_LIMIT) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { localFailed = true; emptyList() }
        InlineSnapshot(term, InlineSearchPolicy.rank(people, term, app.identityRepository.legacy.value), hits.filter { hit -> chats.any { it.chatId == hit.chatId } }, localFailed)
    } }
    var query by rememberSaveable(owner) { mutableStateOf("") }
    var category by rememberSaveable(owner) { mutableStateOf(InlineCategory.CHATS) }
    val snapshot by model.snapshot.collectAsState()
    val loading by model.loading.collectAsState()
    val failed by model.failed.collectAsState()
    val directory by viewModel.uiState.collectAsState()
    val openChat by viewModel.openChatId.collectAsState()
    val openError by viewModel.openError.collectAsState()
    val recent by history.queries.collectAsState()
    val recentPeople by history.people.collectAsState()
    val connection by app.messageRepository.socketState.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    var attached by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var focusRequest by rememberSaveable(owner) { mutableIntStateOf(1) }
    var consumedFocus by rememberSaveable(owner) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val term = InlineSearchPolicy.query(query)
    val directoryQuery = query.trim()
    val online = connection == com.messenger.app.domain.repository.SocketConnectionState.CONNECTED
    val chats by app.chatRepository.chats.collectAsState(initial = emptyList())
    val titles = remember(chats, saved, unknown) { chats.associate { it.chatId to (it.otherUser?.visibleName ?: if (it.type == "saved") saved else unknown) } }
    fun hideKeyboard() { keyboard?.hide(); focusManager.clearFocus() }
    fun close() { hideKeyboard(); model.cancel(); viewModel.close(); onClose() }
    fun record(person: PublicUser? = null) { history.remember(query, person); hideKeyboard() }
    var openingPerson by remember(owner) { mutableStateOf<PublicUser?>(null) }
    fun open(person: SearchPerson) {
        if (viewModel.openingUserId.value != null || viewModel.openChatId.value != null) return
        if (person.chatId != null) { record(person.user); onOpenChat(person.chatId) }
        else person.user?.let { user -> record(); openingPerson = user; viewModel.message(user) }
    }
    BackHandler { close() }
    LaunchedEffect(term, directoryQuery, legacyIdentity, connection, resumed) {
        if (resumed) {
            if (term.isNotEmpty() && online) coroutineScope.launch { contacts.ensureLoaded() }
            model.search(term)
            if (contactsCanSearchDirectory(directoryQuery, legacyIdentity)) viewModel.search(directoryQuery, directory = true, force = true, online = online) else viewModel.close()
        } else { model.cancel(); viewModel.pause() }
    }
    // Attach/resume/focus events drive the single keyboard request; publications never refocus.
    LaunchedEffect(attached, resumed, windowFocused, focusRequest) { if (attached && resumed && windowFocused && consumedFocus != focusRequest) focus.requestFocus() }
    LaunchedEffect(focused, resumed, windowFocused, focusRequest) {
        if (focused && resumed && windowFocused && consumedFocus != focusRequest) { keyboard?.show(); consumedFocus = focusRequest }
    }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) hideKeyboard() }
    LaunchedEffect(openChat) { openChat?.let { history.remember(query, openingPerson); openingPerson = null; viewModel.consumeOpenChatId(); onOpenChat(it) } }
    DisposableEffect(model) { onDispose { model.cancel(); viewModel.pause() } }
    val tabs = remember(snapshot) { InlineCategory.entries.filter { tab -> tab == InlineCategory.CHATS || snapshot.messages.any { tab in InlineSearchPolicy.categories(it) } } }
    LaunchedEffect(tabs) { if (category !in tabs) category = InlineCategory.CHATS }
    Column(modifier.fillMaxSize().imePadding().semantics { testTagsAsResourceId = true }) {
        Row(Modifier.fillMaxWidth().testTag("inline-search-field").padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.weight(1f).glassPanel(CircleShape), shape = CircleShape, color = androidx.compose.ui.graphics.Color.Transparent, border = androidx.compose.foundation.BorderStroke(.5.dp, colors.outlineSecondary)) {
                Row(Modifier.heightIn(min = 48.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(com.messenger.app.ui.theme.CocoIcons.Search, null, Modifier.size(18.dp), tint = colors.secondaryText)
                    BasicTextField(query, { query = it.substring(0, it.offsetByCodePoints(0, it.codePointCount(0, it.length).coerceAtMost(4096))) }, singleLine = true,
                        modifier = Modifier.weight(1f).padding(8.dp).testTag("search-query").focusRequester(focus)
                            .onGloballyPositioned { attached = true }.onFocusChanged { focused = it.isFocused },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.primaryText),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSearch = { record() }),
                        decorationBox = { field -> Box { if (query.isEmpty()) Text(stringResource(R.string.settings_search_label), color = colors.mutedText); field() } })
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; focusRequest++ }, Modifier.testTag("search-clear-query")) {
                        Icon(com.messenger.app.ui.theme.CocoIcons.Close, stringResource(R.string.search_clear))
                    }
                }
            }
            TextButton(onClick = ::close, Modifier.testTag("search-close")) { Text(stringResource(android.R.string.cancel)) }
        }
        if (term.isNotEmpty() && tabs.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            tabs.forEach { tab -> TextButton(onClick = { category = tab; hideKeyboard() }, Modifier.testTag("search-scope-${tab.name.lowercase()}")) {
                Text(stringResource(when(tab) {
                    InlineCategory.CHATS -> R.string.nav_chats; InlineCategory.MEDIA -> R.string.inline_search_media
                    InlineCategory.LINKS -> R.string.inline_search_links; InlineCategory.FILES -> R.string.inline_search_files; InlineCategory.VOICE -> R.string.inline_search_voice
                }), color = if (category == tab) colors.accent else colors.secondaryText)
            } }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).navigationBarsPadding().testTag("search-results"), contentPadding = PaddingValues(16.dp)) {
            if (term.isEmpty()) {
                if (recentPeople.isNotEmpty()) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(recentPeople, key = { it.id }) { user ->
                        Column(Modifier.width(72.dp).clickable { open(SearchPerson(user.id, user.visibleName, user.username, user, chats.firstOrNull { it.otherUser?.id == user.id }?.chatId)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            com.messenger.app.ui.components.UserAvatar(user.id, user.avatarVersion, user.hasAvatar, user.visibleName, colors.surfaceVariant, colors.accent)
                            Text(user.visibleName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } }
                }
                if (recent.isNotEmpty() || recentPeople.isNotEmpty()) item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.search_recent), Modifier.weight(1f))
                    if (recent.isNotEmpty() || recentPeople.isNotEmpty()) TextButton(onClick = history::clear, Modifier.testTag("search-clear-history")) { Text(stringResource(R.string.search_clear)) }
                } }
                items(recent, key = { "query:$it" }) { previous -> TextButton(onClick = { query = previous; focusRequest++ }) { Text(previous) } }
                items(recentPeople, key = { "recent:${it.id}" }) { user -> InlinePersonRow(SearchPerson(user.id, user.visibleName, user.username, user)) { open(SearchPerson(user.id, user.visibleName, user.username, user, chats.firstOrNull { it.otherUser?.id == user.id }?.chatId)) } }
                if (recent.isEmpty() && recentPeople.isEmpty()) item { Text(stringResource(R.string.unified_search_hint), color = colors.secondaryText) }
            } else {
                if (!online) item { Text(stringResource(R.string.inline_search_offline), color = colors.secondaryText) }
                if (loading) item { CircularProgressIndicator(Modifier.size(24.dp)) }
                if (failed) item { TextButton(onClick = { model.search(term) }) { Text(stringResource(R.string.unified_search_local_error) + " " + stringResource(R.string.retry)) } }
                if (openError) item { TextButton(onClick = viewModel::dismissOpenError) { Text(stringResource(R.string.search_open_failed)) } }
                if (category == InlineCategory.CHATS) {
                    InlineSearchPolicy.sections(snapshot.copy(query = term), contactsCanSearchDirectory(directoryQuery, legacyIdentity)).forEach { section ->
                    if (section == "chats") {
                    item { InlineSection(R.string.inline_search_chats, "search-section-chats") }
                    items(snapshot.people, key = { "person:${it.id}" }) { person -> InlinePersonRow(person) { open(person) } }
                    }
                    if (section == "global") {
                        item { InlineDirectoryFeedback(directory) {
                            viewModel.close(); viewModel.search(directoryQuery, directory = true, online = online)
                        } }
                        when (val state = directory) {
                            is SearchUiState.Loaded -> {
                                items(state.items.filterNot { user -> snapshot.people.any { it.id == user.id } }, key = { "global:${it.id}" }) { user ->
                                    InlinePersonRow(SearchPerson(user.id, user.visibleName, user.username, user), Modifier.testTag("inline-unknown-result")) {
                                        open(SearchPerson(user.id, user.visibleName, user.username, user))
                                    }
                                }
                                if (state.canLoadMore) item { TextButton(onClick = viewModel::loadMore, enabled = !state.loadingMore, modifier = Modifier.testTag("search-directory-more")) { Text(stringResource(if (state.pageError) R.string.retry else R.string.load_more)) } }
                                if (state.cached) item { Text(stringResource(R.string.directory_cached_hint)) }
                            }
                            else -> Unit
                        }
                    }
                    if (section == "messages") item { InlineSection(R.string.search_scope_messages, "search-section-messages") }
                    }
                }
                val found = snapshot.messages.filter { category in InlineSearchPolicy.categories(it) }
                if (category == InlineCategory.MEDIA) {
                    items(found.chunked(3), key = { "media:${it.first().messageId}" }) { row -> Row(Modifier.fillMaxWidth()) {
                        row.forEach { hit -> InlineMediaTile(hit, Modifier.weight(1f)) { record(chats.firstOrNull { it.chatId == hit.chatId }?.otherUser); onOpenMessage(hit) } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    } }
                } else items(found, key = { "message:${it.messageId}" }) { hit ->
                    Surface(onClick = { record(chats.firstOrNull { it.chatId == hit.chatId }?.otherUser); onOpenMessage(hit) }, color = colors.background, modifier = Modifier.fillMaxWidth().testTag("search-message-${hit.messageId}")) {
                        Column(Modifier.padding(vertical = 8.dp).heightIn(min = (72 * LocalDensity.current.fontScale).dp)) {
                            Row { Text(titles[hit.chatId] ?: unknown, Modifier.weight(1f), maxLines = 1); Text(hit.createdAt.take(10), style = MaterialTheme.typography.labelSmall) }
                            Text(auxiliarySearchHighlight(searchDisplayText(hit), snapshot.query), minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (category == InlineCategory.CHATS && contactsCanSearchDirectory(directoryQuery, legacyIdentity)) {
                    item { Text(stringResource(R.string.inline_search_privacy), style = MaterialTheme.typography.bodySmall) }
                }
                if (!loading && !failed && directory !is SearchUiState.Loading && directory !is SearchUiState.Error &&
                    (directory as? SearchUiState.Loaded)?.loadingMore != true && snapshot.people.isEmpty() && found.isEmpty() &&
                    (directory as? SearchUiState.Loaded)?.items.isNullOrEmpty()) item { Text(stringResource(R.string.search_no_results)) }
            }
        }
    }
}

@Composable private fun InlineSection(label: Int, tag: String) { Text(stringResource(label), Modifier.padding(vertical = 12.dp).testTag(tag), style = MaterialTheme.typography.titleSmall) }
@Composable internal fun InlineDirectoryFeedback(state: SearchUiState, retry: () -> Unit) {
    val colors = LocalCocoColors.current
    val lines = if (LocalDensity.current.fontScale >= 1.6f) 4 else 2
    val height = with(LocalDensity.current) { (MaterialTheme.typography.bodySmall.lineHeight.toDp() * lines + 8.dp).coerceAtLeast(56.dp) }
    Row(Modifier.fillMaxWidth().height(height).testTag("inline-server-section"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.inline_search_global), Modifier.weight(1f).testTag("search-section-global").semantics { heading() },
            style = MaterialTheme.typography.titleSmall)
        when (state) {
            SearchUiState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
            is SearchUiState.Error -> {
                Text(stringResource(if (state.unavailable) R.string.inline_search_unavailable else R.string.unified_search_network),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText,
                    maxLines = lines, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = retry, Modifier.testTag("search-directory-retry")) { Text(stringResource(R.string.retry)) }
            }
            else -> Unit
        }
    }
}
@Composable private fun InlinePersonRow(person: SearchPerson, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    Surface(onClick = onClick, color = colors.background, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = (80 * LocalDensity.current.fontScale).dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val user = person.user
            com.messenger.app.ui.components.UserAvatar(user?.id.orEmpty(), user?.avatarVersion ?: 0, user?.hasAvatar == true, person.title, colors.surfaceVariant, colors.accent)
            Column { Text(person.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(person.user?.let { com.messenger.app.ui.publicHandle(it) } ?: person.handle, color = colors.secondaryText, maxLines = 1) }
        }
    }
}
@Composable private fun InlineMediaTile(hit: MessageSearchResult, modifier: Modifier, onClick: () -> Unit) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, hit.thumbnailBase64) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { val bytes = android.util.Base64.decode(hit.thumbnailBase64, android.util.Base64.DEFAULT); android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier.height(112.dp).padding(2.dp).clickable(onClick = onClick).testTag("search-media-${hit.messageId}"), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, stringResource(R.string.inline_search_media), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text(stringResource(R.string.inline_search_media))
    }
}
