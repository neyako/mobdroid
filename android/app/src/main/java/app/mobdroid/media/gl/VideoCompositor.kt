package app.mobdroid.media.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

class VideoCompositor(outputWidth: Int, outputHeight: Int) {
    private val thread = HandlerThread("Compositor", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var inputSurface: Surface
        private set
    private var program = 0
    private var texture = 0
    private var aPos = 0
    private var aTex = 0
    private var uTex = 0
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }
    private val stMatrix = FloatArray(16)
    private val sourceMatrix = FloatArray(16)
    private val drawMatrix = FloatArray(16)
    private val previewMatrix = FloatArray(16)
    private val scratch = FloatArray(16)
    private val point = FloatArray(8)
    private var previewWidth = 0
    private var previewHeight = 0
    private var viewX = 0
    private var viewY = 0
    private var viewWidth = 0
    private var viewHeight = 0

    private var outputWidth = outputWidth
    private var outputHeight = outputHeight
    private var inputWidth = 1920
    private var inputHeight = 1080
    private var swapped = false
    private var displayRotation = 0
    private var mirror = false
    private var mirrorStream = false
    private var boottimeTimestamps = false
    private var previewSurface: EGLSurface? = null
    private var encoderSurface: EGLSurface? = null
    private var recorderSurface: EGLSurface? = null
    private var snapshot: ((Bitmap?) -> Unit)? = null
    private var frameIntervalNs = 1_000_000_000L / 30
    private var nextEncodeNs = 0L
    private var released = false

    @Volatile
    var lastFrameNanos = 0L
        private set

    @Volatile
    var measuredFps = 0f
        private set
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0

    init {
        val ready = CountDownLatch(1)
        handler.post {
            egl = EglCore()
            setupGl()
            surfaceTexture = SurfaceTexture(texture).apply {
                setDefaultBufferSize(inputWidth, inputHeight)
                setOnFrameAvailableListener({ render() }, handler)
            }
            inputSurface = Surface(surfaceTexture)
            updateSourceMatrix()
            ready.countDown()
        }
        ready.await()
    }

    fun newInputSurface(): Surface = Surface(surfaceTexture)

    fun setInput(
        width: Int,
        height: Int,
        sensorOrientation: Int,
        displayRotation: Int,
        mirror: Boolean,
        mirrorStream: Boolean,
        boottimeTimestamps: Boolean,
    ) = runBlockingOnThread {
        this.mirrorStream = mirrorStream
        this.boottimeTimestamps = boottimeTimestamps
        inputWidth = width
        inputHeight = height
        swapped = (sensorOrientation + displayRotation) % 180 != 0
        this.displayRotation = displayRotation
        this.mirror = mirror
        surfaceTexture.setDefaultBufferSize(width, height)
        updateSourceMatrix()
    }

    fun setOutputSize(width: Int, height: Int) = handler.post {
        outputWidth = width
        outputHeight = height
        updateSourceMatrix()
    }

    fun setFrameRate(fps: Int) = handler.post {
        frameIntervalNs = 1_000_000_000L / fps
    }

    fun previewToInput(x: Float, y: Float, block: (Float, Float) -> Unit) = handler.post {
        if (released || previewSurface == null || viewWidth <= 0 || viewHeight <= 0) return@post
        point[0] = (x * previewWidth - viewX) / viewWidth
        point[1] = ((1 - y) * previewHeight - viewY) / viewHeight
        point[2] = 0f
        point[3] = 1f
        if (point[0] !in 0f..1f || point[1] !in 0f..1f) return@post
        Matrix.multiplyMV(point, 4, if (mirror) previewMatrix else drawMatrix, 0, point, 0)
        block(point[4], point[5])
    }

    fun setPreview(surface: Surface?) = runBlockingOnThread {
        previewSurface?.let { egl.makeNothingCurrent(); egl.releaseSurface(it) }
        previewSurface = surface?.let { runCatching { egl.createWindowSurface(it) }.getOrNull() }
            ?.takeIf { it != EGL14.EGL_NO_SURFACE }
        viewWidth = 0
    }

    fun setEncoder(surface: Surface?) = runBlockingOnThread {
        encoderSurface?.let { egl.makeNothingCurrent(); egl.releaseSurface(it) }
        encoderSurface = surface?.let { egl.createWindowSurface(it) }
        nextEncodeNs = 0
    }

    fun setRecorder(surface: Surface?) = runBlockingOnThread {
        recorderSurface?.let { egl.makeNothingCurrent(); egl.releaseSurface(it) }
        recorderSurface = surface?.let { egl.createWindowSurface(it) }
        nextEncodeNs = 0
    }

    fun takeSnapshot(onResult: (Bitmap?) -> Unit) = handler.post {
        if (released) onResult(null) else snapshot = onResult
    }

    fun release() {
        runBlockingOnThread {
            released = true
            snapshot?.invoke(null)
            snapshot = null
            previewSurface?.let { egl.releaseSurface(it) }
            encoderSurface?.let { egl.releaseSurface(it) }
            recorderSurface?.let { egl.releaseSurface(it) }
            previewSurface = null
            encoderSurface = null
            recorderSurface = null
            surfaceTexture.setOnFrameAvailableListener(null)
            surfaceTexture.release()
            inputSurface.release()
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            egl.release()
        }
        thread.quitSafely()
    }

