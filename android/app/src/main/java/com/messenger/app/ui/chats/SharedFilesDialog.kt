package com.messenger.app.ui.chats

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.domain.model.ChatSummary
import kotlinx.coroutines.launch
import java.util.UUID

@Composable internal fun SharedFilesDialog(uris: List<Uri>, chats: List<ChatSummary>, app: MessengerApp, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val epoch = remember { app.authRepository.currentSessionEpoch() }
    var chosen by remember { mutableStateOf<ChatSummary?>(null) }
    var busy by remember { mutableStateOf(false) }; var failed by remember { mutableStateOf(false) }
    var sent by remember { mutableIntStateOf(0) }
    val ids = remember(uris) { uris.map { UUID.randomUUID().toString() } }
    val staged = remember(uris) { mutableMapOf<Int, Uri>() }
    val colors = LocalCocoColors.current
    val savedTitle = stringResource(R.string.saved_messages)
    val chatTitle = stringResource(R.string.chat_default_title)
    fun title(chat: ChatSummary) = if (chat.type == "saved") savedTitle else chat.otherUser?.displayName ?: chat.otherUser?.username ?: chatTitle
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(stringResource(R.string.media_share)) }, text = {
        Column(Modifier.imePadding()) {
            Text("${uris.size} · ${chosen?.let(::title) ?: "UNYRI"}")
            if (chosen == null) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) { items(chats, key = { it.chatId }) { chat -> TextButton(onClick = { chosen = chat }, modifier = Modifier.fillMaxWidth()) { Text(title(chat)) } } }
            else Text(stringResource(R.string.media_share_original))
            if (busy) LinearProgressIndicator()
            if (failed) Text(stringResource(R.string.media_action_failed), color = colors.danger)
        }
    }, confirmButton = { TextButton(enabled = chosen != null && !busy, onClick = {
        val chat = chosen ?: return@TextButton
        busy = true; failed = false
        scope.launch {
            try {
                for (index in sent until uris.size) {
                    val uri = staged[index] ?: (app.authRepository.withWorkspaceWrite(epoch) { app.attachmentManager.stageDraft(uris[index]) } ?: error("Account changed")).also { staged[index] = it }
                    app.authRepository.withWorkspaceWrite(epoch) { app.messageRepository.queueMedia(chat.chatId, ids[index], uri.toString(), "file", null, 0, null).getOrThrow(); true } ?: error("Account changed")
                    sent = index + 1
                }
                onClose()
            } catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }) { Text(stringResource(R.string.send)) } }, dismissButton = { TextButton(enabled = !busy,onClick = onClose) { Text(stringResource(R.string.cancel)) } })
}
