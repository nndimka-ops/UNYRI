package com.messenger.app.ui.chats

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.messenger.app.R

/** Confirmation is the only entry to direct preparation; Add never invokes it. */
@Composable
internal fun DirectOriginalConsentDialog(labels: List<String>, captionRetained: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.direct_send_title)) },
        text = { Text(labels.take(8).joinToString("\n") + "\n\n" + stringResource(R.string.direct_send_note) +
            if (captionRetained) "\n\n" + stringResource(R.string.direct_caption_retained) else "") },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("direct-original-confirm")) { Text(stringResource(R.string.send)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("direct-original-cancel")) { Text(stringResource(R.string.cancel)) } })
}
