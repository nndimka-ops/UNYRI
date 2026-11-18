package com.messenger.app.ui.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Same live-state and permission admission for ringing, connecting and connected video. */
@Composable
internal fun rememberCallCameraAction(viewModel: CallViewModel): (String) -> Unit {
    val context = LocalContext.current
    var requestedCall by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = requestedCall
        requestedCall = null
        if (id != null && CallPresentationPolicy.cameraTarget(id, viewModel.state.value) != null) {
            if (granted) viewModel.setCameraEnabled(true) else viewModel.notifyCameraDenied()
        }
    }
    return { id ->
        val target = CallPresentationPolicy.cameraTarget(id, viewModel.state.value)
        if (target != null) {
            if (!target || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                viewModel.setCameraEnabled(target)
            } else { requestedCall = id; permission.launch(Manifest.permission.CAMERA) }
        }
    }
}
