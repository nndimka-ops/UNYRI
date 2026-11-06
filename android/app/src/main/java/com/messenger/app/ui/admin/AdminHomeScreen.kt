package com.messenger.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - dashboard.
//
// Every number comes from GET /admin/summary and is computed by the database.
// Nothing is derived from the first page of a list, and a value the server did
// not send is reported as missing rather than invented as a zero.
// ---------------------------------------------------------------------------
@Composable
internal fun AdminHomeScreen(
    role: String,
    base: String,
    serverName: String,
    serverId: String,
    cache: AdminCache,
    busy: Boolean,
    transport: AdminTransport,
    run: (suspend () -> Unit) -> Unit,
    onOpen: (AdminTarget) -> Unit,
    onOpenConsole: () -> Unit,
    consoleFailure: AdminPresentation.Failure?,
    onClose: () -> Unit,
) {
    val colors = LocalCocoColors.current
    val owner = AdminNavigation.isOwner(role)
    var loaded by remember { mutableStateOf(false) }

    suspend fun load() {
        cache.summaryState = AdminLoadState.Loading
        cache.summaryFailed = null
        try {
            cache.summary = transport.get<AdminSummaryDto>("summary")
            cache.summaryState = AdminLoadState.Ready
        } catch (failure: Exception) {
            cache.summaryState = AdminLoadState.Failed
            cache.summaryFailed = failure.failureKind()
            throw failure
        }
    }

    LaunchedEffect(Unit) { if (!loaded) run { load(); loaded = true } }

    AdminScrollingScreen(
        title = stringResource(R.string.admin_nav_home),
        onBack = null,
        trailing = {
            IconButton(onClick = onClose) {
                Icon(
                    CocoIcons.Close,
                    contentDescription = stringResource(R.string.admin_close),
                    tint = colors.primaryText,
                )
            }
        },
    ) {
        AdminCard {
            Text(
                serverName.ifBlank { base.removePrefix("https://").removePrefix("http://") },
                style = MaterialTheme.typography.titleMedium,
                color = colors.primaryText,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(CocoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AdminStatusChip(
                    stringResource(if (owner) R.string.admin_role_owner else R.string.admin_role_moderator),
                    colors.accentSoft,
                )
                AdminStatusChip(stringResource(R.string.admin_session_unlocked), colors.success)
            }
            if (serverId.isNotBlank()) {
                AdminKeyValue(
                    stringResource(R.string.admin_server_id),
                    AdminPresentation.shortId(serverId),
                    monospace = true,
                )
            }
            Text(base, style = MaterialTheme.typography.labelSmall, color = colors.veryMutedText)
            AdminDivider()
            // ADMIN CONSOLE PACK 3 - the administration product now lives on the
            // server. This device keeps the secure unlock and hands the browser a
            // single-use, one-minute handoff; nothing here duplicates a console
            // screen. The native screens below stay as the documented fallback.
            Text(
                stringResource(R.string.admin_console_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.veryMutedText,
            )
            AdminActionRow(
                title = stringResource(R.string.admin_console_open),
                enabled = !busy,
                onClick = onOpenConsole,
            )
            if (consoleFailure != null) {
                AdminErrorState(consoleFailure, null) { onOpenConsole() }
            }
        }

        val summary = cache.summary
        val summaryFailure = cache.summaryFailed
        when {
            summaryFailure != null -> AdminErrorState(summaryFailure, null) { run { load(); loaded = true } }

            cache.summaryState == AdminLoadState.Loading && summary == null -> AdminLoadingState()

            summary == null -> AdminEmptyState(stringResource(R.string.admin_section_empty))

            else -> {
                AdminSectionHeader(stringResource(R.string.admin_summary_title))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CocoSpacing.md)) {
                    AdminMetricCard(
                        label = stringResource(R.string.admin_counter_open_reports),
                        value = summary.openReports.toString(),
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(AdminTarget.Reports) },
                    )
                    AdminMetricCard(
                        label = stringResource(R.string.admin_counter_in_progress),
                        value = summary.inProgressReports.toString(),
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(AdminTarget.Reports) },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CocoSpacing.md)) {
                    AdminMetricCard(
                        label = stringResource(R.string.admin_counter_feedback),
                        value = summary.unresolvedFeedback.toString(),
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(AdminTarget.Feedback) },
                    )
                    AdminMetricCard(
                        label = stringResource(R.string.admin_counter_suspensions),
                        value = summary.activeAccountSuspensions.toString(),
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(AdminTarget.Users) },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CocoSpacing.md)) {
                    AdminMetricCard(
                        label = stringResource(R.string.admin_counter_devices),
                        value = summary.activeInstallationSuspensions.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    AdminMetricCard(
                        label = stringResource(R.string.admin_counter_last_24h),
                        value = summary.reportsLast24h.toString(),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (owner) {
                    AdminSectionHeader(stringResource(R.string.manage_storage))
                    AdminCard {
                        val storage = summary.storage
                        if (storage == null) {
                            // The server sends null to a moderator; it is never
                            // rendered as a zero.
                            Text(
                                stringResource(R.string.admin_section_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.mutedText,
                            )
                        } else {
                            AdminKeyValue(
                                stringResource(R.string.manage_used),
                                "${AdminPresentation.bytes(storage.usedBytes)} / ${AdminPresentation.bytes(storage.reservedBytes)}",
                            )
                            AdminKeyValue(
                                stringResource(R.string.admin_storage_budget),
                                if (storage.budgetBytes > 0) {
                                    AdminPresentation.bytes(storage.budgetBytes)
                                } else {
                                    stringResource(R.string.admin_storage_no_budget)
                                },
                            )
                        }
                        AdminActionRow(
                            title = stringResource(R.string.admin_nav_storage),
                            subtitle = stringResource(R.string.manage_storage),
                            icon = CocoIcons.File,
                            onClick = { onOpen(AdminTarget.Storage) },
                        )
                    }
                }

                AdminSectionHeader(stringResource(R.string.admin_quick_actions))
                AdminCard {
                    AdminActionRow(
                        title = stringResource(R.string.admin_nav_reports),
                        subtitle = stringResource(R.string.admin_reports),
                        icon = CocoIcons.Bookmark,
                        onClick = { onOpen(AdminTarget.Reports) },
                    )
                    AdminDivider()
                    AdminActionRow(
                        title = stringResource(R.string.admin_nav_users),
                        subtitle = stringResource(R.string.admin_search),
                        icon = CocoIcons.Search,
                        onClick = { onOpen(AdminTarget.Users) },
                    )
                    AdminDivider()
                    AdminActionRow(
                        title = stringResource(R.string.admin_nav_feedback),
                        subtitle = stringResource(R.string.admin_feedback),
                        icon = CocoIcons.Attachment,
                        onClick = { onOpen(AdminTarget.Feedback) },
                    )
                    if (owner) {
                        AdminDivider()
                        AdminActionRow(
                            title = stringResource(R.string.admin_nav_audit),
                            subtitle = stringResource(R.string.admin_audit),
                            icon = CocoIcons.Eye,
                            onClick = { onOpen(AdminTarget.Audit) },
                        )
                        AdminDivider()
                        AdminActionRow(
                            title = stringResource(R.string.admin_nav_settings),
                            subtitle = stringResource(R.string.manage_card),
                            icon = CocoIcons.Logo,
                            onClick = { onOpen(AdminTarget.ServerSettings) },
                        )
                    }
                    AdminDivider()
                    AdminActionRow(
                        title = stringResource(R.string.admin_nav_security),
                        subtitle = stringResource(R.string.admin_security_session),
                        icon = CocoIcons.Security,
                        onClick = { onOpen(AdminTarget.Security) },
                    )
                    AdminDivider()
                    AdminActionRow(
                        title = stringResource(R.string.admin_refresh),
                        enabled = !busy,
                        onClick = { run { load() } },
                    )
                }
            }
        }
    }
}

/** Maps a transport failure to the localized failure kinds the UI renders. */
internal fun Throwable.failureKind(): AdminPresentation.Failure = AdminPresentation.failure(
    (this as? AdminHttpFailure)?.status,
    this is java.io.IOException,
)
