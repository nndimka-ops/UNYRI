package com.messenger.app.ui.call

import android.animation.ValueAnimator
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.R
import com.messenger.app.data.call.WebRtcCallStats
import com.messenger.app.domain.call.*
import com.messenger.app.ui.theme.CocoIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

internal val CallGlass = Color(0xBF202020)
internal val CallWhite = Color(0xFFF1F1F1)
internal val CallEdge = Color.White.copy(alpha = .08f)
internal data class CallChromeSnapshot(val state: CallUiState, val ring: CallRingIndicator.Phase?,
    val stats: StateFlow<WebRtcCallStats?>, val endedSeconds: Long? = null, val quality: StateFlow<CallQualityUiState>? = null,
    val audioRoutes: StateFlow<List<com.messenger.app.data.call.AudioRoute>>? = null,
    val voiceLevels: kotlinx.coroutines.flow.Flow<Float>? = null,
    val comparisonCode: StateFlow<List<Int>?>? = null)
internal val LocalCallChrome = staticCompositionLocalOf<CallChromeSnapshot?> { null }
internal val LocalCallRendererVisible = staticCompositionLocalOf { true }

/** No polling. Network changes and lifecycle notifications only. */
@Composable
internal fun callForeground(): Boolean {
    return callLifecycleAtLeast(Lifecycle.State.RESUMED)
}

@Composable
internal fun callWindowStarted(): Boolean = callLifecycleAtLeast(Lifecycle.State.STARTED)

