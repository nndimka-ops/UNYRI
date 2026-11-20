package com.messenger.app.ui.call

import com.messenger.app.ui.security.rejectObscuredTouches

import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.zIndex
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
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
import com.messenger.app.domain.call.CallRingIndicator
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import com.messenger.app.OrientationLease

/** Process-wide proximity lock (one active call at a time). */
private var proximityLock: PowerManager.WakeLock? = null

/** Code-review 11: bottom offset keeping the transient call notice above the
 *  whole control cluster instead of the old magic 90dp. */
private val CallNoticeBottomOffset = 220.dp

/** Same outer dock gap for audio/video; native system bars remain owned by callSafePadding. */
internal val CallControlsBottomGap = 32.dp

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
    onIncomingCallSurfaceVisible: (String, Boolean) -> Unit = { _, _ -> },
) {
    val controllerState by viewModel.state.collectAsState()
    val startingOutgoing by viewModel.startingOutgoing.collectAsState()
    val actualState = if (controllerState is CallUiState.Idle && startingOutgoing != null) CallUiState.OutgoingRinging(startingOutgoing!!) else controllerState
    val configuration = LocalConfiguration.current
    val portrait = configuration.screenWidthDp <= configuration.screenHeightDp
    val activity = LocalContext.current as? android.app.Activity
    val idle = actualState is CallUiState.Idle
    val leaseHeldAtEnd = remember(idle) { idle && activity?.let { OrientationLease.holds(it, OrientationLease.Call) } == true }
    val requestAtEnd = remember(idle) { activity?.requestedOrientation }
    var closeWaitFinished by remember(idle) { mutableStateOf(false) }
    val portraitNow by rememberUpdatedState(portrait)
    var lastPresentedState by remember { mutableStateOf<CallUiState>(CallUiState.Idle) }
    val closingToPortrait = idle && leaseHeldAtEnd && !closeWaitFinished && !portrait &&
        activity?.isInMultiWindowMode != true && lastPresentedState !is CallUiState.Idle
    // Keep the opaque call surface until the portrait configuration has landed on close.
    val state = if (closingToPortrait) lastPresentedState else actualState
    androidx.compose.runtime.SideEffect { if (!idle) lastPresentedState = actualState }
    var returningToPortrait by remember { mutableStateOf(false) }
    var waitForMinimizePortrait by remember { mutableStateOf(false) }
    var minimizingCallId by remember { mutableStateOf<String?>(null) }
    var afterPortrait by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ringIndicator by viewModel.ringIndicator.collectAsState()
    val view = LocalView.current
    val morph = rememberCallMorph()
    val haptic = LocalHapticFeedback.current
    val presentation = viewModel.presentation
    var lastAnnouncedPhase by presentation.lastAnnouncedPhase
    var rangHaptic by presentation.rangHaptic
    val announcement = ringingAnnouncement(ringIndicator?.phase)
    val answeredAtMs = remember(ringIndicator?.phase) { if (ringIndicator?.phase == CallRingIndicator.Phase.ANSWERED) android.os.SystemClock.elapsedRealtime() else null }
    val context = LocalContext.current
    var pendingAcceptKind by remember { mutableStateOf<CallMediaKind?>(null) }
    var pendingAcceptCallId by remember { mutableStateOf<String?>(null) }
    var pendingVideoCallId by remember { mutableStateOf<String?>(null) }
    val cameraAction = rememberCallCameraAction(viewModel)
    var expanded by presentation.expanded
    var expandedCallId by presentation.callId
    val miniMemory = presentation.mini
    var miniHidden by presentation.miniHidden
    val durationMemory = presentation.duration
    val endedSeconds = durationMemory.observe(state, android.os.SystemClock.elapsedRealtime())
    val terminalControls = rememberCallTerminalControls(state)
    val currentCallId = when (val s = state) {
        is CallUiState.IncomingRinging -> s.call.callId
        is CallUiState.OutgoingRinging -> s.call.callId
        is CallUiState.Connecting -> s.call.callId
        is CallUiState.Connected -> s.call.callId
        is CallUiState.Reconnecting -> s.call.callId
        else -> null
    }
    val incomingId = (state as? CallUiState.IncomingRinging)?.call?.callId
    val surfaceVisibility by rememberUpdatedState(onIncomingCallSurfaceVisible)
    val surfaceLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, incomingId, surfaceLifecycle) {
        if (incomingId == null) return@DisposableEffect onDispose { }
        val power = view.context.getSystemService(PowerManager::class.java)
        fun updateVisibility() = surfaceVisibility(incomingId, view.isShown && view.hasWindowFocus() &&
            power.isInteractive && surfaceLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        val layout = android.view.ViewTreeObserver.OnGlobalLayoutListener { updateVisibility() }
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { updateVisibility() }
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, _ -> updateVisibility() }
        view.viewTreeObserver.addOnGlobalLayoutListener(layout)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        surfaceLifecycle.addObserver(lifecycleObserver)
        view.post { updateVisibility() }
        onDispose {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnGlobalLayoutListener(layout)
                view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            }
            surfaceLifecycle.removeObserver(lifecycleObserver)
            surfaceVisibility(incomingId, false)
        }
    }
    val showExpanded = closingToPortrait || (expanded && expandedCallId == currentCallId) ||
        (currentCallId != null && currentCallId != expandedCallId && state !is CallUiState.IncomingRinging) ||
        deviceLocked || pictureInPicture
    var wasExpanded by remember { mutableStateOf(false) }
    val terminal = actualState is CallUiState.Ended || actualState is CallUiState.Failed
    SideEffect { if (!idle && !terminal) wasExpanded = showExpanded && !pictureInPicture &&
        !returningToPortrait && morph?.isMorphing != true }
    val fullCall = CallPortraitReturnPolicy.fullScreen(state !is CallUiState.Idle, terminal, showExpanded, wasExpanded)
    CallScreenRotation(fullCall && actualState !is CallUiState.Idle && !pictureInPicture && !returningToPortrait && morph?.isMorphing != true)
    LaunchedEffect(idle) {
        if (idle) {
            val requested = activity?.requestedOrientation
            if (CallPortraitReturnPolicy.shouldWait(leaseHeldAtEnd, requested != requestAtEnd,
                    requested == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    portraitNow, activity?.isInMultiWindowMode == true)) {
                CallPortraitReturnPolicy.await { snapshotFlow { portraitNow }.first { it } }
            }
            closeWaitFinished = true
        }
    }
    LaunchedEffect(returningToPortrait) {
        if (returningToPortrait) {
            // One bounded wait; ignored orientation requests must never strand a control.
            if (waitForMinimizePortrait) CallPortraitReturnPolicy.await {
                snapshotFlow { portraitNow }.first { it }
                androidx.compose.runtime.withFrameNanos { }
                androidx.compose.runtime.withFrameNanos { }
            }
            afterPortrait?.invoke()
            afterPortrait = null
        }
    }
    LaunchedEffect(showExpanded, CallMinimizePolicy.callId(actualState)) {
        if (!showExpanded || (returningToPortrait && !CallMinimizePolicy.canComplete(minimizingCallId, actualState))) {
            returningToPortrait = false
            afterPortrait = null
            minimizingCallId = null
        }
    }
    LaunchedEffect(ringIndicator?.phase) {
        val phase = ringIndicator?.phase
        if (phase == null) { lastAnnouncedPhase = null; rangHaptic = false }
        else if (phase != lastAnnouncedPhase) {
            val visible = showExpanded && !pictureInPicture && view.isShown && view.hasWindowFocus()
            if (phase == CallRingIndicator.Phase.RINGING && !rangHaptic) {
                if (visible) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                rangHaptic = true
            }
            if (visible && announcement != null) view.announceForAccessibility(announcement)
            // Consume hidden transitions too; returning to a call never plays
            // a delayed haptic or repeats an old accessibility announcement.
            lastAnnouncedPhase = phase
        }
    }
    LaunchedEffect(restoreGeneration) { if (restoreGeneration > 0) {
        val id = currentCallId
        val present = { if (CallMinimizePolicy.canComplete(id, viewModel.state.value)) expanded = true }
        if (morph != null) morph.expand(systemEntry = pictureInPicture, present = present) else present()
    } }
    androidx.compose.runtime.SideEffect {
        onPrivateContentVisible(!pictureInPicture && (state is CallUiState.Idle ||
            ((CallMinimizePolicy.callId(state) != null || state is CallUiState.IncomingRinging) && !showExpanded)))
    }
    fun minimize() {
        if (returningToPortrait) return
        val requestedCall = expandedCallId
        onRequestPrivateContent {
            if (CallMinimizePolicy.canComplete(requestedCall, viewModel.state.value)) {
                val dismiss = {
                    miniMemory.minimized(requestedCall!!)
                    miniHidden = false
                    expanded = false
                }
                afterPortrait = {
                    if (morph != null) morph.collapse(valid = { CallMinimizePolicy.canComplete(requestedCall, viewModel.state.value) }, dismiss = dismiss)
                    else if (CallMinimizePolicy.canComplete(requestedCall, viewModel.state.value)) dismiss()
                }
                minimizingCallId = requestedCall
                val held = activity?.let { OrientationLease.holds(it, OrientationLease.Call) } == true
                val changed = activity?.let { OrientationLease.release(it, OrientationLease.Call) } == true
                waitForMinimizePortrait = CallPortraitReturnPolicy.shouldWait(held, changed,
                    activity?.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    portrait, activity?.isInMultiWindowMode == true)
                returningToPortrait = true
            }
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
    BackHandler(enabled = state is CallUiState.Ended || state is CallUiState.Failed) { viewModel.acknowledgeEnded() }
    // N.1A §19/§20: proximity (audio+earpiece) and keep-screen-on (video).
    CallDeviceEffects(state)

    val videoState = CallPresentationPolicy.connected(state)?.takeIf { it.videoMode == VideoMode.VIDEO }
    androidx.compose.runtime.CompositionLocalProvider(LocalCallChrome provides CallChromeSnapshot(state, ringIndicator?.phase, viewModel.callStats, endedSeconds, viewModel.quality, viewModel.audioRoutes, viewModel.voiceLevels, viewModel.comparisonCode)) {
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
            title = ringingTitle(ringIndicator?.phase),
            subtitle = if (ringIndicator?.phase == CallRingIndicator.Phase.NOT_ONLINE) stringResource(R.string.call_waiting_for_peer_online) else null,
            ringPhase = ringIndicator?.phase ?: CallRingIndicator.Phase.SENDING,
            ringbackStartedAtMs = viewModel.ringbackStartedAtElapsedRealtimeMs,
            name = s.call.otherDisplayName,
            durationMs = null,
            isMuted = s.isMuted,
            currentRouteKind = s.audioRoute,
            availableRoutes = viewModel.availableAudioRoutes(),
            onToggleMute = viewModel::toggleMute,
            onSelectRoute = viewModel::setAudioRoute,
            onHangup = if (startingOutgoing != null && controllerState is CallUiState.Idle) viewModel::cancelStartingOutgoing else viewModel::cancelOutgoing,
            onMinimize = if (controllerState is CallUiState.Idle) null else { { minimize() } },
            controlsAvailable = controllerState !is CallUiState.Idle,
            videoControls = if (controllerState !is CallUiState.Idle) CallPresentationPolicy.videoControls(s) else null,
            onToggleCamera = { cameraAction(s.call.callId) },
        ) else if (showCompactBar) CompactCallBar(s.call.otherDisplayName, null,
            s.call.mediaKind == CallMediaKind.VIDEO, { expanded = true }, ringingTitle(ringIndicator?.phase))
        is CallUiState.Connecting -> if (showExpanded) ActiveCallScreen(
            callId = s.call.callId,
            title = stringResource(R.string.call_connecting),
            name = s.call.otherDisplayName,
            durationMs = null,
            showSpinner = true,
            ringPhase = ringIndicator?.phase,
            answeredAtMs = answeredAtMs,
            isMuted = s.isMuted,
            currentRouteKind = s.audioRoute,
            availableRoutes = viewModel.availableAudioRoutes(),
            onToggleMute = viewModel::toggleMute,
            onSelectRoute = viewModel::setAudioRoute,
            onHangup = viewModel::hangup,
            onMinimize = { minimize() },
            videoControls = CallPresentationPolicy.videoControls(s),
            onToggleCamera = { cameraAction(s.call.callId) },
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
                    CallMiniVideoHost(state = s, expanded = showExpanded, hidden = miniHidden,
                        memory = miniMemory, onHide = { miniMemory.hide(s.call.callId); miniHidden = true },
                        onReturn = { if (CallMinimizePolicy.canComplete(s.call.callId, viewModel.state.value)) expanded = true }, onToggleMute = viewModel::toggleMute) { mini ->
                        VideoCallScreen(
                            state = if (pictureInPicture && state is CallUiState.Reconnecting) s.copy(remoteVideoState = com.messenger.app.domain.call.RemoteVideoState.WAITING) else s,
                            pictureInPicture = pictureInPicture,
                            availableRoutes = viewModel.availableAudioRoutes(),
                            viewModel = viewModel,
                            onMinimize = { minimize() },
                            ringPhase = ringIndicator?.phase,
                            answeredAtMs = answeredAtMs,
                            indicatorVisible = showExpanded && !pictureInPicture,
                            miniMode = mini,
                            reconnecting = state is CallUiState.Reconnecting && !pictureInPicture,
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
                        ringPhase = ringIndicator?.phase,
                        answeredAtMs = answeredAtMs,
                        isMuted = s.isMuted,
                        currentRouteKind = s.audioRoute,
                        availableRoutes = viewModel.availableAudioRoutes(),
                        requestingVideo = s.videoUpgrade is VideoUpgradeState.Requesting || s.videoUpgrade is VideoUpgradeState.Upgrading,
                        onHangup = viewModel::hangup,
                        onToggleMute = viewModel::toggleMute,
                        onSelectRoute = viewModel::setAudioRoute,
                        onRequestVideo = when (s.videoUpgrade) {
                            is VideoUpgradeState.Upgrading -> null
                            is VideoUpgradeState.Requesting -> viewModel::cancelVideoUpgrade
                            else -> ::requestVideoWithPermissionCheck
                        },
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
        is CallUiState.Ended -> {
            EndedCallScreen(reason = s.reason, ringPhase = ringIndicator?.phase, onDismiss = viewModel::acknowledgeEnded,
                retainedControls = terminalControls)
            LaunchedEffect(s) { delay(1_500); viewModel.acknowledgeEnded() }
        }
        is CallUiState.Failed -> EndedCallScreen(
            reason = if (s.reason == CallFailureReason.BUSY) CallEndReason.BUSY else CallEndReason.FAILED,
            message = stringResource(callFailureReasonRes(s.reason)),
            ringPhase = ringIndicator?.phase,
            onDismiss = viewModel::acknowledgeEnded,
            retainedControls = terminalControls,
        )
    }
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
    CallFailureReason.BUSY -> R.string.call_line_busy
    CallFailureReason.RATE_LIMITED -> R.string.call_rate_limited
    CallFailureReason.RELAY_UNAVAILABLE -> R.string.call_relay_unavailable
}

@Composable
internal fun ringingTitle(phase: CallRingIndicator.Phase?): String = stringResource(when (phase) {
    CallRingIndicator.Phase.RINGING -> R.string.call_ringing
    CallRingIndicator.Phase.NOT_ONLINE -> R.string.call_not_online_yet
    CallRingIndicator.Phase.ANSWERED -> R.string.call_connecting
    CallRingIndicator.Phase.BUSY -> R.string.call_line_busy
    CallRingIndicator.Phase.DECLINED -> R.string.call_declined
    CallRingIndicator.Phase.CANCELLED -> R.string.call_cancelled
    CallRingIndicator.Phase.NO_ANSWER -> R.string.call_no_answer
    CallRingIndicator.Phase.FAILED -> R.string.call_failed
    else -> R.string.call_calling
})

@Composable
private fun ringingAnnouncement(phase: CallRingIndicator.Phase?): String? = when (phase) {
    CallRingIndicator.Phase.NOT_ONLINE -> ringingTitle(phase) + ". " + stringResource(R.string.call_waiting_for_peer_online)
    CallRingIndicator.Phase.RINGING, CallRingIndicator.Phase.BUSY, CallRingIndicator.Phase.DECLINED,
    CallRingIndicator.Phase.NO_ANSWER, CallRingIndicator.Phase.FAILED -> ringingTitle(phase)
    else -> null
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
internal fun CallAtmosphere(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().callAtmosphere())
}

/** iOS CallScreen.UnyriCallAurora's initial frame. Cached until size changes;
 * no drift clock, frame callback or conversation-pattern renderer at rest. */
internal fun Modifier.callAtmosphere(): Modifier = drawWithCache {
    val radius = maxOf(size.width, size.height) * 1.1f / kotlin.math.sqrt(2f)
    fun glow(color: Color, opacity: Float, x: Float, y: Float) = Brush.radialGradient(
        0f to color.copy(alpha = opacity), .45f to color.copy(alpha = opacity * .35f),
        1f to Color.Transparent, center = Offset(size.width * x, size.height * y), radius = radius,
    )
    val teal = glow(Color(.20f, .79f, .91f), .30f, .15f, .20f)
    val purple = glow(Color(.65f, .51f, .93f), .28f, .85f, .70f)
    val blue = glow(Color(.24f, .36f, .85f), .20f, .50f, .95f)
    onDrawBehind {
        drawRect(Color.Black)
        drawRect(teal)
        drawRect(purple)
        drawRect(blue)
    }
}

/** Compact in-app call surface (Этап N.1 §36): shown while a Connected audio
 *  call is minimized - never a second call-state owner, it only observes
 *  [com.messenger.app.domain.call.CallController] via the same CallOverlay. */
@Composable
private fun CompactCallBar(name: String, durationMs: Long?, isVideo: Boolean, onClick: () -> Unit, status: String? = null,
    quality: kotlinx.coroutines.flow.StateFlow<CallQualityUiState>? = null) {
    ActiveMediaReturnStrip(name, stringResource(R.string.media_return_status, status ?: durationMs?.let { callDuration(it) } ?: stringResource(R.string.call_connecting)),
        if (isVideo) CocoIcons.VideoCall else CocoIcons.AudioCall, CallWhite, onClick, "call_return_strip", quality = quality ?: LocalCallChrome.current?.quality)
}

/** Same measured banner for private calls and rooms. Timer work stays inside this composition. */
@Composable fun ActiveMediaReturnStrip(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color, onReturn: () -> Unit, tag: String, modifier: Modifier = Modifier,
    onHeight: (androidx.compose.ui.unit.Dp) -> Unit = {}, quality: kotlinx.coroutines.flow.StateFlow<CallQualityUiState>? = null) {
    if (tag == "call_return_strip") {
        CallReturnBanner(title, subtitle, icon, onReturn, modifier, onHeight, quality)
        return
    }
    val c = LocalCocoColors.current
    val density = LocalDensity.current
    val morph = rememberCallMorph()
    Surface(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(8.dp).heightIn(min = 48.dp)
        .onSizeChanged { onHeight(with(density) { it.height.toDp() } + 16.dp) }
        .onGloballyPositioned { if (tag == "call_return_strip") { val b = it.boundsInWindow(); morph?.barFrame = android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()) } },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = if (tag == "call_return_strip") CallGlass else if (c.isMonochrome) c.surfaceVariant.copy(alpha = .94f) else Color(0xF0151E2B), contentColor = if (tag == "call_return_strip") CallWhite else c.primaryText,
        border = BorderStroke(.5.dp, if (c.isMonochrome) c.outlineSubtle else Color(0x66303B50))) {
        Row(Modifier.testTag(tag).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onReturn).padding(horizontal = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = iconTint)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = if (tag == "call_return_strip") CallWhite.copy(alpha = .75f) else c.mutedText)
            }
            if (quality != null) CallReturnQuality(quality)
            Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(20.dp))
        }
    }
}

