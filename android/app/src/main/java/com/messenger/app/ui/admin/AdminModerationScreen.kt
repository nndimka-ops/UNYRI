package com.messenger.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.CancellationException
import java.time.Instant

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - User Moderation Center.
//
// Everything an administrator needs about one account: profile, moderation
// state, real report/note counts, known devices, the account's reports, the
// moderation timeline and internal notes. Primary text is human-readable;
// installation identifiers stay inside the collapsed diagnostics block.
//
// The server stays the authority: `suspend_allowed` decides whether the account
// actions are offered at all, and a restricted device cannot be re-restricted.
// ---------------------------------------------------------------------------
@Composable
internal fun AdminModerationScreen(
    userId: String,
    cache: AdminCache,
    busy: Boolean,
    transport: AdminTransport,
    run: (suspend () -> Unit) -> Unit,
    onUnauthorized: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalCocoColors.current
    var loaded by remember(userId) { mutableStateOf(false) }
    var sheet by remember(userId) { mutableStateOf<ModerationTarget?>(null) }
    var confirmRestore by remember(userId) { mutableStateOf<ModerationTarget?>(null) }
    var noteDraft by remember(userId) { mutableStateOf("") }
    var noteSaved by remember(userId) { mutableStateOf(false) }
    var diagnostics by remember(userId) { mutableStateOf(false) }
    var requestToken by remember(userId) { mutableStateOf(0) }

    suspend fun loadDetail() {
        val token = requestToken + 1
        requestToken = token
        cache.moderationState = AdminLoadState.Loading
        cache.moderationFailed = null
        try {
            val detail = transport.get<AdminUserDetailDto>("users/$userId")
            if (token != requestToken) return
            cache.moderation = detail
            cache.moderationState = AdminLoadState.Ready
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (token != requestToken) return
            cache.moderationState = AdminLoadState.Failed
            cache.moderationFailed = failure.failureKind()
            if ((failure as? AdminHttpFailure)?.status == 401) onUnauthorized()
        }
    }

    suspend fun loadTimeline() {
        cache.timelineState = AdminLoadState.Loading
        cache.timeline = transport.get<AdminPageDto<AdminTimelineEventDto>>("users/$userId/timeline")
            .let { AdminPage(it.items, it.nextCursor, it.hasMore) }
        cache.timelineState = AdminLoadState.Ready
    }

    suspend fun loadNotes() {
        cache.notesState = AdminLoadState.Loading
        cache.notes = transport.get<AdminPageDto<AdminNoteDto>>("users/$userId/notes")
            .let { AdminPage(it.items, it.nextCursor, it.hasMore) }
        cache.notesState = AdminLoadState.Ready
    }

    suspend fun loadAccountReports() {
        cache.accountReportsState = AdminLoadState.Loading
        cache.accountReports =
            transport.get<AdminPageDto<AdminReportDto>>(AdminPaging.path("reports", "reported_user_id=$userId"))
                .let { AdminPage(it.items, it.nextCursor, it.hasMore) }
        cache.accountReportsState = AdminLoadState.Ready
    }

    LaunchedEffect(userId) {
        if (loaded) return@LaunchedEffect
        run {
            loadDetail()
            loadAccountReports()
            loadTimeline()
            loadNotes()
            loaded = true
        }
    }

    val detail = cache.moderation
    val failure = cache.moderationFailed

    AdminScrollingScreen(
        title = stringResource(R.string.admin_moderation_title),
        onBack = onBack,
        trailing = {
            AdminActionTrailing(
                enabled = !busy,
                onRefresh = {
                    run {
                        loadDetail()
                        loadAccountReports()
                        loadTimeline()
                        loadNotes()
                    }
                },
            )
        },
    ) {
        when {
            failure != null && detail == null -> AdminErrorState(failure, null) { run { loadDetail() } }
            detail == null -> AdminLoadingState()
            else -> {
                val restricted = AdminPresentation.accountStatus(detail.moderation.status) ==
                    AdminPresentation.AccountStatus.Suspended

                // ----- profile -------------------------------------------------
                AdminSectionHeader(stringResource(R.string.admin_moderation_profile))
                AdminCard {
                    Text(
                        AdminTitles.profileName(detail.profile),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.primaryText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        AdminTitles.accountLabel(detail.profile.userId, detail.profile.username),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(CocoSpacing.sm)) {
                        AdminStatusChip(
                            stringResource(AdminPresentation.accountStatus(detail.moderation.status).label),
                            if (restricted) colors.danger else colors.success,
                        )
                        if (detail.isAdministrator) {
                            AdminStatusChip(stringResource(R.string.admin_role_owner), colors.accentSoft)
                        }
                    }
                    AdminDivider()
                    AdminKeyValue(
                        stringResource(R.string.admin_account_created),
                        AdminTitles.time(detail.profile.createdAt),
                    )
                }

                // ----- moderation state ---------------------------------------
                AdminSectionHeader(stringResource(R.string.admin_moderation_state))
                AdminCard {
                    AdminKeyValue(
                        stringResource(R.string.admin_reports_total),
                        detail.moderation.reportsTotal.toString(),
                    )
                    AdminKeyValue(
                        stringResource(R.string.admin_reports_open),
                        detail.moderation.reportsOpen.toString(),
                    )
                    AdminKeyValue(
                        stringResource(R.string.admin_moderation_notes),
                        detail.moderation.notes.toString(),
                    )
                    if (restricted) {
                        AdminKeyValue(
                            stringResource(R.string.admin_restriction_until),
                            AdminTitles.timeOrNull(detail.moderation.until)
                                ?: stringResource(R.string.admin_restriction_forever),
                        )
                        detail.moderation.reason?.let {
                            AdminKeyValue(stringResource(R.string.admin_reason), it)
                        }
                    }
                }

                // ----- account actions ----------------------------------------
                AdminSectionHeader(stringResource(R.string.admin_moderation_actions))
                AdminCard {
                    when {
                        !detail.suspendAllowed -> Text(
                            stringResource(R.string.admin_protected_account),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.warning,
                        )

                        restricted -> AdminActionRow(
                            title = stringResource(R.string.admin_restore),
                            subtitle = stringResource(R.string.admin_reason),
                            destructive = false,
                            enabled = !busy,
                            onClick = { confirmRestore = ModerationTarget.Account },
                        )

                        else -> AdminActionRow(
                            title = stringResource(R.string.admin_suspend),
                            subtitle = stringResource(R.string.admin_reason),
                            destructive = true,
                            enabled = !busy,
                            onClick = { sheet = ModerationTarget.Account },
                        )
                    }
                }

                // ----- devices -------------------------------------------------
                AdminSectionHeader(stringResource(R.string.admin_moderation_devices))
                if (detail.devices.isEmpty()) {
                    AdminCard {
                        Text(
                            stringResource(R.string.admin_moderation_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.mutedText,
                        )
                    }
                } else {
                    detail.devices.forEach { device ->
                        val state = AdminPresentation.deviceState(device.revoked, device.restrictionActive)
                        AdminCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    AdminPresentation.deviceLabel(device.displayName, device.platform, device.osVersion),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.primaryText,
                                    fontWeight = FontWeight.Medium,
                                )
                                AdminStatusChip(
                                    stringResource(state.label),
                                    when (state) {
                                        AdminPresentation.DeviceState.Active -> colors.success
                                        AdminPresentation.DeviceState.Restricted -> colors.danger
                                        AdminPresentation.DeviceState.Revoked -> colors.mutedText
                                    },
                                )
                            }
                            AdminKeyValue(
                                stringResource(R.string.admin_device_last_seen),
                                AdminTitles.time(device.lastActiveAt),
                            )
                            device.appVersion?.let {
                                AdminKeyValue(stringResource(R.string.admin_device_app), it)
                            }
                            if (device.restrictionActive) {
                                AdminKeyValue(
                                    stringResource(R.string.admin_restriction_until),
                                    AdminTitles.timeOrNull(device.restrictionUntil)
                                        ?: stringResource(R.string.admin_restriction_forever),
                                )
                                device.restrictionReason?.let {
                                    AdminKeyValue(stringResource(R.string.admin_reason), it)
                                }
                            }
                            when {
                                device.isAdministratorDevice -> Text(
                                    stringResource(R.string.admin_protected_account),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.warning,
                                )

                                device.restrictionActive -> AdminActionRow(
                                    title = stringResource(R.string.admin_device_restore),
                                    enabled = !busy,
                                    onClick = { confirmRestore = ModerationTarget.Device(device.installationId) },
                                )

                                else -> AdminActionRow(
                                    title = stringResource(R.string.admin_device_suspend),
                                    destructive = true,
                                    enabled = !busy,
                                    onClick = { sheet = ModerationTarget.Device(device.installationId) },
                                )
                            }
                        }
                    }
                }

                // ----- reports involving this account ---------------------------
                AdminSectionHeader(stringResource(R.string.admin_moderation_reports))
                if (cache.accountReports.rows.isEmpty()) {
                    AdminCard {
                        Text(
                            if (cache.accountReportsState == AdminLoadState.Loading) {
                                stringResource(R.string.admin_loading)
                            } else {
                                stringResource(R.string.admin_moderation_none)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.mutedText,
                        )
                    }
                } else {
                    cache.accountReports.rows.forEach { report ->
                        val status = AdminPresentation.reportStatus(report.status)
                        AdminCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    report.reason ?: stringResource(R.string.admin_report_reason),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.primaryText,
                                )
                                AdminStatusChip(
                                    stringResource(status.label),
                                    if (status.closed) colors.mutedText else colors.accentSoft,
                                )
                            }
                            Text(
                                AdminTitles.time(report.createdAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.mutedText,
                            )
                        }
                    }
                }

                // ----- timeline -------------------------------------------------
                AdminSectionHeader(stringResource(R.string.admin_moderation_timeline))
                if (cache.timeline.rows.isEmpty()) {
                    AdminCard {
                        Text(
                            if (cache.timelineState == AdminLoadState.Loading) {
                                stringResource(R.string.admin_loading)
                            } else {
                                stringResource(R.string.admin_timeline_empty)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.mutedText,
                        )
                    }
                } else {
                    AdminCard {
                        cache.timeline.rows.forEach { event ->
                            val kind = AdminPresentation.timelineKind(event.kind)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(kind.label),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.primaryText,
                                )
                                Text(
                                    AdminTitles.time(event.occurredAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.veryMutedText,
                                )
                            }
                            event.reason?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                            }
                        }
                    }
                }

                // ----- internal notes -------------------------------------------
                AdminSectionHeader(stringResource(R.string.admin_moderation_notes))
                AdminCard {
                    Text(
                        stringResource(R.string.admin_note_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                    )
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it.take(2000) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        label = { Text(stringResource(R.string.admin_note_add)) },
                    )
                    AdminActionRow(
                        title = stringResource(R.string.admin_note_add),
                        enabled = !busy && AdminNotePolicy.valid(noteDraft),
                        onClick = {
                            run {
                                transport.post<AdminNoteBody, AdminNoteCreatedDto>(
                                    "users/$userId/notes",
                                    AdminNoteBody(AdminNotePolicy.payload(noteDraft)),
                                )
                                noteDraft = ""
                                noteSaved = true
                                loadNotes()
                                loadDetail()
                            }
                        },
                    )
                    if (noteSaved) {
                        Text(
                            stringResource(R.string.admin_note_saved),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.success,
                        )
                    }
                }
                if (cache.notes.rows.isEmpty()) {
                    AdminCard {
                        Text(
                            stringResource(R.string.admin_notes_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.mutedText,
                        )
                    }
                } else {
                    cache.notes.rows.forEach { note ->
                        AdminCard {
                            Text(note.body, style = MaterialTheme.typography.bodyMedium, color = colors.primaryText)
                            AdminDivider()
                            AdminKeyValue(
                                stringResource(R.string.admin_moderation_notes),
                                AdminTitles.account(note.authorUserId, cache),
                            )
                            Text(
                                AdminTitles.time(note.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.veryMutedText,
                            )
                        }
                    }
                }

                // ----- diagnostics (collapsed by default) -----------------------
                AdminSectionHeader(stringResource(R.string.admin_moderation_diagnostics))
                AdminCard {
                    AdminActionRow(
                        title = stringResource(R.string.admin_moderation_diagnostics),
                        icon = CocoIcons.Security,
                        onClick = { diagnostics = !diagnostics },
                    )
                    if (diagnostics) {
                        AdminDivider()
                        // Technical identifiers, deliberately secondary.
                        Text(
                            buildString {
                                appendLine("user_id=${detail.profile.userId}")
                                detail.devices.forEach { device ->
                                    appendLine("installation_id=${device.installationId}")
                                    appendLine("device_id=${device.id}")
                                }
                            }.trim(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.veryMutedText,
                        )
                    }
                }
            }
        }
    }

    // ----- restriction sheet ---------------------------------------------------
    sheet?.let { target ->
        val device = if (target is ModerationTarget.Device) cache.device(target.installationId) else null
        AdminSuspensionSheet(
            busy = busy,
            accountLabel = when (target) {
                ModerationTarget.Account -> AdminTitles.profileName(detail?.profile)
                is ModerationTarget.Device -> AdminPresentation.deviceLabel(
                    device?.displayName, device?.platform, device?.osVersion,
                )
            },
            allowInstallations = target == ModerationTarget.Account,
            onDismiss = { sheet = null },
            onSubmit = { reason, duration, customHours, includeInstallations ->
                run {
                    val until = AdminSuspensionPolicy.until(duration, customHours, Instant.now())
                    when (target) {
                        ModerationTarget.Account -> transport.postOk(
                            "users/$userId/suspension",
                            AdminSuspensionBody(true, reason, until, includeInstallations),
                        )

                        is ModerationTarget.Device -> transport.postOk(
                            "installations/${target.installationId}/suspension",
                            AdminDeviceSuspensionBody(true, reason, until),
                        )
                    }
                    sheet = null
                    loadDetail()
                    loadTimeline()
                }
            },
        )
    }

    confirmRestore?.let { target ->
        val label = stringResource(R.string.admin_restore)
        AdminConfirmDialog(
            title = stringResource(R.string.admin_unsuspend_confirm_title),
            body = stringResource(R.string.admin_unsuspend_confirm_body),
            confirmLabel = label,
            destructive = false,
            enabled = true,
            busy = busy,
            onConfirm = {
                run {
                    when (target) {
                        ModerationTarget.Account -> transport.postOk(
                            "users/$userId/suspension",
                            AdminSuspensionBody(false, label),
                        )

                        is ModerationTarget.Device -> transport.postOk(
                            "installations/${target.installationId}/suspension",
                            AdminDeviceSuspensionBody(false, label),
                        )
                    }
                    confirmRestore = null
                    loadDetail()
                    loadTimeline()
                }
            },
            onDismiss = { confirmRestore = null },
        )
    }
}

