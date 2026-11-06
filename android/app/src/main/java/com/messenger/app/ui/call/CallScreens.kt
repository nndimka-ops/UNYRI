package com.messenger.app.ui.call

import com.messenger.app.ui.theme.cocoConversationBackground

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.messenger.app.R
import com.messenger.app.data.call.AudioRoute
import com.messenger.app.data.call.AudioRouteKind
import com.messenger.app.data.call.CallAudioPolicy
import com.messenger.app.domain.call.CallNotice
import com.messenger.app.domain.call.CallFailureReason
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.VideoMode
import com.messenger.app.domain.call.VideoUpgradeState
import com.messenger.app.domain.model.CallEndReason
import com.messenger.app.domain.model.CallMediaKind
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.CocoTextRoles
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.delay

/** Process-wide proximity lock (one active call at a time). */
private var proximityLock: PowerManager.WakeLock? = null

/** Code-review 11: bottom offset keeping the transient call notice above the
 *  whole control cluster instead of the old magic 90dp. */
private val CallNoticeBottomOffset = 220.dp

/** Same outer dock gap for audio/video; native system bars remain owned by callSafePadding. */
internal val CallControlsBottomGap = 8.dp

/** Single always-on-top overlay observing the one app-scoped
 *  [CallViewModel]/[com.messenger.app.domain.call.CallController] - shown
 *  regardless of which screen/route is currently active (Этап J п.42/43),
 *  since a call can be answered while anywhere in the app. Callers place
 *  this once at the root of the navigation host (see MainActivity). */
