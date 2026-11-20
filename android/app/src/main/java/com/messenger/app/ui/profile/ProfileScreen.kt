package com.messenger.app.ui.profile

import androidx.compose.material.icons.filled.Menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import com.messenger.app.ui.theme.CocoHaptics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.messenger.app.ui.settings.SettingsDialogButton as TextButton
import com.messenger.app.ui.theme.LocalGlassBottomInset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.messenger.app.BuildConfig
import com.messenger.app.R
import com.messenger.app.domain.model.UserProfile
import com.messenger.app.ui.components.UserAvatar
import com.messenger.app.ui.settings.AppVersion
import com.messenger.app.ui.settings.AuxiliaryCardStyle
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors

/** Approved common Settings order; accounts, server and help retain their existing actions. */
internal val settingsHomeGroups: List<Pair<Int, List<Int>>> = listOf(
    R.string.settings_group_main to listOf(
        R.string.settings_account,
        R.string.notification_settings_title,
        R.string.profile_privacy_security,
        R.string.settings_chats,
        R.string.call_settings_title,
        R.string.settings_data_storage,
    ),
    R.string.settings_group_appearance to listOf(
        R.string.profile_appearance,
        R.string.profile_language,
    ),
)

internal fun settingsCategoryTint(page: Int, colors: com.messenger.app.ui.theme.CocoColors): Color = when (page) {
    R.string.settings_account -> colors.categoryViolet
    R.string.profile_appearance, R.string.settings_data_storage -> colors.categoryBlue
    R.string.settings_chats, R.string.call_settings_title -> colors.categoryMint
    R.string.profile_language -> colors.categoryViolet
    R.string.notification_settings_title -> colors.categoryBlue
    R.string.profile_privacy_security -> colors.categoryAmber
    else -> colors.categoryRose
}

