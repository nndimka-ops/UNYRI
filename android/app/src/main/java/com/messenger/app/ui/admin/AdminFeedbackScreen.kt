package com.messenger.app.ui.admin

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.CancellationException

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - technical feedback.
//
// The detail response carries metadata and screenshot *descriptors* only; the
// bytes are fetched one at a time from the dedicated route, on demand, and the
// full-size view is a separate step. Screenshot bytes are never logged.
// ---------------------------------------------------------------------------
@Composable
internal fun AdminFeedbackScreen(
    cache: AdminCache,
    busy: Boolean,
    transport: AdminTransport,
    run: (suspend () -> Unit) -> Unit,
    onUnauthorized: () -> Unit,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    var filter by remember { mutableStateOf(AdminPresentation.StatusFilter.All) }
    var platform by remember { mutableStateOf(AdminPresentation.Platform.All) }
    val query = remember(filter, platform) {
        AdminPaging.query(
            "status" to filter.status,
            "platform" to platform.api.takeIf { it.isNotBlank() },
        )
    }
    var requestToken by remember { mutableStateOf(0) }

    suspend fun loadPage(reset: Boolean) {
        val token = requestToken + 1
        requestToken = token
        cache.feedbackState = if (reset) AdminLoadState.Loading else AdminLoadState.LoadingMore
        cache.feedbackFailed = null
        try {
            val dto = transport.get<AdminPageDto<AdminFeedbackDto>>(
                AdminPaging.path("feedback", query, if (reset) null else cache.feedback.nextCursor),
            )
            if (token != requestToken) return
            val page = AdminPage(dto.items, dto.nextCursor, dto.hasMore)
            cache.feedback = if (reset) page else AdminPaging.append(cache.feedback, page) { it.id }
            cache.feedbackState = AdminLoadState.Ready
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (token != requestToken) return
            cache.feedbackState = AdminLoadState.Ready
            cache.feedbackFailed = failure.failureKind()
            if ((failure as? AdminHttpFailure)?.status == 401) onUnauthorized()
        }
    }

    LaunchedEffect(query) {
        cache.feedback = AdminPage()
        run { loadPage(true) }
    }

    AdminListScreen(title = stringResource(R.string.admin_nav_feedback), onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = CocoSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(CocoSpacing.sm),
            ) {
                AdminPresentation.statusFilters.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        enabled = !busy,
                        label = { Text(stringResource(option.label)) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = CocoSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(CocoSpacing.sm),
            ) {
                AdminPresentation.platforms.forEach { option ->
                    FilterChip(
                        selected = platform == option,
                        onClick = { platform = option },
                        enabled = !busy,
                        label = { Text(stringResource(option.label)) },
                    )
                }
            }
            when {
                cache.feedbackFailed != null && cache.feedback.rows.isEmpty() ->
                    AdminErrorState(cache.feedbackFailed!!, null) { run { loadPage(true) } }

                cache.feedbackState == AdminLoadState.Loading && cache.feedback.rows.isEmpty() ->
                    AdminLoadingState()

                cache.feedback.rows.isEmpty() ->
                    AdminEmptyState(stringResource(R.string.admin_feedback_filtered_empty))

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = CocoSpacing.lg, end = CocoSpacing.lg,
                        top = CocoSpacing.sm, bottom = CocoSpacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(CocoSpacing.md),
                ) {
                    items(cache.feedback.rows, key = { it.id }) { row ->
                        AdminFeedbackRow(row) { onOpenDetail(row.id) }
                    }
                    item(key = "footer") {
                        AdminListFooter(
                            state = cache.feedbackState,
                            hasMore = cache.feedback.hasMore,
                            failed = cache.feedbackFailed,
                            rowCount = cache.feedback.rows.size,
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
private fun AdminFeedbackRow(row: AdminFeedbackDto, onClick: () -> Unit) {
    val colors = LocalCocoColors.current
    val status = AdminPresentation.feedbackStatus(row.status)
    AdminCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.description?.take(140) ?: stringResource(R.string.admin_section_empty),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.primaryText,
                fontWeight = FontWeight.Medium,
            )
            AdminStatusChip(
                stringResource(status.label),
                if (status == AdminPresentation.FeedbackStatus.Open) colors.accentSoft else colors.mutedText,
            )
        }
        Text(
            listOf(
                row.platform?.uppercase().orEmpty(),
                row.appVersion.orEmpty(),
                AdminTitles.time(row.createdAt),
                if (row.screenshotCount > 0) "${row.screenshotCount} × ${stringResource(R.string.admin_feedback_shot)}" else "",
            ).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.mutedText,
        )
    }
}

@Composable
internal fun AdminFeedbackDetailScreen(
    feedbackId: String,
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
    var loaded by remember(feedbackId) { mutableStateOf(false) }
    var confirm by remember(feedbackId) { mutableStateOf<String?>(null) }
    var fullscreen by remember(feedbackId) { mutableStateOf<ByteArray?>(null) }
    var note by remember(feedbackId) { mutableStateOf("") }
    var decision by remember(feedbackId) { mutableStateOf("") }

    suspend fun load() {
        val detail = transport.get<AdminFeedbackDto>("feedback/$feedbackId")
        cache.rememberFeedback(detail)
    }

    val feedback = cache.feedbackRow(feedbackId)
    LaunchedEffect(feedbackId) {
        if (feedback == null) run { load() } else loaded = true
        note = feedback?.reviewNote.orEmpty()
        decision = feedback?.decisionReason.orEmpty()
    }

    AdminScrollingScreen(title = stringResource(R.string.admin_nav_feedback), onBack = onBack) {
        when {
            error != null && feedback == null -> AdminErrorState(error, diagnostic) { run { load() } }
            feedback == null && !loaded -> AdminLoadingState()
            feedback == null -> AdminEmptyState(stringResource(R.string.admin_section_empty))
            else -> {
                val status = AdminPresentation.feedbackStatus(feedback.status)
                AdminCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            feedback.description?.take(200) ?: stringResource(R.string.admin_section_empty),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.primaryText,
                            fontWeight = FontWeight.SemiBold,
                        )
                        AdminStatusChip(
                            stringResource(status.label),
                            if (status == AdminPresentation.FeedbackStatus.Open) colors.accentSoft else colors.mutedText,
                        )
                    }
                    AdminDivider()
                    AdminKeyValue(
                        stringResource(R.string.admin_feedback_platform),
                        feedback.platform?.uppercase() ?: "—",
                    )
                    AdminKeyValue(
                        stringResource(R.string.admin_feedback_version),
                        feedback.appVersion ?: "—",
                    )
                    AdminKeyValue(
                        stringResource(R.string.admin_report_created),
                        AdminTitles.time(feedback.createdAt),
                    )
                    feedback.reviewedAt?.let {
                        AdminKeyValue(stringResource(R.string.admin_report_reviewed), AdminTitles.time(it))
                    }
                    feedback.reviewedBy?.let {
                        AdminKeyValue(
                            stringResource(R.string.admin_report_reviewed_by),
                            AdminTitles.account(it, cache),
                        )
                    }
                    feedback.userId?.let { target ->
                        AdminActionRow(
                            title = stringResource(R.string.admin_open_user),
                            icon = CocoIcons.Search,
                            enabled = !busy,
                            onClick = { onOpenUser(target) },
                        )
                    }
                }

                // ----- screenshots: descriptors only, bytes on demand ----------
                AdminSectionHeader(stringResource(R.string.admin_feedback_shots))
                if (feedback.screenshots.isEmpty()) {
                    AdminCard {
                        Text(
                            stringResource(R.string.admin_moderation_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.mutedText,
                        )
                    }
                } else {
                    feedback.screenshots.forEach { descriptor ->
                        AdminCard {
                            val bytes = cache.screenshot(feedbackId, descriptor.index)
                            when {
                                bytes != null -> Image(
                                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        .asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { fullscreen = bytes },
                                    contentScale = ContentScale.Fit,
                                )

                                cache.screenshotFailed(feedbackId, descriptor.index) -> Column {
                                    Text(
                                        stringResource(R.string.admin_screenshot_failed),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.danger,
                                    )
                                    AdminActionRow(
                                        title = stringResource(R.string.admin_retry),
                                        enabled = !busy,
                                        onClick = { run { fetchScreenshot(cache, transport, feedbackId, descriptor.index) } },
                                    )
                                }

                                else -> Column {
                                    Text(
                                        "${descriptor.mimeType} · ${AdminPresentation.bytes(descriptor.byteSize)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.mutedText,
                                    )
                                    AdminActionRow(
                                        title = stringResource(R.string.admin_screenshot_tap),
                                        enabled = !busy,
                                        onClick = { run { fetchScreenshot(cache, transport, feedbackId, descriptor.index) } },
                                    )
                                }
                            }
                        }
                    }
                }

                // ----- review ---------------------------------------------------
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
                    if (status != AdminPresentation.FeedbackStatus.Open) {
                        AdminActionRow(
                            title = stringResource(R.string.admin_feedback_reopen),
                            enabled = !busy,
                            onClick = {
                                run {
                                    transport.postOk("feedback/$feedbackId/review", AdminReviewBody("open", note, decision))
                                    load()
                                }
                            },
                        )
                        AdminDivider()
                    }
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
                }
            }
        }
    }

    fullscreen?.let { bytes ->
        Dialog(onDismissRequest = { fullscreen = null }) {
            Box(Modifier.fillMaxSize().padding(CocoSpacing.lg), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.clickable { fullscreen = null },
                    contentScale = ContentScale.Fit,
                )
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
                    transport.postOk("feedback/$feedbackId/review", AdminReviewBody(target, note, decision))
                    confirm = null
                    load()
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

/**
 * Fetches one screenshot on demand. The bytes are held in the panel cache only
 * while it is unlocked, and are never written to a log.
 */
internal suspend fun fetchScreenshot(
    cache: AdminCache,
    transport: AdminTransport,
    feedbackId: String,
    index: Int,
) {
    try {
        val bytes = transport.screenshot("feedback/$feedbackId/screenshots/$index")
        if (bytes.isEmpty()) cache.markScreenshotFailed(feedbackId, index)
        else cache.rememberScreenshot(feedbackId, index, bytes)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        cache.markScreenshotFailed(feedbackId, index)
    }
}
