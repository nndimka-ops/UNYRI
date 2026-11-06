package com.messenger.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.chats.ChatsScreen
import com.messenger.app.ui.chats.ChatsViewModel
import com.messenger.app.ui.profile.ProfileScreen
import com.messenger.app.ui.profile.ProfileViewModel
import com.messenger.app.ui.search.SearchUsersScreen
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
) {
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchPeople by rememberSaveable { mutableStateOf(false) }
    var searchRequestKey by rememberSaveable { mutableStateOf(0) }
    var selectedDestination by rememberSaveable { mutableStateOf(RootDestination.CHATS) }
    var settingsQuickAction by rememberSaveable { mutableStateOf<String?>(null) }
    val tabState = rememberSaveableStateHolder()
    val colors = LocalCocoColors.current
    val sidebarStyle = LocalResolvedSidebarStyle.current
    val unreadTotal by remember(chatsViewModel) {
        chatsViewModel.chats.map { rows -> rows.sumOf { it.unreadCount.coerceAtLeast(0) } }.distinctUntilChanged()
    }.collectAsState(initial = 0)

    val destinations = RootNavigation.visibleDestinations(showCallsTab, showSearch = false)
    val effectiveDestination = RootNavigation.fallbackSelected(selectedDestination, showCallsTab, showSearch = false)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val profileState by profileViewModel.uiState.collectAsState()
    val profile = (profileState as? com.messenger.app.ui.profile.ProfileUiState.Loaded)?.profile
    val drawerApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val drawerAuth by drawerApp.authRepository.authState.collectAsState()
    val drawerUser = (drawerAuth as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user
    var showingArchive by rememberSaveable(drawerUser?.id, drawerApp.authRepository.currentSessionEpoch()) { mutableStateOf(false) }
    val drawerConnection by drawerApp.messageRepository.socketState.collectAsState()
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
        effectiveDestination, drawerNavigationAvailable, rootResumed, blockers.blocked))
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
        searchActive = false; showingArchive = false; searchViewModel.close()
        scope.launch { drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt())); selectedDestination = destination }
    }
    val openSearch: (Boolean) -> Unit = { people ->
        CocoHaptics.select(hapticView)
        showingArchive = false; searchViewModel.close()
        scope.launch {
            drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt())); selectedDestination = RootDestination.CHATS
            searchPeople = people; searchRequestKey++; searchActive = true
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
        selectedDestination = RootNavigation.fallbackSelected(selectedDestination, showCallsTab, showSearch = false)
    }

    val glass = rememberGlassBackdrop(blurEnabled = false)
    CompositionLocalProvider(LocalGlassBackdrop provides glass, LocalRootDrawerBlockers provides blockers) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val menuWidth = sidebarStyle.metrics(maxWidth.value).panelWidth.dp
            ModalNavigationDrawer(drawerState = drawer,
                gesturesEnabled = if (drawer.isOpen) sidebarStyle.behaviorSwipeClose else mayOpen && sidebarStyle.behaviorSwipeOpen,
                scrimColor = Color.Black.copy(alpha = sidebarStyle.behaviorScrim / 100f), drawerContent = {
                    GraphiteDrawer(width = menuWidth, userId = profile?.id ?: drawerUser?.id,
                        displayName = profile?.displayName ?: drawerUser?.displayName ?: "UNYRI",
                        username = profile?.username ?: drawerUser?.username,
                        avatarVersion = profile?.avatarVersion ?: 0, hasAvatar = profile?.hasAvatar == true,
                        selected = effectiveDestination, unread = unreadTotal, showCalls = showCallsTab, showSaved = showSavedMessages,
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
                        isOnline = drawerUser != null && drawerConnection == com.messenger.app.domain.repository.SocketConnectionState.CONNECTED)
                },
            ) {
                Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = LocalCocoColors.current.background,
                ) { padding ->
                    val contentModifier = Modifier.padding(top = padding.calculateTopPadding()).glassSource(glass)
                    CompositionLocalProvider(LocalGlassBottomInset provides padding.calculateBottomPadding()) {
                        tabState.SaveableStateProvider(effectiveDestination.name) {
                            when (effectiveDestination) {
                                RootDestination.CHATS -> ChatsScreen(
                                viewModel = chatsViewModel,
                                showSavedMessages = false,
                                onOpenMenu = openMenu,
                                onOpenChat = onOpenChat,
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
                                RootDestination.SEARCH -> SearchUsersScreen(
                                onOpenMessage = onOpenSearchMessage,
                                viewModel = searchViewModel,
                                onOpenChat = onOpenChat,
                                modifier = contentModifier,
                                )
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

    // Register last so the open modal drawer consumes Back before a search,
    // selection or nested screen's own handler.
    BackHandler(drawer.isOpen) { scope.launch { drawer.animateTo(DrawerValue.Closed, tween(sidebarStyle.behaviorDuration.toInt())) } }
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
