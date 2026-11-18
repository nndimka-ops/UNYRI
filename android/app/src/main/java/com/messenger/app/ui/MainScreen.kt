package com.messenger.app.ui

import androidx.compose.runtime.collectAsState

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import com.messenger.app.ui.theme.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.chats.ChatsScreen
import com.messenger.app.ui.chats.ChatsViewModel
import com.messenger.app.ui.profile.ProfileScreen
import com.messenger.app.ui.profile.ProfileViewModel
import com.messenger.app.ui.search.SearchViewModel
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors

/**
 * COCO root screen: Chats · Calls · Settings. Search opens from the chats toolbar. Calls is
 * conditionally visible in the drawer via the persisted preference; hiding
 * it affects ONLY tab visibility + selection fallback, never call capability.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION") // M3 animateTo keeps its native drawer/gestures with the approved duration.
fun MainScreen(
    chatsViewModel: ChatsViewModel,
    searchViewModel: SearchViewModel,
    profileViewModel: ProfileViewModel,
    showSavedMessages: Boolean,
    onShowSavedMessagesChange: (Boolean) -> Unit,
    showTabLabels: Boolean,
    onShowTabLabelsChange: (Boolean) -> Unit,
    showCallsTab: Boolean,
    onShowCallsTabChange: (Boolean) -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenSearchMessage: (com.messenger.app.domain.model.MessageSearchResult) -> Unit,
    onNavigateToEdit: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToLanguage: () -> Unit,
    onNavigateToServer: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onLogout: () -> Unit,
    onDestinationChanged: (RootDestination) -> Unit = {},
    drawerNavigationAvailable: Boolean = true,
    inlineSearchRequest: Int = 0,
    onOpenRowChat: (String) -> Unit = onOpenChat,
) {
    var handledInlineRequest by rememberSaveable { mutableStateOf(0) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchPeople by rememberSaveable { mutableStateOf(false) }
    var contactsActive by rememberSaveable { mutableStateOf(false) }
    var searchRequestKey by rememberSaveable { mutableStateOf(0) }
    var selectedDestination by rememberSaveable { mutableStateOf(RootDestination.CHATS) }
    var settingsQuickAction by rememberSaveable { mutableStateOf<String?>(null) }
    val tabState = rememberSaveableStateHolder()
    val colors = LocalCocoColors.current
    val sidebarStyle = LocalResolvedSidebarStyle.current

    val destinations = RootNavigation.visibleDestinations(showCallsTab, showSearch = false)
    val effectiveDestination = RootNavigation.fallbackSelected(selectedDestination, showCallsTab, showSearch = false)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val profileState by profileViewModel.uiState.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val profile = (profileState as? com.messenger.app.ui.profile.ProfileUiState.Loaded)?.profile
    val drawerApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val publicIdentity by drawerApp.identityRepository.identity.collectAsStateWithLifecycle()
    val legacyIdentity by drawerApp.identityRepository.legacy.collectAsStateWithLifecycle()
    val drawerAuth by drawerApp.authRepository.authState.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val drawerUser = (drawerAuth as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user
    var showingArchive by rememberSaveable(drawerUser?.id, drawerApp.authRepository.currentSessionEpoch()) { mutableStateOf(false) }
    var showProfileQr by rememberSaveable(drawerUser?.id, drawerApp.authRepository.currentSessionEpoch()) { mutableStateOf(false) }
    val blockers = remember { RootDrawerBlockers() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var rootResumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            rootResumed = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val mayOpen by androidx.compose.runtime.rememberUpdatedState(RootDrawerPolicy.allowsOpening(
        effectiveDestination, drawerNavigationAvailable && !contactsActive && !searchActive, rootResumed, blockers.blocked))
    val openMenu: () -> Unit = {
        if (mayOpen) {
            CocoHaptics.select(hapticView)
            scope.launch {
                if (mayOpen) drawer.animateTo(DrawerValue.Open, tween(sidebarStyle.behaviorDuration.toInt()))
            }
        }
    }
    LaunchedEffect(mayOpen) { if (!mayOpen) drawer.snapTo(DrawerValue.Closed) }
    BackHandler(!drawer.isOpen && mayOpen && effectiveDestination != RootDestination.CHATS) { selectedDestination = RootDestination.CHATS }
    val navigate: (RootDestination) -> Unit = { destination ->
        CocoHaptics.select(hapticView)
        contactsActive = false; searchActive = false; showingArchive = false; searchViewModel.close()
        scope.launch { drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt())); selectedDestination = destination }
    }
    val openSearch: (Boolean) -> Unit = { people ->
        CocoHaptics.select(hapticView)
        showingArchive = false; searchViewModel.close()
        scope.launch {
            drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt())); selectedDestination = RootDestination.CHATS
            if (people) { contactsActive = true; searchActive = false }
            else { contactsActive = false; searchPeople = false; searchRequestKey++; searchActive = true }
        }
    }
    val quickSettings: (String) -> Unit = { action ->
        CocoHaptics.select(hapticView)
        searchActive = false; searchViewModel.close()
        scope.launch { drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt())); selectedDestination = RootDestination.SETTINGS; settingsQuickAction = action }
    }
    val quickNavigate: (() -> Unit) -> Unit = { action ->
        CocoHaptics.select(hapticView)
        searchActive = false; searchViewModel.close()
        scope.launch { drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt())); action() }
    }
    LaunchedEffect(effectiveDestination) {
        onDestinationChanged(effectiveDestination)
    }
    LaunchedEffect(showCallsTab) {
        if (selectedDestination == RootDestination.SEARCH) { searchRequestKey++; searchActive = true }
        selectedDestination = RootNavigation.fallbackSelected(selectedDestination, showCallsTab, showSearch = false)
    }

    LaunchedEffect(inlineSearchRequest) {
        if (inlineSearchRequest > handledInlineRequest) { handledInlineRequest = inlineSearchRequest; contactsActive = false; selectedDestination = RootDestination.CHATS; searchRequestKey++; searchActive = true }
    }
    val chromePolicy = com.messenger.app.ui.components.rememberCocoChromePolicy()
    CompositionLocalProvider(com.messenger.app.ui.components.LocalCocoChromePolicy provides chromePolicy,
        LocalRootDrawerBlockers provides blockers) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val drawerChoice = LocalDrawerChoice.current
            val menuWidth = drawerWidthDp(drawerChoice, maxWidth.value, LocalDensity.current.fontScale).dp
            val menuWidthPx = with(LocalDensity.current) { menuWidth.toPx() }
            val drawerVisible by remember(drawer, menuWidthPx) { derivedStateOf {
                drawer.currentValue == DrawerValue.Open || drawer.targetValue == DrawerValue.Open ||
                    drawer.currentOffset.let { it.isFinite() && it > -menuWidthPx + .5f }
            } }
            // Capture only destination content, only while the drawer needs it.
            val glass = rememberGlassBackdrop(blurEnabled = drawerVisible && rootResumed)
            CompositionLocalProvider(LocalGlassBackdrop provides glass) {
            ModalNavigationDrawer(drawerState = drawer,
                gesturesEnabled = if (drawer.isOpen) sidebarStyle.behaviorSwipeClose else mayOpen && sidebarStyle.behaviorSwipeOpen,
                scrimColor = Color.Black.copy(alpha = sidebarStyle.behaviorScrim / 100f), drawerContent = {
                    VisibleDrawerContent(drawer, menuWidth, rootResumed, drawerApp, chatsViewModel) { badges ->
                    SidebarDrawer(width = menuWidth, userId = profile?.id ?: drawerUser?.id,
                        displayName = profile?.displayName ?: drawerUser?.displayName ?: "UNYRI",
                        username = publicIdentity?.username,
                        publicNumber = publicIdentity?.number,
                        avatarVersion = profile?.avatarVersion ?: 0, hasAvatar = profile?.hasAvatar == true,
                        selected = effectiveDestination, unread = badges.chats, showCalls = showCallsTab, showSaved = showSavedMessages,
                        onClose = { scope.launch { drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt())) } }, onEdit = { quickNavigate(onNavigateToEdit) },
                        onSearch = { openSearch(false) }, onPeople = { openSearch(true) },
                        onDestination = navigate,
                        onSaved = { CocoHaptics.select(hapticView); searchActive = false; showingArchive = false; searchViewModel.close(); scope.launch { drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt())); selectedDestination = RootDestination.CHATS; chatsViewModel.openSavedMessages(onOpenChat) } },
                        onServer = { quickNavigate(onNavigateToServer) }, onDevices = { quickNavigate(onNavigateToDevices) },
                        onHelp = { quickSettings("help") }, onReport = { quickSettings("feedback") }, onLogout = { quickSettings("logout") },
                        onRooms = { navigate(RootDestination.ROOMS) },
                        showingArchive = showingArchive,
                        onArchive = {
                            CocoHaptics.select(hapticView); searchActive = false; searchViewModel.close()
                            scope.launch {
                                drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt()))
                                selectedDestination = RootDestination.CHATS; showingArchive = true
                            }
                        },
                        badges = badges,
                        host = remember { java.net.URI(com.messenger.app.data.api.ServerEndpoint.current()).host.orEmpty() },
                        onQR = {
                            val qrOwner = drawerApp.privacyRepository.ownerKey()
                            CocoHaptics.select(hapticView)
                            scope.launch {
                                closeDrawerThenShowQr(
                                    { drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt())) },
                                    { if (qrOwner != null && qrOwner == drawerApp.privacyRepository.ownerKey()) showProfileQr = true })
                            }
                        },
                        status = { DrawerPresenceMenu(drawerApp, compact = drawerChoice == DrawerChoice.A) },
                        accounts = { layout -> DrawerAccounts(drawerApp, layout) })
                    }
                },
            ) {
                GlassBackdropSource(glass, Modifier.fillMaxSize()) {
                Scaffold(
                // Like Telegram (owner, 18 Nov, B-027): the drawer slides over a still chat list;
                // the content no longer follows the panel (the 18 % parallax is removed).
                modifier = Modifier.fillMaxSize(),
                containerColor = LocalCocoColors.current.background,
                ) { padding ->
                    val contentModifier = Modifier.padding(top = padding.calculateTopPadding())
                    CompositionLocalProvider(LocalGlassBottomInset provides padding.calculateBottomPadding()) {
                        // Contacts keeps its own saved state, so Back restores the chat list's scroll and filter.
                        tabState.SaveableStateProvider(if (effectiveDestination == RootDestination.CHATS && contactsActive) "CONTACTS" else effectiveDestination.name) {
                            when (effectiveDestination) {
                                RootDestination.CHATS -> if (contactsActive) com.messenger.app.ui.search.ContactsScreen(
                                    viewModel = searchViewModel, onOpenChat = onOpenChat,
                                    onBack = { contactsActive = false }, onQr = { showProfileQr = true },
                                    onEdit = onNavigateToEdit, visible = !showProfileQr && drawerNavigationAvailable,
                                    cachedChat = { id -> chatsViewModel.chats.value.firstOrNull { it.type == "direct" && it.otherUser?.id == id }?.chatId },
                                ) else ChatsScreen(
                                viewModel = chatsViewModel,
                                showSavedMessages = false,
                                onOpenMenu = openMenu,
                                onOpenChat = onOpenChat,
                                onOpenRowChat = onOpenRowChat,
                                searchViewModel = searchViewModel,
                                searching = searchActive,
                                searchPeople = searchPeople, searchRequestKey = searchRequestKey,
                                showingArchive = showingArchive, onCloseArchive = { showingArchive = false },
                                onSearchChange = { searchActive = it; if (!it) searchViewModel.close() else { searchPeople = false; searchRequestKey++ } },
                                onOpenSearchMessage = onOpenSearchMessage,
                                modifier = contentModifier,
                                )
                                RootDestination.CALLS -> com.messenger.app.ui.calls.CallHistoryScreen(onOpenMenu = openMenu, modifier = contentModifier, chatSummaries = chatsViewModel.chats, onOpenChat = onOpenChat)
                                RootDestination.ROOMS -> com.messenger.app.conference.ConferenceRoomsScreen(onOpenMenu = openMenu, modifier = contentModifier)
                                RootDestination.TRANSFERS -> com.messenger.app.ui.transfers.DirectTransferManagerScreen(
                                    modifier = contentModifier, chatSummaries = chatsViewModel.chats,
                                    onBack = { navigate(RootDestination.CHATS) }, onOpenChat = onOpenChat,
                                )
                                RootDestination.SEARCH -> Unit // Legacy requests redirect into Chats.
                                RootDestination.SETTINGS -> ProfileScreen(
                                quickAction = settingsQuickAction,
                                onQuickActionConsumed = { settingsQuickAction = null },
                                onOpenMenu = openMenu,
                                viewModel = profileViewModel,
                                showSavedMessages = showSavedMessages,
                                onShowSavedMessagesChange = onShowSavedMessagesChange,
                                showTabLabels = showTabLabels,
                                onShowTabLabelsChange = onShowTabLabelsChange,
                                showCallsTab = showCallsTab,
                                onShowCallsTabChange = onShowCallsTabChange,
                                onNavigateToEdit = onNavigateToEdit,
                                onNavigateToDevices = onNavigateToDevices,
                                onNavigateToAppearance = onNavigateToAppearance,
                                onNavigateToLanguage = onNavigateToLanguage,
                                onNavigateToServer = onNavigateToServer,
                                onNavigateToStorage = onNavigateToStorage,
                                onLogout = onLogout,
                                modifier = contentModifier,
                                )
                            }
                        }
                    }
                }
                }
            }
            }
        }
    }

    // Register last so the open modal drawer consumes Back before a search,
    // selection or nested screen's own handler.
    if (showProfileQr) ProfileQrSheet(profile?.displayName ?: drawerUser?.displayName ?: "UNYRI",
        publicIdentity?.username ?: profile?.username?.takeIf { legacyIdentity }, onEdit = { quickNavigate(onNavigateToEdit) }, onClose = { showProfileQr = false })
    BackHandler(drawer.isOpen && !showProfileQr) { scope.launch { drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt())) } }
}

internal suspend fun closeDrawerThenShowQr(close: suspend () -> Unit, show: () -> Unit) {
    close()
    show()
}

/** Retain the native sheet's measurement/gesture anchor while unmounting live readers offscreen. */
@Composable
internal fun VisibleDrawerContent(drawer: DrawerState, width: androidx.compose.ui.unit.Dp, resumed: Boolean,
    app: com.messenger.app.MessengerApp, chats: ChatsViewModel, content: @Composable (DrawerBadges) -> Unit) {
    val widthPx = with(LocalDensity.current) { width.toPx() }
    val visible by remember(drawer, widthPx) { derivedStateOf {
        drawer.currentValue == DrawerValue.Open || drawer.targetValue == DrawerValue.Open ||
            drawer.currentOffset.let { it.isFinite() && it > -widthPx + .5f }
    } }
    if (visible && resumed) {
        val transfersFlow = remember(app) { app.directTransfers.managerItems.map(::drawerTransferCount).distinctUntilChanged() }
        val transfers by transfersFlow.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED, initialValue = drawerTransferCount(app.directTransfers.managerItems.value))
        val unreadFlow = remember(chats) { chats.chats.map { rows -> rows.sumOf { it.unreadCount.coerceAtLeast(0) } }.distinctUntilChanged() }
        val unread by unreadFlow.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED, initialValue = chats.chats.value.sumOf { it.unreadCount.coerceAtLeast(0) })
        val owner = (app.authRepository.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id.orEmpty()
        val missedFlow = remember(app, owner) { app.callHistory.calls.map { rows -> rows.count {
            it.unread && com.messenger.app.data.call.callOutcome(it, owner) == com.messenger.app.data.call.CallHistoryOutcome.MISSED
        } }.distinctUntilChanged() }
        val missed by missedFlow.collectAsStateWithLifecycle(initialValue = 0, minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
        var archives by remember(app, owner) { mutableStateOf(emptySet<String>()) }
        LaunchedEffect(app, owner) { archives = app.chatRepository.localArchives() }
        val archiveFlow = remember(chats, archives) { chats.chats.map { rows -> rows.filter { it.chatId in archives }.sumOf { it.unreadCount.coerceAtLeast(0) } }.distinctUntilChanged() }
        val archive by archiveFlow.collectAsStateWithLifecycle(initialValue = 0, minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
        LaunchedEffect(unread) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.accountWorkspaces.updateUnread(unread) } }
        content(DrawerBadges(unread, archive, missed, transfers))
    } else ModalDrawerSheet(Modifier.width(width), drawerContainerColor = Color.Transparent, drawerTonalElevation = 0.dp) {}
}

