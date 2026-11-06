package com.messenger.app
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

import android.content.Context
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.messenger.app.data.local.MediaCacheManager
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.ui.MainScreen
import com.messenger.app.ui.auth.AuthViewModel
import com.messenger.app.ui.auth.AuthViewModelFactory
import com.messenger.app.ui.auth.LoginScreen
import com.messenger.app.ui.auth.RegisterScreen
import com.messenger.app.ui.call.CallOverlay
import com.messenger.app.ui.call.callTouchTransitionGuard
import com.messenger.app.ui.call.CallViewModel
import com.messenger.app.ui.call.CallViewModelFactory
import com.messenger.app.ui.chats.ChatDetailViewModel
import com.messenger.app.ui.chats.ChatDetailViewModelFactory
import com.messenger.app.ui.chats.ChatScreen
import com.messenger.app.ui.chats.ChatsViewModel
import com.messenger.app.ui.chats.ChatsViewModelFactory
import com.messenger.app.ui.components.ForegroundMessageBannerHost
import com.messenger.app.ui.theme.glassSource
import com.messenger.app.ui.devices.DevicesScreen
import com.messenger.app.ui.devices.DevicesViewModel
import com.messenger.app.ui.devices.DevicesViewModelFactory
import com.messenger.app.ui.devices.VerificationScreen
import com.messenger.app.ui.devices.VerificationViewModel
import com.messenger.app.ui.devices.VerificationViewModelFactory
import com.messenger.app.ui.media.MediaGalleryScreen
import com.messenger.app.ui.media.MediaGalleryViewModel
import com.messenger.app.ui.media.MediaGalleryViewModelFactory
import com.messenger.app.ui.profile.EditProfileScreen
import com.messenger.app.ui.profile.ProfileViewModel
import com.messenger.app.ui.profile.ProfileViewModelFactory
import com.messenger.app.ui.search.MessageSearchScreen
import com.messenger.app.ui.search.MessageSearchViewModel
import com.messenger.app.ui.search.MessageSearchViewModelFactory
import com.messenger.app.ui.search.SearchUsersScreen
import com.messenger.app.ui.search.SearchViewModel
import com.messenger.app.ui.search.SearchViewModelFactory
import com.messenger.app.ui.settings.AppearanceScreen
import com.messenger.app.ui.settings.LanguageScreen
import com.messenger.app.ui.settings.ServerPickerScreen
import com.messenger.app.ui.settings.StorageScreen
import com.messenger.app.ui.settings.StorageViewModel
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.LanguagePreference
import com.messenger.app.ui.theme.NavigationPreference
import com.messenger.app.ui.theme.ThemeMode
import com.messenger.app.ui.theme.ThemePreference
import com.messenger.app.ui.theme.applyLocale
import java.util.Locale

private const val ROUTE_LOGIN = "login"
private const val ROUTE_REGISTER = "register"
private const val ROUTE_MAIN = "main"
private const val ROUTE_EDIT_PROFILE = "edit_profile"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_DEVICES = "devices"
private const val ROUTE_CHAT_DETAIL = "chat"
private const val ROUTE_VERIFY = "verify"
private const val ROUTE_SEARCH_MESSAGES = "search_messages"
private const val ROUTE_SEARCH_MESSAGES_CHAT = "search_messages_chat"
private const val ROUTE_MEDIA = "media"
private const val ROUTE_APPEARANCE = "appearance"
private const val ROUTE_LANGUAGE = "language"
private const val ROUTE_SERVER = "server"
private const val ROUTE_STORAGE = "storage"
private const val ARG_CHAT_ID = "chatId"
private const val ARG_OTHER_USER_ID = "otherUserId"
private const val ARG_TARGET = "targetMessageId"
private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 7001

private class StorageViewModelFactory(private val manager: MediaCacheManager) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = StorageViewModel(manager) as T
}