    private fun runBlockingOnThread(block: () -> Unit) {
        if (Thread.currentThread() == thread) {
            block()
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            try {
                if (!released) block()
            } finally {
                done.countDown()
            }
        }
        done.await()
    }

    private fun render() {
        if (released) return
        egl.makeNothingCurrent()
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)
        val ts = toMonotonic(surfaceTexture.timestamp)
        lastFrameNanos = SystemClock.elapsedRealtimeNanos()
        countFps()
        Matrix.multiplyMM(drawMatrix, 0, stMatrix, 0, sourceMatrix, 0)
        if (mirror) {
            Matrix.setIdentityM(scratch, 0)
            Matrix.translateM(scratch, 0, 1f, 0f, 0f)
            Matrix.scaleM(scratch, 0, -1f, 1f, 1f)
            Matrix.multiplyMM(previewMatrix, 0, drawMatrix, 0, scratch, 0)
        }
        val streamMatrix = if (mirror && mirrorStream) previewMatrix else drawMatrix
        if ((encoderSurface != null || recorderSurface != null) && shouldEncode(ts)) {
            encoderSurface?.let { encode(it, streamMatrix, ts) }
            recorderSurface?.let { encode(it, streamMatrix, ts) }
        }
        snapshot?.let {
            snapshot = null
            it(runCatching { capture(streamMatrix) }.getOrNull())
        }
        previewSurface?.let { surface ->
            if (egl.makeCurrent(surface)) {
                fitViewport(egl.surfaceWidth(surface), egl.surfaceHeight(surface))
                draw(if (mirror) previewMatrix else drawMatrix)
                egl.swap(surface)
            }
        }
    }

    private fun encode(surface: EGLSurface, matrix: FloatArray, ts: Long) {
        if (!egl.makeCurrent(surface)) return
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        draw(matrix)
        egl.setPresentationTime(surface, ts)
        egl.swap(surface)
    }

    private fun capture(matrix: FloatArray): Bitmap {
        egl.makeNothingCurrent()
        val ids = IntArray(2)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glGenFramebuffers(1, ids, 1)
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outputWidth, outputHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[1])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ids[0], 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            draw(matrix)
            val pixels = ByteBuffer.allocateDirect(outputWidth * outputHeight * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, outputWidth, outputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val upsideDown = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            upsideDown.copyPixelsFromBuffer(pixels)
            val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
            return Bitmap.createBitmap(upsideDown, 0, 0, outputWidth, outputHeight, flip, false).also { upsideDown.recycle() }
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, ids, 1)
            GLES20.glDeleteTextures(1, ids, 0)
        }
    }

    private fun shouldEncode(ts: Long): Boolean {
        if (nextEncodeNs == 0L || ts - nextEncodeNs > frameIntervalNs) {
            nextEncodeNs = ts + frameIntervalNs
            return true
        }
        if (ts + frameIntervalNs / 4 < nextEncodeNs) return false
        nextEncodeNs += frameIntervalNs
        return true
    }

    private fun fitViewport(w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val outAspect = outputWidth.toFloat() / outputHeight
        previewWidth = w
        previewHeight = h
        if (w.toFloat() / h > outAspect) {
            viewWidth = (h * outAspect).toInt()
            viewHeight = h
        } else {
            viewWidth = w
            viewHeight = (w / outAspect).toInt()
        }
        viewX = (w - viewWidth) / 2
        viewY = (h - viewHeight) / 2
        GLES20.glViewport(viewX, viewY, viewWidth, viewHeight)
    }

    private fun countFps() {
        val now = lastFrameNanos
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsWindowFrames++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            measuredFps = fpsWindowFrames * 1e9f / elapsed
            fpsWindowStart = now
            fpsWindowFrames = 0
        }
    }

    private fun toMonotonic(ts: Long): Long =
        if (boottimeTimestamps) ts - (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) else ts

    private fun updateSourceMatrix() {
        val srcAspect = if (swapped) inputHeight.toFloat() / inputWidth else inputWidth.toFloat() / inputHeight
        val outAspect = outputWidth.toFloat() / outputHeight
        val sx = if (srcAspect > outAspect) outAspect / srcAspect else 1f
        val sy = if (srcAspect > outAspect) 1f else srcAspect / outAspect
        Matrix.setIdentityM(sourceMatrix, 0)
        Matrix.translateM(sourceMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(sourceMatrix, 0, -displayRotation.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(sourceMatrix, 0, if (mirror) -sx else sx, sy, 1f)
        Matrix.translateM(sourceMatrix, 0, -0.5f, -0.5f, 0f)
    }

    private fun draw(matrix: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(uTex, 1, false, matrix, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setupGl() {
        program = linkProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun linkProgram(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(it) }
        }
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTex * aTex).xy;
            }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """
    }
}
