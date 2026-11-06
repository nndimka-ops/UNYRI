package com.messenger.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.CancellationException

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - owner-only screens: storage, audit log, settings.
//
// Storage cleanup keeps its mandatory two-step flow: preview first, then an
// explicit confirmation before anything is deleted. The audit log is cursor-paged
// and filterable, and renders machine-readable detail inside collapsed
// diagnostics instead of as primary text. Server settings only regroup the
// existing fields; the /server-info privacy contract is untouched.
// ---------------------------------------------------------------------------

@Composable
internal fun AdminStorageScreen(
    cache: AdminCache,
    busy: Boolean,
    error: AdminPresentation.Failure?,
    diagnostic: String?,
    transport: AdminTransport,
    run: (suspend () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalCocoColors.current
    var loaded by remember { mutableStateOf(cache.storage != null) }
    var plan by remember { mutableStateOf<AdminCleanupPlanDto?>(null) }
    var result by remember { mutableStateOf<AdminCleanupResultDto?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    suspend fun load() {
        cache.storage = transport.get<AdminStorageDto>("storage")
    }

    LaunchedEffect(Unit) { if (!loaded) run { load(); loaded = true } }

    AdminScrollingScreen(title = stringResource(R.string.admin_nav_storage), onBack = onBack) {
        when {
            error != null -> AdminErrorState(error, diagnostic) { run { load(); loaded = true } }
            !loaded -> AdminLoadingState()
            else -> {
                val stats = cache.storage ?: return@AdminScrollingScreen
                AdminCard {
                    Text(
                        stringResource(R.string.manage_storage),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.secondaryText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    AdminKeyValue(
                        stringResource(R.string.manage_used),
                        "${AdminPresentation.bytes(stats.usedBytes)} / ${AdminPresentation.bytes(stats.reservedBytes)}",
                    )
                    AdminKeyValue(
                        stringResource(R.string.admin_storage_budget),
                        if (stats.budgetBytes > 0) {
                            AdminPresentation.bytes(stats.budgetBytes)
                        } else {
                            stringResource(R.string.admin_storage_no_budget)
                        },
                    )
                    if (stats.budgetBytes > 0) {
                        val fraction = (stats.usedBytes.toDouble() / stats.budgetBytes.toDouble())
                            .coerceIn(0.0, 1.0).toFloat()
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = if (stats.warning) colors.danger else colors.accent,
                            trackColor = colors.surfaceVariant,
                        )
                    }
                    if (stats.warning) {
                        Text(
                            stringResource(R.string.manage_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.danger,
                        )
                    }
                    Text(
                        stringResource(R.string.manage_storage_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                    )
                }

                if (stats.users.isNotEmpty()) {
                    AdminSectionHeader(stringResource(R.string.admin_storage_top))
                    AdminCard {
                        stats.users.take(10).forEach { row ->
                            AdminKeyValue(
                                AdminTitles.account(row.userId, cache),
                                AdminPresentation.bytes(row.bytes),
                            )
                        }
                    }
                }

                AdminSectionHeader(stringResource(R.string.manage_preview))
                AdminCard {
                    Text(
                        stringResource(R.string.admin_storage_plan_ttl),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                    )
                    AdminActionRow(
                        title = stringResource(R.string.manage_preview),
                        enabled = !busy,
                        onClick = {
                            run {
                                plan = transport.postEmpty<AdminCleanupPlanDto>("storage/preview")
                                result = null
                            }
                        },
                    )
                    plan?.let { current ->
                        val count = current.items.size
                        AdminDivider()
                        Text(
                            stringResource(R.string.admin_storage_plan),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.secondaryText,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (count == 0) {
                            Text(
                                stringResource(R.string.admin_storage_plan_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.secondaryText,
                            )
                        } else {
                            Text(
                                stringResource(
                                    R.string.admin_storage_plan_item,
                                    count,
                                    AdminPresentation.bytes(current.bytes),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.primaryText,
                            )
                            current.items.take(5).forEach { item ->
                                Text(
                                    "· ${AdminPresentation.shortId(item.id)} · ${AdminTitles.time(item.createdAt)} · ${AdminPresentation.bytes(item.bytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.mutedText,
                                )
                            }
                        }
                        // Two-step flow: the deletion button never performs the
                        // preview itself, and it always asks for confirmation.
                        AdminActionRow(
                            title = stringResource(R.string.manage_cleanup),
                            destructive = true,
                            enabled = !busy && AdminCleanupPolicy.canExecute(
                                CleanupStage.Previewed, count, current.planId,
                            ),
                            onClick = { if (AdminConfirmations.forCleanup(count)) confirmDelete = true },
                        )
                    }
                    result?.let {
                        AdminDivider()
                        Text(
                            stringResource(R.string.manage_result) +
                                ": ${it.deleted} / ${it.skipped} / ${it.failed}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.primaryText,
                        )
                    }
                    AdminActionRow(
                        title = stringResource(R.string.admin_refresh),
                        enabled = !busy,
                        onClick = { run { plan = null; result = null; load() } },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AdminConfirmDialog(
            title = stringResource(R.string.admin_storage_confirm_title),
            body = stringResource(R.string.admin_storage_confirm_body),
            confirmLabel = stringResource(R.string.manage_cleanup),
            destructive = true,
            enabled = true,
            busy = busy,
            onConfirm = {
                val current = plan
                if (current != null) {
                    run {
                        result = transport.post<AdminCleanupBody, AdminCleanupResultDto>(
                            "storage/cleanup",
                            AdminCleanupBody(current.planId),
                        )
                        plan = null
                        confirmDelete = false
                        load()
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
internal fun AdminAuditScreen(
    cache: AdminCache,
    busy: Boolean,
    transport: AdminTransport,
    run: (suspend () -> Unit) -> Unit,
    onUnauthorized: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalCocoColors.current
    var action by remember { mutableStateOf("") }
    var actor by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var requestToken by remember { mutableStateOf(0) }

    val query = remember(action, actor, target, from, to) {
        AdminPaging.query(
            "action" to action.takeIf { it.isNotBlank() },
            "actor" to actor.takeIf { it.isNotBlank() },
            "target" to target.takeIf { it.isNotBlank() },
            "from" to from.takeIf { it.isNotBlank() },
            "to" to to.takeIf { it.isNotBlank() },
        )
    }

    suspend fun loadPage(reset: Boolean) {
        val token = requestToken + 1
        requestToken = token
        cache.auditState = if (reset) AdminLoadState.Loading else AdminLoadState.LoadingMore
        cache.auditFailed = null
        try {
            val dto = transport.get<AdminPageDto<AdminAuditDto>>(
                AdminPaging.path("audit", query, if (reset) null else cache.audit.nextCursor),
            )
            if (token != requestToken) return
            val page = AdminPage(dto.items, dto.nextCursor, dto.hasMore)
            cache.audit = if (reset) page else AdminPaging.append(cache.audit, page) { it.id }
            cache.auditState = AdminLoadState.Ready
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (token != requestToken) return
            cache.auditState = AdminLoadState.Ready
            cache.auditFailed = failure.failureKind()
            if ((failure as? AdminHttpFailure)?.status == 401) onUnauthorized()
        }
    }

    LaunchedEffect(query) {
        cache.audit = AdminPage()
        run { loadPage(true) }
    }

    AdminListScreen(title = stringResource(R.string.admin_nav_audit), onBack = onBack) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = CocoSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(CocoSpacing.sm),
            ) {
                OutlinedTextField(
                    value = action, onValueChange = { action = it.take(100) },
                    modifier = Modifier.width(180.dp), singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.admin_filter_action)) },
                )
                OutlinedTextField(
                    value = actor, onValueChange = { actor = it.take(36) },
                    modifier = Modifier.width(200.dp), singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.admin_filter_actor)) },
                )
                OutlinedTextField(
                    value = target, onValueChange = { target = it.take(36) },
                    modifier = Modifier.width(200.dp), singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.admin_filter_target)) },
                )
                OutlinedTextField(
                    value = from, onValueChange = { from = it.take(30) },
                    modifier = Modifier.width(180.dp), singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.admin_filter_from)) },
                )
                OutlinedTextField(
                    value = to, onValueChange = { to = it.take(30) },
                    modifier = Modifier.width(180.dp), singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.admin_filter_to)) },
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = CocoSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(CocoSpacing.sm),
            ) {
                FilterChip(
                    selected = false,
                    onClick = { action = ""; actor = ""; target = ""; from = ""; to = "" },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.admin_filter_clear)) },
                )
            }
            when {
                cache.auditFailed != null && cache.audit.rows.isEmpty() ->
                    AdminErrorState(cache.auditFailed!!, null) { run { loadPage(true) } }

                cache.auditState == AdminLoadState.Loading && cache.audit.rows.isEmpty() ->
                    AdminLoadingState()

                cache.audit.rows.isEmpty() -> AdminEmptyState(stringResource(R.string.admin_audit_empty))

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = CocoSpacing.lg, end = CocoSpacing.lg,
                        top = CocoSpacing.sm, bottom = CocoSpacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(CocoSpacing.md),
                ) {
                    items(cache.audit.rows, key = { it.id }) { row ->
                        val id = row.id
                        val actionLabel = AdminPresentation.auditAction(row.action)
                        AdminCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    actionLabel?.let { stringResource(it) }
                                        ?: stringResource(R.string.admin_event_other),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.primaryText,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    AdminTitles.time(row.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.veryMutedText,
                                )
                            }
                            AdminKeyValue(
                                stringResource(R.string.admin_audit_actor),
                                AdminTitles.account(row.actor, cache),
                            )
                            row.target?.let {
                                AdminKeyValue(
                                    stringResource(R.string.admin_audit_target),
                                    AdminPresentation.shortId(it),
                                    monospace = true,
                                )
                            }
                            row.reason?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                            }
                            AdminActionRow(
                                title = stringResource(R.string.admin_audit_details),
                                onClick = { expanded = if (id in expanded) expanded - id else expanded + id },
                            )
                            if (id in expanded) {
                                // Structured, non-secret context - never as the
                                // primary label, and never a raw JSON blob.
                                AdminKeyValue(
                                    stringResource(R.string.admin_technical_detail),
                                    buildString {
                                        appendLine("action=${row.action}")
                                        appendLine("actor=${row.actor}")
                                        appendLine("target=${row.target}")
                                        row.detail.reportId?.let { appendLine("report_id=$it") }
                                        row.detail.feedbackId?.let { appendLine("feedback_id=$it") }
                                        row.detail.installationId?.let { appendLine("installation_id=$it") }
                                        row.detail.noteId?.let { appendLine("note_id=$it") }
                                        row.detail.status?.let { appendLine("status=$it") }
                                    }.trim(),
                                    monospace = true,
                                )
                            }
                        }
                    }
                    item(key = "footer") {
                        AdminListFooter(
                            state = cache.auditState,
                            hasMore = cache.audit.hasMore,
                            failed = cache.auditFailed,
                            rowCount = cache.audit.rows.size,
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
internal fun AdminServerSettingsScreen(
    cache: AdminCache,
    busy: Boolean,
    error: AdminPresentation.Failure?,
    diagnostic: String?,
    transport: AdminTransport,
    run: (suspend () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalCocoColors.current
    var loaded by remember { mutableStateOf(cache.policy != null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var saved by remember { mutableStateOf(false) }

    suspend fun load() {
        val data = transport.get<AdminPolicyDto>("server-policy")
        cache.policy = data
        values = mapOf(
            "name" to data.name,
            "operator" to data.operator,
            "contact" to data.contact,
            "rules" to data.rules,
            "retention" to data.retention,
            "max_file_bytes" to data.maxFileBytes.toString(),
            "user_quota_bytes" to data.userQuotaBytes.toString(),
            "storage_budget_bytes" to data.storageBudgetBytes.toString(),
        )
    }

    LaunchedEffect(Unit) { if (!loaded) run { load(); loaded = true } }

    AdminScrollingScreen(title = stringResource(R.string.admin_nav_settings), onBack = onBack) {
        when {
            !loaded && error == null -> AdminLoadingState()
            values.isEmpty() && error != null -> AdminErrorState(error, diagnostic) { run { load() } }
            values.isEmpty() -> AdminEmptyState(stringResource(R.string.admin_section_empty))
            else -> {
                @Composable
                fun field(key: String, label: Int, multiline: Boolean = false) {
                    OutlinedTextField(
                        value = values[key].orEmpty(),
                        onValueChange = { text -> values = values + (key to text.take(if (multiline) 8000 else 200)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(label)) },
                        enabled = !busy,
                        minLines = if (multiline) 3 else 1,
                    )
                }

                AdminSectionHeader(
                    stringResource(R.string.admin_settings_card),
                    stringResource(R.string.admin_settings_card_hint),
                )
                AdminCard {
                    field("name", R.string.manage_name)
                    field("operator", R.string.manage_operator)
                    field("contact", R.string.manage_contact)
                    field("rules", R.string.manage_rules, multiline = true)
                    field("retention", R.string.manage_retention, multiline = true)
                    Text(
                        stringResource(R.string.manage_claim),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning,
                    )
                }

                AdminSectionHeader(stringResource(R.string.admin_settings_limits))
                AdminCard { field("max_file_bytes", R.string.manage_file) }

                AdminSectionHeader(stringResource(R.string.admin_settings_storage))
                AdminCard {
                    field("user_quota_bytes", R.string.manage_quota)
                    field("storage_budget_bytes", R.string.manage_budget)
                }

                AdminCard {
                    AdminActionRow(
                        title = stringResource(R.string.manage_save),
                        enabled = !busy && validPolicy(values),
                        onClick = {
                            run {
                                transport.post<AdminPolicyDto, AdminOkDto>(
                                    "server-policy",
                                    AdminPolicyDto(
                                        name = values.getValue("name").trim(),
                                        operator = values.getValue("operator").trim(),
                                        contact = values.getValue("contact").trim(),
                                        rules = values.getValue("rules").trim(),
                                        retention = values.getValue("retention").trim(),
                                        maxFileBytes = values.getValue("max_file_bytes").trim().toLongOrNull() ?: 0,
                                        userQuotaBytes = values.getValue("user_quota_bytes").trim().toLongOrNull() ?: 0,
                                        storageBudgetBytes = values.getValue("storage_budget_bytes").trim().toLongOrNull() ?: 0,
                                    ),
                                )
                                saved = true
                                load()
                            }
                        },
                    )
                    if (!validPolicy(values)) {
                        Text(
                            stringResource(R.string.admin_settings_invalid),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.warning,
                        )
                    }
                    if (saved) {
                        Text(
                            stringResource(R.string.admin_settings_saved),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.success,
                        )
                    }
                }
            }
        }
    }
}

/** Same bounds the server validates, checked before the request is sent. */
internal fun validPolicy(values: Map<String, String>): Boolean {
    if (values["name"].orEmpty().isBlank()) return false
    val file = values["max_file_bytes"]?.trim()?.toLongOrNull() ?: return false
    if (file !in 4097L..104861696L) return false
    return listOf("user_quota_bytes", "storage_budget_bytes").all { key ->
        values[key]?.trim()?.toLongOrNull()?.let { it in 0L..1_000_000_000_000_000L } == true
    }
}
