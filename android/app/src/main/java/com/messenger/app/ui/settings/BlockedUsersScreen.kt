package com.messenger.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.ui.components.UserAvatar
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoScreenSurface
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.LocalGlassBottomInset

@Composable
fun BlockedUsersScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as MessengerApp
    val auth by app.authRepository.authState.collectAsState()
    // Observing auth invalidates both the view model and remembered avatar nodes.
    val owner = if (auth is com.messenger.app.domain.model.AuthSessionState.LoggedIn) app.blockedUsersRepository.ownerKey() else null
    if (owner == null) return
    key(owner) {
        val model: BlockedUsersViewModel = viewModel(key = "blocked-users:$owner", factory = BlockedUsersViewModel.Factory(app.blockedUsersRepository))
        val state by model.state.collectAsState()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(model, lifecycle) {
            model.open()
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START && !model.state.value.loading && model.state.value.action == null) model.refresh()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer); model.close() }
        }
        fun back() {
            if (!state.actionBusy) {
                if (state.mode == BlockedUsersMode.ADD) model.mode(BlockedUsersMode.BLOCKED) else onClose()
            }
        }
        BackHandler(onBack = ::back)
        BlockedUsersContent(state, model::search, model::refresh, model::loadMore,
            onAdd = { model.mode(BlockedUsersMode.ADD) }, onBack = ::back,
            onChoose = model::choose, onConfirm = model::confirmAction, onDismiss = model::dismissAction, modifier = modifier)
    }
}