@Composable
fun CallOverlay(
    viewModel: CallViewModel,
    notificationAcceptId: String? = null,
    notificationOpenCallId: String? = null,
    onNotificationOpenConsumed: () -> Unit = {},
    deviceLocked: Boolean = false,
    pictureInPicture: Boolean = false,
    showCompactBar: Boolean = true,
    restoreGeneration: Int = 0,
    onRequestPrivateContent: (() -> Unit) -> Unit = { it() },
    onNotificationAcceptConsumed: () -> Unit = {},
    onPrivateContentVisible: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var pendingAcceptKind by remember { mutableStateOf<CallMediaKind?>(null) }
    var pendingAcceptCallId by remember { mutableStateOf<String?>(null) }
    var pendingVideoCallId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var expandedCallId by remember { mutableStateOf<String?>(null) }
    val currentCallId = when (val s = state) {
        is CallUiState.IncomingRinging -> s.call.callId
        is CallUiState.OutgoingRinging -> s.call.callId
        is CallUiState.Connecting -> s.call.callId
        is CallUiState.Connected -> s.call.callId
        is CallUiState.Reconnecting -> s.call.callId
        else -> null
    }
    val showExpanded = (expanded && expandedCallId == currentCallId) ||
        (currentCallId != null && currentCallId != expandedCallId && state !is CallUiState.IncomingRinging) ||
        deviceLocked || pictureInPicture
    LaunchedEffect(restoreGeneration) { if (restoreGeneration > 0) expanded = true }
    androidx.compose.runtime.SideEffect {
        onPrivateContentVisible(!pictureInPicture && (state is CallUiState.Idle ||
            ((CallMinimizePolicy.callId(state) != null || state is CallUiState.IncomingRinging) && !showExpanded)))
    }
    fun minimize() {
        val requestedCall = expandedCallId
        onRequestPrivateContent {
            if (CallMinimizePolicy.canComplete(requestedCall, viewModel.state.value)) expanded = false
        }
    }

    // Code-review 1: expansion is transition-aware. A NEW call (Idle ->
    // outgoing call expands; an incoming call starts compact. The user's
    // explicit Back/minimize collapses it; an ordinary recomposition
    // (mute/camera/route/notice) must NOT re-expand it.
    LaunchedEffect(currentCallId) {
        when {
            currentCallId == null -> {
                expandedCallId = null
                expanded = false
            }
            currentCallId != expandedCallId -> {
                expandedCallId = currentCallId
                expanded = com.messenger.app.call.IncomingCallPresentationPolicy.expandNewCall(state is CallUiState.IncomingRinging)
            }
            else -> Unit
        }
    }

    LaunchedEffect(notificationOpenCallId, currentCallId) {
        if (notificationOpenCallId != null && notificationOpenCallId == currentCallId) {
            expanded = true
            onNotificationOpenConsumed()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Этап J п.46: never proceed into a doomed call - a denied
        // (including permanently "don't ask again") RECORD_AUDIO means
        // this device rejects rather than silently failing to connect.
        // Code-review 10: a denied CAMERA does NOT block accepting an
        // incoming VIDEO call - only the microphone is required at accept.
        val kind = pendingAcceptKind ?: CallMediaKind.AUDIO
        val requestedCallId = pendingAcceptCallId
        pendingAcceptKind = null
        pendingAcceptCallId = null
        val ringing = viewModel.state.value as? CallUiState.IncomingRinging
        if (requestedCallId == null || ringing?.call?.callId != requestedCallId) return@rememberLauncherForActivityResult
        val denied = result.entries.filter { !it.value }.map { it.key }.toSet()
        if (CallAudioPolicy.blockingDenialsForAccept(kind, denied).isEmpty()) viewModel.acceptIncoming() else viewModel.rejectIncoming()
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requestedCallId = pendingVideoCallId
        pendingVideoCallId = null
        val connected = viewModel.state.value as? CallUiState.Connected
        if (requestedCallId == null || connected?.call?.callId != requestedCallId) return@rememberLauncherForActivityResult
        // N.1A §10: camera denied -> the audio call simply continues with a
        // localized notice - never a hangup, never a silent no-op.
        if (granted) viewModel.requestVideoUpgrade() else viewModel.notifyCameraDenied()
    }
    fun acceptWithPermissionCheck(kind: CallMediaKind) {
        expanded = true
        val required = CallAudioPolicy.requiredCallPermissionsForAccept(kind)
        val alreadyGranted = required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (alreadyGranted) {
            viewModel.acceptIncoming()
        } else {
            val ringing = viewModel.state.value as? CallUiState.IncomingRinging ?: return
            pendingAcceptKind = kind
            pendingAcceptCallId = ringing.call.callId
            permissionLauncher.launch(CallAudioPolicy.allCallPermissionsForAccept(kind, Build.VERSION.SDK_INT).toTypedArray())
        }
    }
    LaunchedEffect(notificationAcceptId, state) {
        val ringing = state as? CallUiState.IncomingRinging
        if (notificationAcceptId != null && ringing?.call?.callId == notificationAcceptId) {
            onNotificationAcceptConsumed()
            acceptWithPermissionCheck(ringing.call.mediaKind)
        }
    }
    fun requestVideoWithPermissionCheck() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.requestVideoUpgrade() else {
            val connected = viewModel.state.value as? CallUiState.Connected ?: return
            pendingVideoCallId = connected.call.callId
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // N.1A §18: back / predictive back minimizes the call, never hangs up.
    BackHandler(enabled = CallMinimizePolicy.callId(state) != null && showExpanded) { minimize() }
    // N.1A §19/§20: proximity (audio+earpiece) and keep-screen-on (video).
    CallDeviceEffects(state)

    val videoState = CallPipPolicy.videoState(state)
    CallTouchLayer(fullscreen = CallTouchPolicy.ownsBackdrop(state, showExpanded)) {
    when (val s = videoState ?: state) {
        CallUiState.Idle -> Unit
        is CallUiState.IncomingRinging -> if (!showExpanded) CompactIncomingCall(
            callerName = s.call.otherDisplayName,
            isVideo = s.call.mediaKind == CallMediaKind.VIDEO,
            onExpand = { expanded = true },
            onAccept = { acceptWithPermissionCheck(s.call.mediaKind) },
            onReject = viewModel::rejectIncoming,
        ) else IncomingCallScreen(
            callerName = s.call.otherDisplayName,
            isVideo = s.call.mediaKind == CallMediaKind.VIDEO,
            onAccept = { acceptWithPermissionCheck(s.call.mediaKind) },
            onReject = viewModel::rejectIncoming,
        )
        is CallUiState.OutgoingRinging -> if (showExpanded) ActiveCallScreen(
            callId = s.call.callId,
            title = stringResource(R.string.call_calling),
            name = s.call.otherDisplayName,
            durationMs = null,
            isMuted = s.isMuted,
            currentRouteKind = s.audioRoute,
            availableRoutes = viewModel.availableAudioRoutes(),
            onToggleMute = viewModel::toggleMute,
            onSelectRoute = viewModel::setAudioRoute,
            onHangup = viewModel::cancelOutgoing,
            onMinimize = { minimize() },
        ) else if (showCompactBar) CompactCallBar(s.call.otherDisplayName, null,
            s.call.mediaKind == CallMediaKind.VIDEO, { expanded = true }, stringResource(R.string.call_calling))
        is CallUiState.Connecting -> if (showExpanded) ActiveCallScreen(
            callId = s.call.callId,
            title = stringResource(R.string.call_connecting),
            name = s.call.otherDisplayName,
            durationMs = null,
            showSpinner = true,
            isMuted = s.isMuted,
            currentRouteKind = s.audioRoute,
            availableRoutes = viewModel.availableAudioRoutes(),
            onToggleMute = viewModel::toggleMute,
            onSelectRoute = viewModel::setAudioRoute,
            onHangup = viewModel::hangup,
            onMinimize = { minimize() },
        ) else if (showCompactBar) CompactCallBar(s.call.otherDisplayName, null,
            s.call.mediaKind == CallMediaKind.VIDEO, { expanded = true }, stringResource(R.string.call_connecting))
        is CallUiState.Connected -> {
            if (s.videoMode == VideoMode.VIDEO) {
                // Keep the video surfaces mounted across minimize/expand -
                // releasing and re-initing a SurfaceViewRenderer mid-call
                // black-screens the video on several devices. When minimized
                // the whole screen is translated off-screen (the SurfaceView
                // stays alive) and a compact bar floats on top.
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset { IntOffset(if (showExpanded) 0 else -20000, if (showExpanded) 0 else -20000) },
                    ) {
                        VideoCallScreen(
                            state = s,
                            pictureInPicture = pictureInPicture,
                            availableRoutes = viewModel.availableAudioRoutes(),
                            viewModel = viewModel,
                            onMinimize = { minimize() },
                        )
                    }
                    if (!showExpanded && showCompactBar) {
                        CompactCallBar(
                            name = s.call.otherDisplayName,
                            durationMs = s.connectedAtElapsedRealtimeMs,
                            isVideo = true,
                            onClick = { expanded = true },
                        )
                    }
                }
            } else {
                when {
                    showExpanded -> ActiveCallScreen(
                        callId = s.call.callId,
                        title = stringResource(R.string.call_connected),
                        name = s.call.otherDisplayName,
                        durationMs = s.connectedAtElapsedRealtimeMs,
                        isMuted = s.isMuted,
                        currentRouteKind = s.audioRoute,
                        availableRoutes = viewModel.availableAudioRoutes(),
                        requestingVideo = s.videoUpgrade is VideoUpgradeState.Requesting || s.videoUpgrade is VideoUpgradeState.Upgrading,
                        onHangup = viewModel::hangup,
                        onToggleMute = viewModel::toggleMute,
                        onSelectRoute = viewModel::setAudioRoute,
                        onRequestVideo = ::requestVideoWithPermissionCheck,
                        onMinimize = { minimize() },
                    )
                    else -> if (showCompactBar) CompactCallBar(
                        name = s.call.otherDisplayName,
                        durationMs = s.connectedAtElapsedRealtimeMs,
                        isVideo = false,
                        onClick = { expanded = true },
                    )
                }
            }
            // Transient feedback banner (video request declined / failed).
            if (!pictureInPicture) s.notice?.let { notice -> CallNoticeBanner(text = noticeLabel(notice)) }
        }
        is CallUiState.Reconnecting -> if (showExpanded) ActiveCallScreen(
            callId = s.call.callId,
            isMuted = (s.since as? CallUiState.Connected)?.isMuted ?: false,
            currentRouteKind = (s.since as? CallUiState.Connected)?.audioRoute ?: AudioRouteKind.EARPIECE,
            availableRoutes = viewModel.availableAudioRoutes(),
            onToggleMute = viewModel::toggleMute,
            onSelectRoute = viewModel::setAudioRoute,
            title = stringResource(R.string.call_reconnecting),
            name = s.call.otherDisplayName,
            durationMs = null,
            showSpinner = true,
            onHangup = viewModel::hangup,
            onMinimize = { minimize() },
        ) else if (showCompactBar) CompactCallBar(s.call.otherDisplayName, null,
            s.call.mediaKind == CallMediaKind.VIDEO, { expanded = true }, stringResource(R.string.call_reconnecting))
        is CallUiState.Ended -> LaunchedEffect(s) { viewModel.acknowledgeEnded() }
        is CallUiState.Failed -> EndedCallScreen(
            reason = if (s.reason == CallFailureReason.BUSY) CallEndReason.BUSY else CallEndReason.FAILED,
            message = stringResource(callFailureReasonRes(s.reason)),
            onDismiss = viewModel::acknowledgeEnded,
        )
    }
    }

    // Audio calls upgrade to video immediately when the other participant
    // enables their camera. There is no second in-app confirmation: the
    // camera toggle is the user's explicit action and the peer only receives
    // the resulting video stream.
}

