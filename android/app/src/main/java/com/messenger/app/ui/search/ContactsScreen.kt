package com.messenger.app.ui.search

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.call.CallAudioPolicy
import com.messenger.app.data.profile.ContactsStore
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.CallMediaKind
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.ui.components.*
import com.messenger.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dedicated address book. No chat/message repository observation in the screen or rows. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun ContactsScreen(viewModel: SearchViewModel, onOpenChat: (String) -> Unit,
    onBack: () -> Unit, onQr: () -> Unit, onEdit: () -> Unit, visible: Boolean = true,
    cachedChat: (String) -> String? = { null }, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val owner = app.privacyRepository.ownerKey()
    val auth by app.authRepository.authState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val selfId = (auth as? AuthSessionState.LoggedIn)?.user?.id
    var ownCard by remember(owner) { mutableStateOf<PublicUser?>(null) }
    val contacts = remember(owner) { app.contactsRepository.store }
    val presence = remember(owner) { ContactsPresenceReader() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val colors = LocalCocoColors.current
    var query by rememberSaveable(owner) { mutableStateOf("") }
    var modal by remember { mutableStateOf(false) }
    var scanningOwner by remember { mutableStateOf<String?>(null) }
    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        result.contents?.takeIf { scanningOwner != null && scanningOwner == app.privacyRepository.ownerKey() }?.let { raw ->
            if (!app.serverJoin.offer(raw, app.authRepository.currentSessionEpoch(), com.messenger.app.data.api.ServerEndpoint.current())) {
                contactsScannedQuery(raw)?.let { query = it }
            }
        }
    }
    var failed by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf<Int?>(null) }
    val screenVisible by rememberUpdatedState(visible)
    val scrolledUnder by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    val glass = rememberGlassBackdrop(blurEnabled = visible && scrolledUnder)
    val entries by contacts.entries.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val phase by contacts.phase.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val opening by viewModel.openingUserId.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val openError by viewModel.openError.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val openChat by viewModel.openChatId.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val preparation = androidx.lifecycle.viewmodel.compose.viewModel<ContactsPreparationState>(key = "contacts-preparation/$owner")
    var projection by remember(owner) { mutableStateOf(preparation.snapshot?.takeIf { it.query == query }
        ?: ContactsPreparedSnapshot(query, emptyList(), emptyList())) }
    val prepared = projection.items
    val directory = projection.directory
    val state = projection.search
    val connected = rememberContactsRoute()
    val legacyIdentity by app.identityRepository.legacy.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val searchesDirectory = contactsCanSearchDirectory(query, legacyIdentity)
    val locale = LocalConfiguration.current.locales[0]
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    BackHandler(onBack = onBack)

    LaunchedEffect(contacts, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            contacts.listAppeared()
            try { contacts.ensureLoaded(); kotlinx.coroutines.awaitCancellation() }
            finally { contacts.listDisappeared() }
        }
    }
    LaunchedEffect(contacts, presence, list, lifecycle, locale, legacyIdentity) {
        val classifications = ContactsClassificationPolicy(projection.online)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Presence is consumed here, never as screen State. A label/lease update only
            // reaches its row; deferred classification always uses the newest snapshot.
            combine(contacts.entries, snapshotFlow { query }, presence.online,
                snapshotFlow { list.isScrollInProgress }, viewModel.uiState) { book, text, latest, scrolling, search ->
                ContactsPreparedSnapshot(text, emptyList(), emptyList(),
                    classifications.target(latest.intersect(book.map { it.id }.toSet()), scrolling), search, book, locale.toLanguageTag(), legacyIdentity)
            }.collectLatest { input ->
                val old = projection
                val classificationOnly = input.query == old.query && input.entries == old.entries && input.localeTag == old.localeTag && input.legacyLogins == old.legacyLogins
                val changed = classifications.changed(input.online)
                val next = withContext(Dispatchers.Default) {
                    val items = if (classificationOnly && changed.isEmpty()) old.items else
                        ContactsPresentation.items(ContactsPresentation.rows(input.entries, input.query, input.online, locale, legacyIdentity))
                    val source = contactsDirectoryPage(input.search, input.query, old.directory)
                    input.copy(items = items, directory = ContactsPresentation.directory(source, input.entries.map { it.id }.toSet(),
                        if (legacyIdentity) null else items.filterIsInstance<ContactListItem.Person>().map { it.entry.id }.toSet()))
                }
                // The fling may have begun while Default prepared the projection.
                if (classificationOnly && changed.isNotEmpty() && list.isScrollInProgress) return@collectLatest
                val nextKeys = next.items.map { it.key } + if (contactsCanSearchDirectory(input.query, legacyIdentity))
                    listOf("server") + next.directory.map { "person:${it.id}" } else emptyList()
                val anchor = if (classificationOnly && next.items != old.items) ContactsAnchorPolicy.capture(
                    list.layoutInfo.visibleItemsInfo.filter { it.offset + it.size > list.layoutInfo.viewportStartOffset }
                        .map { ContactsVisiblePerson(it.key.toString(), it.offset) }, changed, nextKeys.toSet()) else null
                projection = next
                preparation.snapshot = next
                classifications.commit(next.online)
                if (anchor != null) {
                    val index = nextKeys.indexOf(anchor.key)
                    // Actions and book feedback are two stable items before the flat projection.
                    // Item offsets include beforeContentPadding; scroll offsets do not.
                    list.requestScrollToItem(index + 2, list.layoutInfo.beforeContentPadding - anchor.offset)
                }
            }
        }
    }
    LaunchedEffect(query, connected, lifecycle, searchesDirectory) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (searchesDirectory) viewModel.search(query, directory = true, online = connected) else viewModel.close()
            try { kotlinx.coroutines.awaitCancellation() } finally { viewModel.pause() }
        }
    }
    LaunchedEffect(openChat) { openChat?.let { viewModel.consumeOpenChatId(); keyboard?.hide(); onOpenChat(it) } }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.filter { it }.collect { keyboard?.hide(); focus.clearFocus() }
    }
    val presenceOwner = remember(owner) { "contacts:" + java.util.UUID.randomUUID() }
    LaunchedEffect(app.presence, lifecycle, owner) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var subscribed: Set<String>? = null
            try {
                combine(snapshotFlow {
                    if (!screenVisible || modal || ownCard != null || permissionError != null) emptySet() else list.layoutInfo.visibleItemsInfo.mapNotNull {
                        it.key.toString().takeIf { key -> key.startsWith("person:") && it.offset + it.size > 0 }?.removePrefix("person:")
                    }.toSet()
                }.distinctUntilChanged(), app.presence.peers, app.presence.lastSeen) { visible, peers, history -> Triple(visible, peers, history) }
                    .collect { (visible, peers, history) ->
                        if (subscribed != visible) {
                            subscribed = visible
                            app.presence.subscribe(presenceOwner, visible.toList())
                        }
                        presence.update(peers, history, visible, android.os.SystemClock.elapsedRealtime())
                    }
            } finally { app.presence.unsubscribe(presenceOwner) }
        }
    }
    var pendingCall by remember { mutableStateOf<Pair<PublicUser, CallMediaKind>?>(null) }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = pendingCall
        pendingCall = null
        if (request != null) {
            val denied = CallAudioPolicy.requiredCallPermissions(request.second).filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }.toSet()
            if (denied.isEmpty()) app.callController.startCall(request.first.id, request.first.visibleName, request.second)
            else permissionError = com.messenger.app.call.CallPermissionMessages.forDeniedCall(request.second, denied)
        }
    }
    fun call(user: PublicUser, video: Boolean) {
        if (com.messenger.app.ui.isOwnPublicProfile(user.id, selfId)) return
        val kind = if (video) CallMediaKind.VIDEO else CallMediaKind.AUDIO
        pendingCall = user to kind
        callPermission.launch(CallAudioPolicy.allCallPermissions(kind, android.os.Build.VERSION.SDK_INT).toTypedArray())
    }
    fun change(user: PublicUser, add: Boolean) {
        if (!com.messenger.app.ui.isOwnPublicProfile(user.id, selfId)) scope.launch { failed = !contacts.set(user, add) }
    }
    fun message(user: PublicUser) {
        keyboard?.hide()
        if (com.messenger.app.ui.isOwnPublicProfile(user.id, selfId)) { ownCard = user; return }
        val cached = cachedChat(user.id)
        if (cached != null) onOpenChat(cached) else viewModel.message(user)
    }
    fun invite() {
        val username = (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.username
        val own = app.identityRepository.identity.value
        val link = if (own != null) com.messenger.app.data.identity.IdentityPolicy.link(own.number, own.publicOrigin)
            else null
        if (link == null) onEdit()
        else context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }, null))
    }
    permissionError?.let { error -> AlertDialog(onDismissRequest = { permissionError = null }, text = { Text(stringResource(error)) },
        confirmButton = { TextButton(onClick = { permissionError = null }) { Text(stringResource(android.R.string.ok)) } }) }
    com.messenger.app.ui.BlockRootDrawerWhile(modal || ownCard != null || permissionError != null)
    CompositionLocalProvider(LocalGlassBackdrop provides glass) {
    CocoScreenSurface(modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.nestedScroll(scrollBehavior.nestedScrollConnection), color = colors.background) {
        // The address book has one title; connection feedback stays with its search results.
        ContactsGlassLayout(Modifier.fillMaxSize().imePadding(), header = {
            Column(if (scrolledUnder) Modifier.glassPanel(RoundedCornerShape(0.dp), glass) else Modifier) {
            Box {
            LargeTopAppBar(title = { Text(stringResource(R.string.contacts_title)) }, navigationIcon = {
                CocoBackButton(onClick = onBack, modifier = Modifier.testTag("contacts-back"))
            }, collapsedHeight = 64.dp, expandedHeight = 112.dp, scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent))
            }
            ContactsQuery(query, { query = it.take(4096) }, onScan = {
                scanningOwner = owner
                scanner.launch(com.journeyapps.barcodescanner.ScanOptions()
                    .setCaptureActivity(com.messenger.app.capture.SharedQrCaptureActivity::class.java)
                    .setOrientationLocked(false).setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    .setBeepEnabled(false).setBarcodeImageEnabled(false).setTimeout(60_000L))
            })
            if (opening != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (openError) TextButton(onClick = viewModel::dismissOpenError) { Text(stringResource(R.string.search_open_failed)) }
            if (failed) Text(stringResource(R.string.contacts_change_failed), color = colors.secondaryText)
            }
        }) { headerInset ->
            // Opaque capture: the frosted header must never show rows sharp behind it.
            GlassBackdropSource(glass, Modifier.fillMaxSize().background(colors.background)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("contacts-list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = headerInset, bottom = 24.dp + LocalGlassBottomInset.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                if (ContactsPresentation.showsActions(query)) item("actions") {
                    Column {
                        ContactsAction(R.string.sidebar_qr_title, CocoIcons.ReactionGrid, "contacts-qr", resource = R.drawable.coco_drawer_qr, onClick = onQr)
                        ContactsAction(R.string.contacts_invite_unyri, CocoIcons.Forward, "contacts-invite", onClick = ::invite)
                    }
                }
                item("book-feedback") {
                    Column {
                        if (!connected) ContactsNote(R.string.inline_search_offline)
                        if (ContactsPresentation.showsBookPlaceholder(searchesDirectory) && entries.isEmpty() && phase == ContactsStore.Phase.LOADING) Box(Modifier.fillMaxWidth().heightIn(min = 76.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp).testTag("contacts-loading"))
                        }
                        if (phase == ContactsStore.Phase.FAILED && connected) {
                            ContactsNote(R.string.contacts_load_failed)
                            TextButton(onClick = { scope.launch { contacts.reload() } }, modifier = Modifier.heightIn(min = 48.dp).testTag("contacts-retry")) { Text(stringResource(R.string.retry)) }
                        }
                        if (ContactsPresentation.showsBookPlaceholder(searchesDirectory)) {
                            if (entries.isEmpty() && phase == ContactsStore.Phase.LOADED) ContactsNote(R.string.contacts_empty_book)
                            else if (prepared.isEmpty() && query.isNotEmpty()) ContactsNote(R.string.search_no_results)
                        }
                    }
                }
                items(prepared, key = { it.key }, contentType = { if (it is ContactListItem.Person) "person" else "section" }) { item ->
                    when (item) {
                        is ContactListItem.Header -> Text(if (item.section == ContactsPresentation.ONLINE) stringResource(R.string.chat_peer_online) else item.section,
                            fontWeight = FontWeight.SemiBold, color = colors.secondaryText, modifier = Modifier.padding(vertical = 10.dp).semantics { heading() })
                        is ContactListItem.Person -> ContactPerson(item.entry.user, query, presence.row(item.entry.id), opening == null, true,
                            onOpen = { message(item.entry.user) }, onChange = { change(item.entry.user, false) },
                            onCall = { call(item.entry.user, it) }, onMenu = { modal = it },
                            isSelf = com.messenger.app.ui.isOwnPublicProfile(item.entry.id, selfId))
                    }
                }
                if (searchesDirectory) {
                    item("server") {
                        Column(Modifier.testTag("contacts-server-section")) {
                            ContactsServerFeedbackLine(contactsServerFeedback(state, connected, directory.isEmpty(), query.trim()),
                                unavailable = (state as? SearchUiState.Error)?.unavailable == true, connected = connected, retry = viewModel::retry)
                        }
                    }
                    items(directory, key = { "person:${it.id}" }, contentType = { "person" }) { user ->
                        val member = user.id in contacts.ids.value
                        Column(Modifier.testTag("contacts-unknown-result")) {
                            ContactPerson(user, query, presence.row(user.id), opening == null, member,
                                onOpen = { message(user) }, onChange = { change(user, !member) }, onCall = { call(user, it) }, onMenu = { modal = it },
                                isSelf = com.messenger.app.ui.isOwnPublicProfile(user.id, selfId))
                        }
                    }
                    val result = state as? SearchUiState.Loaded
                    if (result != null && connected && result.canLoadMore && !result.pageError && !result.refreshing && result.query == query.trim()) {
                        item("more") { TextButton(onClick = viewModel::loadMore, enabled = !result.loadingMore,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("contacts-server-more")) { Text(stringResource(R.string.load_more)) } }
                    }
                    item("server-hint") { ContactsNote(R.string.inline_search_people_hint) }
                } else if (query.isNotEmpty()) {
                    item("short-query") { ContactsNote(R.string.contacts_short_query) }
                }
            }
            }
        }
    }
    }
    ownCard?.takeIf { com.messenger.app.ui.isOwnPublicProfile(it.id, selfId) }?.let { user ->
        if (owner != null) com.messenger.app.ui.ProfileLinkActionCard(user, owner, selfId,
            { app.privacyRepository.ownerKey() }, { ownCard == user }, { ownCard = null }, onOpenChat)
    }
}