/** One 48dp capsule + 4dp above/below, as ConversationReturnBanner on iOS.
 * The root consumes this row's status inset once for every destination. */
@Composable
internal fun CallReturnBanner(
    title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    onReturn: () -> Unit, modifier: Modifier = Modifier, onHeight: (Dp) -> Unit = {},
    quality: kotlinx.coroutines.flow.StateFlow<CallQualityUiState>? = null,
) {
    val density = LocalDensity.current
    val morph = rememberCallMorph()
    val returnLabel = stringResource(R.string.call_mini_return, title)
    Box(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
        Surface(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth()
                .heightIn(min = 48.dp)
                .onSizeChanged { onHeight(with(density) { it.height.toDp() } + 8.dp) }
                .onGloballyPositioned {
                    val b = it.boundsInWindow()
                    morph?.barFrame = android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                },
            shape = CocoShapes.pill, color = CallGlass, contentColor = CallWhite,
            border = BorderStroke(.5.dp, CallEdge),
        ) {
            Row(
                Modifier.testTag("call_return_strip")
                    .semantics(mergeDescendants = true) { contentDescription = returnLabel }
                    .clickable(role = Role.Button, onClick = onReturn)
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .035f), Color.Transparent)))
                    .heightIn(min = 48.dp).padding(start = 6.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(33.dp).background(CallWhite.copy(alpha = .20f), CircleShape))
                    Box(Modifier.size(30.dp).background(Color(0xFFDEDEDE), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(13.dp), tint = Color.Black)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(title, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (quality != null) CallReturnQuality(quality)
                        Text(subtitle, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal,
                            color = Color(0xFFB0B0B0), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"))
                    }
                }
                Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(17.dp), tint = Color(0xFFB0B0B0))
            }
        }
    }
}