@Composable
private fun noticeLabel(notice: CallNotice): String = when (notice) {
    CallNotice.VIDEO_REQUEST_DECLINED -> stringResource(R.string.video_request_declined)
    CallNotice.VIDEO_REQUEST_TIMEOUT -> stringResource(R.string.video_request_timeout)
    CallNotice.VIDEO_FAILED -> stringResource(R.string.video_failed)
    CallNotice.CAMERA_DENIED -> stringResource(R.string.error_camera_denied)
    CallNotice.CAMERA_FAILED -> stringResource(R.string.video_failed)
}

/** Code-review 8: typed call-failure reason -> localized resource; a raw
 *  exception/API message never reaches the UI. */
@Composable
private fun callFailureReasonRes(reason: CallFailureReason): Int = when (reason) {
    CallFailureReason.GENERIC -> R.string.error_generic
    CallFailureReason.NETWORK -> R.string.error_network
    CallFailureReason.DEVICE_RESTRICTED -> R.string.call_device_restricted
    CallFailureReason.BUSY -> R.string.media_busy_peer
}

/** N.1A §19/§20 device side effects: proximity lock for audio+earpiece, and
 *  keep-screen-on for video. Keyed on the EFFECTIVE media mode so a transient
 *  Reconnecting state (during which media RTP can still be live) does NOT
 *  release the flags - only a real mode change or call end clears them. */