class MainActivity : ComponentActivity() {
    val appLockController by lazy { com.messenger.app.security.AppLockController(this) }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
            (application as MessengerApp).performanceDiagnostics.touch()
        }
        return super.dispatchTouchEvent(event)
    }
    private var ringingWindowCall: String? = null
    private var ringingWindowDeadline = 0L
    private var ringingWindowTimer: kotlinx.coroutines.Job? = null
    private var ownsRingingKeepScreen = false
    private fun updateCallWindow(state: com.messenger.app.domain.call.CallUiState) {
        val ringing = state as? com.messenger.app.domain.call.CallUiState.IncomingRinging
        val active = ringing != null || state is com.messenger.app.domain.call.CallUiState.Connecting ||
            state is com.messenger.app.domain.call.CallUiState.Connected || state is com.messenger.app.domain.call.CallUiState.Reconnecting
        if (ringing?.call?.callId != ringingWindowCall) {
            ringingWindowTimer?.cancel()
            ringingWindowCall = ringing?.call?.callId
            ringingWindowDeadline = if (ringing != null) android.os.SystemClock.elapsedRealtime() + 60_000 else 0
            if (ringing != null) ringingWindowTimer = lifecycleScope.launch {
                kotlinx.coroutines.delay(60_000)
                if (ownsRingingKeepScreen) { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); ownsRingingKeepScreen = false }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setTurnScreenOn(false)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
        val wake = ringing != null && android.os.SystemClock.elapsedRealtime() < ringingWindowDeadline
        if (wake) { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); ownsRingingKeepScreen = true }
        else if (ownsRingingKeepScreen) {
            if (!com.messenger.app.call.IncomingCallPresentationPolicy.videoOwnsScreen(state)) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            ownsRingingKeepScreen = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(active); setTurnScreenOn(wake) }
        else {
            val show = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            val turn = android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (active) window.addFlags(show) else window.clearFlags(show)
            if (wake) window.addFlags(turn) else window.clearFlags(turn)
        }
    }

    private var sharedFiles by mutableStateOf<List<android.net.Uri>>(emptyList())
    @Suppress("DEPRECATION")
    private fun receiveSharedFiles(incoming: Intent) {
        sharedFiles = when (incoming.action) {
            Intent.ACTION_SEND -> listOfNotNull(incoming.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> incoming.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> return
        }.filter { it.scheme == "content" }.distinct().take(10)
    }


    private var pipVisible by mutableStateOf(false)
    private var pipRestoreGeneration by mutableStateOf(0)
    private val pipSupported: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun updateCallPip() {
        if (!pipSupported) return
        val state = callViewModel.state.value
        val eligible = com.messenger.app.ui.call.CallPipPolicy.canEnter(state, true, deviceLocked)
        val params = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(3, 4))
        if (Build.VERSION.SDK_INT >= 31) params.setAutoEnterEnabled(eligible).setSeamlessResizeEnabled(true)
        // A device/user policy may disable PiP even when the feature is present.
        runCatching { setPictureInPictureParams(params.build()) }
        if (isInPictureInPictureMode && !com.messenger.app.ui.call.CallPipPolicy.canRemain(state, true, deviceLocked)) {
            // Close only the presentation. The app-scoped call controller owns teardown.
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        // A picker is part of the attachment flow, not the user leaving COCO.
        // Preserve its caller/composition until the result returns, including during calls.
        super.startActivityForResult(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION), requestCode, options)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        appLockController.onUserLeave()
        refreshDeviceLock()
        if (!com.messenger.app.ui.call.CallPipPolicy.canEnter(callViewModel.state.value, pipSupported, deviceLocked)) return
        pipVisible = true // Conceal navigation before the system starts shrinking the window.
        if (Build.VERSION.SDK_INT < 31 && !isInPictureInPictureMode) {
            val entered = runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(3, 4)).build()) }.getOrDefault(false)
            if (!entered) pipVisible = false
        }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, configuration: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, configuration)
        pipVisible = inPip
        if (!inPip) pipRestoreGeneration++
    }

    private val themePreference by lazy { ThemePreference(this) }
    private val languagePreference by lazy { LanguagePreference(this) }
    private val navigationPreference by lazy { NavigationPreference(this) }
    private var themeMode by mutableStateOf(ThemeMode.DARK)
    private var showSavedMessages by mutableStateOf(true)
    private var showCallsTab by mutableStateOf(true)
    private var showTabLabels by mutableStateOf(true)
    private var deviceLocked by mutableStateOf(true)
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(android.os.PowerManager::class.java) }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Screen-off closes private content immediately, including during the
            // system's grace period before credentials are required again.
            if (intent?.action == Intent.ACTION_SCREEN_OFF) deviceLocked = true else refreshDeviceLock()
        }
    }

    private fun refreshDeviceLock() {
        deviceLocked = !powerManager.isInteractive || keyguard.isKeyguardLocked || keyguard.isDeviceLocked
    }

    private fun unlockForPrivateContent(onUnlocked: () -> Unit) {
        com.messenger.app.security.PrivateContentAccess(
            isLocked = { refreshDeviceLock(); deviceLocked },
            requestSystemUnlock = { success ->
                keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        if (!isFinishing && !isDestroyed) success()
                    }
                })
            },
        ).open { appLockController.syncOwner(); if (appLockController.state.value.locked) appLockController.unlock(onUnlocked) else onUnlocked() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        refreshDeviceLock()
    }

    override fun onDestroy() {
        appLockController.destroy()
        (application as MessengerApp).messageReadVisibility.release(this)
        ringingWindowTimer?.cancel()
        if (ownsRingingKeepScreen) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        unregisterReceiver(lockReceiver)
        super.onDestroy()
    }

    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModelFactory((application as MessengerApp).authRepository)
    }

    private val accountViewModelStores: com.messenger.app.ui.auth.AccountViewModelStores by viewModels()

    private val callViewModel: CallViewModel by viewModels {
        CallViewModelFactory((application as MessengerApp).callController)
    }

    private var notificationChatId by mutableStateOf<String?>(null)
    private var notificationOwnerId: String? = null
    private var notificationMessageId by mutableStateOf<String?>(null)
    private var notificationAcceptId by mutableStateOf<String?>(null)
    private var notificationOpenCallId by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        val locale = LanguagePreference(newBase).current()
        super.attachBaseContext(newBase.applyLocale(locale))
    }

    override fun onStart() {
        appLockController.onForeground(); super.onStart()
    }
    override fun onStop() {
        appLockController.onBackground(); super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode && pipVisible) {
            pipVisible = false
            pipRestoreGeneration++
        }
        refreshDeviceLock()
        // A preference, not a forced display mode: keep the current resolution
        // and let Android enforce power-saving and thermal limits.
        @Suppress("DEPRECATION")
        val activeDisplay = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        val mode = activeDisplay?.mode
        val powerSaving = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
        window.attributes = window.attributes.apply {
            preferredRefreshRate = if (powerSaving || mode == null) 0f else activeDisplay.supportedModes
                .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
                .maxOfOrNull { it.refreshRate } ?: 0f
        }
        (application as MessengerApp).localMessaging.resumeFromVisibleActivity()
        lifecycleScope.launch { (application as MessengerApp).privacyRepository.refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLockController.syncOwner()
        (application as MessengerApp).messageReadVisibility.bind(this) {
            !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                !deviceLocked && !pipVisible && appLockController.canReadCurrentOwner()
        }
        pipVisible = isInPictureInPictureMode
        refreshDeviceLock()
        ContextCompat.registerReceiver(this, lockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        enableEdgeToEdge()
        // Keep three-button navigation transparent too; edge-to-edge otherwise
        // adds an opaque contrast scrim on Android 10 and newer.
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        requestNotificationPermissionIfNeeded()
        themeMode = themePreference.current()
        showCallsTab = navigationPreference.showCallsTab()
        showTabLabels = navigationPreference.showTabLabels()
        showSavedMessages = navigationPreference.showSavedMessages()
        handleIncomingCallIntent(intent)
        handleConferenceIntent(intent)
        receiveSharedFiles(intent)
        setContent {
            val authState by authViewModel.authState.collectAsState()
            val app = application as MessengerApp
            appLockController.syncOwner()
            val appLockState by appLockController.state.collectAsState()
            val privateContentLocked = deviceLocked || appLockState.locked
            val accountEpoch = remember(authState) { app.authRepository.currentSessionEpoch() }
            // P1: account-scoped ViewModels are resolved from the CURRENT session at the
            // moment content is composed (see accountStoreOwner) and are never captured
            // here. A navigation destination keeps the content lambda it was built with,
            // so capturing them made the UI keep rendering the previous account's state
            // until the process was restarted.
            val navController = androidx.compose.runtime.key(accountEpoch) { rememberNavController() }
            val privateState = androidx.compose.runtime.key(accountEpoch) { rememberSaveableStateHolder() }
            var callAllowsPrivateContent by remember { mutableStateOf(false) }
            val inputFocus = androidx.compose.ui.platform.LocalFocusManager.current
            LaunchedEffect(callAllowsPrivateContent) {
                if (!callAllowsPrivateContent) {
                    inputFocus.clearFocus(force = true)
                    androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                        .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
            }
            val callState by callViewModel.state.collectAsState()
            SideEffect { updateCallPip() }
            val compactCallVisible = com.messenger.app.ui.call.CallMinimizePolicy.callId(callState) != null && callAllowsPrivateContent
            val currentEntry by navController.currentBackStackEntryAsState()
            LaunchedEffect(currentEntry?.destination?.route) {
                app.performanceDiagnostics.screen(currentEntry?.destination?.route)
            }
            var mainDestination by remember(accountEpoch) { mutableStateOf(com.messenger.app.ui.RootDestination.CHATS) }
            val shareChats by viewModel<ChatsViewModel>(
                viewModelStoreOwner = accountStoreOwner(app, accountViewModelStores),
                factory = ChatsViewModelFactory(app.chatRepository, app.messageRepository.socketState),
            ).chats.collectAsState()
            if (sharedFiles.isNotEmpty() && authState is AuthSessionState.LoggedIn && callAllowsPrivateContent && !privateContentLocked) {
                val shownFiles = sharedFiles
                androidx.compose.runtime.key((authState as AuthSessionState.LoggedIn).user.id, shownFiles) {
                    com.messenger.app.ui.chats.SharedFilesDialog(shownFiles, shareChats, application as MessengerApp) { if (sharedFiles == shownFiles) sharedFiles = emptyList() }
                }
            }

            val historyAuth = (application as MessengerApp).authRepository
            val needsHistoryChoice by historyAuth.needsHistoryChoice.collectAsState()
            val historyScope = androidx.compose.runtime.rememberCoroutineScope()
            var showHistoryRestore by remember(accountEpoch) { mutableStateOf(false) }
            var historyChoiceBusy by remember(accountEpoch) { mutableStateOf(false) }
            var historyChoiceError by remember(authState) { mutableStateOf(false) }
            val chatScrollBookmarks = remember((authState as? AuthSessionState.LoggedIn)?.user?.id) {
                linkedMapOf<String, com.messenger.app.ui.chats.ChatScrollBookmark>()
            }
            LaunchedEffect(authState) {
                (application as MessengerApp).localMessaging.resumeFromVisibleActivity()
            }

            CocoTheme(mode = themeMode) {
                if (needsHistoryChoice && authState is AuthSessionState.LoggedIn && !privateContentLocked && !pipVisible && callState is com.messenger.app.domain.call.CallUiState.Idle) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {},
                        title = { androidx.compose.material3.Text(getString(R.string.history_welcome_title)) },
                        text = { androidx.compose.material3.Text(getString(if (historyChoiceError) R.string.history_choice_failed else R.string.history_welcome_detail)) },
                        confirmButton = { androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.TextButton(enabled = !historyChoiceBusy, onClick = {
                                val restoreEpoch = historyAuth.currentSessionEpoch()
                                historyChoiceBusy = true
                                historyScope.launch {
                                    try {
                                        val result = historyAuth.startWithoutRestoredHistory()
                                        if (historyAuth.currentSessionEpoch() == restoreEpoch) {
                                            historyChoiceError = result.isFailure
                                            showHistoryRestore = result.isSuccess
                                        }
                                    } finally { historyChoiceBusy = false }
                                }
                            }) { androidx.compose.material3.Text(getString(R.string.history_welcome_restore)) }
                            androidx.compose.material3.TextButton(enabled = !historyChoiceBusy, onClick = {
                                historyChoiceBusy = true
                                historyScope.launch { try { historyChoiceError = historyAuth.startWithoutRestoredHistory().isFailure } finally { historyChoiceBusy = false } }
                            }) { androidx.compose.material3.Text(getString(R.string.history_start_without)) }
                            androidx.compose.material3.TextButton(enabled = !historyChoiceBusy, onClick = {
                                historyChoiceBusy = true
                                historyScope.launch { historyAuth.logout() }
                            }) { androidx.compose.material3.Text(getString(R.string.history_sign_out)) }
                        } },
                    )
                }
                if (showHistoryRestore && authState is AuthSessionState.LoggedIn && !privateContentLocked && !pipVisible && callState is com.messenger.app.domain.call.CallUiState.Idle) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showHistoryRestore = false },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
                    ) { com.messenger.app.ui.settings.HistoryKeySettingsScreen { showHistoryRestore = false } }
                }
                val background = com.messenger.app.ui.theme.LocalCocoColors.current.background
                SideEffect { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background.toArgb())) }
                val notificationBackdrop = com.messenger.app.ui.theme.rememberGlassBackdrop()
                val conferenceCompactFlow = remember { (application as MessengerApp).conferenceController.state.map { it.active && it.minimized }.distinctUntilChanged() }
                val conferenceCompact by conferenceCompactFlow.collectAsState(initial = false)
                val conferenceExpandedFlow = remember { (application as MessengerApp).conferenceController.state.map { it.active && !it.minimized }.distinctUntilChanged() }
                val initialConferenceExpanded = remember(conferenceExpandedFlow) { (application as MessengerApp).conferenceController.state.value.let { it.active && !it.minimized } }
                val conferenceExpanded by conferenceExpandedFlow.collectAsState(initial = initialConferenceExpanded)
                val conferenceMiniVisible = conferenceCompact && authState is AuthSessionState.LoggedIn && (application as MessengerApp).conferenceController.belongsToCurrentSession() && !privateContentLocked && !pipVisible
                val callMiniVisible = compactCallVisible && authState is AuthSessionState.LoggedIn && !privateContentLocked && !pipVisible && !conferenceExpanded && !conferenceCompact
                Box(Modifier.fillMaxSize().background(background)
                    .callTouchTransitionGuard(com.messenger.app.ui.call.CallTouchPolicy.privateContentVisible(callAllowsPrivateContent, conferenceExpanded))) {
                // Wait for local session restoration before creating the graph:
                // signed-in users must never see an intermediate login screen.
                if (authState != AuthSessionState.Unknown) {
                val initialRoute = remember(accountEpoch) { if (authState is AuthSessionState.LoggedIn) ROUTE_MAIN else ROUTE_LOGIN }
                // A call may occlude keyguard; the account UI must never do so.
                // Remove private content (including dialogs and accessibility
                // nodes), rather than merely painting a translucent cover.
                if (!privateContentLocked && !pipVisible) {
                privateState.SaveableStateProvider("account-navigation:${(authState as? AuthSessionState.LoggedIn)?.user?.id.orEmpty()}") {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.messenger.app.ui.call.LocalCallIsland provides null
                ) {
                // P1: the authenticated graph and every destination content lambda must
                // belong to the CURRENT session. Otherwise a NavDestination keeps the
                // account-scoped ViewModels captured when the graph was first created and
                // the UI keeps rendering the previous account (or a logged-out error
                // state) until the process is restarted.
                androidx.compose.runtime.key(accountEpoch) {
                Column(Modifier.fillMaxSize()) {
                if (conferenceMiniVisible) com.messenger.app.conference.ConferenceReturnStripHost((application as MessengerApp).conferenceController)
                else if (callMiniVisible) com.messenger.app.ui.call.CallReturnStrip(callState) { pipRestoreGeneration++ }
                Box(Modifier.weight(1f).consumeWindowInsets(if (conferenceMiniVisible || callMiniVisible) WindowInsets.statusBars else WindowInsets(0, 0, 0, 0))) {
                NavHost(
                    navController = navController,
                    startDestination = initialRoute,
                    modifier = Modifier.fillMaxSize().glassSource(notificationBackdrop),
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    composable(ROUTE_LOGIN) {
                        LoginScreen(
                            viewModel = authViewModel,
                            onNavigateToRegister = { navController.navigate(ROUTE_REGISTER) },
                            currentTheme = themeMode,
                            onThemeChange = { mode -> themeMode = mode; themePreference.set(mode) },
                            currentLanguage = languagePreference.current()?.toLanguageTag(),
                            onLanguageChange = { locale -> languagePreference.set(locale); recreate() },
                            serverHost = com.messenger.app.data.api.ServerEndpoint.current(),
                            onOpenServer = { navController.navigate(ROUTE_SERVER) },
                        )
                    }
                    composable(ROUTE_REGISTER) {
                        RegisterScreen(
                            viewModel = authViewModel,
                            onNavigateToLogin = { navController.navigate(ROUTE_LOGIN) },
                            currentTheme = themeMode,
                            onThemeChange = { mode -> themeMode = mode; themePreference.set(mode) },
                            currentLanguage = languagePreference.current()?.toLanguageTag(),
                            onLanguageChange = { locale -> languagePreference.set(locale); recreate() },
                            serverHost = com.messenger.app.data.api.ServerEndpoint.current(),
                            onOpenServer = { navController.navigate(ROUTE_SERVER) },
                        )
                    }
                    composable(ROUTE_MAIN) {
                        val owner = accountStoreOwner(app, accountViewModelStores)
                        MainScreen(
                            onDestinationChanged = { mainDestination = it },
                            drawerNavigationAvailable = callAllowsPrivateContent && !conferenceExpanded && !privateContentLocked && !pipVisible && !needsHistoryChoice && !showHistoryRestore,
                            chatsViewModel = viewModel<ChatsViewModel>(owner,
                                factory = ChatsViewModelFactory(app.chatRepository, app.messageRepository.socketState)),
                            searchViewModel = viewModel<SearchViewModel>(owner,
                                factory = SearchViewModelFactory(app.profileRepository, app.chatRepository)),
                            profileViewModel = viewModel<ProfileViewModel>(owner,
                                factory = ProfileViewModelFactory(app.profileRepository, app.avatarRepository, app.authRepository, app.messageRepository.socketState)),
                            showSavedMessages = showSavedMessages,
                            onShowSavedMessagesChange = { navigationPreference.setShowSavedMessages(it); showSavedMessages = it },
                            showTabLabels = showTabLabels,
                            onShowTabLabelsChange = { navigationPreference.setShowTabLabels(it); showTabLabels = it },
                            showCallsTab = showCallsTab,
                            onShowCallsTabChange = {
                                navigationPreference.setShowCallsTab(it)
                                showCallsTab = it
                            },
                            onOpenChat = { chatId ->
                                if (navController.currentBackStackEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) {
                                    navController.navigate("$ROUTE_CHAT_DETAIL/$chatId") { launchSingleTop = true }
                                }
                            },
                            onOpenSearchMessage = { result -> navController.navigate("$ROUTE_CHAT_DETAIL/${result.chatId}?$ARG_TARGET=${result.messageId}") { launchSingleTop = true } },
                            onNavigateToEdit = { navController.navigate(ROUTE_EDIT_PROFILE) },
                            onNavigateToDevices = { navController.navigate(ROUTE_DEVICES) },
                            onNavigateToAppearance = { navController.navigate(ROUTE_APPEARANCE) },
                            onNavigateToLanguage = { navController.navigate(ROUTE_LANGUAGE) },
                            onNavigateToServer = { navController.navigate(ROUTE_SERVER) },
                            onNavigateToStorage = { navController.navigate(ROUTE_STORAGE) },
                            onLogout = authViewModel::logout,
                        )
                    }
                    composable(ROUTE_DEVICES) {
                        val owner = accountStoreOwner(app, accountViewModelStores)
                        val devicesViewModel = viewModel<DevicesViewModel>(owner,
                            factory = DevicesViewModelFactory(app.devicesRepository))
                        LaunchedEffect(owner) { devicesViewModel.refresh() }
                        DevicesScreen(
                            viewModel = devicesViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(ROUTE_EDIT_PROFILE) {
                        val owner = accountStoreOwner(app, accountViewModelStores)
                        EditProfileScreen(
                            viewModel = viewModel<ProfileViewModel>(owner,
                                factory = ProfileViewModelFactory(app.profileRepository, app.avatarRepository, app.authRepository, app.messageRepository.socketState)),
                            onSaved = { navController.popBackStack() },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(ROUTE_SEARCH_MESSAGES) {
                        val app = application as MessengerApp
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.TextButton(onClick = { navController.popBackStack() }) { androidx.compose.material3.Text(getString(com.messenger.app.R.string.back)) }
                            com.messenger.app.ui.search.SearchUsersScreen(
                                viewModel = viewModel<SearchViewModel>(factory = SearchViewModelFactory(app.profileRepository, app.chatRepository)),
                                onOpenChat = { navController.navigate("$ROUTE_CHAT_DETAIL/$it") { launchSingleTop = true } },
                                onOpenMessage = { result -> navController.navigate("$ROUTE_CHAT_DETAIL/${result.chatId}?$ARG_TARGET=${result.messageId}") { launchSingleTop = true } },
                            )
                        }
                    }
                    composable(
                        route = "$ROUTE_SEARCH_MESSAGES_CHAT/{$ARG_CHAT_ID}",
                        arguments = listOf(navArgument(ARG_CHAT_ID) { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val chatId = backStackEntry.arguments?.getString(ARG_CHAT_ID).orEmpty()
                        val app = application as MessengerApp
                        val searchViewModel = viewModel<MessageSearchViewModel>(
                            factory = MessageSearchViewModelFactory(app.messageSearchRepository, chatId),
                        )
                        MessageSearchScreen(
                            viewModel = searchViewModel,
                            onBack = { navController.popBackStack() },
                            onOpenResult = { result ->
                                navController.navigate("$ROUTE_CHAT_DETAIL/${result.chatId}?$ARG_TARGET=${result.messageId}") {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    composable(
                        route = "$ROUTE_CHAT_DETAIL/{$ARG_CHAT_ID}?$ARG_TARGET={$ARG_TARGET}",
                        arguments = listOf(
                            navArgument(ARG_CHAT_ID) { type = NavType.StringType },
                            navArgument(ARG_TARGET) { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { backStackEntry ->
                        val chatId = backStackEntry.arguments?.getString(ARG_CHAT_ID).orEmpty()
                        val app = application as MessengerApp
                        val chatListViewModel = viewModel<ChatsViewModel>(
                            viewModelStoreOwner = accountStoreOwner(app, accountViewModelStores),
                            factory = ChatsViewModelFactory(app.chatRepository, app.messageRepository.socketState),
                        )
                        val initialChat = remember(chatListViewModel, chatId) {
                            chatListViewModel.chats.value.firstOrNull { it.chatId == chatId }
                        }
                        val chatDetailViewModel = viewModel<ChatDetailViewModel>(
                            factory = ChatDetailViewModelFactory(
                                app.chatRepository,
                                app.messageRepository,
                                app.authRepository,
                                app.attachmentManager,
                                app.voicePlayerController,
                                chatId,
                                initialChat = initialChat,
                            ),
                        )
                        ChatScreen(
                            scrollBookmarks = chatScrollBookmarks,
                            readReceiptsAllowed = callAllowsPrivateContent,
                            viewModel = chatDetailViewModel,
                            onBack = { navController.popBackStack() },
                            onStartCall = { otherUserId, otherDisplayName, mediaKind ->
                                callViewModel.startCall(otherUserId, otherDisplayName, mediaKind)
                            },
                            onVerifyIdentity = { otherUserId ->
                                navController.navigate("$ROUTE_VERIFY/$otherUserId")
                            },
                            onSearchChat = { otherUserId ->
                                navController.navigate("$ROUTE_SEARCH_MESSAGES_CHAT/$chatId")
                            },
                            onOpenMedia = { navController.navigate("$ROUTE_MEDIA/$chatId") },
                            targetMessageId = backStackEntry.arguments?.getString(ARG_TARGET).takeIf { !it.isNullOrEmpty() },
                        )
                    }
                    composable(
                        route = "$ROUTE_MEDIA/{$ARG_CHAT_ID}",
                        arguments = listOf(navArgument(ARG_CHAT_ID) { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val chatId = backStackEntry.arguments?.getString(ARG_CHAT_ID).orEmpty()
                        val app = application as MessengerApp
                        val mediaViewModel = viewModel<MediaGalleryViewModel>(
                            factory = MediaGalleryViewModelFactory(
                                chatId,
                                app.mediaGalleryRepository,
                                app.attachmentManager,
                                app.voicePlayerController,
                            ),
                        )
                        MediaGalleryScreen(
                            viewModel = mediaViewModel,
                            onBack = { navController.popBackStack() },
                            onViewInChat = { clientMessageId ->
                                navController.navigate("$ROUTE_CHAT_DETAIL/$chatId?$ARG_TARGET=$clientMessageId")
                            },
                        )
                    }
                    composable(
                        route = "$ROUTE_VERIFY/{$ARG_OTHER_USER_ID}",
                        arguments = listOf(navArgument(ARG_OTHER_USER_ID) { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val otherUserId = backStackEntry.arguments?.getString(ARG_OTHER_USER_ID).orEmpty()
                        val app = application as MessengerApp
                        val verificationViewModel = viewModel<VerificationViewModel>(
                            factory = VerificationViewModelFactory(app.identityVerification, otherUserId),
                        )
                        VerificationScreen(
                            viewModel = verificationViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(ROUTE_APPEARANCE) {
                        AppearanceScreen(
                            current = themeMode,
                            onSelect = { mode -> themeMode = mode; themePreference.set(mode) },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(ROUTE_LANGUAGE) {
                        LanguageScreen(
                            currentTag = languagePreference.current()?.toLanguageTag(),
                            onSelect = { locale -> languagePreference.set(locale); recreate() },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(ROUTE_SERVER) {
                        ServerPickerScreen(
                            onBack = { navController.popBackStack() },
                            baseUrl = com.messenger.app.data.api.ServerEndpoint.current(),
                        )
                    }
                    composable(ROUTE_STORAGE) {
                        val app = application as MessengerApp
                        val storageViewModel = viewModel<StorageViewModel>(
                            factory = StorageViewModelFactory(app.mediaCacheManager),
                        )
                        StorageScreen(
                            viewModel = storageViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                } // remaining navigation viewport
                } // intrinsic conference return strip + navigation
                } // key(accountEpoch): session-scoped navigation graph

                }
                LaunchedEffect(authState, notificationChatId) {
                    val chatId = notificationChatId
                    val loggedIn = authState as? AuthSessionState.LoggedIn
                    if (loggedIn != null && chatId != null) {
                        if (com.messenger.app.push.MessagePushHandlerImpl.isForCurrentUser(notificationOwnerId, loggedIn.user.id) && notificationOwnerId != null) {
                            navController.navigate("$ROUTE_CHAT_DETAIL/$chatId" + (notificationMessageId?.let { "?$ARG_TARGET=$it" } ?: "")) { launchSingleTop = true }
                        }
                        notificationChatId = null; notificationMessageId = null; notificationOwnerId = null
                    }
                }
                }
                }
                NavigateOnAuthChange(authState, navController)
                }
                // Runtime Correction D: compact foreground message banner for
                // messages arriving from a DIFFERENT chat. Top overlay only -
                // never pushes the app layout. Suppressed during an expanded
                // call by the controller's policy.
                val app = application as MessengerApp
                val messageBanner by app.foregroundMessageBannerController.banner.collectAsState()
                val trackedChat by ActiveChatTracker.activeChatId.collectAsState()
                // Navigation remains authoritative while a dialog pauses the chat lifecycle.
                val routeChat = currentEntry?.takeIf { it.destination.route?.startsWith("$ROUTE_CHAT_DETAIL/") == true }
                    ?.arguments?.getString(ARG_CHAT_ID)
                val visibleChat = routeChat ?: trackedChat
                val notificationHeaderBottom by ActiveChatTracker.headerBottom.collectAsState()
                LaunchedEffect(visibleChat, messageBanner) {
                    if (visibleChat != null && visibleChat == messageBanner?.chatId) app.foregroundMessageBannerController.dismiss()
                }
                LaunchedEffect(currentEntry?.destination?.route, mainDestination) {
                    app.foregroundMessageBannerController.dismiss()
                }
                if (!privateContentLocked && !pipVisible && !(currentEntry?.destination?.route == ROUTE_MAIN && mainDestination == com.messenger.app.ui.RootDestination.CHATS)) {
                ForegroundMessageBannerHost(
                    banner = messageBanner?.takeUnless { it.chatId == visibleChat },
                    backdrop = notificationBackdrop,
                    headerBottom = notificationHeaderBottom,
                    onOpenChat = { chatId ->
                        navController.navigate("$ROUTE_CHAT_DETAIL/$chatId") { launchSingleTop = true }
                    },
                    onDismiss = { app.foregroundMessageBannerController.dismiss() },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                }
                // Shown regardless of which route is active (Этап J п.42/43) -
                // a single always-on-top overlay observing the one app-scoped
                // call controller, never per-screen call UI state.
                SideEffect { updateCallWindow(callState) }

                if (appLockState.locked && !deviceLocked && !pipVisible) com.messenger.app.security.AppLockShield(appLockController)
                CallOverlay(
                    callViewModel, notificationAcceptId,
                    notificationOpenCallId = notificationOpenCallId,
                    onNotificationOpenConsumed = { notificationOpenCallId = null },
                    deviceLocked = privateContentLocked,
                    pictureInPicture = pipVisible,
                    showCompactBar = false,
                    restoreGeneration = pipRestoreGeneration,
                    onRequestPrivateContent = ::unlockForPrivateContent,
                    onNotificationAcceptConsumed = { notificationAcceptId = null },
                    onPrivateContentVisible = {
                        callAllowsPrivateContent = it
                        app.foregroundMessageBannerController.callExpanded = !it
                    },
                )
                com.messenger.app.conference.ConferenceOverlay(app.conferenceController, privateContentLocked, showMinimizedStrip = false)
                }
            }
        }
    }

    /** Android 13+ (API 33): without a runtime POST_NOTIFICATIONS grant every
     *  [android.app.NotificationManager.notify] call is silently dropped, so
     *  BOTH message and incoming-call notifications would never appear (P0).
     *  Request on launch when not yet granted; the user can revoke/re-grant
     *  later in system settings. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!(application as MessengerApp).notificationSettings.current.enabled) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            android.util.Log.d("NotificationPermission", "POST_NOTIFICATIONS granted=$granted")
        }
    }

    /** Handles the incoming-call notification's Accept PendingIntent (Этап
     *  K.5): opens the Activity, reads call_id, and reconciles the
     *  authoritative call. The always-on-top [CallOverlay] then shows the
     *  incoming-call UI where the user taps Accept (which performs the
     *  mic/camera permission flow before ever starting media). `call_id` is
     *  only a lookup key, never an authorization token. */
    private fun handleIncomingCallIntent(intent: Intent?) {
        intent?.getStringExtra(com.messenger.app.push.MessagePushHandlerImpl.EXTRA_CHAT_ID)?.let { value ->
            if (runCatching { java.util.UUID.fromString(value) }.isSuccess) {
                notificationOwnerId = intent.getStringExtra(com.messenger.app.push.MessagePushHandlerImpl.EXTRA_OWNER_ID)
                notificationChatId = value
                notificationMessageId = intent.getStringExtra("reminder_message_id")?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }
            }
            intent.removeExtra(com.messenger.app.push.MessagePushHandlerImpl.EXTRA_CHAT_ID)
            intent.removeExtra(com.messenger.app.push.MessagePushHandlerImpl.EXTRA_OWNER_ID)
        }
        val callId = intent?.getStringExtra(com.messenger.app.call.IncomingCallNotificationManager.EXTRA_CALL_ID) ?: return
        val current = (application as MessengerApp).callController.state.value
        val currentCallId = (current as? com.messenger.app.domain.call.CallUiState.IncomingRinging)?.call?.callId
        if (com.messenger.app.call.IncomingCallPresentationPolicy.mayWakeFromIntent(currentCallId, callId,
                com.messenger.app.call.IncomingCallNotificationManager.presentationOwner(this),
                intent.getStringExtra(com.messenger.app.call.IncomingCallNotificationManager.EXTRA_PRESENTATION_OWNER))) {
            updateCallWindow(current)
        }
        notificationOpenCallId = callId
        val mediaKind = intent.getStringExtra(com.messenger.app.call.IncomingCallNotificationManager.EXTRA_MEDIA_KIND) ?: "audio"
        if (intent.getBooleanExtra("notification_accept", false) &&
            com.messenger.app.call.NotificationAcceptToken.verify(this, callId,
                intent.getStringExtra(com.messenger.app.call.NotificationAcceptToken.EXTRA))) {
            notificationAcceptId = callId
        }
        intent.removeExtra(com.messenger.app.call.NotificationAcceptToken.EXTRA)
        intent.removeExtra("notification_accept")
        intent.removeExtra(com.messenger.app.call.IncomingCallNotificationManager.EXTRA_CALL_ID)
        (application as MessengerApp).callController.showIncomingCallFromPush(callId, mediaKind)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleConferenceIntent(intent)
        receiveSharedFiles(intent)
        refreshDeviceLock()
        handleIncomingCallIntent(intent)
    }

    private fun handleConferenceIntent(intent: Intent?) {
        val controller = (application as MessengerApp).conferenceController
        if (intent?.getBooleanExtra("return_conference", false) == true) {
            controller.minimize(false)
            intent.removeExtra("return_conference")
        }
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "coco" && intent.data?.host in setOf("conference", "c")) {
            controller.offerInvite(intent.data.toString())
            // Drop the bearer capability from the Activity's retained Intent after parsing.
            intent.data = null
        }
    }
}

/** Single source of truth for account-scoped ViewModels: resolves the store for the
 *  session that is CURRENT at the moment this content is composed. Navigation
 *  destination content lambdas must resolve ViewModels through this instead of
 *  capturing them - a destination retains the lambda it was first created with, so
 *  captured ViewModels would keep rendering the previous account (P1). */
@Composable
private fun accountStoreOwner(
    app: MessengerApp,
    stores: com.messenger.app.ui.auth.AccountViewModelStores,
): androidx.lifecycle.ViewModelStoreOwner =
    stores.forSession(app.authRepository.currentSessionEpoch())

/** Redirects between the auth flow and the logged-in area whenever session state changes. */
@Composable
private fun NavigateOnAuthChange(authState: AuthSessionState, navController: NavHostController) {
    val authRoute = when (authState) {
        is AuthSessionState.LoggedIn -> ROUTE_MAIN
        AuthSessionState.LoggedOut -> ROUTE_LOGIN
        AuthSessionState.Unknown -> null
    }
    // Profile/session metadata updates must not reset the active destination.
    LaunchedEffect(authRoute, (authState as? AuthSessionState.LoggedIn)?.user?.id) {
        if (authRoute != null && navController.currentDestination != null && navController.currentDestination?.route != authRoute) {
            navController.navigate(authRoute) { popUpTo(0) }
        }
    }
}
