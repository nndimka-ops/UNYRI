package com.messenger.app.ui.call

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.State
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
import com.messenger.app.R
import com.messenger.app.data.call.AudioRoute
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.CallRingIndicator
import com.messenger.app.domain.call.LocalCameraState
import com.messenger.app.domain.call.RemoteVideoState
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.CocoTextRoles
import com.messenger.app.ui.theme.LocalCocoColors
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.RendererCommon
import com.messenger.app.data.call.CallOrientationPolicy
import com.messenger.app.data.call.selfViewDelta

/** Bridges a single `livekit.org.webrtc.SurfaceViewRenderer` into Compose with a
 *  correct lifecycle (Р­С‚Р°Рї J.1-3 Рї.3):
 *  - the renderer is created exactly once per composition entry (`remember`);
 *  - `attach` (which inits the EGL context and adds the VideoTrack sink) runs
 *    once in a `DisposableEffect`, never re-run on recomposition;
 *  - `detach(renderer)` runs on disposal with the renderer's IDENTITY, so a
 *    stale disposal can never detach a newer renderer (code-review 2).
 *  The renderer stays mounted and visible for the whole VIDEO call; camera
 *  on/off only toggles the VideoTrack, never the surface (destroying a
 *  SurfaceView's surface black-screens it on several devices). */
@Composable
fun VideoSurface(
    attach: (SurfaceViewRenderer) -> Unit,
    detach: (SurfaceViewRenderer) -> Unit,
    mirror: Boolean = false,
    rounded: Boolean = false,
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_BALANCED,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(context).apply {
        // Let native FIT measure the complete frame inside the bounded viewport.
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    } }
    renderer.setMirror(mirror)
    renderer.setScalingType(scalingType)
    renderer.clipToOutline = rounded
    renderer.outlineProvider = if (rounded) object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, 20f * view.resources.displayMetrics.density)
        }
    } else android.view.ViewOutlineProvider.BACKGROUND
    DisposableEffect(renderer) {
        attach(renderer)
        onDispose { detach(renderer) }
    }
    AndroidView(factory = { renderer }, modifier = modifier.wrapContentSize(Alignment.Center))
}

