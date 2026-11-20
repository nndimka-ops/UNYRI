package com.messenger.app.data.call

import com.messenger.app.BuildConfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import com.messenger.app.data.api.IceServerDto
import com.messenger.app.domain.model.CallMediaKind
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import livekit.org.webrtc.AudioSource
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.CameraVideoCapturer
import livekit.org.webrtc.DefaultVideoDecoderFactory
import livekit.org.webrtc.DefaultVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoSource
import livekit.org.webrtc.VideoTrack

/** Real WebRTC implementation (Этап J) - standard `livekit.org.webrtc.*` API via the
 *  shared M144 library bundled with LiveKit. No custom RTP/
 *  SRTP/ICE/codec logic anywhere in this class - purely wires up the
 *  standard PeerConnection APIs. Media requires on-device verification;
 *  the local SDP transaction is shared with [CallLocalSdp]'s JVM boundary tests. */
class WebRtcEngineImpl(private val context: Context) : WebRtcEngine {
    private var peerIsContact = false
    override fun setPeerContact(isContact: Boolean) { peerIsContact = isContact }

    // Lazy on purpose (M.2 / Этап K.9): EglBase.create() initializes EGL and
    // loads native code - several seconds of cold-start cost that must NOT
    // sit on the process-dead FCM incoming-call path. It is only needed once
    // real media setup begins (on Accept), never for the ringing notification.
    private val eglBase: EglBase by lazy { EglBase.create() }
    private val _events = MutableSharedFlow<WebRtcEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<WebRtcEvent> = _events

