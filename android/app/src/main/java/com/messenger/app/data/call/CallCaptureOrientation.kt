package com.messenger.app.data.call

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.database.ContentObserver
import android.provider.Settings
import android.view.WindowManager
import com.messenger.app.OrientationLease
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.VideoFrame

/** Camera-owned listeners. No sensor subscription, timer or frame clock exists at idle/audio-only. */
internal class CallCaptureOrientation(context: Context) : SensorEventListener, DisplayManager.DisplayListener {
    private val resolver = context.contentResolver
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val window = context.getSystemService(WindowManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    @Volatile private var screen = 0
    private val policy = CallOrientationHysteresis()
    @Volatile private var autoRotate = false
    private data class Physical(val degrees: Int, val stableSince: Long)
    @Volatile private var physical = Physical(0, Long.MAX_VALUE)
    // Capture-thread cache: no pair allocation or StateFlow publication for unchanged frames.
    private var lastSnapshot = CallFrameOrientation()
    private var enabled = false
    private val rotationSetting = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshAutoRotate() }
    }
    init { refreshScreen() }
    fun start() {
        if (enabled) return
        enabled = true
        refreshScreen()
        refreshAutoRotate()
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, rotationSetting)
        displays.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, 100_000)
        }
    }
    fun stop() {
        if (!enabled) return
        enabled = false
        policy.interrupt()
        sensors.unregisterListener(this)
        displays.unregisterDisplayListener(this)
        resolver.unregisterContentObserver(rotationSetting)
    }
    @Suppress("DEPRECATION")
    fun refreshScreen() { screen = window.defaultDisplay.rotation * 90 }
    override fun onDisplayChanged(id: Int) = refreshScreen()
    override fun onDisplayAdded(id: Int) = Unit
    override fun onDisplayRemoved(id: Int) = Unit
    private fun refreshAutoRotate() { autoRotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 }
    fun snapshot(): CallFrameOrientation {
        val snapshot = physical
        val display = screen
        val tag = CallOrientationPolicy.tag(display, snapshot.degrees,
            if (snapshot.stableSince == Long.MAX_VALUE) 0 else SystemClock.elapsedRealtime() - snapshot.stableSince,
            preferScreen = autoRotate && OrientationLease.callFullScreen)
        if (lastSnapshot.screen != display || lastSnapshot.tag != tag) lastSnapshot = CallFrameOrientation(display, tag)
        return lastSnapshot
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val next = policy.update(CallOrientationPolicy.physicalFromGravity(x, y, z),
            z / SensorManager.GRAVITY_EARTH, SystemClock.elapsedRealtime())
        if (next != physical.degrees || policy.stableSinceMs != physical.stableSince)
            physical = Physical(next, policy.stableSinceMs)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Only VideoFrame is allocated per frame; the texture/buffer and mirror matrix are untouched. */
internal class CallRotationCapturerObserver(
    private val delegate: CapturerObserver,
    private val rotation: java.util.function.IntSupplier,
    private val onStarted: (Boolean) -> Unit,
) : CapturerObserver {
    override fun onCapturerStarted(success: Boolean) { onStarted(success); delegate.onCapturerStarted(success) }
    override fun onCapturerStopped() { onStarted(false); delegate.onCapturerStopped() }
    override fun onFrameCaptured(frame: VideoFrame) {
        val degrees = rotation.asInt
        frame.buffer.retain() // VideoFrame's constructor takes ownership; it does not retain.
        val tagged = VideoFrame(frame.buffer, degrees, frame.timestampNs)
        try { delegate.onFrameCaptured(tagged) } finally { tagged.release() }
    }
}
