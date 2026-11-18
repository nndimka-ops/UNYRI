package com.messenger.app.conference

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.model.Message
import com.messenger.app.ui.theme.LocalCocoColors

@Composable internal fun ConferenceInvitationCard(message: Message, own: Boolean, selectionMode: Boolean, onSelect: () -> Unit) {
    val metadata = message.conferenceInvitation ?: return
    val app = LocalContext.current.applicationContext as MessengerApp
    val view = LocalView.current
    val epoch = remember(message.clientMessageId) { app.authRepository.currentSessionEpoch() }
    val permission = rememberConferencePermissions()
    var error by remember(message.clientMessageId) { mutableStateOf<String?>(null) }
    val expired = metadata.expiresAt <= java.time.Instant.now().epochSecond
    if (own) {
        val status = stringResource(when {
            message.status == com.messenger.app.domain.model.MessageStatus.PENDING -> R.string.conference_invitation_pending
            message.status == com.messenger.app.domain.model.MessageStatus.FAILED -> R.string.conference_invitation_failed
            expired -> R.string.conference_invitation_expired
            else -> R.string.conference_invitation_label
        })
        ConferenceOutgoingInvitationCard(metadata.title, status)
        return
    }
    ConferenceIncomingInvitationCard(
        title = metadata.title,
        status = stringResource(if (expired) R.string.conference_invitation_expired else R.string.conference_invitation_compact_label),
        error = error,
        enabled = selectionMode || !expired,
        onJoin = {
                if (selectionMode) { onSelect(); return@ConferenceIncomingInvitationCard }
                com.messenger.app.ui.theme.CocoHaptics.tap(view)
                if (app.authRepository.currentSessionEpoch() != epoch) return@ConferenceIncomingInvitationCard
                if (metadata.server != ServerEndpoint.current()) { error = "Приглашение для другого сервера. Выберите его перед входом."; return@ConferenceIncomingInvitationCard }
                val invitation = runCatching { ConferenceInvite.parse(metadata.invite) }.getOrNull() ?: return@ConferenceIncomingInvitationCard
                if (!app.conferenceController.returnToRoom(invitation)) permission(false) {
                    if (app.authRepository.currentSessionEpoch() == epoch && ServerEndpoint.current() == metadata.server) app.conferenceController.join(invitation)
                }
        },
    )
}

/** The normal bubble owns its padding, timestamp and receipts; this is only its compact content. */
@Composable internal fun ConferenceIncomingInvitationCard(title: String, status: String, error: String?, enabled: Boolean, onJoin: () -> Unit) {
    val c = LocalCocoColors.current
    val largeText = LocalDensity.current.fontScale >= 1.4f
    val join: @Composable () -> Unit = {
        Button(onClick = onJoin, enabled = enabled, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.primaryText, contentColor = c.callBackground),
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp).testTag("conference_invitation_accept"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.conference_invitation_join))
        }
    }
    Column(Modifier.widthIn(max = 224.dp).testTag("conference_incoming_invitation"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConferenceInvitationIcon()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.labelSmall, color = c.mutedText, maxLines = if (largeText) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
            if (!largeText) join()
        }
        if (largeText) Box(Modifier.align(Alignment.End)) { join() }
        error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun ConferenceInvitationIcon() {
    val c = LocalCocoColors.current
    Surface(shape = RoundedCornerShape(9.dp), color = c.primaryText.copy(alpha = .08f)) {
        Icon(painterResource(R.drawable.ic_conference_people), null, Modifier.padding(6.dp).size(16.dp), tint = c.primaryText)
    }
}

/** Outgoing messages already carry time/read receipts in their parent bubble. */
@Composable internal fun ConferenceOutgoingInvitationCard(title: String, status: String) {
    val c = LocalCocoColors.current
    Row(Modifier.widthIn(max = 184.dp).heightIn(min = 36.dp).testTag("conference_outgoing_invitation"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConferenceInvitationIcon()
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.labelSmall, color = c.mutedText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
