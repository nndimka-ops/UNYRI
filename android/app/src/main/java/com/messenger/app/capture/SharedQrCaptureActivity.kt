package com.messenger.app.capture

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.camera.CameraSettings

/** App-owned scanner surface; the library owns camera rotation and lifecycle. */
class SharedQrCaptureActivity : CaptureActivity() {
    private var scanner: DecoratedBarcodeView? = null

    override fun initializeContent(): DecoratedBarcodeView = super.initializeContent().also { scanner = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        // initializeFromIntent installs its CameraSettings during super.onCreate.
        // Configure afterwards, before onResume opens the camera. ZXing falls
        // back to supported autofocus modes on cameras without continuous AF.
        scanner?.let { view ->
            view.cameraSettings = view.cameraSettings.apply {
                isAutoFocusEnabled = true
                focusMode = CameraSettings.FocusMode.CONTINUOUS
            }
        }
    }
}
