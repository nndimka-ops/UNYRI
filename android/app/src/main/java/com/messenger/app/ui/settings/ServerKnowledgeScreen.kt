package com.messenger.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.LocalGlassBottomInset

/** Static inventory shared with iOS UnyriServerKnowledgeView; no server request is needed. */
@Composable
fun ServerKnowledgeScreen(onBack: () -> Unit) {
    val colors = LocalCocoColors.current
    SettingsScaffold(stringResource(R.string.server_knowledge_title), onBack) {
        Column(
            Modifier.fillMaxSize().testTag("server-knowledge")
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 16.dp + LocalGlassBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.server_knowledge_intro),
                color = colors.secondaryText, style = MaterialTheme.typography.bodyMedium)
            ServerKnowledgeSection(R.string.server_knowledge_unreadable, unreadableFacts, colors.categoryMint)
            ServerKnowledgeSection(R.string.server_knowledge_absent, absentFacts, colors.categoryBlue)
            ServerKnowledgeSection(R.string.server_knowledge_stored, storedFacts, colors.categoryAmber)
            ServerKnowledgeSection(R.string.server_knowledge_retention, retentionFacts, colors.categoryViolet)
            ServerKnowledgeSection(R.string.server_knowledge_others, otherFacts, colors.categoryRose)
            Text(stringResource(R.string.server_knowledge_footer),
                color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class ServerKnowledgeFact(val title: Int, val detail: Int, val icon: ImageVector)

@Composable
private fun ServerKnowledgeSection(title: Int, facts: List<ServerKnowledgeFact>, tint: Color) {
    val colors = LocalCocoColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), Modifier.padding(start = 4.dp).semantics { heading() },
            color = colors.secondaryText, style = MaterialTheme.typography.labelLarge)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineSubtle)) {
            Column {
                facts.forEachIndexed { index, fact ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = colors.outlineSubtle)
                    Row(Modifier.fillMaxWidth().padding(14.dp).semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(fact.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(fact.title), color = colors.primaryText,
                                style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(fact.detail), color = colors.secondaryText,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private val unreadableFacts by lazy { listOf(
    ServerKnowledgeFact(R.string.server_knowledge_messages_title, R.string.server_knowledge_messages_detail, CocoIcons.Chat),
    ServerKnowledgeFact(R.string.server_knowledge_media_title, R.string.server_knowledge_media_detail, CocoIcons.StackedPhotos),
    ServerKnowledgeFact(R.string.server_knowledge_calls_title, R.string.server_knowledge_calls_detail, CocoIcons.Phone),
    ServerKnowledgeFact(R.string.server_knowledge_backup_title, R.string.server_knowledge_backup_detail, CocoIcons.Archive),
    ServerKnowledgeFact(R.string.server_knowledge_direct_transfer_title, R.string.server_knowledge_direct_transfer_detail, AuxiliaryIcons.Transfer),
    ServerKnowledgeFact(R.string.server_knowledge_live_draft_title, R.string.server_knowledge_live_draft_detail, CocoIcons.Pencil),
) }

private val absentFacts by lazy { listOf(
    ServerKnowledgeFact(R.string.server_knowledge_phone_email_title, R.string.server_knowledge_phone_email_detail, CocoIcons.Phone),
    ServerKnowledgeFact(R.string.server_knowledge_contacts_title, R.string.server_knowledge_contacts_detail, AuxiliaryIcons.People),
    ServerKnowledgeFact(R.string.server_knowledge_location_title, R.string.server_knowledge_location_detail, CocoIcons.Network),
    ServerKnowledgeFact(R.string.server_knowledge_ip_title, R.string.server_knowledge_ip_detail, CocoIcons.Network),
) }

private val storedFacts by lazy { listOf(
    ServerKnowledgeFact(R.string.server_knowledge_profile_title, R.string.server_knowledge_profile_detail, AuxiliaryIcons.Account),
    ServerKnowledgeFact(R.string.server_knowledge_password_title, R.string.server_knowledge_password_detail, AuxiliaryIcons.Key),
    ServerKnowledgeFact(R.string.server_knowledge_devices_title, R.string.server_knowledge_devices_detail, CocoIcons.Devices),
    ServerKnowledgeFact(R.string.server_knowledge_conversations_title, R.string.server_knowledge_conversations_detail, CocoIcons.Clock),
    ServerKnowledgeFact(R.string.server_knowledge_message_actions_title, R.string.server_knowledge_message_actions_detail, CocoIcons.HandRaised),
    ServerKnowledgeFact(R.string.server_knowledge_last_seen_title, R.string.server_knowledge_last_seen_detail, CocoIcons.Eye),
    ServerKnowledgeFact(R.string.server_knowledge_call_history_title, R.string.server_knowledge_call_history_detail, CocoIcons.Clock),
    ServerKnowledgeFact(R.string.server_knowledge_settings_title, R.string.server_knowledge_settings_detail, CocoIcons.ReactionGrid),
) }

private val retentionFacts by lazy { listOf(
    ServerKnowledgeFact(R.string.server_knowledge_encrypted_content_title, R.string.server_knowledge_encrypted_content_detail, CocoIcons.Storage),
    ServerKnowledgeFact(R.string.server_knowledge_reports_title, R.string.server_knowledge_reports_detail, CocoIcons.ReportBubble),
    ServerKnowledgeFact(R.string.server_knowledge_diagnostics_title, R.string.server_knowledge_diagnostics_detail, CocoIcons.Network),
    ServerKnowledgeFact(R.string.server_knowledge_everything_else_title, R.string.server_knowledge_everything_else_detail, CocoIcons.Clock),
) }

private val otherFacts by lazy { listOf(
    ServerKnowledgeFact(R.string.server_knowledge_notifications_title, R.string.server_knowledge_notifications_detail, CocoIcons.Bell),
    ServerKnowledgeFact(R.string.server_knowledge_administrator_title, R.string.server_knowledge_administrator_detail, CocoIcons.Security),
    ServerKnowledgeFact(R.string.server_knowledge_log_title, R.string.server_knowledge_log_detail, CocoIcons.File),
    ServerKnowledgeFact(R.string.server_knowledge_own_server_title, R.string.server_knowledge_own_server_detail, CocoIcons.Storage),
) }