@Composable fun CallReturnStrip(state: CallUiState, ringIndicator: CallRingIndicator? = null,
    quality: kotlinx.coroutines.flow.StateFlow<CallQualityUiState>? = null, onReturn: () -> Unit) {
    val call = when (state) {
        is CallUiState.OutgoingRinging -> state.call
        is CallUiState.Connecting -> state.call
        is CallUiState.Connected -> state.call
        is CallUiState.Reconnecting -> state.call
        else -> return
    }
    val presentation = callIslandPresentation(state, onReturn, ringIndicator) ?: return
    CompactCallBar(call.otherDisplayName, presentation.durationMs, presentation.isVideo, onReturn, presentation.status, quality)
}

data class CallIslandPresentation(val durationMs: Long?, val isVideo: Boolean, val onReturn: () -> Unit, val status: String? = null)

@Composable
fun callIslandPresentation(state: CallUiState, onReturn: () -> Unit, ringIndicator: CallRingIndicator? = null): CallIslandPresentation? = when (state) {
    is CallUiState.OutgoingRinging -> CallIslandPresentation(null, state.call.mediaKind == CallMediaKind.VIDEO,
        onReturn, ringingTitle(ringIndicator?.phase))
    is CallUiState.Connecting -> CallIslandPresentation(null, state.call.mediaKind == CallMediaKind.VIDEO,
        onReturn, stringResource(R.string.call_connecting))
    is CallUiState.Connected -> CallIslandPresentation(state.connectedAtElapsedRealtimeMs, state.videoMode == VideoMode.VIDEO, onReturn)
    is CallUiState.Reconnecting -> CallIslandPresentation(CallPresentationPolicy.connected(state)?.connectedAtElapsedRealtimeMs, CallPipPolicy.videoState(state) != null,
        onReturn, if (callDropShown(true)) stringResource(R.string.call_reconnecting) else null)
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
            color = CallGlass,
            border = BorderStroke(1.dp, CallEdge),
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
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = callReducedMotion()
    val scale = androidx.compose.animation.core.animateFloatAsState(if (pressed && !reduced) .86f else 1f,
        if (reduced) androidx.compose.animation.core.tween(120) else androidx.compose.animation.core.spring(dampingRatio = if (pressed) .7f else .52f), label = "callMinimizePress")
    Surface(onClick = onClick, interactionSource = interaction, modifier = modifier.size(44.dp).testTag("call-minimize")
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = if (reduced && pressed) .6f else 1f },
        border = BorderStroke(1.dp, CallEdge), shape = CocoShapes.pill, color = CallGlass) {
        Box(contentAlignment = Alignment.Center) {
            Icon(CocoIcons.CollapseCall, contentDescription = stringResource(R.string.call_minimize), tint = CallWhite)
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
                Surface(onClick = onAccept, shape = CircleShape, color = Color(0xFF16865C), modifier = Modifier.rejectObscuredTouches().size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(CocoIcons.AudioCall, stringResource(R.string.call_accept), tint = Color.White) }
                }
            }
        }
    }
}

