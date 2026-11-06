package com.messenger.app.data.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.messenger.app.data.api.IceServerDto
import com.messenger.app.domain.model.CallMediaKind
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** Real WebRTC implementation (Этап J) - standard `org.webrtc.*` API via the
 *  `io.getstream:stream-webrtc-android` pre-built library. No custom RTP/
 *  SRTP/ICE/codec logic anywhere in this class - purely wires up the
 *  standard PeerConnection APIs. Not unit-testable on the JVM (real native
 *  WebRTC code); verified via live on-device testing only. */
class WebRtcEngineImpl(private val context: Context) : WebRtcEngine {

    // Lazy on purpose (M.2 / Этап K.9): EglBase.create() initializes EGL and
    // loads native code - several seconds of cold-start cost that must NOT
    // sit on the process-dead FCM incoming-call path. It is only needed once
    // real media setup begins (on Accept), never for the ringing notification.
    private val eglBase: EglBase by lazy { EglBase.create() }
    private val _events = MutableSharedFlow<WebRtcEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<WebRtcEvent> = _events

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    // Java getters replace/dispose wrappers; only the native sender ID is stable.
    private var localVideoSenderId: String? = null
    private var videoNegotiationAllowed = false
    private var remoteVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var captureRunning = false
    private var videoLimits = CallQuality.AUTO.video
    private var audioBitrate = CallQuality.AUTO.audioBitrate
    private var cameraGeneration = 0L
    private var cameraIsFrontFacing = true
    private var cameraSwitchPending = false
    private val cameraCallbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val localRendererSlot = RendererSlot<LocalTextureRenderer>()
    private val remoteRendererSlot = RendererSlot<SurfaceViewRenderer>()
    private var localSink: FirstFrameSink? = null
    private var remoteSink: FirstFrameSink? = null
    private var remoteFrameProbe: FirstFrameSink? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var communicationListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var headsetReceiver: BroadcastReceiver? = null

