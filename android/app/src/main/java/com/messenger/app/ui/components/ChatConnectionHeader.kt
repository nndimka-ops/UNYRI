package com.messenger.app.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.messenger.app.R
import com.messenger.app.data.sync.SyncPhase
import com.messenger.app.domain.repository.MessageRepository
import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.delay

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

/** Animation state and optional RTT stay in this small header, never in the chat list. */
@Composable
fun ChatConnectionHeader(repository: MessageRepository, loading: Boolean, error: Boolean, onRetry: () -> Unit, contentReady: Boolean = false) {
    val connection by repository.socketState.collectAsState()
    val sync by repository.syncPhase.collectAsState()
    val showPing by com.messenger.app.ui.settings.rememberConnectionPingEnabled()
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val connected = connection == SocketConnectionState.CONNECTED
    var expired by remember { mutableStateOf(false) }
    LaunchedEffect(connected, route, active) {
        expired = false
        if (active && route && !connected) { delay(8000); expired = true }
    }
    var ping by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(showPing, active, connected) {
        ping = null
        if (showPing && active && connected) while (true) {
            ping = repository.measurePing()
            delay(5000)
        }
    }
    val status = headerConnectionStatus(connected, route, expired, sync, loading, error, contentReady)
    val label = stringResource(when (status) {
        HeaderConnectionStatus.ONLINE -> R.string.connection_header_online
        HeaderConnectionStatus.CONNECTING -> R.string.connection_connecting
        HeaderConnectionStatus.SYNCING -> R.string.connection_header_syncing
        HeaderConnectionStatus.OFFLINE -> R.string.connection_offline
        HeaderConnectionStatus.UNAVAILABLE -> R.string.connection_header_unavailable
        HeaderConnectionStatus.SYNC_FAILED -> R.string.connection_header_sync_failed
    })
    val busy = status == HeaderConnectionStatus.CONNECTING || status == HeaderConnectionStatus.SYNCING ||
        (status == HeaderConnectionStatus.UNAVAILABLE && connection == SocketConnectionState.CONNECTING)
    val tint = when (status) {
        HeaderConnectionStatus.ONLINE -> Color(0xFF32C9E9)
        HeaderConnectionStatus.SYNCING -> Color(0xFFAA85ED)
        HeaderConnectionStatus.UNAVAILABLE -> Color(0xFFEC8589)
        else -> Color(0xFFE5AC5E)
    }
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(busy, active) {
        sweep.snapTo(0f)
        if (busy && active && ValueAnimator.areAnimatorsEnabled()) while (true) {
            sweep.animateTo(1f, tween(1400)); sweep.snapTo(0f); delay(700)
        }
    }
    Column(Modifier.fillMaxWidth().height(44.dp).clickable(enabled = status == HeaderConnectionStatus.SYNC_FAILED || status == HeaderConnectionStatus.UNAVAILABLE, onClick = onRetry),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = Color(0xFFF2F3F7), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (showPing && status == HeaderConnectionStatus.ONLINE) Text(stringResource(R.string.connection_header_ping_value, ping?.toString() ?: "—"),
                fontSize = 10.sp, color = Color(0xFF97A4BC), maxLines = 1)
        }
        Spacer(Modifier.height(5.dp))
        Canvas(Modifier.width(30.dp).height(2.dp)) {
            drawLine(Brush.horizontalGradient(listOf(tint, if (status == HeaderConnectionStatus.ONLINE) Color(0xFFA783EC) else tint.copy(alpha = .6f))),
                Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
            if (busy && active) {
                val x = sweep.value * (size.width + 8.dp.toPx()) - 8.dp.toPx()
                val start = x.coerceIn(0f, size.width)
                val end = (x + 8.dp.toPx()).coerceIn(0f, size.width)
                if (end > start) drawLine(Color.White.copy(alpha = .65f), Offset(start, size.height / 2), Offset(end, size.height / 2), size.height, StrokeCap.Round)
            }
        }
    }
}
