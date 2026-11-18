package com.messenger.app.data.directtransfer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.messenger.app.MainActivity
import com.messenger.app.data.call.LocalTextureRenderer
import com.messenger.app.data.call.WebRtcEngineImpl
import com.messenger.app.domain.model.CallMediaKind
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoTrack
import kotlinx.coroutines.*

/** Actual installed EGL/TextureView path; synthetic input does not claim camera hardware. */
class LocalMirrorRendererNativeTest {
    @get:Rule val cameraPermissions = GrantPermissionRule.grant(android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS)

    @Test fun actualCamera2FrontRearFrozenFrameAndPreviewGeometry(): Unit = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst.targetContext.packageName == "com.messenger.app.directqa")
        check(!(inst.targetContext.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked) { "Hardware viewport is blocked by system keyguard" }
        val run = UUID.randomUUID().toString()
        val folder = File(inst.targetContext.filesDir, "mirror-hardware/$run").apply { mkdirs() }
        val engine = WebRtcEngineImpl(inst.targetContext)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val eventJob = launch(start = CoroutineStart.UNDISPATCHED, context = Dispatchers.Default) {
            engine.events.collect { event -> if (event is com.messenger.app.data.call.WebRtcEvent.LocalCameraFacingChanged)
                events += "${event.cameraGeneration}:${event.isFrontFacing}:${engine.isCurrentCameraGeneration(event.cameraGeneration)}" }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var renderer: LocalTextureRenderer
        lateinit var parent: LinearLayout
        val proof = JSONObject().put("scope", "Actual Samsung production WebRtcEngineImpl Camera2 capture/facing and retained actual frame through engine-attached LocalTextureRenderer. No full signaling/CallController/session claim.")
        try {
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                parent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
                renderer = LocalTextureRenderer(activity)
                parent.addView(renderer, LinearLayout.LayoutParams(360, 640)); activity.setContentView(parent)
                engine.initialize(emptyList(), CallMediaKind.VIDEO)
                renderer.setMirror(engine.localCameraIsFrontFacing()); engine.attachLocalRenderer(renderer)
            }
            for (front in listOf(true, false)) {
                if (!front) {
                    scenario.onActivity { engine.switchCamera() }
                    withTimeout(10_000) { while (engine.localCameraIsFrontFacing()) delay(50) }
                }
                assertEquals(front, engine.localCameraIsFrontFacing())
                assertTrue(engine.localVideoRunning())
                val track = WebRtcEngineImpl::class.java.getDeclaredField("localVideoTrack").apply { isAccessible = true }.get(engine) as VideoTrack
                val held = AtomicReference<VideoFrame?>()
                val sink = VideoSink { frame ->
                    if (held.get() == null) { frame.retain(); if (!held.compareAndSet(null, frame)) frame.release() }
                }
                track.addSink(sink)
                withTimeout(10_000) { while (held.get() == null) delay(30) }
                track.removeSink(sink)
                val frame = requireNotNull(held.get())
                try {
                    // Freeze an actual captured frame, rather than comparing a moving
                    // scene against a later preview. Capture itself remains running.
                    var normalSink = WebRtcEngineImpl::class.java.getDeclaredField("localSink").apply { isAccessible = true }.get(engine) as VideoSink
                    track.removeSink(normalSink)
                    val raw = frame.toBitmap()
                    raw.writePng(File(folder, "${if (front) "front" else "rear"}-raw-rotated.png"))
                    val phase = JSONObject().put("front", front).put("rotation", frame.rotation)
                        .put("buffer_width", frame.buffer.width).put("buffer_height", frame.buffer.height)
                    for ((name, width) in listOf("expanded" to 480, "pip" to 180, "restored_after_reinit" to 480)) {
                        scenario.onActivity {
                            if (name == "restored_after_reinit") {
                                engine.reinitRenderers()
                                normalSink = WebRtcEngineImpl::class.java.getDeclaredField("localSink").apply { isAccessible = true }.get(engine) as VideoSink
                                track.removeSink(normalSink)
                                // Deliberately no setMirror after resume/re-init: detect
                                // reset instead of masking it with another setter call.
                            } else renderer.setMirror(engine.localCameraIsFrontFacing())
                            renderer.layoutParams = LinearLayout.LayoutParams(width, (width.toLong()*raw.height/raw.width).toInt())
                        }
                        withTimeout(5000) {
                            var available = false
                            while (!available) {
                                scenario.onActivity { available = renderer.isAvailable && renderer.width > 0 && renderer.height > 0 }
                                if (!available) delay(50)
                            }
                        }
                        repeat(15) { renderer.onFrame(frame); delay(33) }
                        lateinit var bitmap: Bitmap
                        scenario.onActivity { bitmap = requireNotNull(renderer.bitmap) }
                        bitmap.writePng(File(folder, "${if (front) "front" else "rear"}-$name.png"))
                        val normal = error(bitmap, raw, false); val mirrored = error(bitmap, raw, true)
                        phase.put(name, JSONObject().put("normal_error", normal).put("mirror_error", mirrored)
                            .put("asymmetric", kotlin.math.abs(normal-mirrored)>5)
                            .put("matches_facing_when_asymmetric", kotlin.math.abs(normal-mirrored)<=5 || if (front) mirrored<normal else normal<mirrored))
                        proof.put(if (front) "front" else "rear", phase)
                        File(folder, "proof.json").writeText(proof.toString())
                        android.util.Log.i("DirectNativeQA", "hardware-mirror-phase=mirror-hardware/$run front=$front phase=$name normal=$normal mirror=$mirrored")
                        if (kotlin.math.abs(normal-mirrored)>5) assertTrue("Hardware $front/$name renderer policy mismatch", if (front) mirrored<normal else normal<mirrored)
                        bitmap.recycle()
                    }
                    proof.put(if (front) "front" else "rear", phase)
                    raw.recycle(); track.addSink(normalSink)
                } finally { frame.release() }
            }
            proof.put("facing_events", org.json.JSONArray(events.toList())).put("passed", true)
            File(folder, "proof.json").writeText(proof.toString())
            android.util.Log.i("DirectNativeQA", "hardware-mirror-proof=mirror-hardware/$run/proof.json $proof")
        } finally {
            scenario.onActivity { engine.dispose() }; scenario.close(); eventJob.cancelAndJoin()
        }
    }

    private fun VideoFrame.toBitmap(): Bitmap {
        val i420 = requireNotNull(buffer.toI420()) { "Hardware buffer could not convert to I420" }
        val bitmap = Bitmap.createBitmap(i420.width, i420.height, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(i420.width*i420.height)
            for (y in 0 until i420.height) for (x in 0 until i420.width) {
                val yy = (i420.dataY.get(y*i420.strideY+x).toInt() and 255)-16
                val u = (i420.dataU.get(y/2*i420.strideU+x/2).toInt() and 255)-128
                val v = (i420.dataV.get(y/2*i420.strideV+x/2).toInt() and 255)-128
                pixels[y*i420.width+x] = Color.rgb(((298*yy+409*v+128) shr 8).coerceIn(0,255),
                    ((298*yy-100*u-208*v+128) shr 8).coerceIn(0,255), ((298*yy+516*u+128) shr 8).coerceIn(0,255))
            }
            bitmap.setPixels(pixels,0,i420.width,0,0,i420.width,i420.height)
        } finally { i420.release() }
        return if (rotation==0) bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply { postRotate(rotation.toFloat()) },false).also { bitmap.recycle() }
    }
    @Test fun asymmetricLocalAndRemoteUseOppositeMirrorPolicies() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst.targetContext.packageName == "com.messenger.app.directqa")
        val run = UUID.randomUUID().toString()
        val folder = File(inst.targetContext.filesDir, "mirror-qa/$run").apply { mkdirs() }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(inst.targetContext).createInitializationOptions())
        val egl = EglBase.create()
        val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.RED)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE }
            drawRect(160f, 0f, 320f, 240f, paint)
            paint.color = Color.WHITE; paint.textSize = 100f; paint.typeface = Typeface.DEFAULT_BOLD
            drawText("L", 35f, 155f, paint); drawText("R", 195f, 155f, paint)
        }
        source.writePng(File(folder, "source.png"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var local: LocalTextureRenderer
        lateinit var remote: SurfaceViewRenderer
        var localCreated: LocalTextureRenderer? = null
        var remoteCreated: SurfaceViewRenderer? = null
        try {
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16); setBackgroundColor(Color.BLACK) }
                root.addView(TextView(activity).apply { text = "SELF: mirror=true BEFORE init"; setTextColor(Color.WHITE) })
                local = LocalTextureRenderer(activity).apply { setMirror(true); init(egl.eglBaseContext) }
                localCreated = local
                root.addView(local, LinearLayout.LayoutParams(640, 480))
                root.addView(TextView(activity).apply { text = "REMOTE: mirror=false"; setTextColor(Color.WHITE) })
                remote = SurfaceViewRenderer(activity).apply { setMirror(false); init(egl.eglBaseContext, null) }
                remoteCreated = remote
                root.addView(remote, LinearLayout.LayoutParams(640, 480))
                activity.setContentView(root)
            }
            Thread.sleep(500)
            repeat(40) {
                val buffer = source.toI420()
                val frame = VideoFrame(buffer, 0, System.nanoTime())
                local.onFrame(frame); remote.onFrame(frame); frame.release()
                Thread.sleep(33)
            }
            lateinit var localBitmap: Bitmap
            scenario.onActivity { localBitmap = requireNotNull(local.bitmap) }
            val remoteBitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            var result = -1
            scenario.onActivity { PixelCopy.request(remote, remoteBitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper())) }
            assertTrue(copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
            localBitmap.writePng(File(folder, "self.png")); remoteBitmap.writePng(File(folder, "remote.png"))
            val screenshot = requireNotNull(inst.uiAutomation.takeScreenshot())
            screenshot.writePng(File(folder, "screen.png")); screenshot.recycle()
            val selfNormal = error(localBitmap, source, false); val selfMirror = error(localBitmap, source, true)
            val remoteNormal = error(remoteBitmap, source, false); val remoteMirror = error(remoteBitmap, source, true)
            val proof = JSONObject().put("scope", "Actual Samsung same production LocalTextureRenderer/EglRenderer; readable synthetic L/R frame. No hardware camera/call-state claim.")
                .put("self_normal_error", selfNormal).put("self_mirror_error", selfMirror)
                .put("remote_normal_error", remoteNormal).put("remote_mirror_error", remoteMirror)
                .put("self_mirrored", selfMirror < selfNormal * 0.2).put("remote_unmirrored", remoteNormal < remoteMirror * 0.2)
            File(folder, "proof.json").writeText(proof.toString())
            android.util.Log.i("DirectNativeQA", "mirror-proof=mirror-qa/$run/proof.json $proof")
            assertTrue("Self preview was not mirrored", selfMirror < selfNormal * 0.2)
            assertTrue("Remote renderer was mirrored", remoteNormal < remoteMirror * 0.2)
        } finally {
            scenario.onActivity { localCreated?.release(); remoteCreated?.release() }
            scenario.close(); egl.release(); source.recycle()
        }
    }
    private fun Bitmap.writePng(file: File) { file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) } }
    private fun Bitmap.toI420(): JavaI420Buffer {
        val out = JavaI420Buffer.allocate(width, height)
        fun clamp(value: Int) = value.coerceIn(0, 255).toByte()
        for (y in 0 until height) for (x in 0 until width) {
            val c = getPixel(x, y); val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            out.dataY.put(y * out.strideY + x, clamp(((66*r+129*g+25*b+128) shr 8)+16))
            if (x % 2 == 0 && y % 2 == 0) {
                out.dataU.put(y/2*out.strideU+x/2, clamp(((-38*r-74*g+112*b+128) shr 8)+128))
                out.dataV.put(y/2*out.strideV+x/2, clamp(((112*r-94*g-18*b+128) shr 8)+128))
            }
        }
        return out
    }
    private fun error(actual: Bitmap, source: Bitmap, mirror: Boolean): Double {
        var total = 0.0; var count = 0
        for (y in 12 until actual.height-12 step 13) for (x in 12 until actual.width-12 step 13) {
            val sx = (x.toLong()*source.width/actual.width).toInt().let { if (mirror) source.width-1-it else it }
            val expected = source.getPixel(sx, (y.toLong()*source.height/actual.height).toInt()); val observed = actual.getPixel(x,y)
            total += kotlin.math.abs(Color.red(expected)-Color.red(observed)) + kotlin.math.abs(Color.green(expected)-Color.green(observed)) + kotlin.math.abs(Color.blue(expected)-Color.blue(observed)); count++
        }
        return total/count
    }
}
