package com.messenger.app.ui.call

import com.messenger.app.ui.theme.cocoConversationBackground
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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.State
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import com.messenger.app.domain.call.LocalCameraState
import com.messenger.app.domain.call.RemoteVideoState
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.CocoTextRoles
import com.messenger.app.ui.theme.LocalCocoColors
import org.webrtc.SurfaceViewRenderer
import org.webrtc.RendererCommon

/** Bridges a single `org.webrtc.SurfaceViewRenderer` into Compose with a
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
    val renderer = remember { com.messenger.app.data.call.LocalTextureRenderer(context).apply {
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 20f * view.resources.displayMetrics.density)
            }
        }
    } }
    renderer.setMirror(mirror)
    DisposableEffect(renderer) {
        viewModel.attachLocalRenderer(renderer)
        onDispose { viewModel.detachLocalRenderer(renderer) }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

/** Full-screen video call UI (N.1A): remote video as the main surface, a
 *  rounded floating local preview that never overlaps the bottom controls,
 *  and a bottom controls row (Mute/Camera/Flip/Audio/End). When the remote
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
) = CallTheme {
    val colors = LocalCocoColors.current
    val context = LocalContext.current
    val supportsCameraFlip = viewModel.hasMultipleCameras()
    var measuredControlsHeightPx by remember { mutableIntStateOf(0) }
    var chromeHidden by remember(state.call.callId) { mutableStateOf(false) }
    var remoteVideoFills by remember(state.call.callId) { mutableStateOf(true) }
    val showChrome = !chromeHidden && !pictureInPicture
    val chromeAccessibilityAction = stringResource(if (chromeHidden) R.string.call_show_controls else R.string.call_hide_controls)
    val scalingAccessibilityAction = stringResource(if (remoteVideoFills) R.string.call_video_fit else R.string.call_video_fill)
    val chromeAlpha = animateFloatAsState(
        targetValue = if (showChrome) 1f else 0f,
        animationSpec = tween(if (ValueAnimator.areAnimatorsEnabled()) 180 else 0),
        label = "callChromeAlpha",
    )
    var pendingCameraCallId by remember { mutableStateOf<String?>(null) }
    val cameraPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val requestedCallId = pendingCameraCallId
        pendingCameraCallId = null
        val connected = viewModel.state.value as? CallUiState.Connected
        if (requestedCallId != null && connected != null && connected.call.callId == requestedCallId && connected.videoMode == com.messenger.app.domain.call.VideoMode.VIDEO) {
            if (granted) viewModel.setCameraEnabled(true) else viewModel.notifyCameraDenied()
        }
    }

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

    Box(modifier = Modifier.fillMaxSize().background(colors.callBackground)) {
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
        when (state.remoteVideoState) {
            RemoteVideoState.ON -> Unit
            RemoteVideoState.OFF, RemoteVideoState.WAITING -> {
                // Opaque full-screen layer: a remote camera-off must NEVER let
                // the still-mounted SurfaceView's last frozen frame show
                // through a transparent placeholder (Runtime Correction A).
                Box(
                    modifier = Modifier.fillMaxSize().cocoConversationBackground(colors),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer { alpha = if (pictureInPicture) 1f else chromeAlpha.value }
                            .then(if (!showChrome && !pictureInPicture) Modifier.clearAndSetSemantics {} else Modifier)) {
                        CallAvatar(name = state.call.otherDisplayName, size = if (pictureInPicture) 48.dp else 96.dp)
                        if (!pictureInPicture) {
                            Spacer(modifier = Modifier.height(CocoSpacing.lg))
                        Text(
                            text = state.call.otherDisplayName,
                            color = colors.primaryText,
                            style = CocoTextRoles.screenTitle,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = CallLayoutPolicy.CALL_NAME_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = CocoSpacing.xl),
                        )
                        Spacer(modifier = Modifier.height(CocoSpacing.md))
                        }
                    }
                }
            }
        }

        // A separate background plane keeps single/double tap exclusive and
        // leaves native preview transforms and control touches above it intact.
        Box(Modifier.fillMaxSize().testTag("call-video-background")
            // During fade-out this plane immediately owns former control hits.
            // It restores chrome instead of dispatching an invisible action.
            .zIndex(if (chromeHidden) 1f else 0f)
            .then(if (!pictureInPicture) Modifier.semantics {
                contentDescription = chromeAccessibilityAction
                onClick(label = chromeAccessibilityAction) { chromeHidden = !chromeHidden; true }
                if (state.remoteVideoState == RemoteVideoState.ON) customActions = listOf(
                    CustomAccessibilityAction(scalingAccessibilityAction) { remoteVideoFills = !remoteVideoFills; true },
                )
            } else Modifier)
            .pointerInput(state.call.callId, pictureInPicture, state.remoteVideoState) {
                if (!pictureInPicture) detectTapGestures(
                    onTap = { chromeHidden = !chromeHidden },
                    onDoubleTap = { if (state.remoteVideoState == RemoteVideoState.ON) remoteVideoFills = !remoteVideoFills },
                )
            })

        // Floating draggable local preview (PiP-style, like other call apps).
        // Positioned absolutely and clamped to the safe drawing area, so it
        // can never be dragged off-screen; the surface stays mounted for the
        // whole VIDEO call so camera on/off only toggles the track, never the
        // surface.
        BoxWithConstraints(modifier = Modifier.fillMaxSize().zIndex(2f).callSafePadding()
            .offset { IntOffset(if (pictureInPicture || !state.localVideoEnabled || state.localCameraState != LocalCameraState.ON) -20000 else 0, 0) }) {
            val density = LocalDensity.current
            val maxW = with(density) { maxWidth.toPx() }
            val maxH = with(density) { maxHeight.toPx() }
            var previewScale by remember { mutableStateOf(1f) }
            val availableW = maxWidth.value - CocoSpacing.lg.value * 2
            val controlsHeightPx = if (showChrome) maxOf(measuredControlsHeightPx.toFloat(),
                with(density) { (CallLayoutPolicy.CONTROL_MAX.dp + 80.dp).toPx() }) else 0f
            // Keep base dimensions independent of chrome visibility; only the
            // permitted drag area expands. A hide tap must not resize the PiP.
            val sizingControlsHeightPx = maxOf(measuredControlsHeightPx.toFloat(),
                with(density) { (CallLayoutPolicy.CONTROL_MAX.dp + 80.dp).toPx() })
            val availableH = maxHeight.value - sizingControlsHeightPx / density.density - CocoSpacing.lg.value * 2
            val preview = CallLayoutPolicy.resizedPreviewSize(availableW, availableH, previewScale)
            val previewSizeFits = CallLayoutPolicy.canShowPreview(preview.first, preview.second)
            val pipW = with(density) { preview.first.dp.toPx() }
            val pipH = with(density) { preview.second.dp.toPx() }
            val marginPx = with(density) { CocoSpacing.lg.toPx() }
            // Code-review 5: exclude the bottom control cluster so the PiP
            // can never cover Mute/Camera/Flip/Audio/End.
            var pipOffset by remember { mutableStateOf(Offset(maxW - pipW - marginPx, marginPx)) }

            fun safeOrigin(x: Float, y: Float, width: Float = pipW, height: Float = pipH): Offset? {
                // Reserve one compact safe-area HUD row while it is visible.
                val right = if (showChrome) maxW else 0f
                val bottom = if (showChrome) (12f + 44f + CallMinimizePolicy.HEADER_CLEARANCE_DP) * density.density else 0f
                return CallLayoutPolicy.previewOrigin(x, y, maxW - width - marginPx,
                    maxH - height - controlsHeightPx - marginPx, marginPx, right, bottom)?.let { Offset(it.first, it.second) }
            }
            val safePreviewOrigin = safeOrigin(pipOffset.x, pipOffset.y)
            val previewFits = previewSizeFits && safePreviewOrigin != null

            // Re-clamp after a size change (e.g. rotation) so the window can
            // never be left stranded off-screen or over the controls.
            LaunchedEffect(pictureInPicture, maxW, maxH, pipW, pipH, controlsHeightPx, marginPx) {
                // PiP hides this preview; its temporary bounds must not move it.
                if (!pictureInPicture) {
                    safeOrigin(pipOffset.x, pipOffset.y)?.let { pipOffset = it }
                }
            }

            Box(
                modifier = Modifier
                    .offset { safePreviewOrigin?.takeIf { previewSizeFits }
                        ?.let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } ?: IntOffset(-20000, 0) }
                    .size(width = preview.first.dp, height = preview.second.dp)
                    .clip(CocoShapes.radiusLocalVideo)
                    .border(1.dp, colors.outlineSecondary, CocoShapes.radiusLocalVideo),
            ) {
                LocalVideoSurface(
                    viewModel = viewModel,
                    mirror = state.localCameraIsFrontFacing,
                    modifier = Modifier.fillMaxSize(),
                )
                // An overlay owns touches; the native renderer cannot swallow drags.
                Box(modifier = Modifier.fillMaxSize()
                    .pointerInput(maxW, maxH, controlsHeightPx) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = CallLayoutPolicy.resizedPreviewSize(availableW, availableH, previewScale * zoom)
                            val base = CallLayoutPolicy.localPreviewSize(availableW, availableH).first
                            previewScale = next.first / base
                            val widthPx = next.first * density.density
                            val heightPx = next.second * density.density
                            safeOrigin(pipOffset.x + pan.x, pipOffset.y + pan.y, widthPx, heightPx)?.let { pipOffset = it }
                        }
                    })
                // Camera off/starting/failed: keep the surface alive but hide
                // any faint frozen local frame behind an OPAQUE placeholder
                // (Runtime Correction B) вЂ” a muted camera glyph, never the
                // last captured image.
                if (state.localCameraState != LocalCameraState.ON) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(colors.callBackground),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = CocoIcons.Camera,
                            contentDescription = stringResource(R.string.call_camera_is_off),
                            tint = colors.mutedText,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        if (!pictureInPicture) {
        // Compact icon controls. Canonical video order:
        // Camera В· Mute В· End В· Speaker В· Flip (End central/danger).
        VideoCallControlTray(
            state = state, availableRoutes = availableRoutes, supportsCameraFlip = supportsCameraFlip,
            onCamera = {
                if (state.localVideoEnabled) viewModel.setCameraEnabled(false)
                else if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) viewModel.setCameraEnabled(true)
                else {
                    pendingCameraCallId = state.call.callId
                    cameraPermission.launch(android.Manifest.permission.CAMERA)
                }
            },
            onToggleMute = viewModel::toggleMute, onHangup = viewModel::hangup,
            onSelectRoute = viewModel::setAudioRoute, onFlip = viewModel::switchCamera,
            onMeasured = { if (measuredControlsHeightPx != it) measuredControlsHeightPx = it },
            modifier = Modifier.align(Alignment.BottomCenter).callSafePadding().callChromeVisibility(showChrome, chromeAlpha),
        )
        // N.1 В§3: explicit "To chat" affordance вЂ” the call minimizes but is
        // never ended; the compact bar (CompactCallBar) returns to it.
        // The preview clamp excludes this strip, including when moved to the
        // left, so a camera drag can never become a minimize swipe.
        Box(Modifier.align(Alignment.TopStart).callSafePadding().padding(12.dp).callChromeVisibility(showChrome, chromeAlpha)
            .width(CallMinimizePolicy.HEADER_WIDTH_DP.dp).height(44.dp)
            .callMinimizeSwipe(state.call.callId, onMinimize)) {
            CallMinimizeButton(onClick = onMinimize)
        }

        // Phase 4 В§4: connected duration pill at top-center (never overlaps
        // the "To chat" affordance or the status bar).
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .callSafePadding()
                .padding(top = 12.dp)
                .height(44.dp)
                .callChromeVisibility(showChrome, chromeAlpha),
            contentAlignment = Alignment.Center,
        ) {
            CallStatusPill(text = callDuration(state.connectedAtElapsedRealtimeMs))
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
) {
    val colors = LocalCocoColors.current
    val canFlipCamera = supportsCameraFlip && state.localVideoEnabled && state.localCameraState == LocalCameraState.ON
    val motionDuration = if (ValueAnimator.areAnimatorsEnabled()) 160 else 0
    val flipVisibility = remember { MutableTransitionState(false) }
    LaunchedEffect(canFlipCamera) { flipVisibility.targetState = canFlipCamera }
    BoxWithConstraints(modifier) {
        val controlCount = if (supportsCameraFlip) 5 else 4
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
        val controlSize = minOf(CallLayoutPolicy.controlSize(maxWidth.value, controlCount).dp, pixelFit)
        val primaryControls: @Composable () -> Unit = {
            CallActionButton(
                icon = CocoIcons.Videocam,
                label = stringResource(if (state.localVideoEnabled) R.string.call_camera_off else R.string.call_camera_on),
                caption = stringResource(R.string.call_caption_camera),
                tint = if (state.localVideoEnabled) colors.onAccent else colors.primaryText,
                background = if (state.localVideoEnabled) colors.accent else colors.glassPrimary,
                active = state.localVideoEnabled,
                size = controlSize,
                onClick = onCamera,
                testTag = "call-camera",
            )
            CallActionButton(
                icon = if (state.isMuted) CocoIcons.MicOff else CocoIcons.Mic,
                label = stringResource(if (state.isMuted) R.string.call_unmute else R.string.call_mute),
                caption = stringResource(R.string.call_caption_mute),
                tint = if (!state.isMuted) colors.onAccent else colors.primaryText,
                background = if (!state.isMuted) colors.accent else colors.glassPrimary,
                active = !state.isMuted,
                size = controlSize,
                onClick = onToggleMute,
                testTag = "call-mute",
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
            CallAudioOutputButton(
                currentKind = state.audioRoute,
                availableRoutes = availableRoutes,
                onSelectRoute = onSelectRoute,
                tint = colors.primaryText,
                background = colors.glassPrimary,
                labelColor = colors.secondaryText,
                size = controlSize,
                showLabel = false,
            )
        }
        val flipControl: @Composable (Boolean) -> Unit = { withGap ->
            if (supportsCameraFlip) {
                AnimatedVisibility(visibleState = flipVisibility,
                    enter = fadeIn(tween(motionDuration)) + expandHorizontally(tween(motionDuration), expandFrom = Alignment.Start),
                    exit = fadeOut(tween(motionDuration)) + shrinkHorizontally(tween(motionDuration), shrinkTowards = Alignment.Start)) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (withGap) Spacer(Modifier.width(controlSpacing))
                        CallActionButton(
                            icon = CocoIcons.SwitchCamera,
                            label = stringResource(R.string.call_switch_camera),
                            caption = stringResource(R.string.call_caption_flip),
                            tint = colors.primaryText,
                            background = colors.glassPrimary,
                            size = controlSize,
                            onClick = onFlip,
                            testTag = "call-flip",
                            interactive = canFlipCamera && flipVisibility.isIdle && flipVisibility.currentState,
                        )
                    }
                }
            }
        }
        Surface(
            shape = CocoShapes.radiusCallControl,
            color = Color.Transparent,
            modifier = Modifier
                .testTag("call-controls-tray")
                .onSizeChanged { onMeasured(it.height) }
                .padding(bottom = CallControlsBottomGap)
                .width(trayWidth),
        ) {
            val compact = trayWidth - trayPadding * 2 < controlSize * 4 + controlSpacing * 3
            FlowRow(
                horizontalArrangement = if (compact) Arrangement.spacedBy(controlSpacing, Alignment.CenterHorizontally) else Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(CocoSpacing.md),
                modifier = Modifier.padding(horizontal = trayPadding, vertical = CocoSpacing.md),
            ) {
                if (compact) {
                    // Keep individual targets wrapping in very narrow split windows.
                    primaryControls()
                    flipControl(false)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(controlSpacing), verticalAlignment = Alignment.Top) { primaryControls() }
                    // The optional gap reveals with Flip; four controls are truly centered OFF.
                    flipControl(true)
                }
            }
        }
    }
}
