package com.messenger.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** Only this overlay ticks, only while visible. Surface and decoder remain native. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun VideoPlaybackControls(player: Player, onVisible: (Boolean) -> Unit, onScrubbing: (Boolean) -> Unit, audioAllowed: Boolean = true, controlsPinned: Boolean = false, onMutedChanged: (Boolean) -> Unit = {}, actions: @Composable () -> Unit = {}) {
    val colors=LocalCocoColors.current
    var visible by remember(player) { mutableStateOf(true) }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var duration by remember(player) { mutableLongStateOf(0) }
    var position by remember(player) { mutableLongStateOf(0) }
    var dragging by remember(player) { mutableStateOf(false) }
    var interaction by remember(player) { mutableIntStateOf(0) }
    var feedback by remember(player) { mutableStateOf("") }
    var muted by remember(player) { mutableStateOf(player.volume == 0f) }
    var timeBar by remember(player) { mutableStateOf<androidx.media3.ui.DefaultTimeBar?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    fun update() {
        muted = player.volume == 0f
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
    LaunchedEffect(visible, playing, dragging, speedMenu, interaction, controlsPinned) {
        if (visible && playing && !dragging && !speedMenu && !controlsPinned) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(3500, containsIcons = true, containsText = true, containsControls = true) ?: 3500)
            visible = false
        }
    }
    LaunchedEffect(visible, playing, dragging) {
        if (visible && !dragging) { update(); while (playing) { delay(250); update() } }
    }
    DisposableEffect(timeBar, visible, playing, dragging) {
        val bar = timeBar
        val tick = object : Runnable {
            override fun run() {
                bar?.setPosition(player.currentPosition.coerceAtLeast(0))
                bar?.postOnAnimation(this)
            }
        }
        if (bar != null && visible && playing && !dragging) bar.postOnAnimation(tick)
        onDispose { bar?.removeCallbacks(tick) }
    }
    LaunchedEffect(feedback, interaction) { if (feedback.isNotEmpty()) { delay(650); feedback = "" } }
    val backLabel = stringResource(R.string.video_back_ten)
    val forwardLabel = stringResource(R.string.video_forward_ten)
    fun skip(delta: Long) {
        if (duration <= 0) return
        position = videoSeekTarget(player.currentPosition, delta, duration)
        player.seekTo(position); interaction++; feedback = if (delta < 0) backLabel else forwardLabel
    }
    Box(Modifier.fillMaxSize()) {
        // Separate from the buttons and slider: their taps cannot toggle the backdrop.
        Box(Modifier.fillMaxSize().testTag("video-tap-surface").pointerInput(player, duration) {
            detectTapGestures(onTap = { visible = !visible; interaction++ }, onDoubleTap = { point ->
                if (point.x < size.width * .4f) skip(-10_000)
                else if (point.x > size.width * .6f) skip(10_000)
            })
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
                .background(colors.surface.copy(alpha = .75f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .padding(horizontal = dockPadding, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(videoTime(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                val seekLabel = stringResource(R.string.video_seek)
                androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                    (android.view.LayoutInflater.from(context).inflate(R.layout.coco_video_timebar, null) as androidx.media3.ui.DefaultTimeBar).apply {
                        setPlayedColor(colors.accent.toArgb())
                        setScrubberColor(android.graphics.Color.WHITE)
                        timeBar = this
                        contentDescription = seekLabel
                        addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
                            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, value: Long) {
                                dragging = true; onScrubbing(true); position = value; interaction++
                                parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, value: Long) { position = value }
                            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, value: Long, canceled: Boolean) {
                                if (!canceled) player.seekTo(value)
                                dragging = false; onScrubbing(false); interaction++; update()
                                parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        })
                    }
                }, update = { bar ->
                    bar.setDuration(duration); bar.isEnabled = duration > 0
                    if (!dragging && !playing) bar.setPosition(position)
                    bar.setBufferedPosition(player.bufferedPosition.coerceAtLeast(0))
                }, modifier = Modifier.weight(1f).height(if (landscape) 36.dp else 44.dp).testTag("video-timebar"))
                Text(videoTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    Box(Modifier.align(Alignment.CenterStart)) { actions() }
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { skip(-10_000) }, enabled = duration > 0, modifier = Modifier.size(48.dp)) {
                        MediaSkipIcon(false, backLabel)
                    }
                    IconButton(onClick = {
                        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause()
                        else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
                        interaction++
                    }, modifier = Modifier.size(48.dp)) {
                        Icon(if (playing) CocoIcons.Pause else CocoIcons.Play, stringResource(if (playing) R.string.pause else R.string.play), tint = colors.onAccent, modifier = Modifier.size(if (landscape) 38.dp else 42.dp).background(colors.accent, CircleShape).padding(9.dp))
                    }
                    IconButton(onClick = { skip(10_000) }, enabled = duration > 0, modifier = Modifier.size(48.dp)) {
                        MediaSkipIcon(true, forwardLabel)
                    }
                    }
                    Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        val speedLabel = stringResource(R.string.video_speed)
                        TextButton(onClick = { speedMenu = true; interaction++ }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp, 48.dp).semantics { contentDescription = speedLabel }) { Text("${if (speed % 1f == 0f) speed.toInt() else speed}×", color = Color.White) }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false; interaction++ },containerColor=colors.surface,shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                            listOf(.5f, 1f, 1.5f, 2f).forEach { value -> DropdownMenuItem(text = { Text("${value}×") }, onClick = { speed = value; player.setPlaybackSpeed(value); speedMenu = false; interaction++ }) }
                        }
                    }
                    IconButton(onClick = { val nextMuted = !muted; player.volume = if (nextMuted) 0f else 1f; muted = nextMuted; onMutedChanged(nextMuted); interaction++ }, enabled = audioAllowed, modifier = Modifier.size(40.dp, 48.dp)) {
                        Icon(if (muted) MediaViewerIcons.Muted else MediaViewerIcons.Speaker, stringResource(if (muted) R.string.video_unmute else R.string.video_mute), tint = if (audioAllowed) Color.White else Color.Gray, modifier = Modifier.size(21.dp))
                    }
                    }
                }
            }

            }
        }
        if (feedback.isNotEmpty()) Text(feedback, color = Color.White, modifier = Modifier.align(Alignment.Center).offset(y = (-90).dp).background(Color.Black.copy(alpha = .7f), CircleShape).padding(14.dp))
    }
}
