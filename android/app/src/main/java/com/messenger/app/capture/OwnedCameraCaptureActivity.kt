package com.messenger.app.capture

import android.app.Activity
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.messenger.app.MessengerApp
import kotlinx.coroutines.launch

class OwnedCameraCaptureContract : ActivityResultContract<String, Boolean>() {
    override fun createIntent(context: Context, input: String) = Intent(context, OwnedCameraCaptureActivity::class.java)
        .putExtra("capture_request", input)
    override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == Activity.RESULT_OK
}

/** Transparent ownership trampoline. Delegates to the original system-camera
 * contracts and returns their Boolean result. The durable request, not a RAM
 * lease or Composable lifetime, owns a completed result after process death. */
// FCM transitively retains Fragment 1.1, but this Activity directly owns the
// modern ComponentActivity registry. It has no FragmentActivity request-code
// interception or Fragment permission callback path described by this lint check.
@SuppressLint("InvalidFragmentVersionForActivityResult")
class OwnedCameraCaptureActivity : ComponentActivity() {
    private val app get() = application as MessengerApp
    private var request: PendingExternalCapture? = null
    private var lease: NonCallCaptureCoordinator.Lease? = null
    private var finished = false
    private var delegateLaunched = false
    private val photo = registerForActivityResult(object : ActivityResultContracts.TakePicture() {
        override fun createIntent(context: Context, input: Uri) = super.createIntent(context, input).granted(input)
    }) { complete(it) }
    private val video = registerForActivityResult(object : ActivityResultContracts.CaptureVideo() {
        override fun createIntent(context: Context, input: Uri) = super.createIntent(context, input).granted(input)
    }) { complete(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        val id = intent.getStringExtra("capture_request")
        val saved = id?.let(app.externalCaptureStore::get)
        if (saved == null) {
            lease = app.nonCallCaptures.find(id)
            if (savedInstanceState?.getBoolean("delegate_launched") == true && lease != null) {
                delegateLaunched = true // Retired on logout; await only actual camera closure/result.
                return
            }
            lease?.let(app.nonCallCaptures::release)
            setResult(Activity.RESULT_CANCELED); finish(); return
        }
        request = saved
        delegateLaunched = saved.launched
        val existing = app.nonCallCaptures.find(saved.id)
        // A restored launched request must await its restored ActivityResult;
        // it must never relaunch the camera or discard a valid completed file.
        lease = existing ?: if (saved.launched) app.nonCallCaptures.restoreExternal(saved.id, saved.owner) else null
        val ticket = lease
        if (saved.launched) return
        if (ticket == null || !saved.mayLaunch(app.captureSession()?.owner, app.nonCallCaptures.valid(ticket))) {
            complete(false); return
        }
        app.nonCallCaptures.bindStop(ticket) { if (request?.launched != true) complete(false) }
        lifecycleScope.launch {
            if (!app.nonCallCaptures.awaitReady(ticket) || !saved.mayLaunch(app.captureSession()?.owner, app.nonCallCaptures.valid(ticket))) {
                complete(false); return@launch
            }
            val uri = Uri.parse(saved.uri)
            if (uri.scheme != "content" || uri.authority != "$packageName.fileprovider") { complete(false); return@launch }
            try {
                val recorded = app.authRepository.withWorkspaceWrite(ticket.session.epoch) {
                    if (!app.nonCallCaptures.valid(ticket)) false else { app.externalCaptureStore.markLaunched(saved); true }
                } ?: false
                if (!recorded) { complete(false); return@launch }
                request = saved.copy(launched = true)
                if (!app.nonCallCaptures.valid(ticket) || app.captureSession()?.owner != saved.owner) { complete(false); return@launch }
                delegateLaunched = true
                if (saved.video) video.launch(uri) else photo.launch(uri)
            } catch (_: Exception) { complete(false) }
        }
    }

    private fun complete(success: Boolean) {
        if (finished) return
        finished = true
        val saved = request
        val stillPending = saved != null && app.externalCaptureStore.get(saved.id) == saved
        val accepted = success && saved?.acceptsCompletedResult(app.captureSession()?.owner, stillPending) == true
        // Result/cancel/launch failure owns release, including stale-account
        // cleanup. Never release an active system camera on mere onStop.
        if (saved != null) runCatching { app.externalCaptureStore.remove(saved.id) }
        lease?.let(app.nonCallCaptures::release)
        setResult(if (accepted) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        // Task/Activity cleanup relinquishes this attached wrapper. Configuration
        // recreation retains ownership and the saved ActivityResult registration.
        // Keep durable result metadata: a later valid result can still be checked.
        if (isFinishing && !finished) lease?.let(app.nonCallCaptures::release)
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("delegate_launched", delegateLaunched)
        super.onSaveInstanceState(outState)
    }
}

private fun Intent.granted(uri: Uri): Intent = addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    .apply { clipData = ClipData.newRawUri("capture", uri) }