    private val audioManager: AudioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        val created = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        factory = created
        return created
    }

    override fun initialize(iceServers: List<IceServerDto>, mediaKind: CallMediaKind) {
        localVideoSenderId = null
        videoNegotiationAllowed = mediaKind == CallMediaKind.VIDEO
        val preferences = context.getSharedPreferences(CallQualityPreferences.STORE, Context.MODE_PRIVATE)
        videoLimits = CallQuality.parse(preferences.getString(CallQualityPreferences.VIDEO, null)).video
        audioBitrate = CallQuality.parse(preferences.getString(CallQualityPreferences.AUDIO, null)).audioBitrate
        requestAudioFocus()
        startAudioDeviceMonitoring()
        val rtcIceServers = iceServers.map { server ->
            val builder = PeerConnection.IceServer.builder(server.urls)
            if (server.username != null && server.credential != null) {
                builder.setUsername(server.username).setPassword(server.credential)
            }
            builder.createIceServer()
        }
        val iceConfig = WebRtcIceTransportPolicy.buildConfig(
            iceServers,
            com.messenger.app.BuildConfig.DEBUG,
            com.messenger.app.BuildConfig.FORCE_WEBRTC_RELAY,
        )
        val rtcConfig = PeerConnection.RTCConfiguration(rtcIceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            if (iceConfig.transports == IceTransportPolicy.RELAY) {
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
        }
        val pcFactory = ensureFactory()
        val generation = ++mediaGeneration
        peerConnection = pcFactory.createPeerConnection(rtcConfig, observer(generation))
            ?: error("Unable to initialize call media")

        audioSource = pcFactory.createAudioSource(MediaConstraints())
        localAudioTrack = pcFactory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, audioSource).also {
            peerConnection?.addTrack(it, listOf(LOCAL_STREAM_ID))
        }

        if (mediaKind == CallMediaKind.VIDEO) {
            setupLocalVideo(pcFactory)
        }
    }

    private fun setupLocalVideo(pcFactory: PeerConnectionFactory, capture: Boolean = true): Boolean {
        if (capture && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try {
            val enumerator = Camera2Enumerator(context)
            val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: return false
            val expectedCamera = ++cameraGeneration
            cameraSwitchPending = false
            cameraIsFrontFacing = enumerator.isFrontFacing(deviceName)
            val videoCapturer = enumerator.createCapturer(deviceName, object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(error: String?) = reportCameraFailure(expectedCamera)
                override fun onCameraDisconnected() = reportCameraFailure(expectedCamera)
                override fun onCameraFreezed(error: String?) = reportCameraFailure(expectedCamera)
                override fun onCameraOpening(cameraName: String?) {}
                override fun onFirstFrameAvailable() {}
                override fun onCameraClosed() {}
            }) ?: return false
            capturer = videoCapturer
            val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            surfaceTextureHelper = helper
            val source = pcFactory.createVideoSource(videoCapturer.isScreencast)
            videoSource = source
            videoCapturer.initialize(helper, context, source.capturerObserver)
            source.adaptOutputFormat(videoLimits.width, videoLimits.height, videoLimits.fps)
            if (capture) videoCapturer.startCapture(videoLimits.width, videoLimits.height, videoLimits.fps)
            captureRunning = capture
            localVideoTrack = pcFactory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, source).also { track ->
                track.setEnabled(capture)
                bindLocalVideoTrack(peerConnection ?: error("Call ended"), track)
                localSink?.let { track.addSink(it) }
            }
            _events.tryEmit(WebRtcEvent.LocalCameraFacingChanged(expectedCamera, cameraIsFrontFacing))
            applyQualityLimits()
            true
        } catch (e: Exception) {
            // Этап J.1-3 п.11: CAMERA denied, no camera, or camera in use
            // must degrade to audio-only instead of crashing the call.
            android.util.Log.w(TAG, "local video unavailable: ${e.javaClass.simpleName}: ${e.message}")
            stopLocalVideo()
            false
        }
    }

    override suspend fun createOffer(): String = suspendCoroutine { continuation ->
        val pc = peerConnection ?: return@suspendCoroutine continuation.resumeWithException(IllegalStateException("not initialized"))
        try { applyVideoNegotiationIntent(pc) }
        catch (error: Exception) { continuation.resumeWithException(error); return@suspendCoroutine }
        pc.createOffer(
            object : SdpObserver by NoopSdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (pc !== peerConnection) {
                        continuation.resumeWithException(IllegalStateException("Call ended"))
                        return
                    }
                    pc.setLocalDescription(object : SdpObserver by NoopSdpObserver {
                        override fun onSetSuccess() {
                            if (pc === peerConnection) { applyQualityLimits(); continuation.resume(description.description) }
                            else continuation.resumeWithException(IllegalStateException("Call ended"))
                        }
                        override fun onSetFailure(error: String?) {
                            continuation.resumeWithException(IllegalStateException("Local SDP failed"))
                        }
                    }, description)
                }
                override fun onCreateFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException(error))
                }
            },
            MediaConstraints(),
        )
    }

    override suspend fun createAnswer(): String = suspendCoroutine { continuation ->
        val pc = peerConnection ?: return@suspendCoroutine continuation.resumeWithException(IllegalStateException("not initialized"))
        try { applyVideoNegotiationIntent(pc) }
        catch (error: Exception) { continuation.resumeWithException(error); return@suspendCoroutine }
        pc.createAnswer(
            object : SdpObserver by NoopSdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (pc !== peerConnection) {
                        continuation.resumeWithException(IllegalStateException("Call ended"))
                        return
                    }
                    pc.setLocalDescription(object : SdpObserver by NoopSdpObserver {
                        override fun onSetSuccess() {
                            if (pc === peerConnection) { applyQualityLimits(); continuation.resume(description.description) }
                            else continuation.resumeWithException(IllegalStateException("Call ended"))
                        }
                        override fun onSetFailure(error: String?) {
                            continuation.resumeWithException(IllegalStateException("Local SDP failed"))
                        }
                    }, description)
                }
                override fun onCreateFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException(error))
                }
            },
            MediaConstraints(),
        )
    }

    override suspend fun setRemoteDescription(type: String, sdp: String): Unit = suspendCoroutine { continuation ->
        val pc = peerConnection ?: return@suspendCoroutine continuation.resumeWithException(IllegalStateException("not initialized"))
        val sdpType = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        pc.setRemoteDescription(
            object : SdpObserver by NoopSdpObserver {
                override fun onSetSuccess() {
                    if (pc !== peerConnection) continuation.resumeWithException(IllegalStateException("Call ended"))
                    else { applyQualityLimits(); continuation.resume(Unit) }
                }
                override fun onSetFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException(error))
                }
            },
            SessionDescription(sdpType, sdp),
        )
    }

    override fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid.orEmpty(), sdpMLineIndex ?: 0, candidate))
    }

    override fun restartIce() {
        peerConnection?.restartIce()
    }

    override fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    override fun setSpeakerOn(on: Boolean) {
        val kind = if (on) AudioRouteKind.SPEAKER else
            CallAudioPolicy.initialRoute(availableAudioRoutes().map { it.kind }, currentAudioRoute().kind) ?: return
        setAudioRoute(AudioRoute(kind, CallAudioPolicy.defaultLabel(kind)))
    }

    override fun setVideoEnabled(enabled: Boolean) {
        if (!enabled) {
            localVideoTrack?.setEnabled(false)
            if (captureRunning) capturer?.stopCapture()
            captureRunning = false
        } else {
            if (!captureRunning) {
                localSink?.reset()
                capturer?.startCapture(videoLimits.width, videoLimits.height, videoLimits.fps)
                captureRunning = capturer != null
            }
            localVideoTrack?.setEnabled(true)
        }
    }

    override fun startLocalVideo(capture: Boolean): Boolean {
        if (localVideoRunning()) return true
        val pc = peerConnection ?: return false
        val pcFactory = factory ?: return false
        return setupLocalVideo(pcFactory, capture)
    }

    private fun applyQualityLimits() {
        // A concurrent hang-up must not strand the SDP continuation if native handles were disposed.
        runCatching {
            peerConnection?.senders?.forEach { sender ->
                val kind = sender.track()?.kind() ?: return@forEach
                if (kind != "audio" && kind != "video") return@forEach
                val parameters = sender.parameters
                if (parameters.encodings.isEmpty()) return@forEach // Applied again after SDP negotiation.
                parameters.encodings.forEach { encoding ->
                    encoding.maxBitrateBps = if (kind == "video") videoLimits.bitrate else audioBitrate
                    if (kind == "video") encoding.maxFramerate = videoLimits.fps
                }
                if (kind == "video") parameters.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.BALANCED
                if (!sender.setParameters(parameters)) android.util.Log.w(TAG, "Call quality limits rejected for $kind")
            }
        }.onFailure { android.util.Log.w(TAG, "Call quality update unavailable") }
    }

    override fun stopLocalVideo() {
        // Invalidate before stopping: stop/dispose can deliver old callbacks.
        cameraGeneration++
        cameraSwitchPending = false
        val pc = peerConnection
        if (pc != null && localVideoTrack != null) {
            runCatching {
                val sender = pc.transceivers.firstOrNull { it.sender.id() == localVideoSenderId }?.sender
                if (sender != null) {
                    if (!pc.removeTrack(sender)) android.util.Log.w(TAG, "Owned video sender removal rejected")
                    // removeTrack changes native state, not Java's cachedTrack.
                    // Drop that wrapper without taking ownership of our track.
                    if (!sender.setTrack(null, false)) android.util.Log.w(TAG, "Owned video sender detach rejected")
                }
            }
        }
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        captureRunning = false
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
    }

    override fun setVideoNegotiationEnabled(enabled: Boolean) {
        videoNegotiationAllowed = enabled
    }

    private fun applyVideoNegotiationIntent(pc: PeerConnection) {
        val direction = if (videoNegotiationAllowed) org.webrtc.RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            else org.webrtc.RtpTransceiver.RtpTransceiverDirection.INACTIVE
        for (transceiver in pc.transceivers) {
            if (transceiver.mediaType == org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !transceiver.isStopped) {
                check(transceiver.setDirection(direction)) { "Video negotiation direction rejected" }
            }
        }
    }

    // Reattach to our existing transceiver. addTrack may allocate another
    // m-section after a sender has previously sent, even with a reused track ID.
    private fun bindLocalVideoTrack(pc: PeerConnection, track: VideoTrack) {
        val owned = localVideoSenderId?.let { id -> pc.transceivers.firstOrNull { it.sender.id() == id && !it.isStopped } }
        if (owned != null) {
            check(owned.sender.setTrack(track, false)) { "Owned video sender rebind rejected" }
            owned.sender.setStreams(listOf(LOCAL_STREAM_ID))
        } else {
            localVideoSenderId = pc.addTrack(track, listOf(LOCAL_STREAM_ID)).id()
        }
    }

    override fun hasMultipleCameras(): Boolean =
        runCatching { Camera2Enumerator(context).deviceNames.size > 1 }.getOrDefault(false)

    override fun switchCamera() {
        val activeCapturer = capturer ?: return
        if (!captureRunning || cameraSwitchPending) return
        val expectedCamera = cameraGeneration
        cameraSwitchPending = true
        try { activeCapturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                cameraCallbackHandler.post {
                    if (isCurrentCameraGeneration(expectedCamera)) {
                        cameraSwitchPending = false
                        cameraIsFrontFacing = isFrontCamera
                        _events.tryEmit(WebRtcEvent.LocalCameraFacingChanged(expectedCamera, isFrontCamera))
                    }
                }
            }
            override fun onCameraSwitchError(error: String?) = reportCameraFailure(expectedCamera)
        }) } catch (_: Exception) { reportCameraFailure(expectedCamera) }
    }

    override fun isCurrentCameraGeneration(generation: Long): Boolean =
        capturer != null && cameraGeneration == generation

    override fun localCameraIsFrontFacing(): Boolean = cameraIsFrontFacing

    private fun reportCameraFailure(expectedCamera: Long) {
        cameraCallbackHandler.post {
            if (isCurrentCameraGeneration(expectedCamera)) {
                cameraSwitchPending = false
                if (captureRunning) _events.tryEmit(WebRtcEvent.LocalCameraFailed(expectedCamera))
            }
        }
    }

    override fun availableAudioRoutes(): List<AudioRoute> {
        val currentId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice?.id else null
        return callOutputDevices().sortedBy { if (it.id == currentId) 0 else 1 }.mapNotNull { device ->
            val kind = routeKind(device.type) ?: return@mapNotNull null
            val name = if (kind == AudioRouteKind.BLUETOOTH || kind == AudioRouteKind.WIRED_HEADSET) device.productName?.toString() else null
            AudioRoute(kind, CallAudioPolicy.label(kind, name))
        }.distinctBy { it.kind }
    }

    private fun callOutputDevices(): List<AudioDeviceInfo> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.availableCommunicationDevices
         else audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList())
            .filter { it.isSink && routeKind(it.type) != null }

    private fun routeKind(type: Int): AudioRouteKind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRouteKind.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRouteKind.SPEAKER
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRouteKind.BLUETOOTH
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AudioRouteKind.BLUETOOTH else null
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRouteKind.WIRED_HEADSET
        else -> null
    }

    override fun setAudioRoute(route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = callOutputDevices()
            val candidates = devices.mapNotNull { device -> routeKind(device.type)?.let { device.id to it } }
            val id = CallAudioPolicy.preferredDeviceId(candidates, route.kind, audioManager.communicationDevice?.id)
            val device = devices.firstOrNull { it.id == id } ?: return
            // An absent/disconnected requested device is not permission to
            // clear a valid headset selection and fall back to a loud route.
            audioManager.setCommunicationDevice(device)
        } else {
            if (availableAudioRoutes().none { it.kind == route.kind }) return
            legacyStopBluetoothSco()
            when (route.kind) {
                AudioRouteKind.SPEAKER -> audioManager.isSpeakerphoneOn = true
                AudioRouteKind.EARPIECE, AudioRouteKind.WIRED_HEADSET -> audioManager.isSpeakerphoneOn = false
                AudioRouteKind.BLUETOOTH -> {
                    audioManager.isSpeakerphoneOn = false
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    legacyScoRequested = true
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyStopBluetoothSco() {
        if (legacyScoRequested || audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
        legacyScoRequested = false
    }

    override fun currentAudioRoute(): AudioRoute {
        val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let { routeKind(it.type) } ?: AudioRouteKind.EARPIECE
        } else {
            @Suppress("DEPRECATION")
            when {
                audioManager.isBluetoothScoOn -> AudioRouteKind.BLUETOOTH
                audioManager.isSpeakerphoneOn -> AudioRouteKind.SPEAKER
                audioManager.isWiredHeadsetOn -> AudioRouteKind.WIRED_HEADSET
                else -> AudioRouteKind.EARPIECE
            }
        }
        return AudioRoute(kind, CallAudioPolicy.defaultLabel(kind))
    }

    override fun attachLocalRenderer(renderer: Any?) {
        val surfaceRenderer = renderer as? LocalTextureRenderer ?: return
        if (localRendererSlot.attached === surfaceRenderer) return // already current - no re-init
        localRendererSlot.attached?.let { old -> detachLocalRenderer(old) }
        localRendererSlot.attach(surfaceRenderer)
        surfaceRenderer.init(eglBase.eglBaseContext, null)
        val sink = localFirstFrameSink(surfaceRenderer)
        localSink = sink
        localVideoTrack?.let { track -> track.addSink(sink) }
    }

    private fun localFirstFrameSink(renderer: LocalTextureRenderer): FirstFrameSink {
        lateinit var sink: FirstFrameSink
        sink = FirstFrameSink(renderer) {
            if (localSink === sink && localRendererSlot.attached === renderer && captureRunning) {
                _events.tryEmit(WebRtcEvent.LocalFirstFrame)
            }
        }
        return sink
    }

    private fun bindRemoteTrack(track: VideoTrack) {
        if (remoteVideoTrack === track) return
        remoteFrameProbe?.let { remoteVideoTrack?.removeSink(it) }
        remoteSink?.let { remoteVideoTrack?.removeSink(it) }
        remoteVideoTrack = track
        val trackGeneration = mediaGeneration
        val probe = FirstFrameSink(VideoSink { }) {
            if (mediaGeneration == trackGeneration && remoteVideoTrack === track) _events.tryEmit(WebRtcEvent.RemoteFirstFrame)
        }
        remoteFrameProbe = probe
        // Announce the track before accepting its first frame, even with no call UI mounted.
        _events.tryEmit(WebRtcEvent.RemoteVideoEnabled(true))
        track.addSink(probe)
        remoteSink?.let { track.addSink(it) }
    }

    override fun attachRemoteRenderer(renderer: Any?) {
        val surfaceRenderer = renderer as? SurfaceViewRenderer ?: return
        if (remoteRendererSlot.attached === surfaceRenderer) return // already current - no re-init
        remoteRendererSlot.attached?.let { old -> detachRemoteRenderer(old) }
        remoteRendererSlot.attach(surfaceRenderer)
        surfaceRenderer.init(eglBase.eglBaseContext, null)
        val sink = FirstFrameSink(surfaceRenderer) { _events.tryEmit(WebRtcEvent.RemoteFirstFrame) }
        remoteSink = sink
        remoteVideoTrack?.let { track -> track.addSink(sink) }
    }

    override fun detachLocalRenderer(renderer: Any?) {
        val surfaceRenderer = renderer as? LocalTextureRenderer ?: return
        if (!localRendererSlot.detach(surfaceRenderer)) return // stale renderer - never touch a newer one
        localSink?.let { localVideoTrack?.removeSink(it) }
        localSink = null
        surfaceRenderer.release()
    }

    override fun detachRemoteRenderer(renderer: Any?) {
        val surfaceRenderer = renderer as? SurfaceViewRenderer ?: return
        if (!remoteRendererSlot.detach(surfaceRenderer)) return // stale renderer - never touch a newer one
        remoteSink?.let { remoteVideoTrack?.removeSink(it) }
        remoteSink = null
        surfaceRenderer.release()
    }

    override fun reinitRenderers() {
        // MIUI (and a few other OEMs) destroy the SurfaceView surface while
        // the app is backgrounded and the renderer does not always recover on
        // its own. Re-establish the EGL surface + sink without touching the
        // PeerConnection or the running camera capture. A fresh FirstFrameSink
        // is created so the next frame re-signals ON after background return.
        localRendererSlot.attached?.let { renderer ->
            localSink?.let { localVideoTrack?.removeSink(it) }
            renderer.release()
            renderer.init(eglBase.eglBaseContext, null)
            val sink = localFirstFrameSink(renderer)
            localSink = sink
            localVideoTrack?.let { track -> track.addSink(sink) }
        }
        remoteRendererSlot.attached?.let { renderer ->
            remoteSink?.let { remoteVideoTrack?.removeSink(it) }
            renderer.release()
            renderer.init(eglBase.eglBaseContext, null)
            val sink = FirstFrameSink(renderer) { _events.tryEmit(WebRtcEvent.RemoteFirstFrame) }
            remoteSink = sink
            remoteVideoTrack?.let { track -> track.addSink(sink) }
        }
    }

    override fun expectRemoteVideoFrame() { remoteFrameProbe?.reset(); remoteSink?.reset() }

    override fun localVideoRunning(): Boolean = localVideoTrack != null

    override fun dispose() {
        cameraGeneration++
        cameraSwitchPending = false
        ++mediaGeneration
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        captureRunning = false
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        // Remove video sinks and release renderers BEFORE the tracks go away.
        detachLocalRenderer(localRendererSlot.attached)
        detachRemoteRenderer(remoteRendererSlot.attached)
        stopAudioDeviceMonitoring()
        localVideoTrack?.dispose()
        localVideoTrack = null
        remoteFrameProbe?.let { remoteVideoTrack?.removeSink(it) }
        remoteFrameProbe = null
        remoteVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        localVideoSenderId = null
        videoNegotiationAllowed = false
        abandonAudioFocus()
    }

    /** Observes hardware output-route changes (Bluetooth / wired headset
     *  connected or disconnected) so the UI recomposes without needing an
     *  unrelated [CallUiState] change. S+ uses the modern AudioDeviceCallback;
     *  pre-S falls back to a wired-headset BroadcastReceiver (Bluetooth SCO on
     *  pre-S is covered by the explicit route reads in [currentAudioRoute]). */
    private fun startAudioDeviceMonitoring() {
        stopAudioDeviceMonitoring()
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = emitAudioRouteChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = emitAudioRouteChanged()
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        deviceCallback = callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { emitAudioRouteChanged() }
            audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
            communicationListener = listener
        } else {
            @Suppress("DEPRECATION")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == AudioManager.ACTION_HEADSET_PLUG || intent?.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) emitAudioRouteChanged()
                }
            }
            val filter = IntentFilter(AudioManager.ACTION_HEADSET_PLUG)
            filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            context.registerReceiver(receiver, filter)
            headsetReceiver = receiver
        }
    }

    private fun emitAudioRouteChanged() {
        _events.tryEmit(WebRtcEvent.AudioRouteChanged(currentAudioRoute()))
    }

    private fun stopAudioDeviceMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            communicationListener?.let { audioManager.removeOnCommunicationDeviceChangedListener(it) }
            communicationListener = null
        }
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        headsetReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        headsetReceiver = null
    }

    override suspend fun getStats(): WebRtcCallStats? {
        val pc = peerConnection ?: return null
        return suspendCoroutine { continuation ->
            pc.getStats { report ->
                val entries = report.statsMap.values.map { stat ->
                    WebRtcStatsEntry(stat.id, stat.type, stat.members)
                }
                continuation.resume(WebRtcCallStatsParser.parse(entries))
            }
        }
    }

    private var callAudioPrepared = false
    private var legacyScoRequested = false

    override fun prepareCallAudio() {
        requestAudioFocus()
        startAudioDeviceMonitoring()
    }

    private fun requestAudioFocus() {
        if (callAudioPrepared) return
        callAudioPrepared = true
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val routes = availableAudioRoutes()
        val current = currentAudioRoute().kind
        val initial = CallAudioPolicy.initialRoute(routes.map { it.kind }, current)
        // Preserve the active physical headset, not merely the first device
        // of its kind. Defaults are chosen only once per call; initialize,
        // route-picker actions and resume must not overwrite a manual route.
        if (initial != null && !(initial == current && current in listOf(AudioRouteKind.BLUETOOTH, AudioRouteKind.WIRED_HEADSET))) {
            routes.firstOrNull { it.kind == initial }?.let(::setAudioRoute)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonAudioFocus() {
        if (!callAudioPrepared) return
        callAudioPrepared = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
        audioFocusRequest = null
        // Этап J.1-7: release any explicitly-selected communication device
        // (e.g. a Bluetooth headset) so the route resets for the next call.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else if (legacyScoRequested) {
            legacyStopBluetoothSco()
        }
        audioManager.mode = previousAudioMode
        audioManager.isSpeakerphoneOn = false
    }

    @Volatile private var mediaGeneration = 0L

    private fun observer(generation: Long) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            if (generation != mediaGeneration) return
            _events.tryEmit(WebRtcEvent.LocalIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (generation != mediaGeneration) return
            val mapped = when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> PeerConnectionState.CONNECTED
                PeerConnection.IceConnectionState.DISCONNECTED -> PeerConnectionState.DISCONNECTED
                PeerConnection.IceConnectionState.FAILED -> PeerConnectionState.FAILED
                PeerConnection.IceConnectionState.CLOSED -> PeerConnectionState.CLOSED
                PeerConnection.IceConnectionState.CHECKING -> PeerConnectionState.CHECKING
                else -> PeerConnectionState.NEW
            }
            _events.tryEmit(WebRtcEvent.ConnectionStateChanged(mapped))
        }
        override fun onAddStream(stream: MediaStream) {
            if (generation != mediaGeneration) return
            val remoteVideo = stream.videoTracks.firstOrNull()
            remoteVideo?.let { track ->
                bindRemoteTrack(track)
            }
        }
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            if (generation != mediaGeneration) return
            val track = transceiver.receiver.track()
            if (track is VideoTrack) {
                bindRemoteTrack(track)
            }
        }
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private object NoopSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    companion object {
        const val TAG = "WebRtcEngine"
        const val LOCAL_AUDIO_TRACK_ID = "messenger-audio-0"
        const val LOCAL_VIDEO_TRACK_ID = "messenger-video-0"
        const val LOCAL_STREAM_ID = "messenger-stream-0"
    }
}

/** Wraps a renderer `VideoSink` to signal the first actual frame, so the UI
 *  can distinguish "signaling says camera on" from "a frame really arrived"
 *  (code-review 3/4). Fires [onFirstFrame] exactly once per wrapper. */
private class FirstFrameSink(
    private val delegate: VideoSink,
    private val onFirstFrame: () -> Unit,
) : VideoSink {
    @Volatile private var fired = false

    fun reset() { fired = false }

    override fun onFrame(frame: VideoFrame) {
        if (!fired) {
            fired = true
            onFirstFrame()
        }
        delegate.onFrame(frame)
    }
}
