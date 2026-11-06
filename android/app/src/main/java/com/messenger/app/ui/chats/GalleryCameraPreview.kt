package com.messenger.app.ui.chats

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.messenger.app.MessengerApp
import com.messenger.app.capture.NonCallCaptureCoordinator
import com.messenger.app.capture.NonCallCaptureKind

@Composable
internal fun GalleryCameraPreview(modifier: Modifier, policyRevision: Long = 0) {
    AndroidView(factory = { Preview(it) }, modifier = modifier,
        update = { it.policyChanged(policyRevision) }, onRelease = { it.release() })
}

@SuppressLint("MissingPermission")
private class Preview(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private val handler = Handler(Looper.getMainLooper())
    private val app = context.applicationContext as MessengerApp
    private val owner = app.captureSession()
    private val coordinator = app.nonCallCaptures
    private var lease: NonCallCaptureCoordinator.Lease? = null
    private var opening = false
    private var released = false
    private var failed = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var target: Surface? = null
    private var closed = false
    private var bufferWidth = 0
    private var bufferHeight = 0
    init { surfaceTextureListener = this; isOpaque = true }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        closed = false
        failed = false
        ensureOpen(texture, width, height)
    }
    fun policyChanged(@Suppress("UNUSED_PARAMETER") revision: Long) {
        if (!released && isAvailable && lease == null) surfaceTexture?.let { ensureOpen(it, width, height) }
    }
    private fun ensureOpen(texture: SurfaceTexture, width: Int, height: Int) {
        if (released || failed || opening || device != null || lease != null) return
        val ticket = coordinator.acquire(NonCallCaptureKind.PREVIEW, owner) { close() } ?: return
        lease = ticket
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: error("No preview camera")
            val info = manager.getCameraCharacteristics(id)
            val sizes = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            val size = sizes.filter { it.width <= 1280 }.minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) } ?: error("No preview format")
            texture.setDefaultBufferSize(size.width, size.height)
            closed = false
            // TextureView already applies the sensor orientation to camera buffers.
            val swapped = (info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) % 180 != 0
            bufferWidth = if (swapped) size.height else size.width
            bufferHeight = if (swapped) size.width else size.height
            updateTransform(width, height)
            target = Surface(texture)
            if (!coordinator.ready(ticket)) { close(); return }
            opening = true
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    device = camera
                    if (closed || released || lease !== ticket || !coordinator.ready(ticket)) { close(); return }
                    val surface = target ?: run { close(); return }
                    runCatching { camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            if (closed || released || lease !== ticket || !coordinator.ready(ticket)) { value.close(); close(); return }
                            session = value
                            runCatching {
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surface)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                }
                                value.setRepeatingRequest(request.build(), null, handler)
                            }.onFailure { failed = true; close() }
                        }
                        override fun onConfigureFailed(value: CameraCaptureSession) { failed = true; value.close(); close() }
                    }, handler) }.onFailure { failed = true; close() }
                }
                override fun onDisconnected(camera: CameraDevice) { failed = true; camera.close(); close() }
                override fun onError(camera: CameraDevice, error: Int) { failed = true; camera.close(); close() }
                override fun onClosed(camera: CameraDevice) {
                    if (device === camera) device = null
                    if (lease === ticket) {
                        opening = false; lease = null
                        coordinator.release(ticket)
                    }
                }
            }, handler)
        }.onFailure { failed = true; opening = false; close() }
    }
    fun close() {
        closed = true
        runCatching { session?.stopRepeating() }; runCatching { session?.close() }; session = null
        val current = device
        if (current != null) current.close()
        // An accepted open request owns the lease until onOpened/onError→onClosed.
        else if (!opening) { val ticket = lease; lease = null; ticket?.let(coordinator::release) }
        target?.release(); target = null
    }
    fun release() { released = true; close() }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { close(); return true }
    private fun updateTransform(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return
        val degrees = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sideways = degrees % 180 != 0
        val scale = maxOf(
            (if (sideways) height else width).toFloat() / bufferWidth,
            (if (sideways) width else height).toFloat() / bufferHeight,
        )
        setTransform(android.graphics.Matrix().apply {
            setScale(bufferWidth * scale / width, bufferHeight * scale / height, width / 2f, height / 2f)
            postRotate(-degrees.toFloat(), width / 2f, height / 2f)
        })
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateTransform(width, height)
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