@Composable
private fun callLifecycleAtLeast(minimum: Lifecycle.State): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember(lifecycle, minimum) { mutableStateOf(lifecycle.currentState.isAtLeast(minimum)) }
    DisposableEffect(lifecycle, minimum) {
        val observer = LifecycleEventObserver { _, _ -> active = lifecycle.currentState.isAtLeast(minimum) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return active
}

@Composable
internal fun callReducedMotion(): Boolean {
    val context = LocalContext.current
    return !ValueAnimator.areAnimatorsEnabled() || context.getSystemService(PowerManager::class.java).isPowerSaveMode
}

@Composable
private fun networkMissing(): Boolean {
    val manager = LocalContext.current.getSystemService(ConnectivityManager::class.java)
    var missing by remember(manager) { mutableStateOf(manager.activeNetwork == null) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { missing = false }
            override fun onLost(network: Network) { missing = manager.activeNetwork == null }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return missing
}

/** Shared by the screen, mini window and return bar; no spinner, one delayed state change. */
@Composable
internal fun callDropShown(reconnecting: Boolean): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(reconnecting) { shown = false; if (reconnecting) { delay(1_200); shown = true } }
    return shown
}

@Composable
internal fun CallTopBar(onMinimize: (() -> Unit)?, modifier: Modifier = Modifier,
    chrome: Boolean = true, overVideo: Boolean = false, fallbackTitle: String = "") {
    val snapshot = LocalCallChrome.current
    val state = snapshot?.state
    val active = callForeground()
    val presentation by (if (active) snapshot?.quality?.collectAsState() else null) ?: remember { mutableStateOf(CallQualityUiState()) }
    val drop = callDropShown(state is CallUiState.Reconnecting)
    val offline = callDropShown(networkMissing())
    val kind = state?.let { CallStatusPolicy.kind(it, snapshot?.ring,
        reconnectForMs = if (drop) 1_200 else 0, offlineForMs = if (offline) 1_200 else 0) } ?: CallStatusPolicy.Kind.HIDDEN
    val link = when (presentation.quality.caption) {
        R.string.call_quality_weak -> CallStatusPolicy.LinkProblem.WEAK
        R.string.call_quality_poor -> CallStatusPolicy.LinkProblem.POOR
        else -> null
    }
    val alert = CallStatusPolicy.problem(kind, link)
    BoxWithConstraints(modifier.fillMaxWidth().height(56.dp).zIndex(3f)
        .then(if (chrome && onMinimize != null) Modifier.callMinimizeSwipe(state?.let(CallMinimizePolicy::callId), onMinimize) else Modifier)) {
        if (chrome && onMinimize != null) Box(Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) {
            CallMinimizeButton(onMinimize)
        }
        if (CallPresentationPolicy.capsuleVisible(chrome, alert, state is CallUiState.Ended)) Box(
            Modifier.align(Alignment.Center).widthIn(max = (maxWidth - 128.dp).coerceAtLeast(48.dp)),
        ) {
            CallStatusCapsule(snapshot, fallbackTitle, kind, presentation, link, overVideo)
        }
    }
}

@Composable
internal fun CallStatusCapsule(snapshot: CallChromeSnapshot?, fallback: String, kind: CallStatusPolicy.Kind,
    presentation: CallQualityUiState, link: CallStatusPolicy.LinkProblem?, overVideo: Boolean) {
    val state = snapshot?.state
    val connected = state?.let(CallPresentationPolicy::connected)
    val active = callForeground()
    val quality = presentation.quality
    val alert = CallStatusPolicy.problem(kind, link)
    var details by remember(connected?.call?.callId) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) details = false }
    val title = when {
        kind == CallStatusPolicy.Kind.ENDED -> stringResource(R.string.call_ended) +
            if (snapshot?.endedSeconds != null) " · ${formatCallDuration(snapshot.endedSeconds)}" else ""
        kind == CallStatusPolicy.Kind.CANCELLED -> stringResource(R.string.call_ui_cancelled)
        kind == CallStatusPolicy.Kind.DECLINED -> stringResource(R.string.call_declined)
        kind == CallStatusPolicy.Kind.NO_ANSWER -> stringResource(R.string.call_no_answer)
        kind == CallStatusPolicy.Kind.BUSY -> stringResource(R.string.call_busy)
        kind == CallStatusPolicy.Kind.FAILED -> fallback.ifEmpty { stringResource(R.string.call_connection_failed) }
        kind == CallStatusPolicy.Kind.WAITING_NETWORK -> stringResource(R.string.call_waiting_network)
        kind == CallStatusPolicy.Kind.RECONNECTING -> stringResource(R.string.call_reconnecting)
        kind == CallStatusPolicy.Kind.SECURED -> stringResource(R.string.call_encrypted)
        state is CallUiState.OutgoingRinging -> ringingTitle(snapshot.ring)
        state is CallUiState.Connecting -> stringResource(R.string.call_connecting)
        else -> fallback
    }
    val subtitle = CallStatusPolicy.subtitle(kind, link)?.let {
        stringResource(when (it) {
            CallStatusPolicy.Subtitle.WEAK -> R.string.call_quality_weak
            CallStatusPolicy.Subtitle.POOR -> R.string.call_quality_poor
            CallStatusPolicy.Subtitle.WAITING_FOR_PEER -> R.string.call_waiting_for_peer_online
        })
    }
    val route = CallStatusPolicy.routeGlyph(kind, presentation.relay)
    val routeDescription = route?.let { stringResource(if (it == CallStatusPolicy.RouteGlyph.SERVER)
        R.string.call_route_relay else R.string.call_route_direct) }
    val reduced = callReducedMotion()
    val pulse = remember { Animatable(0f) }
    val view = LocalView.current
    LaunchedEffect(alert, title, subtitle, active) {
        if (alert && active) { view.announceForAccessibility(title); pulse.animateTo(1f, tween(160)); pulse.animateTo(0f, tween(440)) }
        else pulse.snapTo(0f)
    }
    val qualityCaption = quality.caption?.let { stringResource(it) }
    LaunchedEffect(qualityCaption) { if (qualityCaption != null && active) view.announceForAccessibility(qualityCaption) }
    // Plain ink for normal phases. Only a problem acquires a material surface.
    Surface(shape = RoundedCornerShape(50), color = if (!alert) Color.Transparent else if (overVideo) Color(0xB3303030) else CallGlass,
        border = if (alert) BorderStroke(.5.dp, CallEdge) else null, modifier = Modifier.testTag("call-status")
            .semantics(mergeDescendants = true) { }
            .heightIn(min = 44.dp).graphicsLayer { val p = pulse.value; scaleX = if (reduced) 1f else 1 + p * .05f; scaleY = scaleX }
            .then(if (state != null && CallPresentationPolicy.sheetEnabled(state)) Modifier.clickable { details = true } else Modifier)
            .animateContentSize(if (reduced) tween(200) else spring(dampingRatio = .86f, stiffness = 500f))) {
        Box {
            if (alert) Box(Modifier.matchParentSize().graphicsLayer { alpha = pulse.value * .14f }.background(Color.White))
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CocoIcons.Security, null, Modifier.size(11.dp), tint = CallWhite.copy(alpha = if (connected != null || snapshot?.endedSeconds != null) 1f else .55f))
                Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(title, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "callStatusTitle") { text ->
                            Text(text, style = callStatusInk, color = CallWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (connected != null && CallStatusPolicy.showsTimer(kind) && active) {
                            CallTimerText(connected.connectedAtElapsedRealtimeMs)
                            if (route != null) CallRouteGlyph(route, routeDescription!!)
                        }
                    }
                    AnimatedContent(subtitle, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "callStatusSubtitle") { text ->
                        if (text != null) Text(text, color = CallWhite.copy(alpha = .78f), fontSize = 11.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (CallStatusPolicy.showsBars(kind, link)) CallQualityBars(if (kind == CallStatusPolicy.Kind.SECURED) quality.bars else 0)
            }
        }
    }
    if (details && snapshot != null) CallEncryptionSheet(snapshot, quality, onDismiss = { details = false })
}