@Composable
private fun CallDeviceEffects(state: CallUiState) {
    val context = LocalContext.current
    val activity = remember(context) { context as? Activity }
    val effective = when (state) {
        is CallUiState.Connected -> state
        is CallUiState.Reconnecting -> state.since as? CallUiState.Connected
        else -> null
    }
    val keepScreenOn = effective?.videoMode == VideoMode.VIDEO
    val useProximity = effective != null &&
        effective.videoMode == VideoMode.AUDIO &&
        effective.audioRoute == AudioRouteKind.EARPIECE
    DisposableEffect(keepScreenOn, useProximity) {
        if (keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (useProximity) acquireProximityLock(activity)
        onDispose {
            if (keepScreenOn) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            releaseProximityLock()
        }
    }
}

@Suppress("DEPRECATION")
private fun acquireProximityLock(activity: Activity?) {
    releaseProximityLock()
    val pm = activity?.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
    proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "messenger:proximity").also { lock ->
        runCatching { lock.acquire(30 * 60 * 1000L) }
    }
}

private fun releaseProximityLock() {
    proximityLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
    proximityLock = null
}

/** Shared stationary wallpaper behind the in-call identity. */
@Composable
private fun CallAtmosphere() {
    Box(Modifier.fillMaxSize().cocoConversationBackground(LocalCocoColors.current))
}

/** Compact status pill (Phase 4 §16): glassSecondary + outlineSubtle +
 *  secondaryText, with an optional accentSoft spinner or warning tint. */
@Composable
internal fun CallStatusPill(text: String, showSpinner: Boolean = false, warning: Boolean = false) {
    val colors = LocalCocoColors.current
    Surface(
        shape = CocoShapes.pill,
        color = colors.glassSecondary,
        border = BorderStroke(1.dp, colors.outlineSubtle),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = CocoSpacing.lg, vertical = CocoSpacing.xs),
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = colors.accentSoft,
                )
                Spacer(modifier = Modifier.size(CocoSpacing.sm))
            }
            Text(
                text = text,
                style = CocoTextRoles.status,
                color = if (warning) colors.warning else colors.secondaryText,
                maxLines = 1,
            )
        }
    }
}

/** Compact in-app call surface (Этап N.1 §36): shown while a Connected audio
 *  call is minimized - never a second call-state owner, it only observes
 *  [com.messenger.app.domain.call.CallController] via the same CallOverlay. */
@Composable
private fun CompactCallBar(name: String, durationMs: Long?, isVideo: Boolean, onClick: () -> Unit, status: String? = null) {
    ActiveMediaReturnStrip(name, stringResource(R.string.media_return_status, status ?: durationMs?.let { callDuration(it) } ?: stringResource(R.string.call_connecting)),
        if (isVideo) CocoIcons.VideoCall else CocoIcons.AudioCall, LocalCocoColors.current.categoryMint, onClick, "call_return_strip")
}

/** Same measured banner for private calls and rooms. Timer work stays inside this composition. */
@Composable fun ActiveMediaReturnStrip(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color, onReturn: () -> Unit, tag: String, modifier: Modifier = Modifier,
    onHeight: (androidx.compose.ui.unit.Dp) -> Unit = {}) {
    val c = LocalCocoColors.current
    val density = LocalDensity.current
    Surface(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(8.dp).heightIn(min = 48.dp)
        .onSizeChanged { onHeight(with(density) { it.height.toDp() } + 16.dp) },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = if (c.isMonochrome) c.surfaceVariant.copy(alpha = .94f) else Color(0xF0151E2B), contentColor = c.primaryText,
        border = BorderStroke(.5.dp, if (c.isMonochrome) c.outlineSubtle else Color(0x66303B50))) {
        Row(Modifier.testTag(tag).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onReturn).padding(horizontal = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = iconTint)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = c.mutedText)
            }
            Icon(CocoIcons.CollapseCall, null, Modifier.size(20.dp).rotate(180f))
        }
    }
}

@Composable fun CallReturnStrip(state: CallUiState, onReturn: () -> Unit) {
    val call = when (state) {
        is CallUiState.OutgoingRinging -> state.call
        is CallUiState.Connecting -> state.call
        is CallUiState.Connected -> state.call
        is CallUiState.Reconnecting -> state.call
        else -> return
    }
    val presentation = callIslandPresentation(state, onReturn) ?: return
    CompactCallBar(call.otherDisplayName, presentation.durationMs, presentation.isVideo, onReturn, presentation.status)
}

data class CallIslandPresentation(val durationMs: Long?, val isVideo: Boolean, val onReturn: () -> Unit, val status: String? = null)

@Composable
fun callIslandPresentation(state: CallUiState, onReturn: () -> Unit): CallIslandPresentation? = when (state) {
    is CallUiState.OutgoingRinging -> CallIslandPresentation(null, state.call.mediaKind == CallMediaKind.VIDEO,
        onReturn, stringResource(R.string.call_calling))
    is CallUiState.Connecting -> CallIslandPresentation(null, state.call.mediaKind == CallMediaKind.VIDEO,
        onReturn, stringResource(R.string.call_connecting))
    is CallUiState.Connected -> CallIslandPresentation(state.connectedAtElapsedRealtimeMs, state.videoMode == VideoMode.VIDEO, onReturn)
    is CallUiState.Reconnecting -> CallIslandPresentation(null, CallPipPolicy.videoState(state) != null,
        onReturn, stringResource(R.string.call_reconnecting))
    else -> null
}
// Keyboard insets can outlive the IME when a call wakes a locked phone.
// Only physical system UI and the display cutout constrain call controls.
@Composable
internal fun Modifier.callSafePadding(): Modifier =
    windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))