@Composable
fun IncomingCallScreen(callerName: String, isVideo: Boolean, onAccept: () -> Unit, onReject: () -> Unit) = CallTheme {
    val colors = LocalCocoColors.current
    val avatarSize = CallLayoutPolicy.avatarSize(LocalConfiguration.current.screenHeightDp.toFloat(), isVideo = false).dp
    Surface(modifier = Modifier.fillMaxSize(), color = colors.callBackground) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val landscapeBottomGap = CallLayoutPolicy.landscape(maxWidth.value, maxHeight.value).bottomGap.dp
            val identityAvatar = if (landscape) CallLayoutPolicy.landscape(maxWidth.value, maxHeight.value).avatar.dp else avatarSize
            CallAtmosphere()
            Column(
                modifier = Modifier.fillMaxSize().callSafePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CallTopBar(null, fallbackTitle = stringResource(if (isVideo) R.string.call_incoming_video else R.string.call_incoming_audio))
                CallStageIdentity(callerName, identityAvatar, landscape) {
                Text(
                    text = callerName,
                    style = CocoTextRoles.screenTitle,
                    color = colors.primaryText,
                    fontWeight = FontWeight.Bold,
                    textAlign = if (landscape) TextAlign.Start else TextAlign.Center,
                    maxLines = CallLayoutPolicy.CALL_NAME_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("call-peer-name"),
                )
                Spacer(modifier = Modifier.height(CocoSpacing.md))
                }
                // N.1A §2: Accept/Decline are large and in the bottom third.
                Row(horizontalArrangement = Arrangement.spacedBy(CocoSpacing.xxxl), modifier = Modifier.padding(bottom = if (landscape) landscapeBottomGap else CocoSpacing.xxxl)) {
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
                        acceptCall = true,
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
    subtitle: String? = null,
    ringPhase: CallRingIndicator.Phase? = null,
    answeredAtMs: Long? = null,
    ringbackStartedAtMs: Long? = null,
    controlsAvailable: Boolean = true,
    videoControls: CallPresentationPolicy.VideoControls? = null,
    onToggleCamera: (() -> Unit)? = null,
    terminal: Boolean = false,
) = CallTheme {
    val colors = LocalCocoColors.current
    val liveControls = CallPresentationPolicy.controlsVisible(controlsAvailable, terminal)
    val videoCall = LocalCallChrome.current?.state?.let(CallPresentationPolicy::isVideoCall) == true
    Surface(modifier = Modifier.fillMaxSize(), color = colors.callBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            CallAtmosphere()
            BoxWithConstraints(Modifier.fillMaxSize().callSafePadding()) {
            val landscape = maxWidth > maxHeight
            val landscapeBottomGap = CallLayoutPolicy.landscape(maxWidth.value, maxHeight.value).bottomGap.dp
            val avatarSize = (if (landscape) CallLayoutPolicy.landscape(maxWidth.value, maxHeight.value).avatar else
                CallLayoutPolicy.avatarSize(maxHeight.value, isVideo = false)).dp
            val controlCount = 4
            val controlSize = CallLayoutPolicy.controlSize(maxWidth.value, controlCount).dp
            val controlSpacing = CallLayoutPolicy.controlSpacing(maxWidth.value, controlCount).dp
            val trayWidth = CallLayoutPolicy.trayWidth(maxWidth.value).dp
            val trayPadding = CallLayoutPolicy.trayPadding(maxWidth.value).dp
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CallTopBar(onMinimize, fallbackTitle = title)
                CallStageIdentity(name, avatarSize, landscape, !terminal) {
                CallPeerName(name, landscape)
                if (ringPhase != null) {
                    Spacer(modifier = Modifier.height(CocoSpacing.md))
                    // An answered end reserves the path slot without showing a stale graphic.
                    CallRingingIndicator(ringPhase, durationMs, answeredAtMs, ringbackStartedAtMs,
                        visible = !terminal || ringPhase != CallRingIndicator.Phase.ANSWERED)
                }
                if (subtitle != null) Text(subtitle, style = CocoTextRoles.bodySecondary, color = colors.secondaryText, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = CocoSpacing.sm))
                }

                // Only usable actions enter the centred camera/mic/end/output row.
                if (videoControls != null && onToggleCamera != null) {
                    // The same layout reserves both rows and its own bottom gap after end.
                    VideoCallControlTray(videoControls, availableRoutes, onToggleCamera, onToggleMute, onHangup, onSelectRoute,
                        modifier = if (terminal) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier,
                        enabled = liveControls, landscape = landscape)
                } else if (terminal) {
                    Spacer(Modifier.height(controlSize + CocoSpacing.md * 2))
                } else Surface(
                    shape = CocoShapes.radiusCallControl,
                    color = Color.Transparent,
                    modifier = Modifier.width(trayWidth).testTag("call-controls-tray"),
                ) {
                    CallControlRow(
                        controls = CallControlRowPolicy.audio(
                            videoAvailable = onRequestVideo != null &&
                                LocalCallChrome.current?.state?.let(CallPresentationPolicy::videoUpgradeVisible) == true,
                            live = liveControls, ended = terminal),
                        size = controlSize,
                        spacing = controlSpacing,
                        modifier = Modifier.padding(horizontal = trayPadding, vertical = CocoSpacing.md),
                    ) { control -> when (control) {
                        CallControlRowPolicy.Control.VIDEO -> CallActionButton(
                            icon = CocoIcons.VideoCall,
                            label = stringResource(if (requestingVideo) R.string.cancel else R.string.call_video),
                            caption = stringResource(R.string.call_caption_video),
                            tint = colors.primaryText,
                            background = colors.glassPrimary,
                            size = controlSize,
                            onClick = { onRequestVideo?.invoke() },
                            sensitive = true,
                            testTag = "call-video",
                        )
                        CallControlRowPolicy.Control.MUTE -> CallActionButton(
                            icon = if (isMuted) CocoIcons.MicOff else CocoIcons.Mic,
                            label = stringResource(if (isMuted) R.string.call_unmute else R.string.call_mute),
                            caption = stringResource(R.string.call_caption_mute),
                            tint = if (isMuted) colors.onAccent else colors.primaryText,
                            background = if (isMuted) colors.accent else colors.glassPrimary,
                            active = isMuted,
                            size = controlSize,
                            onClick = onToggleMute,
                            testTag = "call-mute",
                        )
                        CallControlRowPolicy.Control.END -> CallActionButton(
                            icon = CocoIcons.EndCall,
                            label = stringResource(R.string.call_end),
                            caption = stringResource(R.string.call_caption_end),
                            tint = colors.onDanger,
                            background = colors.danger,
                            endCall = true,
                            size = controlSize,
                            onClick = onHangup,
                            enabled = !terminal,
                            testTag = "call-end",
                        )
                        CallControlRowPolicy.Control.OUTPUT -> CallAudioOutputButton(
                            currentKind = currentRouteKind,
                            availableRoutes = availableRoutes,
                            onSelectRoute = onSelectRoute,
                            tint = colors.primaryText,
                            background = colors.glassPrimary,
                            labelColor = colors.secondaryText,
                            size = controlSize,
                            showLabel = false,
                            videoCall = videoCall,
                        )
                        CallControlRowPolicy.Control.CAMERA -> Unit
                    } }
                }
                if (videoControls == null) Spacer(modifier = Modifier.height(if (landscape) landscapeBottomGap else CallControlsBottomGap))
            }
            }
        }
    }
}