/** What a suspension sheet or a restore confirmation is aimed at. */
internal sealed interface ModerationTarget {
    data object Account : ModerationTarget
    data class Device(val installationId: String) : ModerationTarget
}

@Composable
private fun AdminActionTrailing(enabled: Boolean, onRefresh: () -> Unit) {
    val colors = LocalCocoColors.current
    androidx.compose.material3.TextButton(enabled = enabled, onClick = onRefresh) {
        Text(stringResource(R.string.admin_refresh), color = colors.primaryText)
    }
}

/**
 * Restriction form shared by accounts and devices: a duration preset, an optional
 * custom hour count, a mandatory reason and an explicit confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminSuspensionSheet(
    busy: Boolean,
    accountLabel: String,
    allowInstallations: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        reason: String,
        duration: AdminSuspensionDuration,
        customHours: Long?,
        includeInstallations: Boolean,
    ) -> Unit,
) {
    val colors = LocalCocoColors.current
    var duration by remember { mutableStateOf(AdminSuspensionDuration.OneDay) }
    var custom by remember { mutableStateOf("24") }
    var reason by remember { mutableStateOf("") }
    var installations by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    val customValid = duration != AdminSuspensionDuration.Custom ||
        AdminSuspensionPolicy.validCustomHours(custom)
    val ready = reason.isNotBlank() && customValid

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.elevated,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(CocoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(CocoSpacing.md),
        ) {
            Text(
                stringResource(R.string.admin_suspend),
                style = MaterialTheme.typography.titleMedium,
                color = colors.primaryText,
                fontWeight = FontWeight.SemiBold,
            )
            Text(accountLabel, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)

            AdminSectionHeader(stringResource(R.string.admin_suspend_duration))
            AdminSuspensionPolicy.presets.chunked(3).forEach { chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CocoSpacing.sm)) {
                    chunk.forEach { option ->
                        FilterChip(
                            selected = duration == option,
                            onClick = { duration = option },
                            enabled = !busy,
                            label = { Text(stringResource(option.label)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - chunk.size) { Box(Modifier.weight(1f)) }
                }
            }
            if (duration == AdminSuspensionDuration.Custom) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    isError = !customValid,
                    label = { Text(stringResource(R.string.admin_suspend_custom_hours)) },
                )
            }
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(2000) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text(stringResource(R.string.admin_reason)) },
            )
            if (allowInstallations) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(installations, { installations = it }, enabled = !busy)
                    Text(stringResource(R.string.admin_include_installations), Modifier.weight(1f))
                }
            }
            AdminCard {
                AdminActionRow(
                    title = stringResource(R.string.admin_suspend),
                    subtitle = stringResource(R.string.admin_reason),
                    destructive = true,
                    enabled = !busy && ready,
                    onClick = { confirm = true },
                )
            }
            androidx.compose.material3.TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(2.dp))
        }
    }

    if (confirm) {
        AdminConfirmDialog(
            title = stringResource(R.string.admin_suspend_confirm_title),
            body = stringResource(R.string.admin_suspend_confirm_body),
            confirmLabel = stringResource(R.string.admin_suspend),
            destructive = true,
            enabled = ready,
            busy = busy,
            onConfirm = {
                confirm = false
                onSubmit(
                    reason.trim(),
                    duration,
                    custom.trim().toLongOrNull(),
                    allowInstallations && installations,
                )
            },
            onDismiss = { confirm = false },
        )
    }
}
