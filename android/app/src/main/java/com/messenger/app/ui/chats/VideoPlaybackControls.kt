package com.messenger.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.delay

internal fun videoSeekTarget(position: Long, offset: Long, duration: Long): Long =
    (position.coerceAtLeast(0) + offset).coerceIn(0, duration.coerceAtLeast(0))

internal fun videoTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun Modifier.viewerSpeedChip(selected: Boolean): Modifier =
    background(if (selected) Color.White.copy(alpha = .92f) else Color.Transparent, CircleShape)
        .border(if (selected) 0.dp else 1.dp, Color.White.copy(alpha = .22f), CircleShape)

@Composable internal fun ViewerTransportRow(actions: @Composable () -> Unit, transport: @Composable () -> Unit,
    options: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
        if (maxWidth < 336.dp) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) { actions(); transport(); options() }
        else {
            Box(Modifier.align(Alignment.CenterStart)) { actions() }
            Box(Modifier.align(Alignment.Center)) { transport() }
            Box(Modifier.align(Alignment.CenterEnd)) { options() }
        }
    }
}

/** Only this overlay ticks, only while visible. Surface and decoder remain native. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun VideoPlaybackControls(player: Player, onVisible: (Boolean) -> Unit, onScrubbing: (Boolean) -> Unit, audioAllowed: Boolean = true, controlsPinned: Boolean = false, onMutedChanged: ((Boolean) -> Unit)? = null, actions: @Composable () -> Unit = {}, onFill: (Boolean) -> Unit = {}, onSpeed: (Float) -> Unit = {}, onDismiss: () -> Unit = {}, onDrag: (Float, Float) -> Unit = { _, _ -> }) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val foreground = rememberViewerForeground()
    val colors=LocalCocoColors.current
    val fallbackAudio = remember(player) { VideoAudioOwnership(
        volume = { player.volume = if (it) 1f else 0f },
        focus = { (player as? androidx.media3.exoplayer.ExoPlayer)?.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, it) }) }
    fun changeMute(muted: Boolean) {
        VideoPlaybackMemory.muted = muted
        if (onMutedChanged != null) onMutedChanged(muted) else fallbackAudio.apply(audioAllowed, muted, player.playWhenReady, videoAudioAvailability(player.currentTracks, player.playbackState == Player.STATE_READY))
    }
    var visible by remember(player) { mutableStateOf(true) }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var duration by remember(player) { mutableLongStateOf(0) }
    var position by remember(player) { mutableLongStateOf(0) }
    var dragging by remember(player) { mutableStateOf(false) }
    var interaction by remember(player) { mutableIntStateOf(0) }
    var feedback by remember(player) { mutableStateOf("") }
    var muted by remember(player) { mutableStateOf(VideoPlaybackMemory.muted) }
    var timeBar by remember(player) { mutableStateOf<MediaScrubberView?>(null) }
    var fine by remember(player) { mutableFloatStateOf(1f) }
    var remaining by remember { mutableStateOf(VideoPlaybackMemory.remaining) }
    var streakSide by remember(player) { mutableIntStateOf(0) }
    var streakBase by remember(player) { mutableLongStateOf(0) }
    var streakSteps by remember(player) { mutableIntStateOf(0) }
    val controlsContext = androidx.compose.ui.platform.LocalContext.current
    val accessibleControls = remember(controlsContext) { controlsContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    var accessibilityEnabled by remember { mutableStateOf(accessibleControls?.isEnabled == true) }
    DisposableEffect(accessibleControls) {
        val listener = android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener { accessibilityEnabled = it }
        accessibleControls?.addAccessibilityStateChangeListener(listener)
        onDispose { accessibleControls?.removeAccessibilityStateChangeListener(listener) }
    }
    var filled by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    fun update() {
        speed = player.playbackParameters.speed
        muted = VideoPlaybackMemory.muted
        playing = player.isPlaying
        duration = player.duration.coerceAtLeast(0)
        if (!dragging) position = player.currentPosition.coerceIn(0, duration)
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { update(); if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) visible = true }
        }
        player.addListener(listener); update()
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(visible) { onVisible(visible) }
    LaunchedEffect(visible, playing, dragging, speedMenu, interaction, controlsPinned, accessibilityEnabled) {
        if (visible && playing && !dragging && !speedMenu && !controlsPinned && !accessibilityEnabled) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(3500, containsIcons = true, containsText = true, containsControls = true) ?: 3500)
            visible = false
        }
    }
    LaunchedEffect(visible, playing, dragging, foreground) {
        if (visible && !dragging && foreground) { update(); while (playing) { delay(250); update() } }
    }
    DisposableEffect(timeBar, visible, playing, dragging, foreground) {
        val bar = timeBar
        val tick = object : Runnable {
            override fun run() {
                bar?.position = player.currentPosition.coerceAtLeast(0)
                bar?.invalidate()
                bar?.postDelayed(this, (1000 / VideoPolicy.fps(bar.width.toFloat(), duration)).toLong())
            }
        }
        if (bar != null && visible && playing && !dragging && foreground) bar.post(tick)
        onDispose { bar?.removeCallbacks(tick) }
    }
    LaunchedEffect(feedback, interaction) { if (feedback.isNotEmpty()) { delay(700); timeBar?.announceForAccessibility(feedback); feedback = "" } }
    val backLabel = stringResource(R.string.video_back_ten)
    val forwardLabel = stringResource(R.string.video_forward_ten)
    fun skip(delta: Long) {
        if (duration <= 0) return
        position = videoSeekTarget(player.currentPosition, delta, duration)
        player.seekVideoExact(position); interaction++; feedback = if (delta < 0) backLabel else forwardLabel
    }
    val playLabel = stringResource(if (playing) R.string.pause else R.string.play)
    val muteLabel = stringResource(if (muted) R.string.video_unmute else R.string.video_mute)
    val speedLabel = stringResource(R.string.video_speed)
    val fitLabel = stringResource(if (filled) R.string.media_fit else R.string.media_fill)
    val state = stringResource(R.string.media_video_state, videoTime(position),
        stringResource(if (playing) R.string.media_note_playing else R.string.media_note_paused),
        stringResource(if (muted) R.string.media_no_sound else R.string.media_sound))
    Box(Modifier.fillMaxSize()) {
        // Separate from the buttons and slider: their taps cannot toggle the backdrop.
        androidx.compose.ui.viewinterop.AndroidView(factory = { ViewerVideoGestureView(it) }, update = { surface ->
            surface.player = player
            surface.dismiss = { if (!controlsPinned) onDismiss() }
            surface.dragChanged = { x, y -> if (!controlsPinned) onDrag(x, y) }
            surface.toggleChrome = { visible = !visible; interaction++ }
            surface.seek = { side, first ->
                if (first || side != streakSide) { streakBase = player.currentPosition; streakSteps = 0 }
                if (first || side != streakSide) surface.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                streakSide = side; streakSteps++
                val target = videoSeekTarget(streakBase, side * streakSteps * 10_000L, duration)
                player.seekVideoExact(target); position = target; interaction++
                val seconds = (kotlin.math.abs(target - streakBase) / 1000).toInt()
                feedback = surface.resources.getQuantityString(if (side < 0) R.plurals.media_seek_back_seconds else R.plurals.media_seek_forward_seconds, seconds, seconds)
            }
            surface.holdChanged = { rate -> visible = rate == null; feedback = rate?.let { "$it×" }.orEmpty(); interaction++ }
            surface.canFill = { VideoPolicy.offersFill(player.videoSize.width.toFloat() / player.videoSize.height.coerceAtLeast(1), surface.width.toFloat() / surface.height.coerceAtLeast(1)) }
            surface.fillChanged = { fill -> filled = fill; onFill(fill); feedback = surface.context.getString(if (fill) R.string.media_fill else R.string.media_fit); surface.announceForAccessibility(feedback); interaction++ }
        }, modifier = Modifier.fillMaxSize().testTag("video-tap-surface").semantics {
            stateDescription = state
            customActions = listOf(
                CustomAccessibilityAction(playLabel) { if (player.playWhenReady) player.pause() else player.play(); true },
                CustomAccessibilityAction(backLabel) { skip(-10_000); true },
                CustomAccessibilityAction(forwardLabel) { skip(10_000); true },
                CustomAccessibilityAction(speedLabel) { val next = VideoPolicy.speeds.firstOrNull { it > speed } ?: VideoPolicy.speeds.first(); speed = next; onSpeed(next); player.setPlaybackSpeed(next); true },
                CustomAccessibilityAction(muteLabel) { if (audioAllowed) { muted = !muted; changeMute(muted) }; true },
                CustomAccessibilityAction(fitLabel) { filled = !filled; onFill(filled); true })
        })
        if (visible) {
            // The navigation bar is shown in response to this overlay. Reserve its
            // real window inset before that visibility animation can reach Compose.
            // This also keeps the dock still when controls are shown again.
            BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout))
                .padding(vertical = 16.dp)) {
            val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val margin=if(maxWidth<360.dp)0.dp else 16.dp
            val availableWidth=(maxWidth-margin*2).coerceAtLeast(1.dp)
            val dockWidth = if (landscape) minOf(440.dp, availableWidth) else availableWidth
            // Center controls need144dp, and the trailing speed/mute pair needs80dp.
            // Reserve304dp inside a320dp dock so their48dp hit areas never overlap.
            val dockPadding=if(dockWidth<336.dp)8.dp else 12.dp
            Column(Modifier.align(Alignment.BottomCenter).width(dockWidth)
                .testTag("video-controls-dock")
                .viewerGlass(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .padding(horizontal = dockPadding, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(videoTime(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                val seekLabel = stringResource(R.string.video_seek)
                androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                    MediaScrubberView(context).also { timeBar = it }
                }, update = { bar ->
                    bar.canResume = { audioAllowed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                    bar.player = player; bar.duration = duration; bar.isEnabled = duration > 0
                    bar.onScrubbing = { dragging = it; onScrubbing(it); interaction++ }
                    bar.onPosition = { position = it }
                    bar.onFine = { fine = it }
                    if (!dragging) { bar.position = position; bar.invalidate() }
                }, modifier = Modifier.weight(1f).height(44.dp).testTag("video-timebar").semantics { contentDescription = seekLabel })
                Text(if (remaining) "−" + videoTime(VideoPolicy.remaining(position, duration)) else videoTime(VideoPolicy.total(duration)), color = Color.White,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight().clickable { remaining = !remaining; VideoPlaybackMemory.remaining = remaining; interaction++ })
                }
                ViewerTransportRow(actions = actions, transport = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { skip(-10_000) }, enabled = duration > 0, modifier = Modifier.size(48.dp)) {
                        MediaSkipIcon(false, backLabel)
                    }
                    IconButton(onClick = {
                        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause()
                        else { if (player.playbackState == Player.STATE_ENDED) player.seekVideoExact(0); player.play() }
                        interaction++
                    }, modifier = Modifier.size(48.dp)) {
                        Icon(if (playing) CocoIcons.Pause else CocoIcons.Play, stringResource(if (playing) R.string.pause else R.string.play), tint = colors.onAccent, modifier = Modifier.size(36.dp).background(colors.accent, CircleShape).padding(7.5.dp))
                    }
                    IconButton(onClick = { skip(10_000) }, enabled = duration > 0, modifier = Modifier.size(48.dp)) {
                        MediaSkipIcon(true, forwardLabel)
                    }
                    }
                }, options = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        val speedLabel = stringResource(R.string.video_speed)
                        TextButton(onClick = { speedMenu = true; interaction++ }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp).semantics { contentDescription = speedLabel }) {
                            Text("${if (speed % 1f == 0f) speed.toInt() else speed}×", color = if (speed == 1f) Color.White else Color(0xFF111111),
                                modifier = Modifier.heightIn(min = 26.dp).viewerSpeedChip(speed != 1f).padding(horizontal = 8.dp).wrapContentHeight())
                        }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false; interaction++ },containerColor=colors.surface,shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                            VideoPolicy.speeds.forEach { value -> DropdownMenuItem(text = { Text("${value}×") }, onClick = { speed = value; VideoPlaybackMemory.speed = value; onSpeed(value); player.setPlaybackSpeed(value); timeBar?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); speedMenu = false; interaction++ }) }
                        }
                    }
                    IconButton(onClick = { val nextMuted = !muted; muted = nextMuted; changeMute(nextMuted); interaction++ }, enabled = audioAllowed, modifier = Modifier.size(48.dp)) {
                        Icon(if (muted) MediaViewerIcons.Muted else MediaViewerIcons.Speaker, stringResource(if (muted) R.string.video_unmute else R.string.video_mute), tint = if (audioAllowed) Color.White else Color.Gray, modifier = Modifier.size(21.dp))
                    }
                    }
                })
            }

            }
        }
        if (feedback.isNotEmpty()) Text(feedback, color = Color.White, modifier = Modifier.align(Alignment.Center).offset(y = (-90).dp).background(Color.Black.copy(alpha = .7f), CircleShape).padding(14.dp))
    }
}