private fun saveSidebarItemVisibility(context: android.content.Context, id: String, visible: Boolean) {
    val preference = com.messenger.app.ui.theme.SidebarAppearancePreference(context)
    val settings = preference.load()
    preference.save(settings.copy(hiddenItems = if (visible) settings.hiddenItems - id else settings.hiddenItems + id))
}

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    showSavedMessages: Boolean,
    onShowSavedMessagesChange: (Boolean) -> Unit,
    showTabLabels: Boolean,
    onShowTabLabelsChange: (Boolean) -> Unit,
    showCallsTab: Boolean,
    onShowCallsTabChange: (Boolean) -> Unit,
    onNavigateToEdit: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToLanguage: () -> Unit,
    onNavigateToServer: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenMenu: () -> Unit = {},
    quickAction: String? = null,
    onQuickActionConsumed: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val publicIdentity by (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp).identityRepository.identity.collectAsState()
    // Keep the parent section alive while the editor replaces the settings body.
    var settingsPage by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Int?>(null) }
    val settingsScrollHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var settingsDetail by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var sidebarAppearance by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    com.messenger.app.ui.BlockRootDrawerWhile(sidebarAppearance)
    if (sidebarAppearance) {
        com.messenger.app.ui.settings.SidebarAppearanceSettingsScreen(showCalls = showCallsTab, showSaved = showSavedMessages,
            onShowCallsChange = onShowCallsTabChange, onShowSavedChange = onShowSavedMessagesChange) { sidebarAppearance = false }
        return
    }
    var showRecovery by remember { mutableStateOf(false) }
    if (showRecovery) com.messenger.app.ui.auth.AccountRecoveryPanel(signedIn = true, onClose = { showRecovery = false })
    var showServerCard by remember { mutableStateOf(false) }
    if (showServerCard) com.messenger.app.ui.admin.ServerCard { showServerCard = false }
    var showAdministration by remember { mutableStateOf(false) }
    var showOwnerClaim by remember { mutableStateOf(false) }
    com.messenger.app.ui.BlockRootDrawerWhile(showOwnerClaim)
    if (showOwnerClaim) {
        com.messenger.app.ui.selfhost.OwnerClaimScreen({ showOwnerClaim = false }) {
            showOwnerClaim = false
            viewModel.refresh()
            showAdministration = true
        }
        return
    }
    com.messenger.app.ui.BlockRootDrawerWhile(showRecovery ||
        (showAdministration && (uiState as? ProfileUiState.Loaded)?.profile?.adminRole in listOf("owner", "moderator")))
    if (showAdministration && (uiState as? ProfileUiState.Loaded)?.profile?.adminRole in listOf("owner", "moderator")) {
        com.messenger.app.ui.admin.AdminPanel(role = (uiState as? ProfileUiState.Loaded)?.profile?.adminRole.orEmpty(), onClose = { showAdministration = false })
        return
    }
    androidx.activity.compose.BackHandler(enabled = settingsPage != null) { settingsPage = null }
    var alphaPage by remember { mutableStateOf<String?>(null) }
    val diagnosticContext = androidx.compose.ui.platform.LocalContext.current
    val app = diagnosticContext.applicationContext as com.messenger.app.MessengerApp
    androidx.compose.runtime.LaunchedEffect(app.authRepository.currentSessionEpoch()) { app.ownerSetup.checkAvailability() }
    var showBlockedUsers by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showAccountAutoDelete by remember { mutableStateOf(false) }
    val connection by app.messageRepository.socketState.collectAsState()
    val ownerSetupState by app.ownerSetup.state.collectAsState()
    val callState by app.callController.state.collectAsState()
    val mainHapticView = LocalView.current
    var transferMode by remember { mutableStateOf<Boolean?>(null) }
    val diagnosticPrefs = androidx.compose.runtime.remember { diagnosticContext.getSharedPreferences("coco_diagnostics", android.content.Context.MODE_PRIVATE) }
    var shareDiagnostics by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(diagnosticPrefs.getBoolean("enabled", false)) }
    var liveDiagnostics by remember { mutableStateOf(diagnosticPrefs.getBoolean("performance_online", false)) }
    val activityPrefs = remember { diagnosticContext.getSharedPreferences("chat-privacy", android.content.Context.MODE_PRIVATE) }
    var liveDraftEnabled by remember { mutableStateOf(activityPrefs.getBoolean("live-draft", false)) }
    var activityEnabled by remember { mutableStateOf(activityPrefs.getBoolean("activity", false)) }
    val colors = LocalCocoColors.current
    var showLogoutDialog by remember { mutableStateOf(false) }
    var logoutOwner by remember { mutableStateOf<String?>(null) }
    val settingsAuthState by app.authRepository.authState.collectAsState()
    fun currentLogoutOwner(): String? {
        val user = (app.authRepository.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user ?: return null
        return com.messenger.app.data.api.ServerEndpoint.current().trimEnd('/') + "|" + user.id + "|" + app.authRepository.currentSessionEpoch()
    }
    val visibleLogoutOwner = currentLogoutOwner()
    androidx.compose.runtime.LaunchedEffect(settingsAuthState, visibleLogoutOwner) {
        if (showLogoutDialog && logoutOwner != currentLogoutOwner()) { showLogoutDialog = false; logoutOwner = null }
    }
    fun acceptLogoutAction(action: () -> Unit) {
        val accepted = logoutOwner != null && logoutOwner == currentLogoutOwner()
        showLogoutDialog = false; logoutOwner = null
        if (accepted) action()
    }
    fun requestLogout() {
        val owner = currentLogoutOwner()
        if (owner == null) {
            // No authenticated profile is available (offline/expired session),
            // but local account data may still be cached. Let the auth layer
            // perform its normal local cleanup instead of trapping the user.
            onLogout()
        } else {
            logoutOwner = owner
            showLogoutDialog = true
        }
    }
    androidx.compose.runtime.LaunchedEffect(quickAction) {
        when (quickAction) {
            "logout" -> requestLogout()
            "feedback" -> alphaPage = "feedback"
            "help" -> alphaPage = "help"
        }
        if (quickAction != null) onQuickActionConsumed()
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    val deleteState by viewModel.deleteState.collectAsState()
    com.messenger.app.ui.BlockRootDrawerWhile(settingsPage != null || settingsDetail != null || showBlockedUsers || showAccountAutoDelete ||
        alphaPage != null || transferMode != null || showDeleteDialog || showLogoutDialog)

    if (settingsDetail != null) {
        androidx.activity.compose.BackHandler { settingsDetail = null }
        // SettingsScaffold applies the system-bar insets itself; the root's top padding here
        // put these headers one status bar lower than every other settings screen.
        Box(Modifier.fillMaxSize()) {
            when (settingsDetail) {
                "server-knowledge" -> com.messenger.app.ui.settings.ServerKnowledgeScreen(onBack = { settingsDetail = null })
                "calls" -> com.messenger.app.ui.settings.CallSettingsScreen(onBack = { settingsDetail = null })
                "server" -> com.messenger.app.ui.settings.ServerPickerScreen(
                    onBack = { settingsDetail = null },
                    baseUrl = com.messenger.app.data.api.ServerEndpoint.current(),
                    onClaimOwner = if (com.messenger.app.data.selfhost.OwnerClaimPolicy.entryVisible(ownerSetupState)) {
                        { showOwnerClaim = true }
                    } else null,
                    onManageServer = if ((uiState as? ProfileUiState.Loaded)?.profile?.adminRole in listOf("owner", "moderator")) {
                        { showAdministration = true }
                    } else null,
                )
            }
        }
        return
    }

    if (showBlockedUsers) {
        com.messenger.app.ui.settings.BlockedUsersScreen(onClose = { showBlockedUsers = false }, modifier = modifier)
        return
    }

    if (showAccountAutoDelete) {
        com.messenger.app.ui.settings.AccountAutoDeleteSettingsScreen(onClose = { showAccountAutoDelete = false }, modifier = modifier)
        return
    }

    if (alphaPage == "feedback") {
        androidx.activity.compose.BackHandler { alphaPage = null }
        // Use the activity's measured content bounds, not a full-height Dialog.
        // MIUI clips that dialog while Compose still measures it at display height.
        Box(modifier.fillMaxSize().padding(bottom = LocalGlassBottomInset.current)) {
            com.messenger.app.ui.settings.BugReportScreen { alphaPage = null }
        }
        return
    }

    com.messenger.app.ui.theme.CocoScreenSurface(modifier = modifier.fillMaxSize(), color = colors.background) {
        settingsScrollHolder.SaveableStateProvider(settingsPage ?: 0) {
        val settingsScroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Keep the last row (including Sign out) above the system
                // navigation bar even when the scaffold's bottom bar is
                // measured after this scroll container.
                .navigationBarsPadding()
                .verticalScroll(settingsScroll)
                .padding(CocoSpacing.lg).padding(bottom = LocalGlassBottomInset.current),
        ) {
            if (settingsPage == null) SettingsHomeHeader(onOpenMenu)
            else com.messenger.app.ui.components.CocoPageHeader(
                title = stringResource(requireNotNull(settingsPage)),
                titleStyle = MaterialTheme.typography.headlineSmall,
                titleFontWeight = FontWeight.Bold,
                start = {
                    com.messenger.app.ui.components.CocoBackButton(onClick = { settingsPage = null })
                },
            )
            Spacer(modifier = Modifier.height(CocoSpacing.lg))
            if (settingsPage == R.string.notification_settings_title) {
                com.messenger.app.ui.settings.NotificationSettingsContent()
            } else when (val state = uiState) {
                ProfileUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.padding(CocoSpacing.lg))
                    OfflineSettingsFallback(connection, onNavigateToServer, ::requestLogout)
                    com.messenger.app.ui.settings.SettingsSecondaryButton(onClick = { settingsPage = R.string.notification_settings_title }) { Text(stringResource(R.string.notification_settings_title)) }
                }
                is ProfileUiState.Error -> {
                    Text(text = state.message, color = colors.danger)
                    Spacer(modifier = Modifier.height(CocoSpacing.sm))
                    com.messenger.app.ui.settings.SettingsSecondaryButton(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
                    OfflineSettingsFallback(connection, onNavigateToServer, ::requestLogout)
                    com.messenger.app.ui.settings.SettingsSecondaryButton(onClick = { settingsPage = R.string.notification_settings_title }) { Text(stringResource(R.string.notification_settings_title)) }
                }
                is ProfileUiState.Loaded -> {
                    if (state.isCached) Text(stringResource(R.string.offline_saved_data), color = colors.mutedText)
                    if (settingsPage == null) {
                        AccountCard(profile = state.profile, onEdit = { CocoHaptics.tap(mainHapticView); onNavigateToEdit() })
                        SettingsHomeAccounts(app, state.profile, onNavigateToEdit)
                        SettingsHomeCategories { page ->
                            when (page) {
                                R.string.profile_language -> onNavigateToLanguage()
                                R.string.call_settings_title -> settingsDetail = "calls"
                                else -> settingsPage = page
                            }
                        }
                        SettingsHomeGroupTitle(stringResource(R.string.settings_group_server))
                        SettingsHomeServer(connection) { settingsDetail = "server" }
                        SettingsHomeHelp { settingsPage = R.string.settings_about }
                    } else when (settingsPage) {
                        R.string.settings_about -> com.messenger.app.ui.settings.HelpApplicationContent { page -> alphaPage = page }
                        R.string.settings_account -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            IdentityCard()
                            SettingsHomeGroup {
                                SettingsRow(stringResource(R.string.profile_edit_title), CocoIcons.Pencil, colors) { onNavigateToEdit() }
                                SettingsRow(stringResource(R.string.recovery_title), CocoIcons.Security, colors) { showRecovery = true }
                                SettingsRow(stringResource(R.string.settings_group_server), CocoIcons.Security, colors) { settingsDetail = "server" }
                                SettingsRow(stringResource(R.string.manage_card), CocoIcons.Logo, colors) { showServerCard = true }
                                SettingsRow(stringResource(R.string.account_delete), CocoIcons.Delete, colors, destructive = true) {
                                    viewModel.resetDeleteState(); deletePassword = ""; showDeleteDialog = true
                                }
                            }
                        }
                        R.string.settings_chats -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SettingsSection(title = null) {
                                com.messenger.app.ui.settings.ChatFiltersSetting()
                            }
                            com.messenger.app.ui.settings.QuickReactionSettings()
                            SettingsSection(title = null) {
                                SettingsRow(stringResource(R.string.settings_home_history), com.messenger.app.ui.settings.AuxiliaryIcons.Transfer,
                                    colors, subtitle = stringResource(R.string.settings_home_history_detail), tint = colors.categoryViolet) { alphaPage = "history-and-transfer" }
                            }
                        }
                        R.string.profile_appearance -> SettingsHomeGroup {
                            SettingsRow(stringResource(R.string.appearance_editor_entry), CocoIcons.Eye, colors) { onNavigateToAppearance() }
                            SettingsRow(stringResource(R.string.sidebar_appearance_title), Icons.Default.Menu, colors,
                                subtitle = stringResource(R.string.drawer_appearance_summary), tint = colors.categoryViolet,
                                modifier = Modifier.testTag("sidebar-appearance-open")) {
                                CocoHaptics.select(mainHapticView); sidebarAppearance = true
                            }
                            SettingsToggleRow(title = stringResource(R.string.settings_show_saved_menu), subtitle = stringResource(R.string.settings_show_saved_detail), checked = showSavedMessages, onCheckedChange = {
                                saveSidebarItemVisibility(diagnosticContext, "saved", it); onShowSavedMessagesChange(it)
                            }, tint = colors.categoryAmber)
                            SettingsToggleRow(title = stringResource(R.string.settings_show_calls_menu), subtitle = stringResource(R.string.settings_show_calls_subtitle), checked = showCallsTab, onCheckedChange = {
                                saveSidebarItemVisibility(diagnosticContext, "calls", it); onShowCallsTabChange(it)
                            }, tint = colors.categoryMint)
                        }
                        R.string.profile_privacy_security -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SettingsSection(title = stringResource(R.string.privacy_devices_blocks)) {
                                SettingsRow(stringResource(R.string.profile_devices), CocoIcons.Devices, colors, tint = colors.categoryAmber) { onNavigateToDevices() }
                                SettingsRow(stringResource(R.string.blocked_users_title), CocoIcons.Security, colors, tint = colors.categoryRose,
                                    subtitle = stringResource(R.string.blocked_users_settings_detail), modifier = Modifier.testTag("blocked-users-open")) {
                                    CocoHaptics.select(mainHapticView); showBlockedUsers = true
                                }
                                com.messenger.app.security.AppLockSettings()
                            }
                            SettingsSection(title = stringResource(R.string.settings_privacy_visibility)) {
                                com.messenger.app.ui.settings.PrivacySettingsContent(showReadReceipts = false)
                            }
                            SettingsSection(title = stringResource(R.string.privacy_messages_section)) {
                                com.messenger.app.ui.settings.PrivacySettingsContent(showVisibility = false)
                                SettingsToggleRow(title = stringResource(R.string.live_draft_setting), subtitle = stringResource(R.string.live_draft_detail), checked = liveDraftEnabled, onCheckedChange = { liveDraftEnabled = it; activityPrefs.edit().putBoolean("live-draft", it).apply() }, tint = colors.categoryViolet)
                                SettingsToggleRow(title = stringResource(R.string.chat_activity_setting), subtitle = stringResource(R.string.chat_activity_detail), checked = activityEnabled, onCheckedChange = { activityEnabled = it; activityPrefs.edit().putBoolean("activity", it).apply() })
                                SettingsRow(stringResource(R.string.call_p2p_title), CocoIcons.Phone, colors, tint = colors.categoryMint) { settingsDetail = "calls" }
                            }
                            SettingsSection(title = stringResource(R.string.settings_account)) {
                                SettingsRow(stringResource(R.string.settings_recovery_key), CocoIcons.Security, colors, tint = colors.categoryAmber) { showRecovery = true }
                                SettingsRow(stringResource(R.string.auto_delete_title), CocoIcons.Delete, colors, tint = colors.categoryRose,
                                    subtitle = stringResource(R.string.auto_delete_settings_detail), modifier = Modifier.testTag("account-auto-delete-open")) {
                                    CocoHaptics.select(mainHapticView); showAccountAutoDelete = true
                                }
                                SettingsRow(stringResource(R.string.server_knowledge_title), CocoIcons.Security, colors, tint = colors.categoryAmber,
                                    modifier = Modifier.testTag("server-knowledge-open")) { settingsDetail = "server-knowledge" }
                            }
                        }
                        R.string.settings_data_storage -> SettingsHomeGroup {
                            SettingsRow(stringResource(R.string.storage_usage), CocoIcons.Storage, colors) { onNavigateToStorage() }
                            com.messenger.app.ui.settings.MediaDownloadSettings()
                        }
                    }
                    Spacer(modifier = Modifier.height(CocoSpacing.xxl))

                    if (settingsPage == null) {
                        SettingsHomeGroup {
                            SettingsHomeRow(stringResource(R.string.settings_home_sign_out), Icons.AutoMirrored.Filled.ExitToApp, colors.danger,
                                destructive = true) { requestLogout() }
                            SettingsHomeRow(stringResource(R.string.account_delete), Icons.Default.Delete, colors.danger, destructive = true) {
                                viewModel.resetDeleteState(); deletePassword = ""; showDeleteDialog = true
                            }
                        }
                        AboutRow(colors)
                    }
                    Spacer(modifier = Modifier.height(CocoSpacing.lg))
                }
            }
        }
        }
    }

    alphaPage?.let { page ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { alphaPage = if (page in listOf("terms", "privacy", "support")) "about" else null; transferMode = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            securePolicy = if (page == "history" || page == "transfer") androidx.compose.ui.window.SecureFlagPolicy.SecureOn else androidx.compose.ui.window.SecureFlagPolicy.Inherit)) {
            if (page == "history-and-transfer") com.messenger.app.ui.settings.SettingsScaffold(stringResource(R.string.settings_home_history), { alphaPage = null }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SettingsHomeHistory(onTransfer = { alphaPage = "transfer" }, onBackup = { alphaPage = "history" })
                }
            }
            else if (page == "history") com.messenger.app.ui.settings.HistoryKeySettingsScreen { alphaPage = null }
            else if (page == "transfer") {
                val session = remember { app.historySettingsSession() }
                val mode = transferMode
                if (mode != null) com.messenger.app.ui.settings.HistoryTransferScreen(session, mode,
                    callState !is com.messenger.app.domain.call.CallUiState.Idle && callState !is com.messenger.app.domain.call.CallUiState.Ended && callState !is com.messenger.app.domain.call.CallUiState.Failed,
                    onOpenBackupSettings = { transferMode = null; alphaPage = "history" },
                    onDone = { transferMode = null; alphaPage = null },
                    onReportProblem = { transferMode = null; alphaPage = "feedback" }) { transferMode = null }
                else com.messenger.app.ui.settings.HistoryTransferPreparationScreen(
                    username = publicIdentity?.let { com.messenger.app.data.identity.PeerIdentityPolicy.handle(it.username, it.number) }.orEmpty(),
                    server = android.net.Uri.parse(com.messenger.app.data.api.ServerEndpoint.current()).host.orEmpty(),
                    callBusy = callState !is com.messenger.app.domain.call.CallUiState.Idle && callState !is com.messenger.app.domain.call.CallUiState.Ended && callState !is com.messenger.app.domain.call.CallUiState.Failed,
                    onSend = { transferMode = false }, onReceive = { transferMode = true }, onClose = { alphaPage = null })
            }
            else if (page == "help") com.messenger.app.ui.settings.SettingsScaffold(stringResource(R.string.drawer_help), { alphaPage = null }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsHomeHelp(showTitle = false) { alphaPage = it }
                }
            }
            else if (page == "performance") com.messenger.app.ui.settings.SettingsScaffold(stringResource(R.string.settings_performance_diagnostics), { alphaPage = null }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.performance_online_detail), color = colors.secondaryText, style = MaterialTheme.typography.bodyMedium)
                    SettingsHomeGroup {
                        if (shareDiagnostics) SettingsRow(stringResource(R.string.performance_mark), CocoIcons.Chat, colors) { app.performanceDiagnostics.markProblem() }
                        SettingsRow(stringResource(R.string.profile_privacy_security), CocoIcons.Security, colors,
                            subtitle = stringResource(R.string.settings_diagnostics_detail), tint = colors.categoryAmber) {
                            alphaPage = null; settingsPage = R.string.profile_privacy_security
                        }
                        SettingsRow(stringResource(R.string.feedback_title), CocoIcons.Chat, colors, tint = colors.categoryRose) { alphaPage = "feedback" }
                    }
                }
            }
            else com.messenger.app.ui.settings.SettingsScaffold(if (page in listOf("terms", "privacy", "support")) com.messenger.app.ui.settings.cocoLegalTitle(page) else stringResource(R.string.settings_about_project), { alphaPage = if (page in listOf("terms", "privacy", "support")) "about" else null }) {
                if (page in listOf("terms", "privacy", "support")) com.messenger.app.ui.settings.CocoLegalContent(page)
                else com.messenger.app.ui.settings.AlphaAboutContent(onOpenLegal = { alphaPage = it })
            }
        }
    }
    if (showDeleteDialog) {
        val busy = deleteState is SaveState.Saving
        AlertDialog(
            onDismissRequest = { if (!busy) { showDeleteDialog = false; deletePassword = "" } },
            title = { Text(stringResource(R.string.account_delete)) },
            text = {
                Column {
                    Text(stringResource(R.string.account_delete_detail))
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= 1024) deletePassword = it },
                        enabled = !busy, singleLine = true,
                        label = { Text(stringResource(R.string.account_delete_password)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    )
                    if (deleteState is SaveState.Error) Text(stringResource(R.string.account_delete_error), modifier = Modifier.padding(top = 8.dp))
                    if (busy) CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp).size(24.dp))
                }
            },
            confirmButton = {
                TextButton(destructive = true, enabled = !busy && deletePassword.isNotEmpty(), onClick = {
                    viewModel.deleteAccount(deletePassword); deletePassword = ""
                }) { Text(stringResource(R.string.account_delete_confirm)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showDeleteDialog = false; deletePassword = "" }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (showLogoutDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showLogoutDialog = false; logoutOwner = null }) {
            Surface(shape = RoundedCornerShape(20.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineSecondary)) {
                Column(Modifier.fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .8f).dp)
                    .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = colors.danger, modifier = Modifier.size(44.dp)
                        .background(colors.danger.copy(alpha = .12f), RoundedCornerShape(14.dp)).padding(10.dp))
                    Text(stringResource(R.string.settings_home_sign_out), style = MaterialTheme.typography.titleLarge, color = colors.primaryText)
                    Text(stringResource(R.string.settings_logout_local_notice), style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
                    Text(stringResource(R.string.settings_logout_restore_notice), style = MaterialTheme.typography.bodyMedium, color = colors.primaryText)
                    Text(stringResource(R.string.settings_logout_key_notice), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                    com.messenger.app.ui.settings.SettingsSecondaryButton(onClick = { showLogoutDialog = false; logoutOwner = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cancel))
                    }
                    com.messenger.app.ui.settings.SettingsPrimaryButton(onClick = { acceptLogoutAction { alphaPage = "history" } }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_logout_history))
                    }
                    com.messenger.app.ui.settings.SettingsSecondaryButton(onClick = { acceptLogoutAction(onLogout) }, modifier = Modifier.fillMaxWidth(), destructive = true) {
                        Text(stringResource(R.string.settings_home_sign_out))
                    }
                }
            }
        }
    }
}

