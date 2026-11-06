package com.messenger.app.data.call

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import org.webrtc.ThreadUtils

/** Texture-backed self preview: participates in normal view clipping and transforms. */
class LocalTextureRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("COCO-local-preview")
    private var initialized = false
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
    override fun onFrame(frame: VideoFrame) = renderer.onFrame(frame)
    private fun updateAspect(width: Int, height: Int) {
        if (width > 0 && height > 0) renderer.setLayoutAspectRatio(width.toFloat() / height)
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
