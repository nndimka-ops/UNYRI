package com.messenger.app.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.messenger.app.MessengerApp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class OwnedDualCameraResult(
    val success: Boolean, val nonce: String,
    val originalUri: Uri, val originalFile: File,
    val uri: Uri, val file: File,
)

class OwnedDualCameraCapture internal constructor(private val launch: (Boolean) -> Unit) {
    fun request(initialVideo: Boolean = false) = launch(initialVideo)
}

internal class DirectCameraCaptureContract : ActivityResultContract<String, Boolean>() {
    override fun createIntent(context: Context, input: String) =
        Intent(context, DirectCameraCaptureActivity::class.java).putExtra("capture_request", input)
    override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == Activity.RESULT_OK
}

/** Chat capture keeps the same lease, pre-registration and durable result path.
 * The activity's private durable record determines the actual media type; no
 * result Intent, extension guess or transient callback can change ownership. */
@Composable
fun rememberOwnedDualCameraCapture(
    destination: String,
    sessionEpoch: Int,
    canRequest: () -> Boolean = { true },
    onUnavailable: () -> Unit,
    onPermissionDenied: () -> Unit = onUnavailable,
    onResultUnavailable: () -> Unit = onUnavailable,
    resultWasConsumed: suspend (String) -> Boolean,
    beforeLaunch: suspend (Uri, File, String) -> Unit,
    onResult: suspend (OwnedDualCameraResult) -> Unit,
): OwnedDualCameraCapture {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val coordinator = app.nonCallCaptures
    val session = remember(destination, sessionEpoch) { app.captureSession()?.takeIf { it.epoch == sessionEpoch } }
    val scope = rememberCoroutineScope()
    val latestBefore by rememberUpdatedState(beforeLaunch)
    val latestResult by rememberUpdatedState(onResult)
    val latestUnavailable by rememberUpdatedState(onUnavailable)
    val latestDenied by rememberUpdatedState(onPermissionDenied)
    val latestResultUnavailable by rememberUpdatedState(onResultUnavailable)
    val latestWasConsumed by rememberUpdatedState(resultWasConsumed)
    val latestCanRequest by rememberUpdatedState(canRequest)
    var lease by remember(destination, sessionEpoch) { mutableStateOf<NonCallCaptureCoordinator.Lease?>(null) }
    var pendingId by rememberSaveable(destination, sessionEpoch) { mutableStateOf<String?>(null) }
    var permissionId by remember(destination, sessionEpoch) { mutableStateOf<String?>(null) }
    var preparing by remember(destination, sessionEpoch) { mutableStateOf(false) }
    var pendingVideo by remember(destination, sessionEpoch) { mutableStateOf(false) }
    val processing = remember(destination, sessionEpoch) { mutableSetOf<String>() }

    suspend fun deliver(record: PendingExternalCapture) {
        if (!processing.add(record.id)) return
        try {
            val files = DirectCaptureFiles.from(context, record) ?: return
            val current = app.externalCaptureStore.get(record.id)
            val sameOwner = session == app.captureSession() && record.owner == app.captureSession()?.owner
            val eligible = sameOwner && record.acceptsDirectResult(app.captureSession()?.owner, destination, current == record)
            if (eligible && !validDirectCaptureMedia(files.file(record.outputVideo), record.outputVideo)) {
                // The existing VM deletes this temp source only after durable
                // staging/retirement. A failed metadata removal may replay that
                // already-consumed record; retire it without sending again.
                if (latestWasConsumed(record.id)) { app.externalCaptureStore.remove(record.id); return }
                throw java.io.IOException("Confirmed camera media is temporarily unavailable")
            }
            // The durable explicit confirmation is authoritative even if an
            // interrupted/restored Activity has lost its transient RESULT_OK.
            val success = eligible && session == app.captureSession() && app.externalCaptureStore.get(record.id) == record &&
                files.file(record.outputVideo).length() > 0
            val actualVideo = success && record.outputVideo
            latestResult(OwnedDualCameraResult(success, record.id, files.uri(context, false), files.photo,
                files.uri(context, actualVideo), files.file(actualVideo)))
            // A successful callback has committed either the queued attachment
            // or the existing independent camera retry. Failed callbacks keep
            // the confirmed record/file available for a later recovery pass.
            app.externalCaptureStore.remove(record.id)
            if (!success) files.discard() else files.file(!actualVideo).delete()
        } finally {
            coordinator.find(record.id)?.let(coordinator::release)
            if (pendingId == record.id) pendingId = null
            if (lease?.id == record.id) lease = null
            processing.remove(record.id)
        }
    }

    val launcher = rememberLauncherForActivityResult(DirectCameraCaptureContract()) {
        val id = pendingId
        val record = id?.let(app.externalCaptureStore::get)
        if (record != null) scope.launch { runCatching { deliver(record) }.onFailure { latestResultUnavailable() } }
        else {
            id?.let(coordinator::find)?.let(coordinator::release)
            lease = null; pendingId = null
        }
    }

    fun launchCapture(ticket: NonCallCaptureCoordinator.Lease) {
        preparing = true
        scope.launch {
            var file: File? = null
            var registered = false
            var handedOff = false
            try {
                if (!coordinator.awaitReady(ticket) || session != app.captureSession()) return@launch
                val dir = File(context.cacheDir, "camera").apply { check(mkdirs() || isDirectory) }
                file = File(dir, "capture_${ticket.id}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file!!)
                registered = true
                latestBefore(uri, file!!, ticket.id)
                if (!coordinator.ready(ticket) || session != app.captureSession()) return@launch
                val prepared = app.authRepository.withWorkspaceWrite(ticket.session.epoch) {
                    app.externalCaptureStore.prepareDirect(coordinator, ticket, uri, file!!, destination, pendingVideo)
                } ?: false
                if (!prepared || !coordinator.valid(ticket) || session != app.captureSession()) return@launch
                pendingId = ticket.id
                launcher.launch(ticket.id)
                handedOff = true
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
              catch (_: Exception) { latestUnavailable() }
            finally {
                preparing = false
                if (!handedOff) withContext(NonCancellable) {
                    if (registered && file != null) runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file!!)
                        latestResult(OwnedDualCameraResult(false, ticket.id, uri, file!!, uri, file!!))
                    }
                    runCatching { app.externalCaptureStore.remove(ticket.id) }
                    file?.delete()
                    coordinator.release(ticket)
                    if (lease === ticket) lease = null
                    if (pendingId == ticket.id) pendingId = null
                }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = permissionId
        permissionId = null
        val ticket = lease?.takeIf { it.id == id }
        if (granted && ticket != null && coordinator.valid(ticket) && session == app.captureSession()) launchCapture(ticket)
        else {
            ticket?.let(coordinator::release)
            if (lease === ticket) lease = null
            if (!granted) latestDenied()
        }
    }

    LaunchedEffect(destination, session) {
        // ActivityResult restoration normally delivers this. A durable,
        // explicitly confirmed record also survives a lost task/result callback.
        app.externalCaptureStore.all().filter {
            it.destination == destination && it.directSource != null && it.confirmed && it.owner == session?.owner
        }.forEach { record -> runCatching { deliver(record) }.onFailure { latestResultUnavailable() } }
    }
    DisposableEffect(coordinator, destination, sessionEpoch) {
        onDispose {
            lease?.let { ticket ->
                if (!coordinator.isExternal(ticket)) {
                    coordinator.interrupt(ticket)
                    if (!preparing) coordinator.release(ticket)
                }
            }
        }
    }
    return OwnedDualCameraCapture { initialVideo ->
        if (pendingId != null || lease != null || preparing || !latestCanRequest()) return@OwnedDualCameraCapture
        val retry = app.externalCaptureStore.all().firstOrNull {
            it.directSource != null && it.confirmed && it.owner == session?.owner && it.destination == destination
        }
        if (retry != null) {
            // A failed DB/disk commit retains its confirmed source; this action
            // retries that result instead of replacing it with a new capture.
            scope.launch { runCatching { deliver(retry) }.onFailure { latestResultUnavailable() } }
            return@OwnedDualCameraCapture
        }
        val ticket = coordinator.acquire(NonCallCaptureKind.CAMERA, session) {
            lease?.let { current ->
                if (!coordinator.isExternal(current) && !preparing) {
                    coordinator.release(current); lease = null; permissionId = null
                }
            }
        }
        if (ticket == null) { latestUnavailable(); return@OwnedDualCameraCapture }
        lease = ticket
        pendingVideo = initialVideo
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCapture(ticket)
        else {
            permissionId = ticket.id
            try { permission.launch(Manifest.permission.CAMERA) }
            catch (_: Exception) { coordinator.release(ticket); lease = null; permissionId = null; latestUnavailable() }
        }
    }
}