@Composable
private fun LocalVideoSurface(viewModel: CallViewModel, mirror: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    // The outer Compose tile clips once, after rotation. Rounding the inner native
    // view too can expose black corners when its bounds swap for compensation.
    val renderer = remember { com.messenger.app.data.call.LocalTextureRenderer(context) }
    renderer.setMirror(mirror)
    DisposableEffect(renderer) {
        viewModel.attachLocalRenderer(renderer)
        onDispose { viewModel.detachLocalRenderer(renderer) }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

/** Full-screen video call UI (N.1A): remote video as the main surface, a
 *  rounded floating local preview that never overlaps the bottom controls,
 *  and a bottom controls row (Camera/Mute/End/Audio). When the remote
 *  camera is off, an avatar + "Camera is off" state is shown instead of a
 *  black rectangle. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun VideoCallScreen(
    state: CallUiState.Connected,
    availableRoutes: List<AudioRoute>,
    viewModel: CallViewModel,
    onMinimize: () -> Unit = {},
    pictureInPicture: Boolean = false,
    ringPhase: CallRingIndicator.Phase? = null,
    answeredAtMs: Long? = null,
    indicatorVisible: Boolean = true,
    miniMode: Boolean = false,
    reconnecting: Boolean = false,
) = CallTheme(manageSystemBars = !miniMode) {
    val colors = LocalCocoColors.current
    val context = LocalContext.current
    val supportsCameraFlip = viewModel.hasMultipleCameras()
    val torch by viewModel.torchState.collectAsState()
    val orientation by viewModel.localOrientation.collectAsState()
    val remoteGeometry by viewModel.remoteGeometry.collectAsState()
    val screenDegrees = rememberCallScreenDegrees()
    var appliedDelta by remember(state.call.callId) { mutableIntStateOf(0) }
    val selfViewRotation = orientation.selfViewDelta(screenDegrees, appliedDelta)
    androidx.compose.runtime.SideEffect { appliedDelta = selfViewRotation }
    val renderers = CallPresentationPolicy.rendererVisibility(callForeground(), LocalCallRendererVisible.current,
        miniMode, pictureInPicture, state.localVideoEnabled && state.localCameraState == LocalCameraState.ON,
        state.remoteVideoState != RemoteVideoState.OFF, started = callWindowStarted())
    LaunchedEffect(state.call.callId, renderers) {
        if (CallMinimizePolicy.canComplete(state.call.callId, viewModel.state.value)) viewModel.setRendererVisibility(renderers.first, renderers.second)
    }
    DisposableEffect(state.call.callId) { onDispose {
        if (CallMinimizePolicy.canComplete(state.call.callId, viewModel.state.value)) viewModel.setRendererVisibility(false, false)
    } }
    var measuredControlsHeightPx by remember { mutableIntStateOf(0) }
    var chromeHidden by remember(state.call.callId) { mutableStateOf(false) }
    var hadRemoteFrame by remember(state.call.callId) { mutableStateOf(state.remoteVideoState == RemoteVideoState.ON) }
    LaunchedEffect(state.remoteVideoState) {
        if (state.remoteVideoState == RemoteVideoState.ON) hadRemoteFrame = true
        if (state.remoteVideoState == RemoteVideoState.OFF) hadRemoteFrame = false
    }
    val showRemotePicture = state.remoteVideoState == RemoteVideoState.ON ||
        (reconnecting && state.remoteVideoEnabled && hadRemoteFrame && state.remoteVideoState != RemoteVideoState.OFF)
    val anyPicture = CallPresentationPolicy.anyPicture(state, showRemotePicture)
    val showChrome = !chromeHidden && !pictureInPicture && !miniMode && indicatorVisible
    LaunchedEffect(anyPicture) { if (!anyPicture) chromeHidden = false }
    val chromeAccessibilityAction = stringResource(if (chromeHidden) R.string.call_show_controls else R.string.call_hide_controls)
    val chromeAlpha = animateFloatAsState(
        targetValue = if (showChrome) 1f else 0f,
        animationSpec = tween(if (ValueAnimator.areAnimatorsEnabled()) 180 else 0),
        label = "callChromeAlpha",
    )
    val cameraAction = rememberCallCameraAction(viewModel)

    // Re-establish the renderers' EGL surfaces after the app returns from the
    // background (MIUI destroys SurfaceView surfaces while backgrounded).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reinitRenderers()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(colors.callBackground)) {
        val landscape = maxWidth > maxHeight && !miniMode && !pictureInPicture
        val landscapeAvatar = CallLayoutPolicy.landscape(maxWidth.value, maxHeight.value).avatar.dp
        val identityWidth = CallLayoutPolicy.videoIdentityWidth(maxWidth.value, maxHeight.value,
            state.localVideoEnabled && state.localCameraState == LocalCameraState.ON).dp
        var scaleOverride by remember(state.call.callId, screenDegrees, remoteGeometry.rotation,
            remoteGeometry.width > remoteGeometry.height, maxWidth > maxHeight) { mutableStateOf<Boolean?>(null) }
        val remoteVideoFills = scaleOverride ?: CallOrientationPolicy.remoteFills(remoteGeometry.width,
            remoteGeometry.height, maxWidth.value, maxHeight.value)
        // Remote video stays mounted and visible for the whole VIDEO call.
        // The avatar placeholder is layered on top until a real remote frame
        // is rendered - a signaling "camera on" alone never hides it
        // (code-review 3: OFF vs WAITING vs ON).
        VideoSurface(
            attach = viewModel::attachRemoteRenderer,
            detach = viewModel::detachRemoteRenderer,
            scalingType = if (remoteVideoFills) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT,
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(visible = !showRemotePicture, enter = fadeIn(tween(0)),
            exit = fadeOut(tween(if (callReducedMotion()) 150 else 250))) {
                // Opaque full-screen layer: a remote camera-off must NEVER let
                // the still-mounted SurfaceView's last frozen frame show
                // through a transparent placeholder (Runtime Correction A).
                Box(
                    modifier = Modifier.fillMaxSize().callAtmosphere(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer { alpha = if (pictureInPicture || miniMode || !anyPicture) 1f else chromeAlpha.value }
                            .then(if (!showChrome && !pictureInPicture && !miniMode && anyPicture) Modifier.clearAndSetSemantics {} else Modifier)) {
                        if (landscape) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = AbsoluteAlignment.CenterLeft) {
                            Box(Modifier.width(identityWidth), contentAlignment = Alignment.Center) {
                            Row(Modifier.padding(horizontal = 32.dp).widthIn(max = 600.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                CallAvatar(state.call.otherDisplayName, landscapeAvatar,
                                    showHalo = showChrome || !anyPicture)
                                Column(Modifier.widthIn(max = (minOf(identityWidth, 600.dp) - 64.dp - landscapeAvatar - 24.dp).coerceAtLeast(1.dp)),
                                    horizontalAlignment = Alignment.Start) { CallPeerName(state.call.otherDisplayName, landscape = true) }
                            }
                            }
                            }
                        } else {
                        CallAvatar(name = state.call.otherDisplayName, size = if (pictureInPicture || miniMode) 48.dp else 136.dp, showHalo = showChrome || !anyPicture)
                        if (!pictureInPicture) {
                            Spacer(modifier = Modifier.height(CocoSpacing.lg))
                        Text(
                            text = state.call.otherDisplayName,
                            color = colors.primaryText,
                            style = if (miniMode) MaterialTheme.typography.labelSmall else CocoTextRoles.screenTitle,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = if (miniMode) 1 else CallLayoutPolicy.CALL_NAME_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = if (miniMode) 4.dp else CocoSpacing.xl),
                        )
                        Spacer(modifier = Modifier.height(CocoSpacing.md))
                        }
                        }
                    }
                }
        }

        if (!miniMode && !pictureInPicture) CallVideoScrims(showChrome, picture = showRemotePicture)

        CallVideoTapPlane(state.call.callId, chromeHidden,
            !pictureInPicture && !miniMode && anyPicture, state.remoteVideoState == RemoteVideoState.ON,
            onToggle = { chromeHidden = !chromeHidden }, onScale = { scaleOverride = !remoteVideoFills })
        CallSelfViewLayout(
            owner = state.call.callId,
            visible = !pictureInPicture && !miniMode && state.localVideoEnabled && state.localCameraState == LocalCameraState.ON,
            chrome = showChrome,
            measuredTrayHeightPx = measuredControlsHeightPx,
            rotation = selfViewRotation,
            modifier = Modifier.fillMaxSize().zIndex(2f).callSafePadding(),
            picture = {
                LocalVideoSurface(viewModel, state.localCameraIsFrontFacing, Modifier.fillMaxSize())
                // Keep the native surface mounted, with no frozen frame while off.
                if (state.localCameraState != LocalCameraState.ON) Box(
                    Modifier.fillMaxSize().background(colors.callBackground), contentAlignment = Alignment.Center,
                ) {
                    Icon(CocoIcons.Camera, stringResource(R.string.call_camera_is_off),
                        Modifier.size(24.dp), tint = colors.mutedText)
                }
            },
            controls = {
                CallSelfViewControls(state, supportsCameraFlip, showChrome,
                    liveState = { viewModel.state.value }, hasMultipleCameras = viewModel::hasMultipleCameras,
                    onFlip = viewModel::switchCamera, modifier = Modifier.align(Alignment.BottomEnd),
                    torch = torch, onTorch = viewModel::toggleTorch)
            },
        )
        if (!pictureInPicture && !miniMode) {
        // Compact icon controls. Canonical video order:
        // Camera · Mute · End · Speaker (End central/danger).
        VideoCallControlTray(
            state = state, availableRoutes = availableRoutes, supportsCameraFlip = supportsCameraFlip,
            landscape = landscape,
            onCamera = { cameraAction(state.call.callId) },
            onToggleMute = viewModel::toggleMute, onHangup = viewModel::hangup,
            onSelectRoute = viewModel::setAudioRoute, onFlip = viewModel::switchCamera,
            onMeasured = { if (measuredControlsHeightPx != it) measuredControlsHeightPx = it },
            modifier = Modifier.align(Alignment.BottomCenter).callSafePadding().callChromeVisibility(showChrome, chromeAlpha),
        )
        CallTopBar(onMinimize, modifier = Modifier.align(Alignment.TopCenter).callSafePadding(),
            chrome = showChrome, overVideo = showRemotePicture)
        if (ringPhase != null) Box(Modifier.align(Alignment.TopCenter).callSafePadding().padding(top = 60.dp)) {
            CallRingingIndicator(ringPhase, connectedAtMs = state.connectedAtElapsedRealtimeMs,
                answeredAtMs = answeredAtMs, visible = indicatorVisible && showChrome)
        }
        }
    }
}

/** Keep chrome composed and measured while its pixels, hits and accessibility disappear. */
private fun Modifier.callChromeVisibility(visible: Boolean, opacity: State<Float>): Modifier =
    // Read the animated value in the layer, avoiding whole-screen recomposition
    // on every fade frame. Geometry and native renderers stay untouched.
    this.graphicsLayer { alpha = opacity.value }
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})

