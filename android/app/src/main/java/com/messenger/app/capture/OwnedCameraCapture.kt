package com.messenger.app.capture

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import kotlinx.serialization.json.Json
import java.io.File

data class OwnedCameraResult(val success: Boolean, val uri: Uri, val file: File, val nonce: String)
class OwnedCameraCapture internal constructor(val request: (video: Boolean) -> Unit)

/** Shared permission/registration adapter. Saved URI/file/nonce retain the
 * original result pipeline; only fresh capture requires a live RAM lease. */
@Composable
fun rememberOwnedCameraCapture(
    directory: String,
    prefix: String,
    canRequest: () -> Boolean = { true },
    onUnavailable: () -> Unit,
    onPermissionDenied: () -> Unit = onUnavailable,
    beforeLaunch: suspend (Uri, File, String) -> Unit = { _, _, _ -> },
    onResult: suspend (OwnedCameraResult) -> Unit,
): OwnedCameraCapture {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val coordinator = app.nonCallCaptures
    val scope = rememberCoroutineScope()
    val session = remember { app.captureSession() }
    val latestBefore by rememberUpdatedState(beforeLaunch)
    val latestResult by rememberUpdatedState(onResult)
    val latestUnavailable by rememberUpdatedState(onUnavailable)
    val latestPermissionDenied by rememberUpdatedState(onPermissionDenied)
    val latestCanRequest by rememberUpdatedState(canRequest)
    var lease by remember { mutableStateOf<NonCallCaptureCoordinator.Lease?>(null) }
    var waitingPermission by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var pendingUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingFile by rememberSaveable { mutableStateOf<File?>(null) }
    var pendingNonce by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVideo by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(OwnedCameraCaptureContract()) { success ->
        val uri = pendingUri; val file = pendingFile; val nonce = pendingNonce
        val owner = pendingOwner?.let { runCatching { Json.decodeFromString(CaptureOwner.serializer(), it) }.getOrNull() }
        val ticket = lease
        val sameSession = owner != null && owner == app.captureSession()?.owner && (ticket == null || ticket.session == app.captureSession())
        pendingUri = null; pendingFile = null; pendingNonce = null; pendingOwner = null
        lease = null; waitingPermission = null
        ticket?.let(coordinator::release) // The trampoline already observed the actual result.
        if (uri != null && file != null && nonce != null) scope.launch {
            try { latestResult(OwnedCameraResult(success && sameSession, uri, file, nonce)) }
            finally { if (!success || !sameSession) file.delete() }
        } else file?.delete()
    }
    val launchCapture: (NonCallCaptureCoordinator.Lease) -> Unit = { ticket ->
        preparing = true
        scope.launch {
            var uri: Uri? = null
            var file: File? = null
            var registered = false
            var handedOff = false
            try {
                if (!coordinator.awaitReady(ticket) || session != app.captureSession()) return@launch
                val dir = File(context.cacheDir, directory).apply { mkdirs() }
                file = File(dir, "${prefix}_${ticket.id}.${if (pendingVideo) "mp4" else "jpg"}")
                uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file!!)
                pendingUri = uri; pendingFile = file; pendingNonce = ticket.id
                pendingOwner = Json.encodeToString(CaptureOwner.serializer(), ticket.session.owner)
                registered = true
                latestBefore(uri!!, file!!, ticket.id)
                // Permission/DB work may outlive a call, account or server change.
                if (!coordinator.ready(ticket) || session != app.captureSession()) return@launch
                val prepared = app.authRepository.withWorkspaceWrite(ticket.session.epoch) {
                    app.externalCaptureStore.prepare(coordinator, ticket, uri!!, pendingVideo)
                } ?: false
                if (!prepared) return@launch
                if (!coordinator.valid(ticket) || session != app.captureSession()) return@launch
                launcher.launch(ticket.id)
                handedOff = true
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
              catch (_: Exception) { latestPermissionDenied() }
            finally {
                preparing = false
                if (!handedOff) withContext(NonCancellable) {
                    if (registered && uri != null && file != null) runCatching { latestResult(OwnedCameraResult(false, uri!!, file!!, ticket.id)) }
                    runCatching { app.externalCaptureStore.remove(ticket.id) }
                    file?.delete()
                    if (pendingNonce == ticket.id) { pendingUri = null; pendingFile = null; pendingNonce = null; pendingOwner = null }
                    coordinator.release(ticket)
                    if (lease === ticket) lease = null
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = waitingPermission
        waitingPermission = null
        val ticket = lease?.takeIf { it.id == id }
        if (granted && ticket != null && coordinator.valid(ticket) && session == app.captureSession()) launchCapture(ticket)
        else {
            ticket?.let(coordinator::release)
            if (lease === ticket) lease = null
            if (!granted) latestPermissionDenied()
        }
    }
    DisposableEffect(coordinator) {
        onDispose {
            lease?.let { ticket ->
                if (!coordinator.isExternal(ticket)) {
                    coordinator.interrupt(ticket)
                    if (!preparing) coordinator.release(ticket)
                }
            }
        }
    }
    return OwnedCameraCapture { video ->
        if (pendingNonce != null || lease != null || preparing || !latestCanRequest()) return@OwnedCameraCapture
        val ticket = coordinator.acquire(NonCallCaptureKind.CAMERA, session) {
            lease?.let { current ->
                if (!coordinator.isExternal(current) && !preparing) {
                    coordinator.release(current); lease = null; waitingPermission = null
                }
            }
        }
        if (ticket == null) { latestUnavailable(); return@OwnedCameraCapture }
        lease = ticket
        pendingVideo = video
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCapture(ticket)
        else {
            waitingPermission = ticket.id
            try { permission.launch(Manifest.permission.CAMERA) }
            catch (_: Exception) { coordinator.release(ticket); lease = null; waitingPermission = null; latestUnavailable() }
        }
    }
}