/** Measure the collapsing header before its list in the same pass, then draw it over the capture.
 * No first-frame inset correction, on-size callback, or sampling of the header's own layer. */
@Composable private fun ContactsGlassLayout(modifier: Modifier, header: @Composable () -> Unit,
    content: @Composable (androidx.compose.ui.unit.Dp) -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val heading = subcompose("header", header).single().measure(constraints.copy(minHeight = 0))
        val list = subcompose("list") { content(heading.height.toDp()) }.single().measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            list.placeRelative(0, 0)
            heading.placeRelative(0, 0)
        }
    }
}

@Composable private fun ContactsQuery(query: String, onQuery: (String) -> Unit, onScan: () -> Unit) {
    val colors = LocalCocoColors.current
    val hint = stringResource(R.string.contacts_search)
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().testTag("contacts-search-field")
        .background(colors.deepPanel.copy(alpha = .94f), CircleShape).border(.5.dp, colors.outlineSecondary, CircleShape)
        .heightIn(min = 48.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(CocoIcons.Search, null, tint = colors.secondaryText, modifier = Modifier.size(20.dp))
        BasicTextField(query, onQuery, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.primaryText),
            cursorBrush = SolidColor(colors.accent), modifier = Modifier.weight(1f).padding(horizontal = 10.dp).testTag("contacts-query").semantics { contentDescription = hint },
            decorationBox = { field -> Box { if (query.isEmpty()) Text(hint, color = colors.secondaryText); field() } })
        // Like iOS: the clear button exists only while there is something to clear.
        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(48.dp).testTag("contacts-clear-query")) {
            Icon(CocoIcons.Close, stringResource(R.string.search_clear), tint = colors.secondaryText, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onScan, modifier = Modifier.size(48.dp).testTag("contacts-scan-qr")) {
            Icon(painterResource(R.drawable.coco_drawer_qr), stringResource(R.string.contacts_scan_qr), tint = colors.secondaryText, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable private fun ContactsAction(title: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String,
    resource: Int? = null, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick, role = Role.Button).heightIn(min = 48.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        if (resource == null) Icon(icon, null, tint = colors.primaryText, modifier = Modifier.size(20.dp))
        else Icon(painterResource(resource), null, tint = colors.primaryText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = colors.primaryText, modifier = Modifier.weight(1f))
    }
}

@Composable private fun ContactsNote(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = LocalCocoColors.current.secondaryText,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
}

@Composable internal fun ContactsServerFeedbackLine(feedback: ContactsServerFeedback, unavailable: Boolean, connected: Boolean, retry: () -> Unit) {
    val colors = LocalCocoColors.current
    val large = LocalDensity.current.fontScale >= 1.6f
    val lines = if (large) 4 else 2
    val height = with(LocalDensity.current) { (20.sp.toDp() * lines + 8.dp).coerceAtLeast(56.dp) }
    Row(Modifier.fillMaxWidth().height(height).testTag("contacts-server-feedback"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // The heading occupies the stable feedback row even after a successful answer.
        Text(stringResource(R.string.contacts_find_server), color = colors.primaryText, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() })
        when (feedback) {
            ContactsServerFeedback.LOADING -> CircularProgressIndicator(Modifier.size(24.dp))
            ContactsServerFeedback.ERROR -> {
                Text(stringResource(if (unavailable) R.string.inline_search_unavailable else R.string.contacts_search_failed),
                    color = colors.secondaryText, style = MaterialTheme.typography.bodySmall, maxLines = lines,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = retry, enabled = connected, modifier = Modifier.heightIn(min = 48.dp).testTag("contacts-server-retry")) { Text(stringResource(R.string.retry)) }
            }
            ContactsServerFeedback.CONNECT -> Text(stringResource(R.string.contacts_connect_search), color = colors.secondaryText,
                style = MaterialTheme.typography.bodySmall, maxLines = lines, modifier = Modifier.weight(1f))
            ContactsServerFeedback.EMPTY -> Text(stringResource(R.string.contacts_no_users), color = colors.secondaryText,
                style = MaterialTheme.typography.bodySmall, maxLines = lines, modifier = Modifier.weight(1f))
            ContactsServerFeedback.BLANK -> Unit
        }
    }
}

/** REST search follows route availability, independently of WebSocket handshakes. */
@Composable private fun rememberContactsRoute(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var connected by remember(manager) { mutableStateOf(manager.activeNetwork != null) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { connected = true }
            override fun onLost(network: Network) { connected = manager.activeNetwork?.let { it != network } == true }
            override fun onUnavailable() { connected = false }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { connected = manager.activeNetwork != null }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return connected
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ContactPerson(user: PublicUser, query: String, reader: StateFlow<ContactPresence>, enabled: Boolean,
    contact: Boolean, onOpen: () -> Unit, onChange: () -> Unit, onCall: (Boolean) -> Unit, onMenu: (Boolean) -> Unit,
    isSelf: Boolean = false, modifier: Modifier = Modifier) {
    val value by reader.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val colors = LocalCocoColors.current
    var menu by remember(user.id) { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (menu) onMenu(false) } }
    val locale = LocalConfiguration.current.locales[0]
    // Calendar labels refresh on screen/presence events, without a timer per row.
    val seen = value.lastSeen?.let { com.messenger.app.ui.chats.presenceDate(it, java.time.Instant.now(), java.time.ZoneId.systemDefault(), locale) }
    val status = if (isSelf) stringResource(R.string.contacts_self) else if (value.online) stringResource(R.string.chat_peer_online) else if (seen != null)
        stringResource(when (seen.first) { 0 -> R.string.presence_today; 1 -> R.string.presence_yesterday; else -> R.string.presence_date }, seen.second)
        else if (value.hidden) stringResource(R.string.contacts_status_hidden) else ""
    val large = LocalDensity.current.fontScale >= 1.6f
    Column(modifier.fillMaxWidth().testTag("contacts-person-${user.id}")) {
        Row(Modifier.fillMaxWidth().combinedClickable(enabled = enabled, onClick = onOpen,
            onLongClick = if (isSelf) null else { { menu = true; onMenu(true) } })
            .heightIn(min = 76.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp)) {
                UserAvatar(user.id, user.avatarVersion, user.hasAvatar, user.visibleName, colors.surfaceVariant, colors.primaryText, size = 44.dp)
                if (value.online) Box(Modifier.align(Alignment.BottomEnd).size(10.dp).background(Color(0xFF77E6C6), CircleShape).border(2.dp, colors.background, CircleShape))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(remember(user.visibleName, query) { contactHighlight(user.visibleName, query) }, color = colors.primaryText, fontSize = 16.sp,
                    maxLines = if (large) Int.MAX_VALUE else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                // B-051: the public number (and @username) is always shown, with the status under it.
                val caption = com.messenger.app.ui.contactCaption(user)
                if (caption.isNotBlank()) Text(contactHighlight(caption, query), color = colors.secondaryText, fontSize = 13.sp,
                    maxLines = if (large) Int.MAX_VALUE else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("contacts-person-number"))
                if (status.isNotBlank())
                Text(status, color = if (value.online) Color(0xFF77E6C6) else colors.secondaryText, fontSize = 12.sp,
                    maxLines = if (large) Int.MAX_VALUE else 1)
            }
            // Owner, 14 Nov: a person found on the server is added with a button on the row
            // (no separate "add contact" row and dialog).
            if (!isSelf && !contact) IconButton(onClick = onChange, enabled = enabled, modifier = Modifier.size(48.dp).testTag("contacts-add-${user.id}")) {
                Icon(CocoIcons.Plus, stringResource(R.string.contacts_add), tint = colors.primaryText, modifier = Modifier.size(22.dp))
            }
        }
        if (!isSelf) DropdownMenu(expanded = menu, onDismissRequest = { menu = false; onMenu(false) }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.contacts_write)) }, onClick = { menu = false; onMenu(false); onOpen() })
            if (contact) {
                DropdownMenuItem(text = { Text(stringResource(R.string.contacts_call)) }, onClick = { menu = false; onMenu(false); onCall(false) })
                DropdownMenuItem(text = { Text(stringResource(R.string.contacts_video)) }, onClick = { menu = false; onMenu(false); onCall(true) })
            }
            DropdownMenuItem(text = { Text(stringResource(if (contact) R.string.contacts_remove else R.string.contacts_add)) },
                onClick = { menu = false; onMenu(false); onChange() })
        }
        HorizontalDivider(Modifier.padding(start = 56.dp), .5.dp, colors.divider)
    }
}

private fun contactHighlight(text: String, query: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        ContactsPresentation.matchRanges(text, query).forEach { range ->
            addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
        }
    }