private val callStatusInk = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .65f), Offset(0f, 1f), 4f))

/** Static outline phones; route words stay available to TalkBack. No animation clock. */
@Composable
private fun CallRouteGlyph(route: CallStatusPolicy.RouteGlyph, description: String) {
    val server = route == CallStatusPolicy.RouteGlyph.SERVER
    Canvas(Modifier.padding(start = 6.dp).size(if (server) 29.dp else 22.dp, 12.dp)
        .semantics { contentDescription = description }) {
        val stroke = Stroke(1.dp.toPx())
        val phoneWidth = 5.dp.toPx()
        val phoneHeight = 9.dp.toPx()
        val top = (size.height - phoneHeight) / 2
        fun phone(x: Float) {
            drawRoundRect(CallWhite.copy(alpha = .8f), Offset(x, top), Size(phoneWidth, phoneHeight),
                androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()), style = stroke)
            drawLine(CallWhite, Offset(x + phoneWidth * .35f, top + phoneHeight - 2.dp.toPx()),
                Offset(x + phoneWidth * .65f, top + phoneHeight - 2.dp.toPx()), strokeWidth = 1.dp.toPx())
        }
        phone(1.dp.toPx()); phone(size.width - phoneWidth - 1.dp.toPx())
        val y = size.height / 2
        val left = phoneWidth + 2.dp.toPx()
        val right = size.width - left
        if (server) {
            val boxWidth = 6.dp.toPx()
            val x = (size.width - boxWidth) / 2
            drawLine(CallWhite.copy(alpha = .65f), Offset(left, y), Offset(x - 1.dp.toPx(), y), stroke.width)
            drawLine(CallWhite.copy(alpha = .65f), Offset(x + boxWidth + 1.dp.toPx(), y), Offset(right, y), stroke.width)
            drawRoundRect(CallWhite.copy(alpha = .8f), Offset(x, y - 3.dp.toPx()), Size(boxWidth, 6.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(.5.dp.toPx()), style = stroke)
            drawLine(CallWhite, Offset(x + 1.dp.toPx(), y), Offset(x + boxWidth - 1.dp.toPx(), y), stroke.width)
        } else drawLine(CallWhite.copy(alpha = .65f), Offset(left, y), Offset(right, y), stroke.width)
    }
}

