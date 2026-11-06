package com.messenger.app.conference

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.CocoHaptics

@Composable internal fun ConferenceJoinRequestCard(request: ConferenceJoinRequest, busy: Boolean,
    onAccept: () -> Unit, onDecline: () -> Unit) {
    val colors = LocalCocoColors.current
    val view = LocalView.current
    Surface(color = colors.surfaceVariant, contentColor = colors.primaryText, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.conference_join_wants_in, request.displayName), style = MaterialTheme.typography.bodyMedium)
            Text("@" + request.username, style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { CocoHaptics.select(view); onDecline() }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                    .testTag("conference_join_decline_" + request.requestId)) { Text(stringResource(R.string.conference_join_reject)) }
                Button(onClick = { CocoHaptics.select(view); onAccept() }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                    .testTag("conference_join_accept_" + request.requestId)) { Text(stringResource(R.string.conference_join_accept)) }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConferenceJoinRequestSheet(controller: ConferenceController, state: ConferenceState, onDismiss: () -> Unit) {
    val colors = LocalCocoColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.primaryText) {
        Text(stringResource(R.string.conference_join_requests), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.joinRequests, key = { it.requestId }) { request ->
                ConferenceJoinRequestCard(request, state.decidingJoinRequest != null,
                    onAccept = { controller.decideJoinRequest(request.requestId, true) },
                    onDecline = { controller.decideJoinRequest(request.requestId, false) })
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
