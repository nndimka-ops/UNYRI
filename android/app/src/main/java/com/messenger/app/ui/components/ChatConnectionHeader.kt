package com.messenger.app.ui.components

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.messenger.app.R
import com.messenger.app.data.sync.SyncPhase
import com.messenger.app.domain.repository.MessageRepository
import com.messenger.app.domain.repository.SocketConnectionState
import com.messenger.app.ui.chats.ChatMotionSettings
import com.messenger.app.ui.chats.rememberChatMotionSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal enum class HeaderConnectionStatus { ONLINE, CONNECTING, SYNCING, OFFLINE, UNAVAILABLE, SYNC_FAILED }
internal fun headerConnectionStatus(connected: Boolean, route: Boolean, expired: Boolean,
    sync: SyncPhase, loading: Boolean, error: Boolean, contentReady: Boolean = false): HeaderConnectionStatus = when {
    connected && (sync == SyncPhase.FAILED || error) -> HeaderConnectionStatus.SYNC_FAILED
    connected && !contentReady && (sync == SyncPhase.RUNNING || loading) -> HeaderConnectionStatus.SYNCING
    connected -> HeaderConnectionStatus.ONLINE
    !route -> HeaderConnectionStatus.OFFLINE
    expired -> HeaderConnectionStatus.UNAVAILABLE
    else -> HeaderConnectionStatus.CONNECTING
}

/** Keep the last RTT away from the screen; cancelled requests cannot overwrite a newer session. */
internal class HeaderPingState {
    var value: Long? by mutableStateOf(null)
        private set
    private var generation = 0L
    fun start(enabled: Boolean, connected: Boolean, active: Boolean): Long? {
        generation++
        if (!enabled || !connected) value = null
        return generation.takeIf { enabled && connected && active }
    }
    fun accept(ticket: Long, measured: Long?) {
        if (ticket == generation) value = measured?.takeIf { it >= 0 }
    }
    fun stop(ticket: Long) { if (ticket == generation) generation++ }
}

/** One sample per visible entry/resume/reconnect. No idle retry or polling schedule. */
internal suspend fun sampleHeaderPing(state: HeaderPingState, enabled: Boolean, connected: Boolean, active: Boolean,
    measure: suspend () -> Long?) {
    val ticket = state.start(enabled, connected, active) ?: return
    try {
        val measured = measure()
        currentCoroutineContext().ensureActive()
        state.accept(ticket, measured)
    } finally { state.stop(ticket) }
}

internal fun headerPingFigurePadding(value: Long?): String =
    "\u2007".repeat((3 - (value?.toString() ?: "—").length).coerceAtLeast(0))

internal enum class ConnectionStripMotion { Still, Sweep }
internal fun connectionStripMotion(online: Boolean, busy: Boolean, settings: ChatMotionSettings): ConnectionStripMotion = when {
    settings.calm -> ConnectionStripMotion.Still
    online -> ConnectionStripMotion.Still
    busy -> ConnectionStripMotion.Sweep
    else -> ConnectionStripMotion.Still
}