/** Pure state/callback surface also used for narrow-screen and accessibility tests. */
@Composable
internal fun BlockedUsersContent(
    state: BlockedUsersUiState,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    onChoose: (PublicUser) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCocoColors.current
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val adding = state.mode == BlockedUsersMode.ADD
    fun tap(action: () -> Unit) { CocoHaptics.tap(view); action() }
    CocoScreenSurface(modifier.fillMaxSize().testTag("blocked-users-screen"), color = colors.background) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(bottom = LocalGlassBottomInset.current)) {
            com.messenger.app.ui.components.CocoPageHeader(
                title = stringResource(if (adding) R.string.blocked_users_add_header else R.string.blocked_users_header),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                titleFontWeight = FontWeight.SemiBold,
                start = {
                    com.messenger.app.ui.components.CocoBackButton(onClick = { tap(onBack) }, enabled = !state.actionBusy,
                        modifier = Modifier.testTag("blocked-users-back"))
                },
                end = {
                    if (!adding) IconButton(onClick = { tap(onAdd) }, enabled = !state.actionBusy, modifier = Modifier.testTag("blocked-users-add")) {
                        Icon(CocoIcons.Plus, stringResource(R.string.blocked_users_add), tint = colors.categoryViolet)
                    }
                },
            )
            OutlinedTextField(value = state.query, onValueChange = onSearch, enabled = !state.actionBusy,
                singleLine = true, shape = RoundedCornerShape(18.dp),
                placeholder = { Text(stringResource(R.string.blocked_users_search)) },
                leadingIcon = { Icon(CocoIcons.Search, null) },
                trailingIcon = if (state.query.isNotEmpty()) ({
                    IconButton(onClick = { onSearch("") }, enabled = !state.actionBusy) {
                        Icon(CocoIcons.Close, stringResource(R.string.search_clear))
                    }
                }) else null,
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = colors.inputSurface,
                    unfocusedContainerColor = colors.inputSurface, focusedBorderColor = colors.outlineSelected,
                    unfocusedBorderColor = colors.outlineSubtle, focusedTextColor = colors.primaryText,
                    unfocusedTextColor = colors.primaryText, focusedLeadingIconColor = colors.categoryViolet,
                    unfocusedLeadingIconColor = colors.mutedText),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("blocked-users-search"))
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("blocked-users-list"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item("explanation") {
                    Text(stringResource(if (adding) R.string.blocked_users_add_detail else R.string.blocked_users_detail),
                        style = MaterialTheme.typography.bodySmall, color = colors.mutedText, modifier = Modifier.padding(bottom = 8.dp))
                }
                when {
                    state.loading -> item("loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.categoryViolet, modifier = Modifier.size(28.dp).testTag("blocked-users-loading"))
                        }
                    }
                    state.loadError -> item("error") {
                        BlockedUsersRetry(R.string.blocked_users_load_error, onRefresh)
                    }
                    state.users.isEmpty() -> item("empty") {
                        Surface(color = colors.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(CocoIcons.Security, null, tint = colors.categoryMint, modifier = Modifier.size(30.dp))
                                Text(stringResource(if (state.query.isNotBlank() || adding) R.string.blocked_users_no_results else R.string.blocked_users_empty),
                                    color = colors.primaryText, style = MaterialTheme.typography.titleMedium)
                                if (!adding && state.query.isBlank()) SettingsSecondaryButton(onClick = { tap(onAdd) }, modifier = Modifier.testTag("blocked-users-empty-add")) {
                                    Text(stringResource(R.string.blocked_users_add))
                                }
                            }
                        }
                    }
                }
                items(state.users, key = { it.id }) { user ->
                    Surface(color = colors.surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, colors.outlineSubtle)) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !state.actionBusy) { tap { focus.clearFocus(); onChoose(user) } }
                            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp).testTag("blocked-users-row-${user.id}"), verticalAlignment = Alignment.CenterVertically) {
                            UserAvatar(user.id, user.avatarVersion, user.hasAvatar, user.displayName.ifBlank { user.username },
                                colors.categoryViolet.copy(alpha = .14f), colors.categoryViolet, size = 44.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(user.displayName.ifBlank { user.username }, color = colors.primaryText, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (user.username.isNotBlank()) Text("@${user.username}", color = colors.mutedText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { tap { focus.clearFocus(); onChoose(user) } }, enabled = !state.actionBusy,
                                modifier = Modifier.testTag("blocked-users-action-${user.id}")) {
                                Surface(shape = CircleShape, color = (if (adding) colors.danger else colors.categoryMint).copy(alpha = .12f)) {
                                    Icon(if (adding) CocoIcons.Plus else CocoIcons.Close,
                                        stringResource(if (adding) R.string.blocked_users_block_person else R.string.blocked_users_unblock_person, user.displayName.ifBlank { user.username }),
                                        tint = if (adding) colors.danger else colors.categoryMint, modifier = Modifier.padding(8.dp).size(20.dp))
                                }
                            }
                        }
                    }
                }
                if (state.pageError) item("page-error") { BlockedUsersRetry(R.string.blocked_users_load_error, onLoadMore) }
                else if (state.canLoadMore && !state.loading) item("more") {
                    SettingsSecondaryButton(onClick = { tap(onLoadMore) }, enabled = !state.loadingMore && !state.actionBusy,
                        modifier = Modifier.fillMaxWidth().testTag("blocked-users-more")) {
                        if (state.loadingMore) CircularProgressIndicator(Modifier.size(18.dp), color = colors.mutedText, strokeWidth = 2.dp)
                        else Text(stringResource(R.string.blocked_users_more))
                    }
                }
            }
        }
    }
    state.action?.let { action ->
        val name = action.user.displayName.ifBlank { action.user.username }
        AlertDialog(onDismissRequest = { if (!state.actionBusy) onDismiss() }, containerColor = colors.elevated,
            titleContentColor = colors.primaryText, textContentColor = colors.secondaryText,
            icon = { Icon(CocoIcons.Security, null, tint = if (action.block) colors.danger else colors.categoryMint) },
            title = { Text(stringResource(if (action.block) R.string.blocked_users_confirm_block else R.string.blocked_users_confirm_unblock, name)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (action.block) R.string.blocked_users_block_detail else R.string.blocked_users_unblock_detail))
                if (state.actionError) Text(stringResource(R.string.blocked_users_action_error), color = colors.danger, modifier = Modifier.testTag("blocked-users-action-error"))
            } },
            confirmButton = {
                SettingsDialogButton(onClick = { tap(onConfirm) }, enabled = !state.actionBusy, destructive = action.block,
                    modifier = Modifier.testTag("blocked-users-confirm")) {
                    if (state.actionBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(if (action.block) R.string.blocked_users_block else R.string.blocked_users_unblock))
                }
            },
            dismissButton = {
                SettingsDialogButton(onClick = { tap(onDismiss) }, enabled = !state.actionBusy) { Text(stringResource(R.string.cancel)) }
            })
    }
}

@Composable
private fun BlockedUsersRetry(message: Int, onRetry: () -> Unit) {
    val colors = LocalCocoColors.current
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = colors.mutedText)
        SettingsSecondaryButton(onClick = { CocoHaptics.tap(view); onRetry() }) { Text(stringResource(R.string.retry)) }
    }
}
