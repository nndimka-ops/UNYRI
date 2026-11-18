package com.messenger.app.ui.chats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.model.*
import com.messenger.app.ui.theme.CocoIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

internal fun noteMayPlay(visible: Boolean, pending: Boolean, callBusy: Boolean, captureBusy: Boolean) =
    visible && !pending && !callBusy && !captureBusy
internal fun mediaCallBusy(state: CallUiState) = state !is CallUiState.Idle && state !is CallUiState.Ended && state !is CallUiState.Failed

/** A plain owner reference: idle notes never observe the global player. Main thread only. */
internal object InlineVideoNotes {
    var revision = 0L
        private set
    val hasActive: Boolean get() = owner != null && activeCheck?.invoke() == true
    private var owner: Any? = null
    private var stop: (() -> Unit)? = null
    private var activeCheck: (() -> Boolean)? = null
    fun claim(token: Any, isActive: () -> Boolean, release: () -> Unit) { if (owner !== token) { stop?.invoke(); revision++ }; owner = token; activeCheck = isActive; stop = release }
    fun release(token: Any) { if (owner === token) { owner = null; stop = null; activeCheck = null; revision++ } }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun VideoNoteContent(attachment: Attachment, mine: Boolean, download: (Attachment) -> Unit) {
    val app = LocalContext.current.applicationContext as MessengerApp
    val allowed = rememberMediaWorkAllowed()
    val pending = mine && attachment.uploadState != AttachmentUploadState.SENT && attachment.uploadState != AttachmentUploadState.CANCELLED
    val preview = rememberConversationMediaPreview(attachment, 480)
    val token = remember(attachment.attachmentId) { Any() }
    val playRequest = remember(token) { VideoNotePlayRequest() }
    var requested by remember(token) { mutableStateOf(false) }
    fun audioSnapshot() = NoteAudioSnapshot(InlineVideoNotes.revision, app.voicePlayerController.playbackRevision, app.nonCallCaptures.state.value.revision)
    fun audioBusy() = mediaCallBusy(app.callController.state.value) || app.conferenceController.state.value.active || app.nonCallCaptures.busy
    var active by remember(token) { mutableStateOf(false) }
    var playing by remember(token) { mutableStateOf(false) }
    var player by remember(token) { mutableStateOf<Player?>(null) }
    var failure by remember(token) { mutableStateOf(false) }
    var progress by remember(token) { mutableFloatStateOf(0f) }
    val title = stringResource(R.string.media_video_note)
    val value = stringResource(if (playing) R.string.media_note_playing else R.string.media_note_paused)
    val quick = LocalAttachmentQuickReaction.current
    val long = LocalAttachmentLongPress.current
    val width = minOf(LocalChatMediaWidth.current ?: 220f, 220f).coerceAtLeast(44f)
    fun tap() {
        if (pending || !allowed || audioBusy()) return
        if (attachment.localPath == null) {
            playRequest.request(attachment.attachmentId, audioSnapshot()); requested = true
            if (attachment.downloadState != AttachmentDownloadState.DOWNLOADING) download(attachment)
            return
        }
        if (!noteMayPlay(allowed, pending, mediaCallBusy(app.callController.state.value) || app.conferenceController.state.value.active, app.nonCallCaptures.busy)) return
        failure = false
        if (active) { if (player?.playWhenReady == true) player?.pause() else { app.voicePlayerController.stop(); player?.play() } }
        else { app.voicePlayerController.stop(); InlineVideoNotes.claim(token, { active }) { active = false }; active = true }
    }
    LaunchedEffect(allowed, attachment.localPath, attachment.downloadState, requested) {
        if (!allowed) { active = false; playRequest.cancel(); requested = false }
        if (requested) {
            val failed = attachment.downloadState == AttachmentDownloadState.FAILED || attachment.downloadState == AttachmentDownloadState.CORRUPT
            if (playRequest.consume(attachment.attachmentId, attachment.localPath != null, failed, allowed,
                    audioBusy(), InlineVideoNotes.hasActive || app.voicePlayerController.state.value.playingAttachmentId != null, audioSnapshot())) {
                requested = false; failure = false
                InlineVideoNotes.claim(token, { active }) { active = false }; active = true
            } else if (!playRequest.pending) requested = false
        }
    }
    if (requested) {
        fun cancelRequest() { playRequest.cancel(); requested = false }
        LaunchedEffect(token) { app.callController.state.map(::mediaCallBusy).distinctUntilChanged().collect { if (it) cancelRequest() } }
        LaunchedEffect(token) { app.conferenceController.state.map { it.active }.distinctUntilChanged().collect { if (it) cancelRequest() } }
        LaunchedEffect(token) { app.nonCallCaptures.state.map { it.busy }.distinctUntilChanged().collect { if (it) cancelRequest() } }
    }
    DisposableEffect(token) { onDispose { InlineVideoNotes.release(token) } }
    Box(Modifier.size(width.dp)) {
    Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF202020))
        .testTag("chat-video-note-" + attachment.attachmentId)
        .semantics(mergeDescendants = true) { contentDescription = title; stateDescription = value }
        .combinedClickable(onClick = { tap() }, onDoubleClick = quick, onLongClick = long), contentAlignment = Alignment.Center) {
        DevelopingMediaImage(attachment.attachmentId, attachment.localPath, preview, null)
        if (active && allowed && attachment.localPath != null) {
            InlineNoteSurface(File(attachment.localPath), { player = it }, { playing = it }, { progress = it }, { active = false; failure = it })
        }
        if (!playing && !pending && attachment.downloadState != AttachmentDownloadState.DOWNLOADING)
            Icon(CocoIcons.Play, title, tint = Color.White, modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = .45f), CircleShape).padding(12.dp))
        if (playing || attachment.downloadState == AttachmentDownloadState.DOWNLOADING) Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val fraction = if (playing) progress else attachment.progress / 100f
            drawCircle(Color.White.copy(alpha = .2f), style = Stroke(3.dp.toPx()))
            drawArc(Color.White, -90f, 360f * fraction.coerceIn(0f, 1f), false, style = Stroke(3.dp.toPx()))
        }
        if (failure) Text(stringResource(R.string.media_note_failed), color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = .7f)).padding(12.dp))
    }
    attachment.durationMs?.let { Text(com.messenger.app.data.attachment.FilePresentation.duration(it), color = Color.White,
        style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .6f), CircleShape).padding(horizontal = 6.dp, vertical = 3.dp)) }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun InlineNoteSurface(file: File, ready: (Player?) -> Unit, playing: (Boolean) -> Unit,
    progress: (Float) -> Unit, ended: (Boolean) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val player = remember(file) { androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        setHandleAudioBecomingNoisy(true)
        setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
    } }
    var running by remember(player) { mutableStateOf(false) }
    val latestPlaying by rememberUpdatedState(playing)
    val latestEnded by rememberUpdatedState(ended)
    DisposableEffect(player) {
        val lease = com.messenger.app.data.local.MediaCacheUse.acquire(listOf(file))
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { running = value; latestPlaying(value) }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) latestEnded(false) }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { latestEnded(true) }
        }
        player.addListener(listener); ready(player); player.prepare(); player.play()
        onDispose { ready(null); latestPlaying(false); player.removeListener(listener); player.release(); lease.close() }
    }
    // Only the active note subscribes, with progress stripped from the voice signal.
    LaunchedEffect(player) { app.voicePlayerController.state.map { it.playingAttachmentId }.distinctUntilChanged().collect { if (it != null) latestEnded(false) } }
    LaunchedEffect(player) { app.nonCallCaptures.state.map { it.busy }.distinctUntilChanged().collect { if (it) latestEnded(false) } }
    LaunchedEffect(player) { app.conferenceController.state.map { it.active }.distinctUntilChanged().collect { if (it) latestEnded(false) } }
    LaunchedEffect(player) { app.callController.state.map(::mediaCallBusy).distinctUntilChanged().collect { if (it) latestEnded(false) } }
    LaunchedEffect(player, running) { while (running) { progress(if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f); delay(100) } }
    AndroidView(factory = { android.view.LayoutInflater.from(it).inflate(R.layout.media_note_player, null) as androidx.media3.ui.PlayerView },
        update = { it.player = player; it.keepScreenOn = running }, modifier = Modifier.fillMaxSize())
}