    override fun remoteVoiceLevels(): kotlinx.coroutines.flow.Flow<Float> = flow {
        val estimator = CallVoiceLevelEstimator()
        while (currentCoroutineContext().isActive) {
            val sample = withTimeoutOrNull(400) {
                // Serialize the request with receiver replacement/disposal. Native stats
                // collection/callback and energy calculation run off the UI thread.
                withContext(Dispatchers.Main.immediate) {
                    val pc = peerConnection ?: return@withContext null
                    val generation = mediaGeneration
                    // Never call pc.receivers here: that getter disposes/replaces wrappers.
                    val receiver = remoteReceivers.values.firstOrNull {
                        runCatching { it.track()?.kind() == "audio" }.getOrDefault(false)
                    } ?: return@withContext null
                    suspendCancellableCoroutine<CallAudioEnergy?> { continuation ->
                        try {
                            pc.getStats(receiver) { report ->
                                val audio = report.statsMap.values.firstOrNull { it.type == "inbound-rtp" &&
                                    (it.members["kind"] == "audio" || it.members["mediaType"] == "audio") }
                                fun value(key: String) = (audio?.members?.get(key) as? Number)?.toDouble()
                                val energy = value("totalAudioEnergy"); val duration = value("totalSamplesDuration"); val bytes = value("bytesReceived")
                                val next = if (generation == mediaGeneration && energy != null && duration != null && bytes != null)
                                    CallAudioEnergy(energy, duration, bytes) else null
                                if (continuation.isActive) continuation.resume(next)
                            }
                        } catch (_: RuntimeException) { if (continuation.isActive) continuation.resume(null) }
                    }
                }
            }
            emit(estimator.update(sample))
            delay(140)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    override fun dtlsFingerprints(): Pair<String, String>? {
        val pc = peerConnection ?: return null
        val local = pc.localDescription?.description?.let(com.messenger.app.domain.call.CallSasHandshake::fingerprint) ?: return null
        val remote = pc.remoteDescription?.description?.let(com.messenger.app.domain.call.CallSasHandshake::fingerprint) ?: return null
        return local to remote
    }
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    // Java getters replace/dispose wrappers; only the native sender ID is stable.
    private var localVideoSenderId: String? = null
    private var videoNegotiationAllowed = false
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteVideoStreamIds = emptySet<String>()
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var captureOrientation: CallCaptureOrientation? = null
    private val _localOrientation = kotlinx.coroutines.flow.MutableStateFlow(CallFrameOrientation())
    override val localOrientation: kotlinx.coroutines.flow.StateFlow<CallFrameOrientation> = _localOrientation
    private val _remoteGeometry = kotlinx.coroutines.flow.MutableStateFlow(CallRemoteGeometry())
    override val remoteGeometry: kotlinx.coroutines.flow.StateFlow<CallRemoteGeometry> = _remoteGeometry
    private val _torchState = kotlinx.coroutines.flow.MutableStateFlow(CallTorchState())
    override val torchState: kotlinx.coroutines.flow.StateFlow<CallTorchState> = _torchState
    override fun setTorch(on: Boolean) {
        if (captureRunning && !cameraSwitchPending && (!on || _torchState.value.available)) {
            (capturer as? livekit.org.webrtc.CallTorchCamera2Capturer)?.setTorch(on)
        }
    }
    override fun toggleTorch() {
        if (captureRunning && !cameraSwitchPending && _torchState.value.available) {
            (capturer as? livekit.org.webrtc.CallTorchCamera2Capturer)?.toggleTorch()
        }
    }
    private var captureRunning = false
    private var videoLimits = CallQuality.AUTO.video
    private var audioBitrate = CallQuality.AUTO.audioBitrate
    private var mediaProfile = CallMediaLadder.cellular
    private var callDataSaving = CallDataSaving.NEVER
    private var videoSendActive = true
    private var pausePolicy = CallVideoPausePolicy()
    private var deadPairPolicy = CallDeadPairPolicy()
    private var connected = false
    private var networkInterface: Int? = null
    private var lastLink: CallBitrateSeedPolicy.Link? = null
    private var lastLinkAt: Long? = null
    private var diagnostics = CallMediaDiagnostics(SystemClock.elapsedRealtime())
    @Volatile private var diagnosticsRecording = false
    private var localSdp: String? = null
    private var remoteSdp: String? = null
    private val remoteReceivers = mutableMapOf<String, RtpReceiver>()
    private val receiverObservers = mutableMapOf<String, RtpReceiver.Observer>()
    private var audioPathDiagnostics = CallAudioPathDiagnostics(context)
    private var lastStats: WebRtcCallStats? = null
    private var cameraGeneration = 0L
    private var cameraIsFrontFacing = true
    private var cameraSwitchPending = false
    private val cameraCallbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val localRendererSlot = RendererSlot<LocalTextureRenderer>()
    private val remoteRendererSlot = RendererSlot<SurfaceViewRenderer>()
    private var localRendererVisible = true
    private var remoteRendererVisible = true
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
        // Share LiveKit's once-only native initialization and logger; no Room is created.
        io.livekit.android.LiveKit.init(context)
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        val created = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        factory = created
        return created
    }

    override fun initialize(iceServers: List<IceServerDto>, mediaKind: CallMediaKind) =
        initialize(iceServers, mediaKind, captureVideo = mediaKind == CallMediaKind.VIDEO)

    private fun iceConfig(iceServers: List<IceServerDto>, contact: Boolean): WebRtcIceConfig {
        val preferences = context.getSharedPreferences(CallQualityPreferences.STORE, Context.MODE_PRIVATE)
        return WebRtcIceTransportPolicy.buildConfig(iceServers, com.messenger.app.BuildConfig.DEBUG,
            com.messenger.app.BuildConfig.FORCE_WEBRTC_RELAY,
            CallPeerToPeer.resolved(preferences.getString(CallPeerToPeer.KEY, null)), contact)
    }

    override fun relayUnavailable(iceServers: List<IceServerDto>, peerIsContact: Boolean): Boolean =
        iceConfig(iceServers, peerIsContact).relayUnavailable

    override fun initialize(iceServers: List<IceServerDto>, mediaKind: CallMediaKind, captureVideo: Boolean) {
        val iceConfig = iceConfig(iceServers, peerIsContact)
        peerIsContact = false // Membership must be supplied again for each new call.
        check(!iceConfig.relayUnavailable) { "relay_unavailable" }
        sdpMutex = kotlinx.coroutines.sync.Mutex()
        localVideoSenderId = null
        videoNegotiationAllowed = mediaKind == CallMediaKind.VIDEO
        val preferences = context.getSharedPreferences(CallQualityPreferences.STORE, Context.MODE_PRIVATE)
        networkInterface = activeNetworkInterface()
        mediaProfile = networkProfile()
        videoLimits = CallVideoLimits(mediaProfile.width, mediaProfile.height, mediaProfile.fps, mediaProfile.maxVideoBitrate)
        audioBitrate = mediaProfile.audioMaxBitrate
        videoSendActive = true
        connected = false
        pausePolicy = CallVideoPausePolicy()
        deadPairPolicy = CallDeadPairPolicy()
        lastLink = null; lastLinkAt = null
        diagnostics = CallMediaDiagnostics(SystemClock.elapsedRealtime())
        audioPathDiagnostics = CallAudioPathDiagnostics(context)
        lastStats = null
        localSdp = null; remoteSdp = null; remoteReceivers.clear(); receiverObservers.clear()
        remoteVideoStreamIds = emptySet()
        requestAudioFocus()
        startAudioDeviceMonitoring()
        recordAudioPath(1)
        val rtcIceServers = iceServers.map { server ->
            val builder = PeerConnection.IceServer.builder(server.urls)
            if (server.username != null && server.credential != null) {
                builder.setUsername(server.username).setPassword(server.credential)
            }
            builder.createIceServer()
        }
        remoteRelayOnly = iceConfig.transports == IceTransportPolicy.RELAY
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
        // This SDK's RTCConfiguration has no audioRtcpReportIntervalMs property.
        // The native default remains until the dependency exposes the public setting.
        seedEstimate(mediaProfile.startBitrate)

        audioSource = pcFactory.createAudioSource(MediaConstraints())
        localAudioTrack = pcFactory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, audioSource).also {
            peerConnection?.addTrack(it, listOf(LOCAL_AUDIO_STREAM_ID))
        }

        if (mediaKind == CallMediaKind.VIDEO) {
            setupLocalVideo(pcFactory, capture = captureVideo, seed = null)
        }
    }