/** The public number keeps the drawer's existing raw-copy action; the rest opens editing. */
@Composable
private fun AccountCard(profile: UserProfile, onEdit: () -> Unit) {
    val colors = LocalCocoColors.current
    val identityApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val publicIdentity by identityApp.identityRepository.identity.collectAsState()
    val legacyIdentity by identityApp.identityRepository.legacy.collectAsState()
    val editLabel = stringResource(R.string.profile_edit)
    Surface(
        onClick = onEdit,
        shape = AuxiliaryCardStyle.shape,
        color = AuxiliaryCardStyle.background,
        border = AuxiliaryCardStyle.border,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = editLabel },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(CocoSpacing.lg),
        ) {
            UserAvatar(
                userId = profile.id,
                avatarVersion = profile.avatarVersion,
                hasAvatar = profile.hasAvatar,
                name = profile.displayName,
                color = colors.accent,
                contentColor = colors.onAccent,
                size = 48.dp,
            )
            Spacer(modifier = Modifier.width(CocoSpacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primaryText,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                com.messenger.app.ui.DrawerPublicNumber(publicIdentity?.number)
                val username = publicIdentity?.username
                if (!username.isNullOrBlank()) Text(
                    text = "@$username", style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(modifier = Modifier.size(32.dp).background(colors.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Edit, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Grouped glass section (Phase 6 §25): muted caption header + one glass card
 *  containing the rows. No strong glow, just translucency + outline. */
@Composable
private fun SettingsSection(title: String?, content: @Composable () -> Unit) {
    val colors = LocalCocoColors.current
    Column {
        if (title != null) Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.mutedText,
            modifier = Modifier.padding(start = CocoSpacing.xs, bottom = CocoSpacing.xs),
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineSubtle),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, colors: com.messenger.app.ui.theme.CocoColors, subtitle: String? = null, destructive: Boolean = false, tint: Color = colors.categoryBlue, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background((if (destructive) colors.danger else tint).copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = if (destructive) colors.danger else tint, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(CocoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (destructive) colors.danger else colors.primaryText)
            subtitle?.let { Text(it, modifier = Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText) }
        }
        if (!destructive) Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.mutedText,
        )
    }
}

/** Toggle row for real functional settings (Phase 8 §34): a Switch bound to a
 *  persisted preference — never a fake, non-persisted toggle. */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val colors = LocalCocoColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 14.dp, vertical = CocoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = colors.primaryText)
            subtitle?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
            }
        }
        Spacer(modifier = Modifier.width(CocoSpacing.md))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = tint ?: colors.categoryMint, checkedThumbColor = colors.onAccent))
    }
}

