package com.messenger.app.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.messenger.app.R
import com.messenger.app.domain.repository.FailedMessageAction

@Composable
internal fun FailedMessageActionNotice(action: FailedMessageAction, onRetry: () -> Unit, onCancel: () -> Unit) {
    val deletedTarget = action.kind == "reaction" && action.reasonCode == "reaction_target_deleted"
    MessageFailureNoticeContent(
        label = stringResource(if (deletedTarget) R.string.chat_reaction_target_deleted else when (action.kind) {
            "edit" -> R.string.chat_failed_edit
            "delete" -> R.string.chat_failed_delete
            else -> R.string.chat_failed_reaction
        }),
        onRetry = if (deletedTarget) null else onRetry,
        onCancel = onCancel,
    )
}