/** Reserve the same two-line name slot in every phase, bottom aligned on iOS. */
@Composable
internal fun CallPeerName(name: String, landscape: Boolean = false) {
    Box(if (landscape) Modifier else Modifier.fillMaxWidth().padding(horizontal = CocoSpacing.xl),
        contentAlignment = if (landscape) Alignment.CenterStart else Alignment.BottomCenter) {
        Text("A\nA", style = CocoTextRoles.screenTitle, fontWeight = FontWeight.Bold,
            modifier = Modifier.alpha(0f).clearAndSetSemantics {})
        Text(name, style = CocoTextRoles.screenTitle, color = CallWhite, fontWeight = FontWeight.Bold,
            textAlign = if (landscape) TextAlign.Start else TextAlign.Center, maxLines = CallLayoutPolicy.CALL_NAME_MAX_LINES,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("call-peer-name"))
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

/** Portrait keeps the original slots; landscape groups avatar and identity in the free area. */
@Composable
internal fun ColumnScope.CallStageIdentity(name: String, avatar: Dp, landscape: Boolean,
    showHalo: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    if (!landscape) CallScrollableIdentity {
        CallAvatar(name, avatar, showHalo)
        Spacer(Modifier.height(CocoSpacing.xl))
        content()
    } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("call-landscape-identity"), contentAlignment = Alignment.Center) {
        val textWidth = (minOf(maxWidth, 600.dp) - 64.dp - avatar - 24.dp).coerceAtLeast(1.dp)
        Row(Modifier.padding(horizontal = 32.dp).widthIn(max = 600.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.testTag("call-landscape-avatar")) { CallAvatar(name, avatar, showHalo) }
            Column(Modifier.widthIn(max = textWidth).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start, content = content)
        }
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
    AudioRouteKind.EARPIECE -> CocoIcons.Phone
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
    videoCall: Boolean = false,
    enabled: Boolean = true,
) {
    if (!enabled) return
    val colors = LocalCocoColors.current
    var routeSheetOpen by remember { mutableStateOf(false) }
    val routes by LocalCallChrome.current?.audioRoutes?.collectAsState() ?: remember(availableRoutes) { mutableStateOf(availableRoutes) }
    // A phone has a receiver even while Android is still discovering routes (empty list).
    val telephony = LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    val hasEarpiece = routes.any { it.kind == AudioRouteKind.EARPIECE } || (routes.isEmpty() && telephony)
    val outputKind = if (!enabled || routes.any { it.kind == currentKind }) currentKind else CallPresentationPolicy.assumedOutput(videoCall, hasEarpiece)
    val presentation = CallAudioOutputPresentation.present(outputKind, videoCall, routes.map { it.kind }, hasEarpiece)
    val highlighted = presentation.highlighted
    // Keep the output slot mounted while Android discovers routes; the toggle still works.
    LaunchedEffect(routes.isEmpty()) { if (routes.isEmpty()) routeSheetOpen = false }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = if (highlighted) CallWhite else Color.White.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, CallEdge),
            modifier = Modifier.testTag("call-output"),
            enabled = enabled,
            onClick = {
                val target = presentation.toggleTo
                if (target != null) onSelectRoute(routes.firstOrNull { it.kind == target } ?: AudioRoute(target, CallAudioPolicy.defaultLabel(target)))
                else if (routes.isNotEmpty()) routeSheetOpen = true
            },
        ) {
            Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = when (presentation.glyph) {
                        CallOutputGlyph.SPEAKER -> CocoIcons.Speaker
                        CallOutputGlyph.SPEAKER_OFF -> CallSpeakerOffIcon
                        CallOutputGlyph.PHONE -> CocoIcons.Phone
                        CallOutputGlyph.HEADPHONES -> CocoIcons.Headset
                        CallOutputGlyph.BLUETOOTH -> CocoIcons.Bluetooth
                    },
                    contentDescription = stringResource(R.string.call_audio_output) + ": " + currentRouteLabel(outputKind, routes),
                    tint = if (highlighted) Color(0xFF202020) else CallWhite,
                    modifier = Modifier.size(size * 0.42f),
                )
            }
        }
        if (showLabel) {
            Spacer(modifier = Modifier.height(CocoSpacing.xs))
            Text(
                text = stringResource(R.string.call_audio_output),
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
    if (routeSheetOpen && routes.isNotEmpty()) {
        AudioRouteSheet(
            routes = routes,
            currentKind = outputKind,
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF202020), contentColor = CallWhite) {
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
                    if (route == routes.firstOrNull { it.kind == currentKind }) {
                        Icon(imageVector = CocoIcons.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
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
            TextButton(modifier = Modifier.rejectObscuredTouches(), onClick = onAccept) { Text(stringResource(R.string.call_accept), color = colors.accent) }
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
internal fun rememberCallTerminalControls(state: CallUiState): CallTerminalControls? {
    val memory = rememberSaveable(saver = CallTerminalControlsSaver) { CallTerminalPresentationMemory() }
    return memory.observe(state)
}

private val CallTerminalControlsSaver = listSaver<CallTerminalPresentationMemory, Any>(
    save = { memory -> memory.snapshot?.let {
        listOf(it.callId, it.videoLayout, it.localVideoEnabled, it.isMuted, it.audioRoute.name)
    } ?: emptyList() },
    restore = { saved -> CallTerminalPresentationMemory(runCatching {
        CallTerminalControls(saved[0] as String, saved[1] as Boolean, saved[2] as Boolean,
            saved[3] as Boolean, AudioRouteKind.valueOf(saved[4] as String))
    }.getOrNull()) },
)

@Composable
internal fun EndedCallScreen(reason: CallEndReason, message: String? = null, ringPhase: CallRingIndicator.Phase? = null,
    onDismiss: () -> Unit, retainedControls: CallTerminalControls? = null) {
    val peerCall = when (val state = LocalCallChrome.current?.state) {
        is CallUiState.Ended -> state.call
        else -> null
    }
    if (peerCall != null) {
        // The controller owns delayed dismissal. Retain the identity and tray
        // spacing until then, without presenting inactive call actions.
        val controls = retainedControls?.takeIf { it.callId == peerCall.callId } ?: CallTerminalControls.initial(peerCall)
        ActiveCallScreen(title = message ?: reasonLabel(reason), name = peerCall.otherDisplayName,
            durationMs = null, onHangup = onDismiss, controlsAvailable = false,
            isMuted = controls.isMuted, currentRouteKind = controls.audioRoute,
            videoControls = controls.videoControls, onToggleCamera = {}, ringPhase = ringPhase, terminal = true)
        return
    }
    CallTheme {
    val colors = LocalCocoColors.current
    Surface(modifier = Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }, color = colors.callBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            CallAtmosphere()
            CallTopBar(null, Modifier.callSafePadding(), fallbackTitle = message ?: reasonLabel(reason))
            Column(
                modifier = Modifier.fillMaxSize().callSafePadding().padding(CocoSpacing.xxxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (ringPhase != null && ringPhase != CallRingIndicator.Phase.ANSWERED) ringingTitle(ringPhase) else reasonLabel(reason),
                    style = CocoTextRoles.screenTitle,
                    color = colors.primaryText,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (ringPhase != null && ringPhase != CallRingIndicator.Phase.ANSWERED) CallRingingIndicator(ringPhase)
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
                    modifier = Modifier.testTag("call-terminal-dismiss"),
                ) {
                    Text(
                        text = stringResource(R.string.dismiss),
                        color = colors.primaryText,
                        style = CocoTextRoles.buttonLabel,
                        modifier = Modifier.padding(horizontal = CocoSpacing.xxxl, vertical = CocoSpacing.md),
                    )
                }
            }
        }
    }
}

}

@Composable
internal fun CallAvatar(name: String, size: Dp = 96.dp, showHalo: Boolean = true) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val call = LocalCallChrome.current?.state ?: app.callController.state.value
    val peerId = when (call) {
        is CallUiState.IncomingRinging -> call.call.otherUserId
        is CallUiState.OutgoingRinging -> call.call.otherUserId
        is CallUiState.Connecting -> call.call.otherUserId
        is CallUiState.Connected -> call.call.otherUserId
        is CallUiState.Reconnecting -> call.call.otherUserId
        is CallUiState.Ended -> call.call.otherUserId
        is CallUiState.Failed -> call.call?.otherUserId
        else -> null
    }
    val peer by remember(app, peerId) { app.chatRepository.chats.map { chats ->
        chats.firstOrNull { it.otherUser?.id == peerId }?.otherUser
    }.distinctUntilChanged() }.collectAsState(initial = null)
    Box(Modifier.size(size).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
    if (showHalo && size >= 96.dp) CallVoiceHalo(size)
    if (showHalo) Box(Modifier.requiredSize(size + 10.dp)
        .border(1.5.dp, CallWhite.copy(alpha = .30f), CircleShape))
    MaterialTheme(typography = MaterialTheme.typography.copy(titleMedium =
        MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * .4f).sp,
            lineHeight = (size.value * .48f).sp, fontWeight = FontWeight.SemiBold))) {
    com.messenger.app.ui.components.UserAvatar(
        userId = peerId.orEmpty(), avatarVersion = peer?.avatarVersion ?: 0,
        hasAvatar = peer?.hasAvatar == true, name = name, size = size,
        color = Color.Transparent, contentColor = Color(0xFFDEDEDE),
        fallbackBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFDEDEDE).copy(alpha = .14f)),
    )
    }
    }
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
    acceptCall: Boolean = false,
    sensitive: Boolean = false,
    size: Dp = 64.dp,
    showLabel: Boolean = false,
    caption: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    if (!enabled) return
    val colors = LocalCocoColors.current
    val presentation = if (interactive) modifier else modifier.clearAndSetSemantics { }
    Column(presentation, horizontalAlignment = Alignment.CenterHorizontally) {
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
                    tint = if (endCall || acceptCall) Color.White else if (active) Color(0xFF202020) else CallWhite,
                    modifier = Modifier.size(size * 0.42f),
                )
            }
        }
        val circleColor = if (endCall) colors.danger else if (acceptCall) Color(0xFF34A86A) else if (active) CallWhite else Color.White.copy(alpha = .18f)
        if (interactive) {
            Surface(shape = CircleShape, color = circleColor, border = BorderStroke(1.dp, CallEdge), onClick = onClick, enabled = enabled,
                modifier = (if (testTag == null) Modifier else Modifier.testTag(testTag))
                    .then(if (acceptCall || sensitive) Modifier.rejectObscuredTouches() else Modifier), content = circleContent)
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
    val foreground = callForeground()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtElapsedMs, foreground) {
        if (!foreground) return@LaunchedEffect
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

// Scoped B11 chrome preserves status/security behavior and uses the reference lock.
// CallChrome.kt is outside this batch; its other consumers remain unchanged.
@Composable
private fun callReferenceNetworkMissing(): Boolean {
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

@Composable
internal fun CallReferenceTopBar(onMinimize: (() -> Unit)?, modifier: Modifier = Modifier,
    chrome: Boolean = true, overVideo: Boolean = false, fallbackTitle: String = "") {
    val snapshot = LocalCallChrome.current
    val state = snapshot?.state
    val connected = state?.let(CallPresentationPolicy::connected)
    val drop = callDropShown(state is CallUiState.Reconnecting)
    val offline = callDropShown(callReferenceNetworkMissing())
    val alert = (drop || offline) && state !is CallUiState.Ended && state !is CallUiState.Failed
    BoxWithConstraints(modifier.fillMaxWidth().height(56.dp).zIndex(3f)
        .then(if (chrome && onMinimize != null) Modifier.callMinimizeSwipe(state?.let(CallMinimizePolicy::callId), onMinimize) else Modifier)) {
        if (chrome && onMinimize != null) Box(Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) {
            CallMinimizeButton(onMinimize)
        }
        if (CallPresentationPolicy.capsuleVisible(chrome, alert, state is CallUiState.Ended)) Box(
            Modifier.align(Alignment.Center).widthIn(max = (maxWidth - 128.dp).coerceAtLeast(48.dp)),
        ) {
            CallReferenceStatusCapsule(snapshot, fallbackTitle, alert, offline, overVideo)
        }
    }
}

@Composable
private fun CallReferenceStatusCapsule(snapshot: CallChromeSnapshot?, fallback: String, alert: Boolean,
    offline: Boolean, overVideo: Boolean) {
    val state = snapshot?.state
    val connected = state?.let(CallPresentationPolicy::connected)
    val active = callForeground()
    val presentation by (if (active) snapshot?.quality?.collectAsState() else null) ?: remember { mutableStateOf(CallQualityUiState()) }
    val quality = presentation.quality
    val kind = state?.let { CallStatusPolicy.kind(it, snapshot?.ring,
        reconnectForMs = if (alert && !offline) 1_200 else 0, offlineForMs = if (offline) 1_200 else 0) }
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
    val subtitle = if (!alert && connected != null) quality.caption?.let { stringResource(it) } ?:
        presentation.relay?.let { stringResource(if (it) R.string.call_route_relay else R.string.call_route_direct) } else null
    val reduced = callReducedMotion()
    val pulse = remember { Animatable(0f) }
    val view = LocalView.current
    LaunchedEffect(alert, title) {
        if (alert && active) { view.announceForAccessibility(title); pulse.animateTo(1f, tween(160)); pulse.animateTo(0f, tween(440)) }
        else pulse.snapTo(0f)
    }
    val qualityCaption = quality.caption?.let { stringResource(it) }
    LaunchedEffect(qualityCaption) { if (qualityCaption != null && active) view.announceForAccessibility(qualityCaption) }
    val encryptionActionLabel = stringResource(R.string.call_encryption_title)
    Surface(shape = RoundedCornerShape(50), color = if (overVideo) Color(0xB3303030) else CallGlass,
        border = BorderStroke(1.dp, CallEdge), modifier = Modifier.testTag("call-status")
            .heightIn(min = 44.dp).graphicsLayer { val p = pulse.value; scaleX = if (reduced) 1f else 1 + p * .05f; scaleY = scaleX }
            .then(if (state != null && CallPresentationPolicy.sheetEnabled(state))
                Modifier.clickable(role = Role.Button, onClickLabel = encryptionActionLabel) { details = true } else Modifier)
            .animateContentSize(if (reduced) tween(200) else spring(dampingRatio = .86f, stiffness = 500f))) {
        Box {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = pulse.value * .14f }.background(Color.White))
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lock, null, Modifier.size(11.dp), tint = CallWhite.copy(alpha = if (connected != null || snapshot?.endedSeconds != null) 1f else .55f))
                Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(title, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "callStatusTitle") { text ->
                            Text(text, color = CallWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (connected != null && !alert && active) CallReferenceTimerText(connected.connectedAtElapsedRealtimeMs)
                    }
                    AnimatedContent(subtitle, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "callStatusSubtitle") { text ->
                        if (text != null) Text(text, color = CallWhite.copy(alpha = .78f), fontSize = 11.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (connected != null) CallQualityBars(if (alert) 0 else quality.bars)
            }
        }
    }
    if (details && snapshot != null) CallReferenceEncryptionSheet(snapshot, quality, onDismiss = { details = false })
}

/** Isolated 1Hz text; no timer is mounted in hidden chrome or a background window. */
@Composable
private fun CallReferenceTimerText(start: Long) {
    Text(" · ${callDuration(start)}", color = CallWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallReferenceEncryptionSheet(snapshot: CallChromeSnapshot, quality: CallQualityPresentation.Result, onDismiss: () -> Unit) {
    val stats by snapshot.stats.collectAsState()
    val pair = stats?.selectedCandidatePair
    val relay = pair?.localCandidateType == "relay" || pair?.remoteCandidateType == "relay"
    var statistics by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    // A half-height landscape peek strands the footer below the window. Keep
    // this opening choice stable so a system rotation does not reset the sheet.
    val startExpanded = remember { configuration.screenWidthDp > configuration.screenHeightDp }
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = startExpanded)
    LaunchedEffect(landscape) { if (landscape) sheetState.expand() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
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
            TextButton(onClick = { statistics = !statistics }, enabled = stats != null,
                modifier = Modifier.testTag("call-statistics-toggle")) {
                Text(stringResource(R.string.call_statistics), color = CallWhite.copy(alpha = if (stats != null) 1f else .45f))
            }
            if (statistics) stats?.let { CallStatisticsContent(it) }
        }
    }
}