/** About/version row (Phase 6 §20): actual installed build metadata only. */
@Composable
private fun AboutRow(colors: com.messenger.app.ui.theme.CocoColors) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CocoSpacing.md, vertical = CocoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "UNYRI",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.primaryText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.app_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.labelMedium,
            color = colors.mutedText,
        )
    }
}

/** Main-screen surfaces are static: no blur, shader animation or background jobs. */
@Composable private fun SettingsHomeGroup(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = AuxiliaryCardStyle.shape,
        color = AuxiliaryCardStyle.background, border = AuxiliaryCardStyle.border) { Column { content() } }
}

/** The visible large title is the only root heading; no empty CocoPageHeader is composed. */
@Composable internal fun SettingsHomeHeader(onOpenMenu: () -> Unit) {
    val colors = LocalCocoColors.current
    Column(Modifier.fillMaxWidth()) {
        IconButton(onClick = onOpenMenu) {
            Icon(Icons.Filled.Menu, stringResource(R.string.navigation_menu), tint = colors.primaryText)
        }
        Text(stringResource(R.string.nav_settings), fontSize = 34.sp, fontWeight = FontWeight.Bold,
            color = colors.primaryText, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
    }
}

@Composable private fun SettingsHomeGroupTitle(title: String) {
    Text(title, fontSize = 12.sp, color = LocalCocoColors.current.secondaryText,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp).semantics { heading() })
}