    private fun setupLocalVideo(pcFactory: PeerConnectionFactory, capture: Boolean = true, seed: Int? = cameraStartSeed()): Boolean {
        if (capture && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try {
            val enumerator = Camera2Enumerator(context)
            val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: return false
            val expectedCamera = ++cameraGeneration
            cameraSwitchPending = false
            cameraIsFrontFacing = enumerator.isFrontFacing(deviceName)
            val videoCapturer = livekit.org.webrtc.CallTorchCamera2Capturer(context, deviceName, object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(error: String?) = reportCameraFailure(expectedCamera)
                override fun onCameraDisconnected() = reportCameraFailure(expectedCamera)
                override fun onCameraFreezed(error: String?) = reportCameraFailure(expectedCamera)
                override fun onCameraOpening(cameraName: String?) {}
                override fun onFirstFrameAvailable() {}
                override fun onCameraClosed() {}
            }, { available, on ->
                cameraCallbackHandler.post {
                    if (isCurrentCameraGeneration(expectedCamera)) {
                        _torchState.value = CallTorchState(available && captureRunning, on && captureRunning)
                    }
                }
            })
            capturer = videoCapturer
            val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            surfaceTextureHelper = helper
            val source = pcFactory.createVideoSource(videoCapturer.isScreencast)
            videoSource = source
            val orientation = CallCaptureOrientation(context)
            captureOrientation = orientation
            var loggedTag = -1
            var loggedMetadata = -1
            videoCapturer.initialize(helper, context, CallRotationCapturerObserver(source.capturerObserver, {
                val pair = orientation.snapshot()
                if (_localOrientation.value != pair) _localOrientation.value = pair
                val tag = pair.tag
                val metadata = videoCapturer.orientationMetadata
                val sensor = metadata and 511
                val front = metadata and 512 != 0
                val rotation = CallOrientationPolicy.frameRotation(sensor, front, tag)
                if (BuildConfig.DEBUG && (loggedTag != tag || loggedMetadata != metadata)) {
                    android.util.Log.d("CallOrientation", "tag=$tag sensor=$sensor front=$front rot=$rotation screen=${pair.screen}")
                    loggedTag = tag; loggedMetadata = metadata
                }
                rotation
            }, { started -> cameraCallbackHandler.post {
                if (isCurrentCameraGeneration(expectedCamera)) {
                    if (started && captureRunning) orientation.start() else orientation.stop()
                }
            } }))
            source.adaptOutputFormat(videoLimits.width, videoLimits.height, videoLimits.fps)
            if (capture) videoCapturer.startCapture(captureProfile().width, captureProfile().height, captureProfile().fps)
            captureRunning = capture
            localVideoTrack = pcFactory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, source).also { track ->
                track.setEnabled(capture)
                seedEstimate(seed)
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

    // Rollback waits for an in-flight native SDP callback. An invalidated
    // producer cannot bind its later offer after the crossed offer is answered.
    private var sdpMutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun createOffer(): String = createOffer { true }
    override suspend fun createOffer(isCurrent: () -> Boolean): String {
        val pc = peerConnection ?: error("not initialized")
        val mutex = sdpMutex
        fun current() = pc === peerConnection && mutex === sdpMutex && isCurrent()
        return mutex.withLock {
            check(current()) { "Offer superseded" }
            createLocalDescriptionLocked(pc, SessionDescription.Type.OFFER, ::current)
        }
    }
    override suspend fun createAnswer(): String = createAnswer { true }
    override suspend fun createAnswer(isCurrent: () -> Boolean): String {
        val pc = peerConnection ?: error("not initialized")
        val mutex = sdpMutex
        fun current() = pc === peerConnection && mutex === sdpMutex && isCurrent()
        return mutex.withLock {
            check(current()) { "Answer superseded" }
            createLocalDescriptionLocked(pc, SessionDescription.Type.ANSWER, ::current)
        }
    }
    override suspend fun setRemoteDescription(type: String, sdp: String) = setRemoteDescription(type, sdp) { true }
    override suspend fun setRemoteDescription(type: String, sdp: String, isCurrent: () -> Boolean) {
        val pc = peerConnection ?: error("not initialized")
        val mutex = sdpMutex
        fun current() = pc === peerConnection && mutex === sdpMutex && isCurrent()
        mutex.withLock {
            check(current()) { "Description superseded" }
            setRemoteDescriptionLocked(pc, type, sdp, ::current)
        }
    }
    override suspend fun rollbackLocalDescription() = rollbackLocalDescription { true }
    override suspend fun rollbackLocalDescription(isCurrent: () -> Boolean) {
        // Capture before suspension. initialize() replaces both fields; a queued
        // old operation must never select that replacement connection inside the lock.
        val pc = peerConnection ?: return
        val mutex = sdpMutex
        mutex.withLock {
            if (pc !== peerConnection || mutex !== sdpMutex || !isCurrent()) return@withLock
            rollbackLocalDescriptionLocked(pc)
        }
    }

    private suspend fun createLocalDescriptionLocked(pc: PeerConnection, type: SessionDescription.Type, isCurrent: () -> Boolean): String {
        traceSdp(pc, "create-$type")
        try {
            applyVideoNegotiationIntent(pc)
            applyAudioCodecPreferences(pc)
            applyVideoCodecPreferences(pc)
            return CallLocalSdp.createAndApply(pc, type, mediaProfile.requestsDtx,
                isCurrent = isCurrent) { local ->
                applyQualityLimits()
                noteDescription(pc, local.description, true)
                traceSdp(pc, "local-$type-applied")
            }
        } catch (error: Exception) {
            traceSdp(pc, "local-$type-failed error=${error.javaClass.simpleName}")
            throw error
        }
    }

    /** Negotiation-only diagnostics: never log SDP, candidates or personal identifiers. */
    private fun traceSdp(pc: PeerConnection, operation: String) {
        if (BuildConfig.DEBUG) {
            val state = if (pc === peerConnection) runCatching { pc.signalingState().name }.getOrDefault("unavailable") else "superseded"
            android.util.Log.d("CallNegotiation", "$operation signaling=$state video=$videoNegotiationAllowed")
        }
    }

    private var remoteRelayOnly = false

    private suspend fun setRemoteDescriptionLocked(pc: PeerConnection, type: String, sdp: String, isCurrent: () -> Boolean) {
        traceSdp(pc, "remote-$type-start")
        val sdpType = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        suspendCoroutine<Unit> { continuation ->
            pc.setRemoteDescription(
                object : SdpObserver by NoopSdpObserver {
                    override fun onSetSuccess() { continuation.resume(Unit) }
                    override fun onSetFailure(error: String?) {
                        traceSdp(pc, "remote-$type-failed")
                        continuation.resumeWithException(IllegalStateException(error))
                    }
                },
                SessionDescription(sdpType, RemoteIcePolicy.filterSdp(sdp, remoteRelayOnly)),
            )
        }
        // Complete on the owning coroutine, checking the captured session again
        // before quality/diagnostic bookkeeping can touch current engine state.
        check(isCurrent()) { "Description superseded" }
        traceSdp(pc, "remote-$type-applied")
        applyQualityLimits()
        noteDescription(pc, sdp, false)
    }

    override fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        if (!RemoteIcePolicy.allows(candidate, remoteRelayOnly)) return
        if (sdpMLineIndex != null && sdpMLineIndex !in 0..16) return
        peerConnection?.addIceCandidate(IceCandidate(sdpMid.orEmpty(), sdpMLineIndex ?: 0, candidate))
    }

    private suspend fun rollbackLocalDescriptionLocked(pc: PeerConnection): Unit = suspendCoroutine { continuation ->
        traceSdp(pc, "rollback-start")
        val state = pc.signalingState()
        if (state == PeerConnection.SignalingState.STABLE || state == PeerConnection.SignalingState.CLOSED) {
            continuation.resume(Unit); return@suspendCoroutine
        }
        pc.setLocalDescription(object : SdpObserver by NoopSdpObserver {
            override fun onSetSuccess() { traceSdp(pc, "rollback-applied"); continuation.resume(Unit) }
            override fun onSetFailure(error: String?) {
                traceSdp(pc, "rollback-failed")
                continuation.resumeWithException(IllegalStateException("Local SDP rollback failed"))
            }
        }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
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
            captureOrientation?.stop()
            if (captureRunning) capturer?.stopCapture()
            captureRunning = false
            _torchState.value = CallTorchState()
            endPauseForCameraChange(stopped = true)
        } else {
            // A camera-on seed belongs at capture restart, before a retained encoding can activate.
            seedEstimate(cameraStartSeed())
            if (!captureRunning) {
                localSink?.reset()
                captureOrientation?.refreshScreen()
                capturer?.startCapture(captureProfile().width, captureProfile().height, captureProfile().fps)
                captureRunning = capturer != null
            }
            localVideoTrack?.setEnabled(true)
            endPauseForCameraChange()
        }
        pausePolicy.captureChanged(SystemClock.elapsedRealtime())
    }

    override fun setVideoSendActive(active: Boolean) {
        // Timed retries intentionally keep the estimate reached during the pause.
        if (videoSendActive == active) return
        videoSendActive = active
        applyQualityLimits()
        _events.tryEmit(WebRtcEvent.VideoSendPausedChanged(!active))
    }

    override fun currentMediaProfile() = mediaProfile
    override fun setDiagnosticsRecording(recording: Boolean) { diagnosticsRecording = recording }

    private fun captureProfile() = callDataSaving.captureProfile()

    private fun seedEstimate(bps: Int?) { if (bps != null) peerConnection?.setBitrate(null, bps, null) }

    private fun cameraStartSeed(): Int? {
        val now = SystemClock.elapsedRealtime()
        val link = lastLink.takeIf { lastLinkAt?.let { now - it in 0..3_000 } == true }
        return CallBitrateSeedPolicy.seed(CallBitrateSeedPolicy.Cause.CAMERA_START, mediaProfile, link)
    }

    private fun activeNetworkInterface(): Int? = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching null
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapabilities.TRANSPORT_WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapabilities.TRANSPORT_CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkCapabilities.TRANSPORT_ETHERNET
            else -> NetworkCapabilities.TRANSPORT_VPN
        }
    }.getOrNull()

    private fun networkProfile(): CallMediaProfile {
        val preferences = context.getSharedPreferences(CallQualityPreferences.STORE, Context.MODE_PRIVATE)
        val saving = CallDataSaving.resolved(preferences.getString(CallDataSaving.KEY, null),
            preferences.getString(CallQualityPreferences.VIDEO, null), preferences.getString(CallQualityPreferences.AUDIO, null))
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        val metered = manager.isActiveNetworkMetered
        val tier = saving.tier(networkAvailable = capabilities != null,
            cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            metered = metered,
            constrained = metered && manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        callDataSaving = saving
        return CallMediaLadder.profile(tier)
    }

    private fun updateNetworkProfile(now: Long) {
        val currentInterface = activeNetworkInterface()
        if (currentInterface == null) deadPairPolicy.noteOutage(now)
        val changed = currentInterface != null && networkInterface != null && currentInterface != networkInterface
        if (currentInterface != null) networkInterface = currentInterface
        val nextProfile = networkProfile()
        if (nextProfile != mediaProfile || changed) {
            mediaProfile = nextProfile
            videoLimits = CallVideoLimits(mediaProfile.width, mediaProfile.height, mediaProfile.fps, mediaProfile.maxVideoBitrate)
            audioBitrate = mediaProfile.audioMaxBitrate
            // The seed always precedes activation or any row's new encodings.
            seedEstimate(CallBitrateSeedPolicy.seed(CallBitrateSeedPolicy.Cause.NEW_PATH, mediaProfile, null))
            val resume = pausePolicy.networkChanged(now) == CallVideoPausePolicy.Decision.RESUME
            if (resume) { videoSendActive = true; _events.tryEmit(WebRtcEvent.VideoSendPausedChanged(false)); recordVideoDecision(116, 2) }
            videoSource?.adaptOutputFormat(videoLimits.width, videoLimits.height, videoLimits.fps)
            applyQualityLimits()
        }
        if (changed) {
            deadPairPolicy.noteOutage(now)
            val code = when (currentInterface) { NetworkCapabilities.TRANSPORT_WIFI -> 1; NetworkCapabilities.TRANSPORT_CELLULAR -> 2; NetworkCapabilities.TRANSPORT_ETHERNET -> 3; else -> 4 }
            emitDiagnostics(CallDiagnosticEvent.create(3, 120, mapOf("network" to code)))
            _events.tryEmit(WebRtcEvent.NetworkInterfaceChanged)
        }
    }

    private fun endPauseForCameraChange(stopped: Boolean = false) {
        val wasPaused = pausePolicy.reset() != null
        if (wasPaused && stopped) recordVideoDecision(116, 3)
        if (!videoSendActive) { videoSendActive = true; applyQualityLimits(); _events.tryEmit(WebRtcEvent.VideoSendPausedChanged(false)) }
    }

    private fun recordVideoDecision(phase: Int, status: Int? = null) {
        val stats = lastStats
        val metrics = diagnostics.metrics
        emitDiagnostics(CallDiagnosticEvent.create(2, phase, mapOf("status" to status, "items" to metrics.availableOutgoingKbps,
            "budget_ms" to metrics.rttMs, "count" to metrics.outgoingLoss?.times(100),
            "reason" to CallMediaDiagnosticsPolicy.limitationCode(stats?.videoOutbound?.qualityLimitationReason),
            "width" to stats?.videoOutbound?.frameWidth, "height" to stats?.videoOutbound?.frameHeight,
            "attempt" to pausePolicy.failedTries.takeIf { phase == 115 })))
    }

    private fun emitDiagnostics(metrics: Map<String, Double>?) {
        if (diagnosticsRecording && !metrics.isNullOrEmpty()) _events.tryEmit(WebRtcEvent.Diagnostics(metrics))
    }

    private fun recordAudioPath(status: Int) {
        if (diagnosticsRecording) emitDiagnostics(audioPathDiagnostics.snapshot(status))
    }

    private fun noteDescription(pc: PeerConnection, sdp: String, local: Boolean) {
        if (local) localSdp = sdp else remoteSdp = sdp
        emitDiagnostics(diagnostics.descriptionApplied(localSdp, remoteSdp, local))
        refreshRemoteReceivers(pc)
    }

    private fun applyAudioCodecPreferences(pc: PeerConnection) {
        val codecs = factory?.getRtpReceiverCapabilities(livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)?.codecs ?: return
        val preferred = CallAudioCodecPreference.order(codecs.map { it.name }, mediaProfile.prefersRed).map { codecs[it] }
        if (preferred.isEmpty()) return
        pc.transceivers.filter { it.mediaType == livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO && !it.isStopped }
            .forEach { it.setCodecPreferences(preferred) }
    }

    /** Hardware H.264 first in both directions, VP8 and the rest kept ([CallVideoCodecPreference], B-047). */
    private fun applyVideoCodecPreferences(pc: PeerConnection) {
        val codecs = factory?.getRtpReceiverCapabilities(livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)?.codecs ?: return
        val preferred = CallVideoCodecPreference.order(codecs.map { it.name to it.parameters?.get("profile-level-id") }).map { codecs[it] }
        if (preferred.isEmpty()) return
        pc.transceivers.filter { it.mediaType == livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !it.isStopped }
            .forEach { transceiver ->
                // A refusal leaves WebRTC's own order; the call still negotiates.
                runCatching { transceiver.setCodecPreferences(preferred) }
                    .onFailure { android.util.Log.w(TAG, "video codec preferences refused: ${it.javaClass.simpleName}") }
            }
    }

    private fun refreshRemoteReceivers(pc: PeerConnection) {
        val generation = mediaGeneration
        pc.receivers.forEach { receiver ->
            val id = receiver.id()
            remoteReceivers[id] = receiver
            val observer = object : RtpReceiver.Observer {
                override fun onFirstPacketReceived(mediaType: livekit.org.webrtc.MediaStreamTrack.MediaType) {
                    if (generation != mediaGeneration) return
                    val kind = when (mediaType) {
                        livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> "audio"
                        livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> "video"
                        else -> return
                    }
                    emitDiagnostics(diagnostics.firstPacket(kind, SystemClock.elapsedRealtime()))
                }
            }
            receiverObservers[id] = observer
            receiver.SetObserver(observer)
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
                    if (kind == "video") { encoding.maxFramerate = videoLimits.fps; encoding.active = videoSendActive }
                    else {
                        encoding.minBitrateBps = mediaProfile.audioMinBitrate
                        encoding.bitratePriority = 4.0
                    }
                }
                if (kind == "video") parameters.degradationPreference = livekit.org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                if (!sender.setParameters(parameters)) android.util.Log.w(TAG, "Call quality limits rejected for $kind")
            }
        }.onFailure { android.util.Log.w(TAG, "Call quality update unavailable") }
    }

    override fun stopLocalVideo() {
        captureOrientation?.stop()
        captureOrientation = null
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
        _torchState.value = CallTorchState()
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        // Resume only after camera/sender teardown; a later pause cannot be overwritten by a queued resume.
        endPauseForCameraChange(stopped = true)
    }

    override fun setVideoNegotiationEnabled(enabled: Boolean) {
        videoNegotiationAllowed = enabled
    }

    private fun applyVideoNegotiationIntent(pc: PeerConnection) {
        val direction = if (videoNegotiationAllowed) livekit.org.webrtc.RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            else livekit.org.webrtc.RtpTransceiver.RtpTransceiverDirection.INACTIVE
        for (transceiver in pc.transceivers) {
            if (transceiver.mediaType == livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !transceiver.isStopped) {
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
            owned.sender.setStreams(listOf(LOCAL_VIDEO_STREAM_ID))
        } else {
            localVideoSenderId = pc.addTrack(track, listOf(LOCAL_VIDEO_STREAM_ID)).id()
        }
    }

    override fun hasMultipleCameras(): Boolean =
        runCatching { Camera2Enumerator(context).deviceNames.size > 1 }.getOrDefault(false)

    override fun switchCamera() {
        val activeCapturer = capturer ?: return
        if (!captureRunning || cameraSwitchPending) return
        val expectedCamera = cameraGeneration
        cameraSwitchPending = true
        _torchState.value = CallTorchState()
        (activeCapturer as? livekit.org.webrtc.CallTorchCamera2Capturer)?.setTorch(false)
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
                captureOrientation?.stop()
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
            AudioRoute(kind, CallAudioPolicy.label(kind, name), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) device.id else null)
        }.distinctBy { it.deviceId ?: it.kind }
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
            val id = CallAudioPolicy.preferredDeviceId(candidates, route.kind, audioManager.communicationDevice?.id, route.deviceId)
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
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice else null
        val name = device?.takeIf { kind == AudioRouteKind.BLUETOOTH || kind == AudioRouteKind.WIRED_HEADSET }?.productName?.toString()
        return AudioRoute(kind, CallAudioPolicy.label(kind, name), device?.id)
    }

    override fun attachLocalRenderer(renderer: Any?) {
        val surfaceRenderer = renderer as? LocalTextureRenderer ?: return
        if (localRendererSlot.attached === surfaceRenderer) return // already current - no re-init
        localRendererSlot.attached?.let { old -> detachLocalRenderer(old) }
        localRendererSlot.attach(surfaceRenderer)
        surfaceRenderer.init(eglBase.eglBaseContext, null)
        surfaceRenderer.setPaused(!localRendererVisible)
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

    private fun bindRemoteTrack(track: VideoTrack, streamIds: Set<String> = emptySet()) {
        if (remoteVideoTrack?.id() == track.id()) {
            if (streamIds.isNotEmpty()) remoteVideoStreamIds = remoteVideoStreamIds + streamIds
            return
        }
        remoteFrameProbe?.let { remoteVideoTrack?.removeSink(it) }
        remoteSink?.let { remoteVideoTrack?.removeSink(it) }
        remoteVideoTrack = track
        remoteVideoStreamIds = streamIds
        val trackGeneration = mediaGeneration
        val probe = FirstFrameSink(VideoSink { frame ->
            if (mediaGeneration == trackGeneration && remoteVideoTrack === track) {
                val old = _remoteGeometry.value
                if (old.width != frame.rotatedWidth || old.height != frame.rotatedHeight || old.rotation != frame.rotation) {
                    _remoteGeometry.value = CallRemoteGeometry(frame.rotatedWidth, frame.rotatedHeight, frame.rotation)
                    if (BuildConfig.DEBUG) android.util.Log.d("CallOrientation", "remote ${frame.rotatedWidth}x${frame.rotatedHeight} rot=${frame.rotation}")
                }
            }
        }) {
            if (mediaGeneration == trackGeneration && remoteVideoTrack === track) _events.tryEmit(WebRtcEvent.RemoteFirstFrame)
        }
        remoteFrameProbe = probe
        // Announce the track before accepting its first frame, even with no call UI mounted.
        _events.tryEmit(WebRtcEvent.RemoteVideoEnabled(true))
        track.addSink(probe)
        remoteSink?.let { track.addSink(it) }
    }

    private fun removeRemoteVideo() {
        remoteFrameProbe?.let { remoteVideoTrack?.removeSink(it) }
        remoteSink?.let { remoteVideoTrack?.removeSink(it) }
        remoteVideoTrack = null; remoteFrameProbe = null; remoteVideoStreamIds = emptySet()
        _events.tryEmit(WebRtcEvent.RemoteVideoEnabled(false))
    }

    override fun attachRemoteRenderer(renderer: Any?) {
        val surfaceRenderer = renderer as? SurfaceViewRenderer ?: return
        if (remoteRendererSlot.attached === surfaceRenderer) return // already current - no re-init
        remoteRendererSlot.attached?.let { old -> detachRemoteRenderer(old) }
        remoteRendererSlot.attach(surfaceRenderer)
        surfaceRenderer.init(eglBase.eglBaseContext, null)
        if (remoteRendererVisible) surfaceRenderer.disableFpsReduction() else surfaceRenderer.pauseVideo()
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
            renderer.setPaused(!localRendererVisible)
            val sink = localFirstFrameSink(renderer)
            localSink = sink
            localVideoTrack?.let { track -> track.addSink(sink) }
        }
        remoteRendererSlot.attached?.let { renderer ->
            remoteSink?.let { remoteVideoTrack?.removeSink(it) }
            renderer.release()
            renderer.init(eglBase.eglBaseContext, null)
            if (remoteRendererVisible) renderer.disableFpsReduction() else renderer.pauseVideo()
            val sink = FirstFrameSink(renderer) { _events.tryEmit(WebRtcEvent.RemoteFirstFrame) }
            remoteSink = sink
            remoteVideoTrack?.let { track -> track.addSink(sink) }
        }
    }

    override fun setRendererVisibility(localVisible: Boolean, remoteVisible: Boolean) {
        localRendererVisible = localVisible; remoteRendererVisible = remoteVisible
        localRendererSlot.attached?.setPaused(!localVisible)
        remoteRendererSlot.attached?.let { if (remoteVisible) it.disableFpsReduction() else it.pauseVideo() }
    }

    override fun expectRemoteVideoFrame() { remoteFrameProbe?.reset(); remoteSink?.reset() }

    override fun localVideoRunning(): Boolean = localVideoTrack != null

    override fun dispose() {
        captureOrientation?.stop()
        captureOrientation = null
        _localOrientation.value = CallFrameOrientation()
        _remoteGeometry.value = CallRemoteGeometry()
        cameraGeneration++
        cameraSwitchPending = false
        ++mediaGeneration
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        captureRunning = false
        _torchState.value = CallTorchState()
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
        connected = false
        pausePolicy.reset()
        remoteVideoStreamIds = emptySet(); remoteReceivers.clear(); receiverObservers.clear()
        lastLink = null; lastLinkAt = null; lastStats = null
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
        recordAudioPath(2)
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
                    WebRtcStatsEntry(stat.id, stat.type, stat.members, stat.timestampUs)
                }
                val stats = WebRtcCallStatsParser.parse(entries, SystemClock.elapsedRealtime().toDouble())
                // Serialize policy/effects with camera toggles and teardown on the main thread.
                cameraCallbackHandler.post {
                    if (pc !== peerConnection) { continuation.resume(null); return@post }
                    val now = SystemClock.elapsedRealtime()
                    updateNetworkProfile(now)
                    lastStats = stats
                    val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val manager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        when (manager.currentThermalStatus) {
                            android.os.PowerManager.THERMAL_STATUS_NONE, android.os.PowerManager.THERMAL_STATUS_LIGHT -> 0
                            android.os.PowerManager.THERMAL_STATUS_MODERATE -> 1
                            android.os.PowerManager.THERMAL_STATUS_SEVERE -> 2
                            else -> 3
                        }
                    } else 0
                    diagnostics.sample(stats, now, connected, localVideoTrack?.enabled() == true,
                        diagnosticsRecording, thermal, videoSending = videoSendActive && captureRunning && localVideoTrack?.enabled() == true).forEach(::emitDiagnostics)
                    if (diagnostics.audioPathDue) recordAudioPath(3)
                    val m = diagnostics.metrics
                    lastLink = CallBitrateSeedPolicy.Link(m.rttMs, m.outgoingLoss, m.availableOutgoingKbps)
                    lastLinkAt = now
                    if (connected) {
                        when (pausePolicy.update(now, m.availableOutgoingKbps,
                            stats.videoOutbound?.qualityLimitationReason == "bandwidth", m.rttMs, m.outgoingLoss,
                            localVideoTrack?.enabled() == true, !captureRunning)) {
                            CallVideoPausePolicy.Decision.PAUSE -> { recordVideoDecision(115); setVideoSendActive(false) }
                            CallVideoPausePolicy.Decision.RESUME -> { recordVideoDecision(116, 1); setVideoSendActive(true) }
                            null -> Unit
                        }
                        val pair = stats.selectedCandidatePair
                        if (deadPairPolicy.update(now, pair?.id, stats.dtlsConnected, stats.audioInbound?.packetsReceived,
                            pair?.packetsDiscardedOnSend, pair?.bytesSent, !mediaProfile.requestsDtx)) {
                            _events.tryEmit(WebRtcEvent.DeadPairDetected(deadPairPolicy.sendsRefused, deadPairPolicy.packets,
                                deadPairPolicy.refused, deadPairPolicy.watchedMs))
                        }
                    }
                    continuation.resume(stats.copy(metrics = diagnostics.metrics))
                }
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
            cameraCallbackHandler.post {
                if (generation != mediaGeneration) return@post
                val now = SystemClock.elapsedRealtime()
                connected = mapped == PeerConnectionState.CONNECTED
                if (mapped == PeerConnectionState.DISCONNECTED || mapped == PeerConnectionState.FAILED) deadPairPolicy.noteOutage(now)
                emitDiagnostics(diagnostics.iceState(mapped, now))
                _events.tryEmit(WebRtcEvent.ConnectionStateChanged(mapped))
            }
        }
        override fun onAddStream(stream: MediaStream) {
            if (generation != mediaGeneration) return
            val remoteVideo = stream.videoTracks.firstOrNull()
            remoteVideo?.let { track ->
                bindRemoteTrack(track, setOf(stream.id))
            }
        }
        override fun onTrack(transceiver: livekit.org.webrtc.RtpTransceiver) {
            if (generation != mediaGeneration) return
            val track = transceiver.receiver.track()
            if (track is VideoTrack) {
                bindRemoteTrack(track)
            }
        }
        override fun onRemoveStream(stream: MediaStream) {
            if (generation != mediaGeneration) return
            val current = remoteVideoTrack?.id() ?: return
            // libwebrtc may have emptied the stream by the removal callback.
            if (stream.id in remoteVideoStreamIds || stream.videoTracks.any { it.id() == current }) removeRemoteVideo()
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onDataChannel(dataChannel: livekit.org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            if (generation != mediaGeneration) return
            val track = receiver.track()
            if (track is VideoTrack) bindRemoteTrack(track, streams.map { it.id }.toSet())
        }
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
        const val LOCAL_AUDIO_STREAM_ID = "messenger-audio-stream-0"
        const val LOCAL_VIDEO_STREAM_ID = "messenger-video-stream-0"
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