val LocalCallIsland = androidx.compose.runtime.staticCompositionLocalOf<CallIslandPresentation?> { null }

@Composable
fun CallIslandControl(durationMs: Long?, isVideo: Boolean, onClick: () -> Unit, status: String? = null) {
        Surface(
            color = Color(0xFF16754A),
            shape = CocoShapes.pill,
            onClick = onClick,
        ) {
            Row(
                modifier = Modifier
                    .height(28.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isVideo) CocoIcons.VideoCall else CocoIcons.AudioCall,
                    contentDescription = stringResource(if (isVideo) R.string.call_video_call else R.string.call_audio),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.size(CocoSpacing.sm))
                Text(text = status ?: durationMs?.let { callDuration(it) } ?: stringResource(R.string.call_connecting),
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
}

@Composable
fun CallMinimizeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCocoColors.current
    Surface(onClick = onClick, modifier = modifier.size(44.dp), shape = CocoShapes.pill, color = colors.glassFloating) {
        Box(contentAlignment = Alignment.Center) {
            Icon(CocoIcons.CollapseCall, contentDescription = stringResource(R.string.call_minimize), tint = colors.primaryText)
        }
    }
}

/** Restricted to a header strip; never intercept the movable camera or the controls. */
internal fun Modifier.callMinimizeSwipe(owner: Any?, onMinimize: () -> Unit): Modifier = composed {
    val density = LocalDensity.current.density
    val currentMinimize by rememberUpdatedState(onMinimize)
    pointerInput(owner, density) {
        var travel = Offset.Zero
        detectDragGestures(
            onDragStart = { travel = Offset.Zero },
            onDragCancel = { travel = Offset.Zero },
            onDragEnd = {
                if (CallMinimizePolicy.isDownwardSwipe(travel.x / density, travel.y / density)) currentMinimize()
                travel = Offset.Zero
            },
            onDrag = { change, amount -> travel += amount; change.consume() },
        )
    }
}

@Composable
private fun CompactIncomingCall(callerName: String, isVideo: Boolean, onExpand: () -> Unit, onAccept: () -> Unit, onReject: () -> Unit) {
    val colors = LocalCocoColors.current
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Surface(onClick = onExpand, shape = CocoShapes.radiusCard, color = colors.glassFloating,
            modifier = Modifier.fillMaxWidth().padding(12.dp), shadowElevation = 6.dp) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(callerName, color = colors.primaryText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (isVideo) R.string.call_incoming_video else R.string.call_incoming_audio),
                        color = colors.secondaryText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(onClick = onReject, shape = CircleShape, color = colors.danger, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(CocoIcons.Close, stringResource(R.string.call_decline), tint = Color.White) }
                }
                Spacer(Modifier.width(12.dp))
                Surface(onClick = onAccept, shape = CircleShape, color = Color(0xFF16865C), modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(CocoIcons.AudioCall, stringResource(R.string.call_accept), tint = Color.White) }
                }
            }
        }
    }
}