/** Observe the account registry once for the group, using the drawer's isolated-workspace handoff. */
@Composable private fun SettingsHomeAccounts(app: com.messenger.app.MessengerApp, profile: UserProfile, onEdit: () -> Unit) {
    val workspaces = app.accountWorkspaces
    val revision by workspaces.changes.collectAsState()
    val accounts = remember(workspaces, revision) { workspaces.accounts() }
    val colors = LocalCocoColors.current
    SettingsHomeGroupTitle(stringResource(R.string.settings_group_accounts))
    SettingsHomeGroup {
        SettingsHomeRow(profile.displayName, com.messenger.app.ui.settings.AuxiliaryIcons.Account, colors.categoryViolet,
            subtitle = stringResource(R.string.settings_account_current), onClick = onEdit)
        accounts.filter { it.key != workspaces.activeKey }.forEach { account ->
            val switchLabel = stringResource(R.string.drawer_switch_to, account.displayName)
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
                .clickable(role = Role.Button, onClickLabel = switchLabel) { app.switchLocalAccount(account.key) }
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                com.messenger.app.ui.components.AccountAvatar(account, workspaces.avatarStore, 32.dp, colors.primaryText)
                Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                    Text(account.displayName, color = colors.primaryText, style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (account.unreadCount > 0) Text(stringResource(R.string.drawer_account_unread, account.unreadCount),
                        color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.mutedText, modifier = Modifier.size(22.dp))
            }
        }
        if (accounts.size < 2) SettingsHomeRow(stringResource(R.string.sidebar_add_account), CocoIcons.Plus,
            colors.categoryViolet) { app.addLocalAccount() }
    }
}

