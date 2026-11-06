package com.messenger.app.capture

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.ui.components.CocoBackButton
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** A private, result-producing CameraX surface. Only explicit review confirmation
 * sends media. Preview, file creation, recording stop and lifecycle callbacks do
 * not confirm a capture. Avatar capture retains its existing OEM contract. */
@SuppressLint("InvalidFragmentVersionForActivityResult")
class DirectCameraCaptureActivity : ComponentActivity() {
    private val app get() = application as MessengerApp
    private val captureMainExecutor by lazy { ContextCompat.getMainExecutor(this) }
    private var request by mutableStateOf<PendingExternalCapture?>(null)
    private var files: DirectCaptureFiles? = null
    private var lease: NonCallCaptureCoordinator.Lease? = null
    private var provider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var photo: ImageCapture? = null
    private var video: VideoCapture<Recorder>? = null
    private var activeCamera: Camera? = null
    private val ownedUseCases = mutableListOf<UseCase>()
    private val cameraStates = mutableMapOf<Camera, CameraState.Type>()
    private val cameraObservers = mutableMapOf<Camera, Observer<CameraState>>()
    private var recording: Recording? = null
    private var recordingWantsReview = false
    private var photoPending = false
    private var deferredBind = false
    private var closing = false
    private var completed = false
    private var initialized = false
    private var cancelBeforeInitialization = false
    private var recreating = false
    private val hardwareStopped = CompletableDeferred<Unit>()
    private var previousHardware: CompletableDeferred<Unit>? = null
    private var foreground = false
    private var waitingAudio by mutableStateOf(false)
    private var bindInProgress = false
    private var busy by mutableStateOf(false)
    private var ready by mutableStateOf(false)
    private var isRecording by mutableStateOf(false)
    private var seconds by mutableLongStateOf(0)
    private var error by mutableStateOf<Int?>(null)
    private var recoverableReview by mutableStateOf<PendingExternalCapture?>(null)

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = waitingAudio
        waitingAudio = false
        if (requested && granted && foreground && valid() && request?.stage == "preview" && request?.outputVideo == true) startVideo()
        else if (requested && !granted) error = R.string.camera_audio_required
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = intent.getStringExtra("capture_request")?.let(app.externalCaptureStore::get)
        val captureFiles = saved?.let { DirectCaptureFiles.from(this, it) }
        if (saved == null || saved.destination == null || captureFiles == null ||
            saved.owner != app.captureSession()?.owner ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setResult(Activity.RESULT_CANCELED); finish(); return
        }
        files = captureFiles
        request = saved
        previousHardware = DirectCameraActivityOwners.attach(saved.id, hardwareStopped)
        previousHardware?.invokeOnCompletion { captureMainExecutor.execute { finishIfClosed() } }
        setContent { CocoTheme { Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { CaptureScreen() } } }
        captureOperation {
            // A configuration/background Activity replacement must wait for
            // the previous instance's actual camera close and write callbacks.
            // It cannot reuse its lease while an old encoder still owns it.
            previousHardware?.await()
            val current = app.externalCaptureStore.get(saved.id)
            if (current == null || current.owner != app.captureSession()?.owner) {
                initialized = true; close(); return@captureOperation
            }
            request = current
            lease = app.nonCallCaptures.find(saved.id) ?: if (current.launched)
                app.nonCallCaptures.restoreExternal(saved.id, saved.owner) else null
            initialized = true
            val ticket = lease
            if (ticket == null) { close(); return@captureOperation }
            if (current.confirmed) {
                // A reviewed, confirmed file needs no camera or microphone.
                // An incoming call must not destroy a durable send/retry.
                // Result adapter revalidates the durable source before ingest.
                close()
                return@captureOperation
            }
            app.nonCallCaptures.bindStop(ticket) { runOnUiThread { close() } }
            if (cancelBeforeInitialization) { close(); return@captureOperation }
            if (!app.nonCallCaptures.awaitReady(ticket) || !valid()) { close(); return@captureOperation }
            // A killed process cannot continue a recording/image write. Its
            // partial bytes are never promoted to review or sent automatically.
            if (current.stage == "capturing") {
                captureFiles.discard()
                error = R.string.camera_capture_interrupted
            }
            val reviewValid = current.stage == "review" && validMedia(captureFiles.file(current.outputVideo), current.outputVideo)
            if (!commit(current.copy(launched = true, stage = if (reviewValid) "review" else "preview"))) { close(); return@captureOperation }
            val future = ProcessCameraProvider.getInstance(this@DirectCameraCaptureActivity)
            future.addListener({
                if (closing || isDestroyed) return@addListener
                runCatching { provider = future.get(); bindCamera() }.onFailure { error = R.string.camera_capture_failed }
            }, captureMainExecutor)
        }
    }

    private fun valid(): Boolean {
        val record = request ?: return false
        val ticket = lease ?: return false
        return !closing && app.captureSession()?.owner == record.owner &&
            app.nonCallCaptures.valid(ticket) && app.externalCaptureStore.get(record.id) == record
    }

    private fun captureOperation(operation: suspend () -> Unit) = lifecycleScope.launch {
        directCaptureOperation(::persistenceFailed, operation)
    }

    private fun stopRecordingSafely() {
        try { recording?.stop() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = R.string.camera_capture_interrupted }
        // Keep the Recording reference until its Finalize callback; unbinding
        // requests actual source closure even if stop itself reports an error.
    }

    private fun persistenceFailed(@Suppress("UNUSED_PARAMETER") failure: Exception) {
        busy = false
        waitingAudio = false
        deferredBind = false
        error = R.string.camera_save_failed
        recordingWantsReview = false
        stopRecordingSafely()
        unbindOwned()
        if (completed) return
        if (closing || !valid()) { close(); return }
        // A failed review/confirmation write leaves the reviewed bytes and the
        // acknowledged unconfirmed record intact. Send retries the write;
        // Retake/Back remain explicit cancellation paths. The normal visible
        // review reservation still answers a call stop with actual closure.
        if (recoverableReview != null || request?.stage == "review") return
        android.widget.Toast.makeText(this, R.string.camera_save_failed, android.widget.Toast.LENGTH_LONG).show()
        close()
    }

    private fun discardUnconfirmedOutput() {
        val expected = request
        val current = expected?.let { app.externalCaptureStore.get(it.id) }
        if (directCaptureDisposition(expected, current, app.captureSession()?.owner, recreating) ==
            DirectCaptureDisposition.DISCARD_UNCONFIRMED) files?.discard()
    }

    /** Workspace write gate and CAS retain owner/epoch ordering around logout. */
    private suspend fun commit(next: PendingExternalCapture): Boolean {
        val old = request ?: return false
        val ticket = lease ?: return false
        if (!valid()) return false
        val saved = app.authRepository.withWorkspaceWrite(ticket.session.epoch) {
            if (!valid()) false else app.externalCaptureStore.replaceDirect(old, next)
        } ?: false
        if (saved) request = next
        // Saved confirmation owns its media even when a call revokes hardware
        // admission immediately after the CAS. Callers recheck before hardware.
        return saved
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (initialized && request != null && !valid()) close() else bindCamera()
    }

    override fun onStop() {
        foreground = false
        waitingAudio = false
        // An interrupted video stays unconfirmed and is discarded on Finalize.
        recordingWantsReview = false
        stopRecordingSafely()
        unbindOwned()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rotation = previewView?.display?.rotation ?: return
        photo?.targetRotation = rotation
        video?.targetRotation = rotation
    }

    private fun unbindOwned() {
        ready = false
        val useCases = ownedUseCases.toTypedArray()
        ownedUseCases.clear()
        if (useCases.isNotEmpty()) runCatching { provider?.unbind(*useCases) }
        photo = null; video = null
        finishIfClosed()
    }

    @SuppressLint("MissingPermission") // CAMERA is checked before entry; valid() fences every bind/capture.
    private fun bindCamera() {
        val record = request ?: return
        val owner = provider ?: return
        val view = previewView ?: return
        if (!foreground || closing || busy || record.stage != "preview" || !record.launched || !valid()) return
        if (ownedUseCases.isNotEmpty()) return
        if (cameraStates.values.any { it != CameraState.Type.CLOSED }) { deferredBind = true; return }
        if (bindInProgress) return
        bindInProgress = true
        deferredBind = false
        try {
            val selector = if (record.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            require(owner.hasCamera(selector))
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
            val capture: UseCase = if (record.outputVideo) {
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                VideoCapture.withOutput(recorder).also { it.targetRotation = rotation; video = it }
            } else ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(rotation).build().also { photo = it }
            ownedUseCases.add(preview); ownedUseCases.add(capture)
            val camera = owner.bindToLifecycle(this, selector, preview, capture)
            activeCamera = camera
            if (camera !in cameraObservers) {
                val observer = Observer<CameraState> { state ->
                    cameraStates[camera] = state.type
                    if (camera === activeCamera) ready = !closing && ownedUseCases.isNotEmpty() && state.type == CameraState.Type.OPEN
                    if (state.error != null && !closing) error = R.string.camera_capture_failed
                    if (state.type == CameraState.Type.CLOSED) {
                        finishIfClosed()
                        if (deferredBind && !closing) previewView?.post { bindCamera() }
                    }
                }
                cameraObservers[camera] = observer
                cameraStates[camera] = camera.cameraInfo.cameraState.value?.type ?: CameraState.Type.CLOSED
                camera.cameraInfo.cameraState.observeForever(observer)
            }
        } catch (_: Exception) {
            error = R.string.camera_capture_failed
            unbindOwned()
        } finally { bindInProgress = false }
    }

    private fun changeMode(isVideo: Boolean? = null, flip: Boolean = false) {
        if (busy || isRecording || !valid() || request?.stage != "preview") return
        val old = request ?: return
        val front = if (flip) !old.front else old.front
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (provider?.hasCamera(selector) != true) { error = R.string.camera_lens_unavailable; return }
        busy = true
        captureOperation {
            try {
                if (!commit(old.copy(outputVideo = isVideo ?: old.outputVideo, front = front))) { close(); return@captureOperation }
                error = null
                deferredBind = true
                unbindOwned()
            } finally { busy = false; bindCamera() }
        }
    }

    private fun capture() {
        if (waitingAudio) return
        if (isRecording) { recordingWantsReview = true; busy = true; stopRecordingSafely(); return }
        if (!ready || busy || !valid()) return
        if (request?.outputVideo == true) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVideo()
            else {
                waitingAudio = true
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else takePhoto()
    }

    @SuppressLint("MissingPermission")
    private fun takePhoto() {
        val capture = photo ?: return
        val output = files?.photo ?: return
        busy = true
        captureOperation {
            if (!commit(request!!.copy(stage = "capturing", outputVideo = false))) { busy = false; close(); return@captureOperation }
            if (!foreground || !valid()) { close(); return@captureOperation }
            photoPending = true
            try {
                capture.targetRotation = previewView?.display?.rotation ?: capture.targetRotation
                capture.takePicture(ImageCapture.OutputFileOptions.Builder(output).build(), captureMainExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(results: ImageCapture.OutputFileResults) { photoPending = false; reviewOutput(false) }
                        override fun onError(exception: ImageCaptureException) { photoPending = false; failedCapture(); finishIfClosed() }
                    })
            } catch (cancel: CancellationException) { photoPending = false; throw cancel }
              catch (_: Exception) { photoPending = false; failedCapture() }
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested only from the Record action.
    private fun startVideo() {
        if (!ready || busy || isRecording || !valid() || !foreground ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val capture = video ?: return
        val output = files?.video ?: return
        busy = true
        captureOperation {
            if (!commit(request!!.copy(stage = "capturing", outputVideo = true))) { busy = false; close(); return@captureOperation }
            if (!foreground || !valid()) { failedCapture(); return@captureOperation }
            recordingWantsReview = false
            seconds = 0
            try {
                capture.targetRotation = previewView?.display?.rotation ?: capture.targetRotation
                recording = capture.output.prepareRecording(this@DirectCameraCaptureActivity, FileOutputOptions.Builder(output).build())
                    .withAudioEnabled().start(captureMainExecutor) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> { isRecording = true; busy = false }
                            is VideoRecordEvent.Status -> seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                            is VideoRecordEvent.Finalize -> {
                                recording = null; isRecording = false
                                if (recordingWantsReview && !event.hasError() && !closing && valid()) reviewOutput(true)
                                else failedCapture()
                                finishIfClosed()
                            }
                        }
                    }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { recording = null; isRecording = false; failedCapture() }
        }
    }

    private fun reviewOutput(isVideo: Boolean) {
        if (closing) {
            discardUnconfirmedOutput()
            busy = false
            finishIfClosed()
            return
        }
        busy = true
        captureOperation {
            val output = files?.file(isVideo)
            if (closing || !valid()) { discardUnconfirmedOutput(); busy = false; finishIfClosed(); return@captureOperation }
            if (output == null || !validMedia(output, isVideo)) {
                failedCapture(); return@captureOperation
            }
            val reviewed = request!!.copy(stage = "review", outputVideo = isVideo, confirmed = false)
            recoverableReview = reviewed
            if (!commit(reviewed)) { recoverableReview = null; close(); return@captureOperation }
            recoverableReview = null
            error = null
            unbindOwned()
            busy = false
        }
    }

    private fun failedCapture() {
        discardUnconfirmedOutput()
        busy = false
        if (closing) { finishIfClosed(); return }
        error = R.string.camera_capture_interrupted
        captureOperation {
            if (valid()) commit(request!!.copy(stage = "preview", confirmed = false))
            else close()
            bindCamera()
        }
    }

    private fun retake() {
        if (busy || !valid()) return
        busy = true
        captureOperation {
            if (!commit(request!!.copy(stage = "preview", confirmed = false))) { busy = false; close(); return@captureOperation }
            recoverableReview = null
            discardUnconfirmedOutput(); error = null; busy = false; bindCamera()
        }
    }

    private fun confirm() {
        if (busy || !valid() || (request?.stage != "review" && recoverableReview == null)) return
        busy = true
        captureOperation {
            recoverableReview?.let {
                if (!commit(it)) { close(); return@captureOperation }
                recoverableReview = null
            }
            val record = request ?: return@captureOperation
            if (!validMedia(files!!.file(record.outputVideo), record.outputVideo)) { busy = false; failedCapture(); return@captureOperation }
            if (!commit(record.copy(confirmed = true))) { busy = false; close(); return@captureOperation }
            // Persist the confirmation before returning. A killed caller can
            // replay this exact owner/chat result without re-capturing or sending
            // an unconfirmed preview. Caller retirement follows its durable send.
            close()
        }
    }

    private fun close() {
        if (completed) return
        if (!initialized) { cancelBeforeInitialization = true; return }
        if (closing) { finishIfClosed(); return }
        closing = true
        busy = true
        deferredBind = false
        waitingAudio = false
        recordingWantsReview = false
        stopRecordingSafely()
        unbindOwned()
        finishIfClosed()
    }

    private fun finishIfClosed() {
        if (!closing || completed || previousHardware?.isCompleted == false || photoPending || recording != null || ownedUseCases.isNotEmpty() ||
            cameraStates.values.any { it != CameraState.Type.CLOSED }) return
        completed = true
        cameraObservers.forEach { (camera, observer) -> camera.cameraInfo.cameraState.removeObserver(observer) }
        cameraObservers.clear()
        val record = recoverableReview ?: request
        val current = record?.let { app.externalCaptureStore.get(it.id) }
        val disposition = directCaptureDisposition(record, current, app.captureSession()?.owner, recreating)
        val confirmed = confirmedDirectCapture(record, current, app.captureSession()?.owner)
        val success = disposition == DirectCaptureDisposition.DELIVER_CONFIRMED && confirmed != null &&
            DirectCaptureFiles.from(this, confirmed)?.file(confirmed.outputVideo)?.let { it.isFile && it.length() > 0 } == true
        // A transient stop/cancel or stale local request cannot delete a still-
        // pending durable confirmation. Old-account cleanup belongs to the
        // existing workspace purger, not this Activity's late callback.
        if (disposition == DirectCaptureDisposition.DISCARD_UNCONFIRMED) files?.discard()
        lease?.let(app.nonCallCaptures::release)
        hardwareStopped.complete(Unit)
        record?.let { DirectCameraActivityOwners.stopped(it.id, hardwareStopped) }
        if (recreating) return
        setResult(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        if (!completed) {
            recreating = isChangingConfigurations || !isFinishing
            closing = true; deferredBind = false
            recordingWantsReview = false; stopRecordingSafely(); unbindOwned()
            // Keep closure observers until the actual hardware/write callbacks
            // acknowledge stop; the next instance awaits hardwareStopped.
            finishIfClosed()
        }
        super.onDestroy()
    }

    private suspend fun validMedia(file: File, isVideo: Boolean): Boolean = validDirectCaptureMedia(file, isVideo)

    @Composable
    private fun CaptureScreen() {
        val record = recoverableReview ?: request
        val review = record?.stage == "review"
        BackHandler { close() }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().testTag("camera-direct")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CocoBackButton(onClick = { close() }, label = stringResource(R.string.cancel))
                Text(stringResource(R.string.attach_camera), Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium)
                if (isRecording) Text(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("camera-record-time"))
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                if (review && record != null) ReviewMedia(files!!.file(record.outputVideo), record.outputVideo)
                else AndroidView(factory = { context -> PreviewView(context).also {
                    it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    it.scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView = it
                    it.post { bindCamera() }
                } }, modifier = Modifier.fillMaxSize(), onRelease = { if (previewView === it) previewView = null })
                if (busy && !isRecording) CircularProgressIndicator(Modifier.size(36.dp))
            }
            error?.let { Text(stringResource(it), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (review) Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::retake, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("camera-retake")) {
                    Text(stringResource(R.string.camera_retake))
                }
                Button(onClick = ::confirm, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("camera-confirm")) {
                    Icon(CocoIcons.Check, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.send))
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = record?.outputVideo == false, onClick = { changeMode(false) }, enabled = !busy && !isRecording,
                        label = { Text(stringResource(R.string.photo_preview)) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("camera-mode-photo"))
                    FilterChip(selected = record?.outputVideo == true, onClick = { changeMode(true) }, enabled = !busy && !isRecording,
                        label = { Text(stringResource(R.string.media_video)) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("camera-mode-video"))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { changeMode(flip = true) }, enabled = !busy && !isRecording,
                        modifier = Modifier.size(48.dp).testTag("camera-flip")) {
                        Icon(CocoIcons.SwitchCamera, stringResource(R.string.call_switch_camera))
                    }
                    if (isRecording) Button(onClick = ::capture, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp).testTag("camera-stop"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.voice_stop)) }
                    else FilledIconButton(onClick = ::capture, enabled = ready && !busy && !waitingAudio, shape = CircleShape,
                        modifier = Modifier.size(72.dp).testTag("camera-shutter")) {
                        Icon(if (record?.outputVideo == true) CocoIcons.Videocam else CocoIcons.Camera,
                            stringResource(if (record?.outputVideo == true) R.string.camera_record_video else R.string.camera_take_photo), Modifier.size(32.dp))
                    }
                    if (!ready && !busy && error != null) TextButton(onClick = { error = null; bindCamera() }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Text(stringResource(R.string.retry))
                    } else Spacer(Modifier.size(48.dp))
                }
            }
        }
    }

    @Composable
    private fun ReviewMedia(file: File, isVideo: Boolean) {
        if (isVideo) AndroidView(factory = { context -> VideoView(context).apply {
            contentDescription = getString(R.string.media_video)
            setMediaController(MediaController(context))
            setVideoPath(file.path)
            setOnPreparedListener { seekTo(1) }
            setOnClickListener { if (isPlaying) pause() else start() }
        } }, modifier = Modifier.fillMaxSize().testTag("camera-video-review"), onRelease = { it.stopPlayback() })
        else {
            var bitmap by remember(file.path) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(file.path) { bitmap = withContext(Dispatchers.IO) { decodePhoto(file) } }
            bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.photo_preview), Modifier.fillMaxSize().testTag("camera-photo-review"), contentScale = ContentScale.Fit) }
        }
    }

    private fun decodePhoto(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1920) sample *= 2
        val original = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) original else Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true).also { original.recycle() }
    }.getOrNull()
}

/** In-process Activity recreation handoff only. Process death closes Android's
 * camera handles; the private persisted request owns result recovery. */
private object DirectCameraActivityOwners {
    private val owners = mutableMapOf<String, CompletableDeferred<Unit>>()
    @Synchronized fun attach(id: String, stopped: CompletableDeferred<Unit>): CompletableDeferred<Unit>? = owners.put(id, stopped)
    @Synchronized fun stopped(id: String, stopped: CompletableDeferred<Unit>) { if (owners[id] === stopped) owners.remove(id) }
}
