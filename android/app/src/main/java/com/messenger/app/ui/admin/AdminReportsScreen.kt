package com.messenger.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.CancellationException

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - user reports.
//
// The status/type filters are applied by the server and the list is cursor-paged,
// so a moderator can walk the whole queue instead of the first hundred rows.
// Changing a filter starts a fresh walk; an answer that arrives after the filter
// changed is discarded rather than merged.
// ---------------------------------------------------------------------------
@Composable
internal fun AdminReportsScreen(
    cache: AdminCache,
    busy: Boolean,
    transport: AdminTransport,
    run: (suspend () -> Unit) -> Unit,
    onUnauthorized: () -> Unit,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    var filter by remember { mutableStateOf(AdminPresentation.ReportFilter.Open) }
    var reason by remember { mutableStateOf("") }
    val query = remember(filter, reason) {
        AdminPaging.query("status" to filter.status?.api, "reason" to reason.takeIf { it.isNotBlank() })
    }
    var requestToken by remember { mutableStateOf(0) }

    suspend fun loadPage(reset: Boolean) {
        val token = requestToken + 1
        requestToken = token
        cache.reportsState = if (reset) AdminLoadState.Loading else AdminLoadState.LoadingMore
        cache.reportsFailed = null
        try {
            val dto = transport.get<AdminPageDto<AdminReportDto>>(
                AdminPaging.path("reports", query, if (reset) null else cache.reports.nextCursor),
            )
            if (token != requestToken) return
            val page = AdminPage(dto.items, dto.nextCursor, dto.hasMore)
            cache.reports = if (reset) page else AdminPaging.append(cache.reports, page) { it.id }
            cache.reportsState = AdminLoadState.Ready
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (token != requestToken) return
            cache.reportsState = AdminLoadState.Ready
            cache.reportsFailed = failure.failureKind()
            if ((failure as? AdminHttpFailure)?.status == 401) onUnauthorized()
        }
    }

    // A filter change starts a new walk from the beginning: no stale cursor and
    // no rows from the previous selection may survive it.
    LaunchedEffect(query) {
        cache.reports = AdminPage()
        run { loadPage(true) }
    }

    AdminListScreen(title = stringResource(R.string.admin_nav_reports), onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = CocoSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(CocoSpacing.sm),
            ) {
                AdminPresentation.filters.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        enabled = !busy,
                        label = { Text(stringResource(option.label)) },
                    )
                }
            }
            when {
                cache.reportsFailed != null && cache.reports.rows.isEmpty() ->
                    AdminErrorState(cache.reportsFailed!!, null) { run { loadPage(true) } }

                cache.reportsState == AdminLoadState.Loading && cache.reports.rows.isEmpty() ->
                    AdminLoadingState()

                cache.reports.rows.isEmpty() ->
                    AdminEmptyState(stringResource(R.string.admin_reports_empty))

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = CocoSpacing.lg, end = CocoSpacing.lg,
                        top = CocoSpacing.sm, bottom = CocoSpacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(CocoSpacing.md),
                ) {
                    items(cache.reports.rows, key = { it.id }) { report ->
                        AdminReportRow(report, cache) { onOpenDetail(report.id) }
                    }
                    item(key = "footer") {
                        AdminListFooter(
                            state = cache.reportsState,
                            hasMore = cache.reports.hasMore,
                            failed = cache.reportsFailed,
                            rowCount = cache.reports.rows.size,
                            enabled = !busy,
                            onLoadMore = { run { loadPage(false) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminReportRow(report: AdminReportDto, cache: AdminCache, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    val status = AdminPresentation.reportStatus(report.status)
    AdminCard(Modifier.clickable(enabled = true, onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                report.reason ?: stringResource(R.string.admin_report_reason),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.primaryText,
                fontWeight = FontWeight.Medium,
            )
            AdminStatusChip(
                stringResource(status.label),
                if (status.closed) colors.mutedText else colors.accentSoft,
            )
        }
        Text(
            listOf(
                AdminTitles.account(report.userId, cache),
                AdminTitles.time(report.createdAt),
            ).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.mutedText,
        )
    }
}

@Composable
internal fun AdminReportDetailScreen(
    reportId: String,
    cache: AdminCache,
    busy: Boolean,
    error: AdminPresentation.Failure?,
    diagnostic: String?,
    transport: AdminTransport,
    run: (suspend () -> Unit) -> Unit,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val colors = LocalCocoColors.current
    var loaded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var note by remember(reportId) { mutableStateOf("") }
    var decision by remember(reportId) { mutableStateOf("") }

    suspend fun load() {
        val dto = transport.get<AdminPageDto<AdminReportDto>>(AdminPaging.path("reports"))
        cache.reports = AdminPage(dto.items, dto.nextCursor, dto.hasMore)
    }

    val report = cache.report(reportId)
    LaunchedEffect(reportId) {
        if (report == null) run { load() } else loaded = true
        note = report?.reviewNote.orEmpty()
        decision = report?.decisionReason.orEmpty()
    }

    AdminScrollingScreen(title = stringResource(R.string.admin_report_reason), onBack = onBack) {
        when {
            error != null && report == null -> AdminErrorState(error, diagnostic) { run { load() } }
            report == null && !loaded -> AdminLoadingState()
            report == null -> AdminEmptyState(stringResource(R.string.admin_section_empty))
            else -> {
                val status = AdminPresentation.reportStatus(report.status)
                AdminCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            report.reason ?: stringResource(R.string.admin_report_reason),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.primaryText,
                            fontWeight = FontWeight.SemiBold,
                        )
                        AdminStatusChip(
                            stringResource(status.label),
                            if (status.closed) colors.mutedText else colors.accentSoft,
                        )
                    }
                    report.description?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
                    }
                    AdminDivider()
                    AdminKeyValue(
                        stringResource(R.string.admin_report_target),
                        AdminTitles.account(report.userId, cache),
                    )
                    AdminKeyValue(
                        stringResource(R.string.admin_report_created),
                        AdminTitles.time(report.createdAt),
                    )
                    report.reviewedAt?.let {
                        AdminKeyValue(stringResource(R.string.admin_report_reviewed), AdminTitles.time(it))
                    }
                    report.reviewedBy?.let {
                        AdminKeyValue(
                            stringResource(R.string.admin_report_reviewed_by),
                            AdminTitles.account(it, cache),
                        )
                    }
                    // Report -> account, without a manual re-search.
                    report.userId?.let { target ->
                        AdminActionRow(
                            title = stringResource(R.string.admin_open_user),
                            icon = CocoIcons.Search,
                            enabled = !busy,
                            onClick = { onOpenUser(target) },
                        )
                    }
                }

                AdminSectionHeader(stringResource(R.string.manage_review))
                AdminCard {
                    OutlinedTextField(
                        value = note, onValueChange = { note = it.take(2000) },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy,
                        label = { Text(stringResource(R.string.manage_note)) },
                    )
                    OutlinedTextField(
                        value = decision, onValueChange = { decision = it.take(2000) },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy,
                        label = { Text(stringResource(R.string.manage_decision)) },
                    )
                    AdminActionRow(
                        title = stringResource(R.string.manage_progress),
                        destructive = false,
                        enabled = !busy && !status.reviewing,
                        onClick = {
                            run {
                                transport.postOk("reports/$reportId", AdminReviewBody("in_progress", note, decision))
                                saved = true
                                load()
                            }
                        },
                    )
                    AdminDivider()
                    AdminActionRow(
                        title = stringResource(R.string.admin_report_resolve),
                        enabled = !busy && decision.isNotBlank(),
                        onClick = { confirm = "resolved" },
                    )
                    AdminDivider()
                    AdminActionRow(
                        title = stringResource(R.string.admin_report_dismiss),
                        destructive = true,
                        enabled = !busy && decision.isNotBlank(),
                        onClick = { confirm = "dismissed" },
                    )
                    if (saved) {
                        Text(
                            stringResource(R.string.admin_saved_action),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.success,
                        )
                    }
                }
            }
        }
    }

    confirm?.let { target ->
        val resolving = target == "resolved"
        AdminConfirmDialog(
            title = stringResource(if (resolving) R.string.admin_confirm_resolve_title else R.string.admin_confirm_dismiss_title),
            body = stringResource(if (resolving) R.string.admin_confirm_resolve_body else R.string.admin_confirm_dismiss_body),
            confirmLabel = stringResource(if (resolving) R.string.admin_report_resolve else R.string.admin_report_dismiss),
            destructive = !resolving,
            enabled = decision.isNotBlank(),
            busy = busy,
            onConfirm = {
                run {
                    transport.postOk("reports/$reportId", AdminReviewBody(target, note, decision))
                    saved = true
                    confirm = null
                    load()
                }
            },
            onDismiss = { confirm = null },
        )
    }
}