/** Isolated 1Hz text; no timer is mounted in hidden chrome or a background window. */
@Composable
private fun CallTimerText(start: Long) {
    Text(" · ${callDuration(start)}", style = callStatusInk, color = CallWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
}

@Composable
internal fun CallQualityBars(bars: Int) {
    Canvas(Modifier.size(17.dp, 13.dp).clearAndSetSemantics { }) {
        repeat(4) { i -> val h = size.height * (i + 1) / 4
            drawRoundRect(CallWhite.copy(alpha = if (i < bars) 1f else .25f), Offset(size.width * i / 4, size.height - h),
                Size(size.width / 4 - 1.dp.toPx(), h), androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
        }
    }
}

@Composable
internal fun CallReturnQuality(flow: StateFlow<CallQualityUiState>) {
    if (callForeground()) {
        val state by flow.collectAsState()
        CallQualityBars(state.quality.bars)
    }
}

@Composable
internal fun routeLabel(stats: WebRtcCallStats?): String? {
    val pair = stats?.selectedCandidatePair ?: return null
    if (pair.localCandidateType == null || pair.remoteCandidateType == null) return null
    return stringResource(if (pair.localCandidateType == "relay" || pair.remoteCandidateType == "relay") R.string.call_route_relay else R.string.call_route_direct)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallEncryptionSheet(snapshot: CallChromeSnapshot, quality: CallQualityPresentation.Result, onDismiss: () -> Unit) {
    val stats by snapshot.stats.collectAsState()
    val pair = stats?.selectedCandidatePair
    val relay = pair?.localCandidateType == "relay" || pair?.remoteCandidateType == "relay"
    var statistics by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(),
        containerColor = Color(0xFF202020), contentColor = CallWhite) {
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.call_encryption_title), style = MaterialTheme.typography.titleLarge)
            if (pair == null) Text(stringResource(R.string.call_encryption_pending))
            CallComparisonCode(snapshot)
            routeLabel(stats)?.let { route -> Text(route + ((pair?.relayProtocol ?: pair?.protocol)?.uppercase()?.let { " · $it" } ?: "")) }
            Text(stringResource(when (quality.bars) {
                4 -> R.string.call_quality_excellent
                3 -> R.string.call_quality_good
                2 -> R.string.call_quality_weak
                1 -> R.string.call_quality_poor
                else -> R.string.call_quality_measuring
            }))
            TextButton(onClick = { statistics = !statistics }, modifier = Modifier.testTag("call-statistics-toggle")) {
                Text(stringResource(R.string.call_statistics), color = CallWhite)
            }
            if (statistics) stats?.let { CallStatisticsContent(it) }
        }
    }
}

@Composable
internal fun CallVideoScrims(chrome: Boolean, modifier: Modifier = Modifier, picture: Boolean = true) {
    val pictureAlpha = androidx.compose.animation.core.animateFloatAsState(if (picture) 1f else 0f,
        tween(if (callReducedMotion()) 150 else 250), label = "videoShadePresence")
    Box(modifier.fillMaxSize().graphicsLayer { alpha = pictureAlpha.value }) {
        Box(Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .38f), .5f to Color.Black.copy(alpha = .16f), 1f to Color.Transparent)))
        val alpha = androidx.compose.animation.core.animateFloatAsState(if (chrome) 1f else 0f,
            tween(if (callReducedMotion()) 0 else 180), label = "videoBottomShade")
        Box(Modifier.fillMaxWidth().height(200.dp).align(Alignment.BottomCenter).graphicsLayer { this.alpha = alpha.value }
            .background(Brush.verticalGradient(0f to Color.Transparent, .5f to Color.Black.copy(alpha = .16f), 1f to Color.Black.copy(alpha = .4f))))
    }
}