@Composable private fun SettingsHomeHelp(showTitle: Boolean = true, onSelect: (String) -> Unit) {
    val colors = LocalCocoColors.current
    if (showTitle) SettingsHomeGroupTitle(stringResource(R.string.settings_group_help))
    SettingsHomeGroup {
        SettingsHomeRow(stringResource(R.string.settings_about), CocoIcons.Logo, colors.categoryBlue) { onSelect("about") }
    }
}

@Composable internal fun SettingsHomeCategories(onSelect: (Int) -> Unit) {
    val colors = LocalCocoColors.current
    settingsHomeGroups.forEach { (groupTitle, pages) ->
        SettingsHomeGroupTitle(stringResource(groupTitle))
        SettingsHomeGroup {
            pages.forEachIndexed { index, page ->
                SettingsHomeRow(stringResource(page), when (page) {
                    R.string.settings_account -> com.messenger.app.ui.settings.AuxiliaryIcons.Account
                    R.string.profile_privacy_security -> com.messenger.app.ui.settings.AuxiliaryIcons.LockShield
                    R.string.notification_settings_title -> com.messenger.app.ui.settings.AuxiliaryIcons.Bell
                    R.string.profile_appearance -> com.messenger.app.ui.settings.AuxiliaryIcons.Palette
                    R.string.settings_chats -> com.messenger.app.ui.settings.AuxiliaryIcons.Chats
                    R.string.call_settings_title -> CocoIcons.Phone
                    R.string.settings_data_storage -> CocoIcons.Network
                    else -> CocoIcons.Language
                }, settingsCategoryTint(page, colors), subtitle = when (page) {
                    R.string.settings_account -> R.string.settings_account_summary
                    R.string.profile_privacy_security -> R.string.settings_privacy_summary
                    R.string.notification_settings_title -> R.string.settings_home_notifications_summary
                    R.string.profile_appearance -> R.string.settings_home_appearance_summary
                    R.string.settings_chats -> R.string.settings_home_chat_summary
                    R.string.settings_data_storage -> R.string.settings_home_connection_detail
                    else -> null
                }?.let { stringResource(it) }) { onSelect(page) }
                if (index != pages.lastIndex) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 58.dp), thickness = .5.dp, color = colors.outlineSubtle)
            }
        }
    }
}