@Composable
fun IncomingCallScreen(callerName: String, isVideo: Boolean, onAccept: () -> Unit, onReject: () -> Unit) = CallTheme {
    val colors = LocalCocoColors.current
    val config = LocalConfiguration.current
    val avatarSize = CallLayoutPolicy.avatarSize(config.screenHeightDp.toFloat(), isVideo = false).dp
    Surface(modifier = Modifier.fillMaxSize(), color = colors.callBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            CallAtmosphere()
            Column(
                modifier = Modifier.fillMaxSize().callSafePadding().padding(CocoSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CallScrollableIdentity {
                CallAvatar(name = callerName, size = avatarSize)
                Spacer(modifier = Modifier.height(CocoSpacing.xl))
                Text(
                    text = callerName,
                    style = CocoTextRoles.screenTitle,
                    color = colors.primaryText,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = CallLayoutPolicy.CALL_NAME_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(CocoSpacing.md))
                CallStatusPill(text = stringResource(if (isVideo) R.string.call_incoming_video else R.string.call_incoming_audio))
                }
                // N.1A §2: Accept/Decline are large and in the bottom third.
                Row(horizontalArrangement = Arrangement.spacedBy(CocoSpacing.xxxl), modifier = Modifier.padding(bottom = CocoSpacing.xxxl)) {
                    CallActionButton(
                        icon = CocoIcons.EndCall,
                        label = stringResource(R.string.call_decline),
                        tint = colors.onDanger,
                        background = colors.danger,
                        endCall = true,
                        testTag = "call-decline",
                        onClick = onReject,
                    )
                    CallActionButton(
                        icon = CocoIcons.AudioCall,
                        label = stringResource(R.string.call_accept),
                        tint = colors.onAccent,
                        background = colors.success,
                        testTag = "call-accept",
                        onClick = onAccept,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActiveCallScreen(
    title: String,
    name: String,
    durationMs: Long?,
    isMuted: Boolean = false,
    currentRouteKind: AudioRouteKind = AudioRouteKind.EARPIECE,
    availableRoutes: List<AudioRoute> = emptyList(),
    requestingVideo: Boolean = false,
    showSpinner: Boolean = false,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit = {},
    onSelectRoute: (AudioRoute) -> Unit = {},
    onRequestVideo: (() -> Unit)? = null,
    onMinimize: (() -> Unit)? = null,
    callId: String? = null,
) = CallTheme {
    val colors = LocalCocoColors.current
    val config = LocalConfiguration.current
    val avatarSize = CallLayoutPolicy.avatarSize(config.screenHeightDp.toFloat(), isVideo = false).dp
    Surface(modifier = Modifier.fillMaxSize(), color = colors.callBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            CallAtmosphere()
            BoxWithConstraints(Modifier.fillMaxSize().callSafePadding().padding(horizontal = CocoSpacing.xl)) {
            val controlCount = if (onRequestVideo == null) 3 else 4
            val controlSize = CallLayoutPolicy.controlSize(maxWidth.value, controlCount).dp
            val controlSpacing = CallLayoutPolicy.controlSpacing(maxWidth.value, controlCount).dp
            val trayWidth = CallLayoutPolicy.trayWidth(maxWidth.value).dp
            val trayPadding = CallLayoutPolicy.trayPadding(maxWidth.value).dp
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (onMinimize != null) {
                    Row(modifier = Modifier.fillMaxWidth().callMinimizeSwipe(callId, onMinimize), horizontalArrangement = Arrangement.Start) {
                        CallMinimizeButton(onMinimize)
                    }
                }
                CallScrollableIdentity {
                CallAvatar(name = name, size = avatarSize)
                Spacer(modifier = Modifier.height(CocoSpacing.xl))
                Text(
                    text = name,
                    style = CocoTextRoles.screenTitle,
                    color = colors.primaryText,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = CallLayoutPolicy.CALL_NAME_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(CocoSpacing.md))
                CallStatusPill(text = title, showSpinner = showSpinner)
                if (durationMs != null) {
                    Spacer(modifier = Modifier.height(CocoSpacing.md))
                    Text(text = callDuration(durationMs), style = CocoTextRoles.callDuration, color = colors.primaryText)
                }
                }

                // Compact icon controls retain their full accessibility descriptions.
                // Audio order: Mute · Speaker · Video-upgrade · End.
                Surface(
                    shape = CocoShapes.radiusCallControl,
                    color = Color.Transparent,
                    modifier = Modifier.width(trayWidth).testTag("call-controls-tray"),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(controlSpacing, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(CocoSpacing.md),
                        modifier = Modifier.padding(horizontal = trayPadding, vertical = CocoSpacing.md),
                    ) {
                        CallAudioOutputButton(
                            currentKind = currentRouteKind,
                            availableRoutes = availableRoutes,
                            onSelectRoute = onSelectRoute,
                            tint = colors.primaryText,
                            background = colors.glassPrimary,
                            labelColor = colors.secondaryText,
                            size = controlSize,
                            showLabel = false,
                        )
                        CallActionButton(
                            icon = if (isMuted) CocoIcons.MicOff else CocoIcons.Mic,
                            label = stringResource(if (isMuted) R.string.call_unmute else R.string.call_mute),
                            caption = stringResource(R.string.call_caption_mute),
                            tint = if (!isMuted) colors.onAccent else colors.primaryText,
                            background = if (!isMuted) colors.accent else colors.glassPrimary,
                            active = !isMuted,
                            size = controlSize,
                            onClick = onToggleMute,
                            testTag = "call-mute",
                        )
                        if (onRequestVideo != null) CallActionButton(
                            icon = CocoIcons.VideoCall,
                            label = stringResource(R.string.call_video),
                            caption = stringResource(R.string.call_caption_video),
                            tint = colors.primaryText,
                            background = colors.glassPrimary,
                            size = controlSize,
                            onClick = { onRequestVideo?.invoke() },
                            enabled = !requestingVideo,
                            testTag = "call-video",
                        )
                        CallActionButton(
                            icon = CocoIcons.EndCall,
                            label = stringResource(R.string.call_end),
                            caption = stringResource(R.string.call_caption_end),
                            tint = colors.onDanger,
                            background = colors.danger,
                            endCall = true,
                            size = controlSize,
                            onClick = onHangup,
                            testTag = "call-end",
                        )
                    }
                }
                Spacer(modifier = Modifier.height(CallControlsBottomGap))
            }
            }
        }
    }
}

/** Scroll only identity/status when height or text scale is constrained.
 *  The sibling Accept/Decline and active controls remain reachable onscreen. */
@Composable
private fun ColumnScope.CallScrollableIdentity(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight).testTag("call-identity-scroll"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/** Localized fallback label for a generic built-in route kind. The data layer
 *  keeps a non-localized default ([CallAudioPolicy.defaultLabel]); the UI swaps
 *  it for the localized string so the sheet reads correctly in every locale. */
private fun audioRouteLabelRes(kind: AudioRouteKind): Int = when (kind) {
    AudioRouteKind.EARPIECE -> R.string.call_earpiece
    AudioRouteKind.SPEAKER -> R.string.call_speaker
    AudioRouteKind.BLUETOOTH -> R.string.call_bluetooth
    AudioRouteKind.WIRED_HEADSET -> R.string.call_wired_headset
}

/** Icon for a given audio-output route kind (speaker / earpiece / Bluetooth /
 *  wired headset), so both the button and the picker sheet stay consistent. */
private fun audioRouteIcon(kind: AudioRouteKind): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    AudioRouteKind.SPEAKER -> CocoIcons.Speaker
    AudioRouteKind.EARPIECE -> CocoIcons.Earpiece
    AudioRouteKind.BLUETOOTH -> CocoIcons.Bluetooth
    AudioRouteKind.WIRED_HEADSET -> CocoIcons.Headset
}

/** Current audio output label: the real device name when available, otherwise
 *  the localized fallback for the kind. Never blank. */
@Composable
private fun currentRouteLabel(kind: AudioRouteKind, routes: List<AudioRoute>): String {
    val route = routes.firstOrNull { it.kind == kind }
    return if (route != null && route.label != CallAudioPolicy.defaultLabel(route.kind)) {
        route.label
    } else {
        stringResource(audioRouteLabelRes(kind))
    }
}

/** Audio-output control (N.1 §2): the icon and label always show the CURRENT
 *  output, and tapping opens the route picker. Shared by the audio and video
 *  call screens so both behave identically. */
@Composable
fun CallAudioOutputButton(
    currentKind: AudioRouteKind,
    availableRoutes: List<AudioRoute>,
    onSelectRoute: (AudioRoute) -> Unit,
    tint: Color,
    background: Color,
    labelColor: Color,
    size: Dp = 64.dp,
    showLabel: Boolean = true,
    caption: String? = null,
) {
    val colors = LocalCocoColors.current
    var routeSheetOpen by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = if (currentKind == AudioRouteKind.SPEAKER) Color.White else Color.White.copy(alpha = 0.18f),
            modifier = Modifier.testTag("call-output"),
            onClick = {
                val target = if (currentKind == AudioRouteKind.SPEAKER) {
                    availableRoutes.firstOrNull { it.kind == AudioRouteKind.BLUETOOTH || it.kind == AudioRouteKind.WIRED_HEADSET }
                        ?: availableRoutes.firstOrNull { it.kind == AudioRouteKind.EARPIECE }
                } else availableRoutes.firstOrNull { it.kind == AudioRouteKind.SPEAKER }
                target?.let(onSelectRoute)
            },
        ) {
            Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = CocoIcons.Speaker,
                    contentDescription = stringResource(R.string.call_speaker),
                    tint = if (currentKind == AudioRouteKind.SPEAKER) colors.callBackground else Color.White,
                    modifier = Modifier.size(size * 0.42f),
                )
            }
        }
        if (showLabel) {
            Spacer(modifier = Modifier.height(CocoSpacing.xs))
            Text(
                text = stringResource(R.string.call_speaker),
                style = CocoTextRoles.caption,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(size),
            )
        }
    }
    if (routeSheetOpen) {
        AudioRouteSheet(
            routes = availableRoutes,
            currentKind = currentKind,
            onSelect = { route -> onSelectRoute(route); routeSheetOpen = false },
            onDismiss = { routeSheetOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioRouteSheet(
    routes: List<AudioRoute>,
    currentKind: AudioRouteKind,
    onSelect: (AudioRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCocoColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = CocoSpacing.xxxl)) {
            Text(
                text = stringResource(R.string.call_audio_output),
                style = MaterialTheme.typography.titleMedium,
                color = colors.primaryText,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = CocoSpacing.xl, vertical = CocoSpacing.lg),
            )
            routes.forEach { route ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(route) }.padding(horizontal = CocoSpacing.xl, vertical = CocoSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = audioRouteIcon(route.kind),
                        contentDescription = null,
                        tint = colors.primaryText,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.size(CocoSpacing.lg))
                    Text(
                        text = if (route.label == CallAudioPolicy.defaultLabel(route.kind)) {
                            stringResource(audioRouteLabelRes(route.kind))
                        } else {
                            route.label
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.primaryText,
                        modifier = Modifier.weight(1f),
                    )
                    if (route.kind == currentKind) {
                        Icon(imageVector = CocoIcons.Eye, contentDescription = stringResource(R.string.security_verified), tint = colors.accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoUpgradeRequestDialog(name: String, onAccept: () -> Unit, onReject: () -> Unit) {
    val colors = LocalCocoColors.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onReject,
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.call_accept), color = colors.accent) }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text(stringResource(R.string.call_not_now), color = colors.secondaryText) }
        },
        title = { Text(stringResource(R.string.call_video)) },
        text = { Text(stringResource(R.string.call_video_request, name)) },
    )
}

@Composable
private fun CallNoticeBanner(text: String) {
    val colors = LocalCocoColors.current
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            shape = CocoShapes.pill,
            color = colors.glassSecondary,
            border = BorderStroke(1.dp, colors.outlineSubtle),
            // Code-review 11: sit ABOVE the entire bottom control cluster
            // (Mute/Camera/Audio-route/End), never over it.
            modifier = Modifier.align(Alignment.BottomCenter).callSafePadding().padding(bottom = CallNoticeBottomOffset),
        ) {
            Text(
                text = text,
                color = colors.secondaryText,
                style = CocoTextRoles.bodySecondary,
                modifier = Modifier.padding(horizontal = CocoSpacing.xl, vertical = CocoSpacing.md),
            )
        }
    }
}

