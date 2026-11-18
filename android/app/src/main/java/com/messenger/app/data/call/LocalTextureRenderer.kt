package com.messenger.app.data.call

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.view.TextureView
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.EglRenderer
import livekit.org.webrtc.GlRectDrawer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import livekit.org.webrtc.ThreadUtils

/** Texture-backed self preview: participates in normal view clipping and transforms. */
class LocalTextureRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("COCO-local-preview")
    private var initialized = false
    @Volatile private var frameAspect = 0f
    init { surfaceTextureListener = this; isOpaque = false }
    fun init(context: EglBase.Context, unused: Any? = null) {
        if (initialized) return
        renderer.init(context, EglBase.CONFIG_PLAIN, GlRectDrawer())
        initialized = true
        if (isAvailable) surfaceTexture?.let { renderer.createEglSurface(it) }
        updateAspect(width, height)
    }
    fun release() { if (initialized) { initialized = false; renderer.release() } }
    fun setMirror(mirror: Boolean) = renderer.setMirror(mirror)
    fun setPaused(paused: Boolean) { if (paused) renderer.pauseVideo() else renderer.disableFpsReduction() }
    override fun onFrame(frame: VideoFrame) {
        val aspect = frame.rotatedWidth.toFloat() / frame.rotatedHeight.coerceAtLeast(1)
        if (frameAspect != aspect) {
            frameAspect = aspect
            // EGL supplies the complete frame; TextureView center-crops inside the tile.
            renderer.setLayoutAspectRatio(aspect)
            post { updateAspect(width, height) }
        }
        renderer.onFrame(frame)
    }
    private fun updateAspect(width: Int, height: Int) {
        val crop = CallOrientationPolicy.previewCrop(width, height, frameAspect)
        setTransform(Matrix().apply { setScale(crop.first, crop.second, width / 2f, height / 2f) })
        invalidateOutline()
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (initialized) renderer.createEglSurface(surface)
        updateAspect(width, height)
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateAspect(width, height)
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (initialized) {
            val released = CountDownLatch(1)
            renderer.releaseEglSurface { released.countDown() }
            ThreadUtils.awaitUninterruptibly(released)
        }
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