@Composable private fun SettingsHomeRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color, subtitle: String? = null, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { CocoHaptics.tap(view); onClick() }
        .heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp).background(tint.copy(alpha = .10f), RoundedCornerShape(9.dp)).padding(6.dp))
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(title, color = if (destructive) tint else colors.primaryText, style = MaterialTheme.typography.titleSmall,
                fontWeight = if (destructive) FontWeight.SemiBold else FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = colors.secondaryText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
        }
        if (!destructive) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.mutedText, modifier = Modifier.size(22.dp))
    }
}

/** Offline/expired-profile fallback: the profile API can fail while the local
 *  workspace, cached chats and server settings still exist. Never leave the
 *  user with only a spinner/error: server switch and sign-out stay reachable. */
@Composable private fun OfflineSettingsFallback(
    connection: com.messenger.app.domain.repository.SocketConnectionState,
    onNavigateToServer: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = LocalCocoColors.current
    Spacer(Modifier.height(CocoSpacing.md))
    Text(stringResource(R.string.settings_offline_fallback), color = colors.mutedText, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(CocoSpacing.sm))
    SettingsHomeServer(connection, onNavigateToServer)
    Spacer(Modifier.height(CocoSpacing.md))
    SettingsHomeGroup {
        SettingsHomeRow(stringResource(R.string.settings_home_sign_out), Icons.AutoMirrored.Filled.ExitToApp, colors.danger, destructive = true) {
            onLogout()
        }
    }
}