/** Calls the existing isolated-workspace handoff; never logs the current account out. */
@Composable
internal fun DrawerAccounts(app: com.messenger.app.MessengerApp, layout: DrawerAccountsLayout) {
    val compact = layout == DrawerAccountsLayout.COMPACT
    val narrow = layout == DrawerAccountsLayout.NARROW
    val workspaces = app.accountWorkspaces
    val accountRevision by workspaces.changes.collectAsState()
    var accounts by remember(workspaces, accountRevision) { mutableStateOf(workspaces.accounts()) }
    val style = LocalResolvedSidebarStyle.current
    val ink = sidebarInk(style.typographyText)
    val muted = sidebarInk(style.typographyMuted)
    val switchLabel = stringResource(R.string.sidebar_switch_account)
    var menu by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val action = drawerAccountAction(accounts, workspaces.activeKey, accounts.size < 2)
    val addLabel = stringResource(R.string.sidebar_add_account)
    Column(Modifier.testTag("drawer-accounts"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (action is DrawerAccountAction.Switch) {
            val account = action.account
            val label = stringResource(R.string.drawer_switch_to, account.displayName)
            Row((if (compact) Modifier else Modifier.fillMaxWidth()).heightIn(min = if (compact) 44.dp else if (narrow) 48.dp else 52.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = .05f))
                .then(Modifier.combinedAccountClick({ app.switchLocalAccount(account.key) }, { menu = true }))
                .semantics { contentDescription = label; onClick(label = switchLabel, action = null) }
                .padding(horizontal = 8.dp, vertical = 4.dp).testTag("drawer-account-1"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(if (compact) 28.dp else if (narrow) 32.dp else 36.dp), contentAlignment = Alignment.Center) {
                    com.messenger.app.ui.components.AccountAvatar(account, workspaces.avatarStore, if (compact) 28.dp else if (narrow) 32.dp else 36.dp, ink)
                    if (compact && account.unreadCount > 0) Box(Modifier.align(Alignment.TopEnd).size(7.dp).background(LocalCocoColors.current.accent, CircleShape))
                }
                if (!compact) Column(Modifier.weight(1f)) {
                    Text(account.displayName, color = ink, fontSize = if (narrow) 14.sp else 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = if (narrow) 1 else Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (account.unreadCount > 0) Text(stringResource(R.string.drawer_account_unread, account.unreadCount), color = muted, fontSize = 12.sp)
                }
                if (!compact) Text("⇄", color = muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.drawer_remove_account)) }, onClick = {
                    menu = false
                    scope.launch {
                        failed = !kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { workspaces.removeInactive(account.key) }.isSuccess }
                        accounts = workspaces.accounts()
                    }
                })
            }
        }
        if (action == DrawerAccountAction.Add) Row((if (compact) Modifier.widthIn(min = 44.dp) else Modifier.fillMaxWidth())
            .heightIn(min = if (compact) 44.dp else if (narrow) 48.dp else 52.dp).dashedAccountOutline()
            .clickable(role = Role.Button) { app.addLocalAccount() }
            .semantics { contentDescription = addLabel }
            .padding(horizontal = 6.dp, vertical = 4.dp).testTag("drawer-add-account"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(30.dp).background(Color.White.copy(alpha = .08f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(CocoIcons.Plus, null, tint = ink, modifier = Modifier.size(18.dp))
            }
            if (!compact) Text(stringResource(R.string.sidebar_add_account), color = ink, fontSize = if (narrow) 14.sp else 15.sp, lineHeight = if (narrow) 17.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                fontFamily = sidebarFont(style.typographyFamily), modifier = Modifier.weight(1f))
        }
        if (failed) Text(stringResource(R.string.error_generic), color = muted)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedAccountClick(click: () -> Unit, longClick: () -> Unit) =
    this.combinedClickable(onClick = click, onLongClick = longClick, role = Role.Button)

private fun Modifier.dashedAccountOutline(): Modifier = this.drawWithCache {
    val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(
        androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), androidx.compose.ui.geometry.CornerRadius(26.dp.toPx()))) }
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
    onDrawBehind { drawPath(path, Color.White.copy(alpha = .25f), style = stroke) }
}

private fun iconFor(destination: RootDestination): ImageVector = when (destination) {
    RootDestination.CHATS -> RootNavIcons.Chats
    RootDestination.CALLS -> CocoIcons.AudioCall
    RootDestination.ROOMS -> CocoIcons.AudioCall
    RootDestination.TRANSFERS -> CocoIcons.File
    RootDestination.SEARCH -> CocoIcons.Search
    RootDestination.SETTINGS -> RootNavIcons.Settings
}

private fun labelRes(destination: RootDestination): Int = when (destination) {
    RootDestination.CHATS -> R.string.nav_chats
    RootDestination.CALLS -> R.string.nav_calls
    RootDestination.ROOMS -> R.string.conference_rooms_title
    RootDestination.TRANSFERS -> R.string.direct_manager_title
    RootDestination.SEARCH -> R.string.nav_search
    RootDestination.SETTINGS -> R.string.nav_settings
}
