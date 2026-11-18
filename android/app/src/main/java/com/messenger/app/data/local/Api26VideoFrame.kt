package com.messenger.app.data.local

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Decode to a GPU texture, then read back only the bounded pbuffer. Never allocate a source-size bitmap. */
internal object Api26VideoFrame {
    fun read(file: File, edge: Int, targetUs: Long, checkActive: () -> Unit): Bitmap? {
        checkActive()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var surface: BoundedFrameSurface? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            val format = extractor.getTrackFormat(track)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val plan = VideoFramePlan.create(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), rotation, edge, targetUs)
            // The GL quad handles rotation so the output geometry is bounded after rotation too.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            checkActive()
            surface = BoundedFrameSurface(plan)
            extractor.selectTrack(track); extractor.seekTo(plan.targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            checkActive()
            codec.configure(format, surface.surface, null, 0); codec.start()
            var inputEnded = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < deadline) {
                checkActive()
                if (!inputEnded) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(input)); buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) { inputEnded = true; codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                        else { codec.queueInputBuffer(input, 0, count, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) {
                    val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = info.size > 0 && (info.presentationTimeUs >= plan.targetUs || ended)
                    codec.releaseOutputBuffer(output, render)
                    if (render) { checkActive(); return surface.read(checkActive) }
                    if (ended) return null
                }
            }
            throw java.io.IOException("Video frame decode timed out")
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { surface?.close() }; runCatching { extractor.release() }
        }
    }
}

private class BoundedFrameSurface(private val plan: VideoFramePlan) : AutoCloseable {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var texture: SurfaceTexture? = null
    private var output: Surface? = null
    private var callbacks: HandlerThread? = null
    private val available = CountDownLatch(1)
    val surface: Surface get() = requireNotNull(output)
    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, plan.width, EGL14.EGL_HEIGHT, plan.height, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context))
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); textureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            callbacks = HandlerThread("media-frame-once").apply { start() }
            texture = SurfaceTexture(textureId).apply { setOnFrameAvailableListener({ available.countDown() }, Handler(requireNotNull(callbacks).looper)) }
            output = Surface(texture)
            program = linkProgram()
        } catch (failure: Throwable) { close(); throw failure }
    }
    fun read(checkActive: () -> Unit): Bitmap {
        var tries = 0
        while (!available.await(50, TimeUnit.MILLISECONDS)) { checkActive(); if (++tries >= 40) throw java.io.IOException("Video surface unavailable") }
        checkActive()
        val input = requireNotNull(texture); input.updateTexImage()
        val uv = FloatArray(16); input.getTransformMatrix(uv)
        val rotation = FloatArray(16); Matrix.setIdentityM(rotation, 0); Matrix.rotateM(rotation, 0, -plan.rotation.toFloat(), 0f, 0f, 1f)
        GLES20.glUseProgram(program); GLES20.glViewport(0, 0, plan.width, plan.height)
        val vertex = buffer(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
        val coords = buffer(floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f))
        val position = GLES20.glGetAttribLocation(program, "aPosition"); val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertex)
        GLES20.glEnableVertexAttribArray(texCoord); GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexture"), 1, false, uv, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uRotation"), 1, false, rotation, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val rgba = ByteBuffer.allocateDirect(plan.rgbaBytes)
        GLES20.glReadPixels(0, 0, plan.width, plan.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Frame readback failed" }
        val pixels = IntArray(plan.width * plan.height)
        for (y in 0 until plan.height) for (x in 0 until plan.width) {
            val i = (y * plan.width + x) * 4
            pixels[(plan.height - 1 - y) * plan.width + x] = ((rgba.get(i + 3).toInt() and 255) shl 24) or
                ((rgba.get(i).toInt() and 255) shl 16) or ((rgba.get(i + 1).toInt() and 255) shl 8) or (rgba.get(i + 2).toInt() and 255)
        }
        checkActive()
        return Bitmap.createBitmap(pixels, plan.width, plan.height, Bitmap.Config.ARGB_8888)
    }
    private fun buffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    private fun linkProgram(): Int {
        fun shader(kind: Int, source: String): Int {
            val id = GLES20.glCreateShader(kind); GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
            val compiled = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) { GLES20.glDeleteShader(id); error("Frame shader unavailable") }; return id
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aPosition; attribute vec4 aTexCoord; uniform mat4 uTexture; uniform mat4 uRotation; varying vec2 vTex; void main(){gl_Position=uRotation*aPosition;vTex=(uTexture*aTexCoord).xy;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTex; uniform samplerExternalOES sTexture; void main(){gl_FragColor=texture2D(sTexture,vTex);}")
        try {
            val id = GLES20.glCreateProgram(); GLES20.glAttachShader(id, vertex); GLES20.glAttachShader(id, fragment); GLES20.glLinkProgram(id)
            val linked = IntArray(1); GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) { GLES20.glDeleteProgram(id); error("Frame program unavailable") }; return id
        } finally { GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment) }
    }
    override fun close() {
        runCatching { output?.release() }; runCatching { texture?.release() }
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                if (program != 0) GLES20.glDeleteProgram(program)
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        }
        callbacks?.quitSafely()
    }
}