@Composable private fun SettingsHomeServer(connection: com.messenger.app.domain.repository.SocketConnectionState, onClick: () -> Unit) {
    SettingsHomeServerCard(
        server = android.net.Uri.parse(com.messenger.app.data.api.ServerEndpoint.current()).authority.orEmpty(),
        status = stringResource(when (connection) {
            com.messenger.app.domain.repository.SocketConnectionState.CONNECTED -> R.string.settings_server_connected
            com.messenger.app.domain.repository.SocketConnectionState.CONNECTING -> R.string.connection_connecting
            else -> R.string.connection_offline
        }), online = connection == com.messenger.app.domain.repository.SocketConnectionState.CONNECTED, onClick = onClick)
}

/** Both text columns receive a share of the width before either translated label is measured. */
@Composable internal fun SettingsHomeServerCard(server: String, status: String, online: Boolean, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    val view = LocalView.current
    SettingsHomeGroup {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { CocoHaptics.tap(view); onClick() }.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(.4f)) {
                Text(stringResource(R.string.settings_current_server), color = colors.secondaryText, fontSize = 11.sp)
                // A generated cloud host would wrap mid-word over three lines: keep prefix + domain.
                Text(com.messenger.app.ui.drawerHostLabel(server, narrow = true), color = colors.primaryText, fontSize = 12.sp,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Row(Modifier.weight(.6f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(if (online) Color(0xFF73E6A0) else colors.mutedText, CircleShape))
                Text(status, Modifier.weight(1f), color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.mutedText, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable internal fun SettingsHomeHistory(onTransfer: () -> Unit, onBackup: () -> Unit) {
    val colors = LocalCocoColors.current
    Surface(Modifier.fillMaxWidth(), shape = AuxiliaryCardStyle.shape, color = AuxiliaryCardStyle.background,
        border = AuxiliaryCardStyle.border) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(com.messenger.app.ui.settings.AuxiliaryIcons.Transfer, null, tint = colors.categoryViolet, modifier = Modifier.padding(8.dp).size(24.dp))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.settings_home_history), style = MaterialTheme.typography.titleMedium, color = colors.primaryText)
                    Text(stringResource(R.string.settings_home_history_detail), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                }
            }
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints {
                val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                if (maxWidth < 280.dp || fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsHomeHistoryAction(stringResource(R.string.settings_home_transfer), com.messenger.app.ui.settings.AuxiliaryIcons.Transfer, colors.categoryBlue, Modifier.fillMaxWidth(), onTransfer)
                    SettingsHomeHistoryAction(stringResource(R.string.settings_home_backup), CocoIcons.Security, colors.categoryAmber, Modifier.fillMaxWidth(), onBackup)
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsHomeHistoryAction(stringResource(R.string.settings_home_transfer), com.messenger.app.ui.settings.AuxiliaryIcons.Transfer, colors.categoryBlue, Modifier.weight(1f), onTransfer)
                    SettingsHomeHistoryAction(stringResource(R.string.settings_home_backup), CocoIcons.Security, colors.categoryAmber, Modifier.weight(1f), onBackup)
                }
            }
        }
    }
}

@Composable private fun SettingsHomeHistoryAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val view = LocalView.current
    Surface(onClick = { CocoHaptics.tap(view); onClick() }, modifier = modifier, shape = RoundedCornerShape(15.dp), color = tint.copy(alpha = .09f), border = BorderStroke(1.dp, tint.copy(alpha = .35f))) {
        Row(Modifier.heightIn(min = 48.dp).padding(10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Text(title, color = tint, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