/** The connected underline is static; a visible connection operation gets one finite sweep. */
@Composable
fun ChatConnectionHeader(repository: MessageRepository, loading: Boolean, error: Boolean, onRetry: () -> Unit,
    contentReady: Boolean = false, visible: Boolean = true) {
    val connection by repository.socketState.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val sync by repository.syncPhase.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val showPing by com.messenger.app.ui.settings.rememberConnectionPingEnabled()
    val motion = rememberChatMotionSettings(visible)
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var route by remember { mutableStateOf(manager.activeNetwork != null) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { route = true }
            override fun onLost(network: Network) { route = manager.activeNetwork?.let { it != network } == true }
            override fun onUnavailable() { route = false }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { route = manager.activeNetwork != null }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    val connected = connection == SocketConnectionState.CONNECTED
    var expired by remember { mutableStateOf(false) }
    LaunchedEffect(connected, route, motion.active) {
        expired = false
        if (motion.active && route && !connected) { delay(8000); expired = true }
    }
    val status = headerConnectionStatus(connected, route, expired, sync, loading, error, contentReady)
    val busy = status == HeaderConnectionStatus.CONNECTING || status == HeaderConnectionStatus.SYNCING ||
        (status == HeaderConnectionStatus.UNAVAILABLE && connection == SocketConnectionState.CONNECTING)
    ChatConnectionHeaderContent(status, connected, busy, showPing, visible, onRetry, repository) { repository.measurePing() }
}

/** Same production header/sampling, without the repository adapter, for dialog interaction fixtures. */
@Composable
internal fun ChatConnectionHeaderContent(status: HeaderConnectionStatus, connected: Boolean, busy: Boolean,
    showPing: Boolean, visible: Boolean, onRetry: () -> Unit, pingOwner: Any = Unit, measurePing: suspend () -> Long?) {
    val motion = rememberChatMotionSettings(visible)
    val ping = remember(pingOwner) { HeaderPingState() }
    val measure by rememberUpdatedState(measurePing)
    LaunchedEffect(pingOwner, showPing, motion.active, connected) {
        sampleHeaderPing(ping, showPing, connected, motion.active, { measure() })
    }
    val label = stringResource(when (status) {
        HeaderConnectionStatus.ONLINE -> R.string.connection_header_online
        HeaderConnectionStatus.CONNECTING -> R.string.connection_connecting
        HeaderConnectionStatus.SYNCING -> R.string.connection_header_syncing
        HeaderConnectionStatus.OFFLINE -> R.string.connection_offline
        HeaderConnectionStatus.UNAVAILABLE -> R.string.connection_header_unavailable
        HeaderConnectionStatus.SYNC_FAILED -> R.string.connection_header_sync_failed
    })
    val tint = when (status) {
        HeaderConnectionStatus.ONLINE -> 0xFF32C9E9.toInt()
        HeaderConnectionStatus.SYNCING -> 0xFFAA85ED.toInt()
        HeaderConnectionStatus.UNAVAILABLE -> 0xFFEC8589.toInt()
        else -> 0xFFE5AC5E.toInt()
    }
    val online = status == HeaderConnectionStatus.ONLINE
    val stripMotion = connectionStripMotion(online, busy, motion)
    val pingText = if (showPing && connected)
        stringResource(R.string.connection_header_ping_value, ping.value?.toString() ?: "—") else null
    val statusLine = buildAnnotatedString {
        append(label)
        if (pingText != null) withStyle(SpanStyle(fontSize = 10.sp, color = Color(0xFFB0B0B0), fontFeatureSettings = "tnum")) {
            append("  "); append(pingText); append(headerPingFigurePadding(ping.value))
        }
    }
    Column(Modifier.fillMaxWidth().height(44.dp)
        .clickable(enabled = status == HeaderConnectionStatus.SYNC_FAILED || status == HeaderConnectionStatus.UNAVAILABLE,
            role = Role.Button, onClick = onRetry),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // One text layout prevents ping/status overlap and reserves three digit columns.
        Text(statusLine, color = Color(0xFFF2F3F7), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(5.dp))
        AndroidView(factory = { ConnectionUnderlineView(it) }, update = { it.configure(tint, online, stripMotion) },
            onRelease = { it.stop() }, modifier = Modifier.width(30.dp).height(2.dp).clearAndSetSemantics {})
    }
}

/** Finite property animation on RenderThread. Configuration is idempotent; hidden views cancel it. */
internal class ConnectionUnderlineView(context: Context) : FrameLayout(context) {
    private val base = View(context).also(::addView)
    private val sweep = View(context).also(::addView)
    private var tint = 0
    private var online = false
    private var requested = ConnectionStripMotion.Still
    private var configured = false
    private var aggregatedVisible = false
    private var running = ConnectionStripMotion.Still
    private var generation = 0
    private val linear = LinearInterpolator()
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        background = GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT); cornerRadius = 100f }
        clipToOutline = true
        sweep.visibility = INVISIBLE
        sweep.background = GradientDrawable().apply { setColor(0xA6FFFFFF.toInt()); cornerRadius = 100f }
    }
    fun configure(color: Int, isOnline: Boolean, motion: ConnectionStripMotion) {
        if (configured && color == tint && isOnline == online && motion == requested) return
        tint = color; online = isOnline; requested = motion; configured = true
        base.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            if (online) intArrayOf(0xFF33C9E8.toInt(), 0xFFA682ED.toInt(), 0xFF33C9E8.toInt())
            else intArrayOf(tint, (tint and 0x00FFFFFF) or 0x99000000.toInt()))
        updateMotion()
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        base.layout(0, 0, width, height)
        sweep.layout(0, 0, (8 * resources.displayMetrics.density).toInt(), height)
        if (changed) stop()
        updateMotion()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updateMotion()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateMotion() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    fun stop() {
        generation++
        running = ConnectionStripMotion.Still
        sweep.animate().cancel()
        sweep.visibility = INVISIBLE
    }
    private fun updateMotion() {
        val next = if (configured && isAttachedToWindow && aggregatedVisible && windowVisibility == VISIBLE && width > 0 &&
            android.animation.ValueAnimator.areAnimatorsEnabled())
            requested else ConnectionStripMotion.Still
        if (next == running) return
        stop()
        running = next
        val ticket = generation
        when (next) {
            ConnectionStripMotion.Sweep -> { sweep.visibility = VISIBLE; startSweep(ticket) }
            ConnectionStripMotion.Still -> Unit
        }
    }
    private fun valid(ticket: Int) = ticket == generation && isAttachedToWindow && aggregatedVisible &&
        android.animation.ValueAnimator.areAnimatorsEnabled()
    private fun startSweep(ticket: Int) {
        if (!valid(ticket)) return
        sweep.translationX = -sweep.width.toFloat()
        sweep.animate().translationX(width.toFloat()).setStartDelay(700).setDuration(1400).setInterpolator(linear)
            .withEndAction { if (valid(ticket)) sweep.visibility = INVISIBLE }.start()
    }
}
