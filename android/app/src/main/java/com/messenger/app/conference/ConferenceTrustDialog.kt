package com.messenger.app.conference

import com.messenger.app.ui.security.rejectObscuredTouches

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.messenger.app.R

/** Shared by provider and requester. The pending mailbox exchange is retained during review. */
@Composable internal fun ConferenceTrustDialog(mailbox: ConferenceKeyMailbox, locked: Boolean) {
    val pending by mailbox.trustReview.collectAsState()
    val review = pending?.takeUnless { locked } ?: return
    AlertDialog(onDismissRequest = { mailbox.decideTrust(review, false) },
        title = { Text(stringResource(R.string.verify_title)) },
        text = { Column {
            Text(stringResource(R.string.hostile_room_approve))
            Text(review.snapshot.userId)
            Text(review.snapshot.deviceId)
            Text(review.snapshot.safetyNumber.orEmpty())
            Text(stringResource(R.string.hostile_room_limit))
        } },
        confirmButton = { TextButton(modifier = androidx.compose.ui.Modifier.rejectObscuredTouches(), enabled = review.snapshot.safetyNumber != null, onClick = { mailbox.decideTrust(review, true) }) { Text(stringResource(R.string.verify_confirm_action)) } },
        dismissButton = { TextButton(onClick = { mailbox.decideTrust(review, false) }) { Text(stringResource(R.string.cancel)) } })
}