/** Actual production tray, separated from media/controller ownership for native layout checks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoCallControlTray(
    state: CallUiState.Connected,
    availableRoutes: List<AudioRoute>,
    supportsCameraFlip: Boolean,
    onCamera: () -> Unit,
    onToggleMute: () -> Unit,
    onHangup: () -> Unit,
    onSelectRoute: (AudioRoute) -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
    onMeasured: (Int) -> Unit = {},
    landscape: Boolean = false,
) {
    VideoCallControlTray(CallPresentationPolicy.VideoControls(state.call.callId, state.localVideoEnabled, state.isMuted, state.audioRoute),
        availableRoutes, onCamera, onToggleMute, onHangup, onSelectRoute, modifier, onMeasured, landscape = landscape)
}

/** Identical camera/mic/end/output layout for ringing, connecting and connected video. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoCallControlTray(
    state: CallPresentationPolicy.VideoControls,
    availableRoutes: List<AudioRoute>,
    onCamera: () -> Unit,
    onToggleMute: () -> Unit,
    onHangup: () -> Unit,
    onSelectRoute: (AudioRoute) -> Unit,
    modifier: Modifier = Modifier,
    onMeasured: (Int) -> Unit = {},
    enabled: Boolean = true,
    landscape: Boolean = false,
) {
    val colors = LocalCocoColors.current
    androidx.compose.runtime.key(state.callId) {
    BoxWithConstraints(modifier) {
        val controlCount = CallPresentationPolicy.VIDEO_CONTROL_COUNT
        val controlSpacing = CallLayoutPolicy.controlSpacing(maxWidth.value, controlCount).dp
        val trayWidth = CallLayoutPolicy.trayWidth(maxWidth.value).dp
        val trayPadding = CallLayoutPolicy.trayPadding(maxWidth.value).dp
        val density = LocalDensity.current
        // Row rounds each circle and gap separately. Fit those actual pixels so
        // a fractional-dp estimate cannot wrap Flip onto a second row by1px.
        val pixelFit = with(density) {
            ((trayWidth.roundToPx() - 2 * trayPadding.roundToPx() - (controlCount - 1) * controlSpacing.roundToPx()) / controlCount)
                .coerceAtLeast(48.dp.roundToPx()).toDp()
        }
        val controlSize = minOf(CallLayoutPolicy.controlSize(maxWidth.value, if (CallPresentationPolicy.controlsPerRow(maxWidth.value) == 2) 2 else controlCount).dp, if (CallPresentationPolicy.controlsPerRow(maxWidth.value) == 2) 64.dp else pixelFit)
        val primaryControl: @Composable (CallControlRowPolicy.Control) -> Unit = { control ->
            when (control) {
                CallControlRowPolicy.Control.CAMERA -> CallActionButton(
                    icon = if (state.localVideoEnabled) CocoIcons.Videocam else CallVideoOffIcon,
                    label = stringResource(if (state.localVideoEnabled) R.string.call_camera_off else R.string.call_camera_on),
                    caption = stringResource(R.string.call_caption_camera),
                    tint = if (!state.localVideoEnabled) colors.onAccent else colors.primaryText,
                    background = if (!state.localVideoEnabled) colors.accent else colors.glassPrimary,
                    active = !state.localVideoEnabled,
                    size = controlSize,
                    onClick = onCamera,
                    sensitive = true,
                    enabled = enabled,
                    testTag = "call-camera",
                )
                CallControlRowPolicy.Control.MUTE -> CallActionButton(
                    icon = if (state.isMuted) CocoIcons.MicOff else CocoIcons.Mic,
                    label = stringResource(if (state.isMuted) R.string.call_unmute else R.string.call_mute),
                    caption = stringResource(R.string.call_caption_mute),
                    tint = if (state.isMuted) colors.onAccent else colors.primaryText,
                    background = if (state.isMuted) colors.accent else colors.glassPrimary,
                    active = state.isMuted,
                    size = controlSize,
                    onClick = onToggleMute,
                    enabled = enabled,
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
                    enabled = enabled,
                    testTag = "call-end",
                )
                CallControlRowPolicy.Control.OUTPUT -> CallAudioOutputButton(
                    currentKind = state.audioRoute,
                    availableRoutes = availableRoutes,
                    onSelectRoute = onSelectRoute,
                    tint = colors.primaryText,
                    background = colors.glassPrimary,
                    labelColor = colors.secondaryText,
                    size = controlSize,
                    showLabel = false,
                    videoCall = true,
                    enabled = enabled,
                )
                CallControlRowPolicy.Control.VIDEO -> Unit
            }
        }
        Surface(
            shape = CocoShapes.radiusCallControl,
            color = Color.Transparent,
            modifier = Modifier
                .testTag("call-controls-tray")
                .onSizeChanged { onMeasured(it.height) }
                .padding(bottom = if (landscape) CallLayoutPolicy.landscape(maxWidth.value, maxHeight.value).bottomGap.dp else CallControlsBottomGap)
                .width(trayWidth),
        ) {
            val compact = CallPresentationPolicy.controlsPerRow(maxWidth.value) == 2
            CallControlRow(
                controls = CallControlRowPolicy.video(live = enabled, ended = !enabled),
                size = controlSize,
                spacing = controlSpacing,
                maxPerRow = if (compact) 2 else 4,
                retainTerminalHeight = !enabled,
                modifier = Modifier.padding(horizontal = trayPadding, vertical = CocoSpacing.md),
                content = primaryControl,
            )
        }
    }
}
}
