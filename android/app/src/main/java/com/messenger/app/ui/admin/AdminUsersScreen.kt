package com.messenger.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - account search.
//
// Search runs on the server (username or display name) with a debounce, is
// cursor-paged, and can be narrowed to restricted accounts. The account itself
// is opened in the User Moderation Center.
// ---------------------------------------------------------------------------
@Composable
internal fun AdminUsersScreen(
    cache: AdminCache,
    busy: Boolean,
    transport: AdminTransport,
    run: (suspend () -> Unit) -> Unit,
    onUnauthorized: () -> Unit,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val colors = LocalCocoColors.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AdminPresentation.AccountFilter.All) }
    var searching by remember { mutableStateOf(false) }
    var requestToken by remember { mutableStateOf(0) }

    val search = remember(query, filter) {
        AdminPaging.query("query" to query.trim(), "status" to filter.status)
    }

    suspend fun loadPage(reset: Boolean) {
        val token = requestToken + 1
        requestToken = token
        cache.usersState = if (reset) AdminLoadState.Loading else AdminLoadState.LoadingMore
        cache.usersFailed = null
        searching = reset
        try {
            val dto = transport.get<AdminPageDto<AdminUserRowDto>>(
                AdminPaging.path("users", search, if (reset) null else cache.users.nextCursor),
            )
            if (token != requestToken) return
            val page = AdminPage(dto.items, dto.nextCursor, dto.hasMore)
            cache.users = if (reset) page else AdminPaging.append(cache.users, page) { it.userId }
            cache.usersState = AdminLoadState.Ready
            cache.usersSearched = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (token != requestToken) return
            cache.usersState = AdminLoadState.Ready
            cache.usersFailed = failure.failureKind()
            if ((failure as? AdminHttpFailure)?.status == 401) onUnauthorized()
        } finally {
            if (token == requestToken) searching = false
        }
    }

    LaunchedEffect(search) {
        // A blank query means "no search yet" rather than "every account".
        if (query.isBlank() && filter.status == null) {
            cache.users = AdminPage()
            cache.usersSearched = false
            cache.usersFailed = null
            return@LaunchedEffect
        }
        delay(AdminSearchDebounceMs)
        cache.users = AdminPage()
        run { loadPage(true) }
    }

    AdminListScreen(title = stringResource(R.string.admin_nav_users), onBack = onBack) {
        val failure = cache.usersFailed
        Column(Modifier.fillMaxSize().padding(horizontal = CocoSpacing.lg)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(100) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                enabled = !busy,
                label = { Text(stringResource(R.string.admin_search_display_name)) },
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(CocoSpacing.sm),
            ) {
                AdminPresentation.accountFilters.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        enabled = !busy,
                        label = { Text(stringResource(option.label)) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when {
                    failure != null && cache.users.rows.isEmpty() ->
                        AdminErrorState(failure, null) { run { loadPage(true) } }

                    AdminPresentation.searchState(
                        query = query,
                        searching = searching,
                        searched = cache.usersSearched,
                        count = cache.users.rows.size,
                    ) == AdminPresentation.SearchState.Idle ->
                        AdminEmptyState(stringResource(R.string.admin_user_idle))

                    searching && cache.users.rows.isEmpty() -> AdminLoadingState()

                    cache.users.rows.isEmpty() ->
                        AdminEmptyState(stringResource(R.string.admin_users_empty))

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = CocoSpacing.md, bottom = CocoSpacing.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(CocoSpacing.md),
                    ) {
                        items(cache.users.rows, key = { it.userId }) { row ->
                            AdminUserRow(row) { onOpenDetail(row.userId) }
                        }
                        item(key = "footer") {
                            AdminListFooter(
                                state = cache.usersState,
                                hasMore = cache.users.hasMore,
                                failed = cache.usersFailed,
                                rowCount = cache.users.rows.size,
                                enabled = !busy,
                                onLoadMore = { run { loadPage(false) } },
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.admin_search_scope_hint),
                modifier = Modifier.padding(vertical = CocoSpacing.sm),
                style = MaterialTheme.typography.bodySmall,
                color = colors.veryMutedText,
            )
        }
    }
}

@Composable
private fun AdminUserRow(row: AdminUserRowDto, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    val status = AdminPresentation.accountStatus(row.status)
    val restricted = status == AdminPresentation.AccountStatus.Suspended
    AdminCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    AdminTitles.displayName(row),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.primaryText,
                    fontWeight = FontWeight.Medium,
                )
                AdminTitles.meaningful(row.username)?.let {
                    Text("@$it", style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
                }
            }
            AdminStatusChip(
                stringResource(status.label),
                if (restricted) colors.danger else colors.success,
            )
        }
    }
}