@Composable
fun EndedCallScreen(reason: CallEndReason, message: String? = null, onDismiss: () -> Unit) = CallTheme {
    val colors = LocalCocoColors.current
    Surface(modifier = Modifier.fillMaxSize(), color = colors.callBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            CallAtmosphere()
            Column(
                modifier = Modifier.fillMaxSize().callSafePadding().padding(CocoSpacing.xxxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = reasonLabel(reason),
                    style = CocoTextRoles.screenTitle,
                    color = colors.primaryText,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                message?.let {
                    Spacer(modifier = Modifier.height(CocoSpacing.sm))
                    Text(text = it, style = CocoTextRoles.bodySecondary, color = colors.secondaryText, textAlign = TextAlign.Center)
                }
                Spacer(modifier = Modifier.height(CocoSpacing.xxl))
                Surface(
                    shape = CocoShapes.pill,
                    color = colors.glassPrimary,
                    border = BorderStroke(1.dp, colors.outlineSecondary),
                    onClick = onDismiss,
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = colors.primaryText,
                        style = CocoTextRoles.buttonLabel,
                        modifier = Modifier.padding(horizontal = CocoSpacing.xxxl, vertical = CocoSpacing.md),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CallAvatar(name: String, size: Dp = 96.dp) {
    val colors = LocalCocoColors.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val state by app.callController.state.collectAsState()
    val chats by app.chatRepository.chats.collectAsState(initial = emptyList())
    val peerId = when (val call = state) {
        is CallUiState.IncomingRinging -> call.call.otherUserId
        is CallUiState.OutgoingRinging -> call.call.otherUserId
        is CallUiState.Connecting -> call.call.otherUserId
        is CallUiState.Connected -> call.call.otherUserId
        is CallUiState.Reconnecting -> call.call.otherUserId
        else -> null
    }
    val peer = chats.firstOrNull { it.otherUser?.id == peerId }?.otherUser
    com.messenger.app.ui.components.UserAvatar(
        userId = peerId.orEmpty(), avatarVersion = peer?.avatarVersion ?: 0,
        hasAvatar = peer?.hasAvatar == true, name = name, size = size,
        color = colors.accent, contentColor = colors.onAccent,
    )
}

@Composable
internal fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    background: Color,
    onClick: () -> Unit,
    active: Boolean = false,
    endCall: Boolean = false,
    size: Dp = 64.dp,
    showLabel: Boolean = false,
    caption: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val colors = LocalCocoColors.current
    val presentation = if (interactive) modifier else modifier.clearAndSetSemantics { }
    Column(presentation.alpha(if (enabled) 1f else 0.45f), horizontalAlignment = Alignment.CenterHorizontally) {
        val circleContent: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label.takeIf { interactive },
                    tint = if (active && !endCall) colors.callBackground else tint,
                    modifier = Modifier.size(size * 0.42f),
                )
            }
        }
        val circleColor = if (endCall) colors.danger else if (active) Color.White else background
        if (interactive) {
            Surface(shape = CircleShape, color = circleColor, onClick = onClick, enabled = enabled,
                modifier = if (testTag == null) Modifier else Modifier.testTag(testTag), content = circleContent)
        } else {
            // Retain the measured control while fading. No clickable surface,
            // content description or action tag can survive camera OFF, even during exit.
            Surface(shape = CircleShape, color = circleColor, content = circleContent)
        }
        if (showLabel) {
            Spacer(modifier = Modifier.height(CocoSpacing.xs))
            Text(
                text = caption ?: label,
                style = CocoTextRoles.caption,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(size),
            )
        }
    }
}

internal fun formatCallDuration(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

@Composable
internal fun callDuration(startedAtElapsedMs: Long): String {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtElapsedMs) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val totalSeconds = ((now - startedAtElapsedMs) / 1000).coerceAtLeast(0)
    return formatCallDuration(totalSeconds)
}

@Composable
private fun reasonLabel(reason: CallEndReason): String = stringResource(
    when (reason) {
        CallEndReason.REJECTED -> R.string.call_declined
        CallEndReason.CANCELLED -> R.string.call_ended
        CallEndReason.BUSY -> R.string.call_busy
        CallEndReason.MISSED -> R.string.call_no_answer
        CallEndReason.HANGUP -> R.string.call_ended
        CallEndReason.FAILED -> R.string.call_connection_failed
        CallEndReason.TIMEOUT -> R.string.call_no_answer
        CallEndReason.LOCAL_ERROR -> R.string.call_connection_failed
    },
)
