package com.opirl.app.media.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

sealed interface OverlaySource

data class BrowserPage(val url: String, val width: Int, val height: Int) : OverlaySource

data class CameraFeed(val camera: String) : OverlaySource

data class VTuberSource(
    val file: String,
    val side: Int,
    val positionY: Float,
    val fieldOfView: Int,
    val armsAngle: Int,
    val mirror: Boolean,
    val camera: String?,
    val mouth: Float,
    val eyes: Float,
) : OverlaySource

class Overlay(
    val id: String,
    val bitmap: Bitmap?,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val previewOnly: Boolean,
    val source: OverlaySource? = null,
    val surface: String = id,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val cropWidth: Float = 1f,
    val cropHeight: Float = 1f,
    val cornerRadius: Float = 0f,
    val borderWidth: Float = 0f,
    val borderColor: Int = 0,
) {
    fun same(other: Overlay): Boolean = id == other.id && bitmap === other.bitmap && x == other.x && y == other.y &&
        width == other.width && height == other.height && previewOnly == other.previewOnly && source == other.source &&
        surface == other.surface && rotation == other.rotation && alpha == other.alpha && cropX == other.cropX &&
        cropY == other.cropY && cropWidth == other.cropWidth && cropHeight == other.cropHeight &&
        cornerRadius == other.cornerRadius && borderWidth == other.borderWidth && borderColor == other.borderColor
}

private class OverlayTexture(val id: Int, var bitmap: Bitmap?)

private class SurfaceOverlay(val texture: Int, val surfaceTexture: SurfaceTexture, val surface: Surface) {
    @Volatile
    var dirty = false
    var ready = false
    val matrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    val fill = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    var input: FeedInput? = null
}

class FrameTap(val surface: String?, val intervalNs: Long, val deliver: (Bitmap) -> Unit) {
    val busy = AtomicBoolean(false)
}

class FeedInput(val width: Int, val height: Int, val sensorOrientation: Int, val displayRotation: Int, val mirror: Boolean)

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
    private var overlayProgram = 0
    private var overlayPos = 0
    private var overlayRect = 0
    private var overlayCrop = 0
    private var overlayTurn = 0
    private var overlayAlpha = 0
    private var overlays = emptyList<Overlay>()
    private val overlayTextures = HashMap<String, OverlayTexture>()
    private var surfaceProgram = 0
    private var surfacePos = 0
    private var surfaceRect = 0
    private var surfaceCrop = 0
    private var surfaceTurn = 0
    private var surfaceAlpha = 0
    private var surfaceSize = 0
    private var surfaceShape = 0
    private var surfaceBorder = 0
    private var surfaceSt = 0
    private val surfaceOverlays = HashMap<String, SurfaceOverlay>()
    private val surfaceOverlayList = ArrayList<SurfaceOverlay>()
    private var tap: FrameTap? = null
    private var tapNextNs = 0L
    private var tapBitmap: Bitmap? = null
    private var tapPixels: ByteBuffer? = null
    private val tapIds = IntArray(2)
    private val tapMatrix = FloatArray(16)
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

    fun repeatFrame() = handler.post { render(System.nanoTime()) }

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

    fun setOverlays(next: List<Overlay>) = handler.post {
        if (released) return@post
        egl.makeNothingCurrent()
        val ids = IntArray(1)
        overlayTextures.keys.filter { id -> next.none { it.id == id } }.forEach { id ->
            ids[0] = overlayTextures.remove(id)!!.id
            GLES20.glDeleteTextures(1, ids, 0)
        }
        for (overlay in next) {
            val bitmap = overlay.bitmap ?: continue
            val texture = overlayTextures.getOrPut(overlay.id) {
                GLES20.glGenTextures(1, ids, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                OverlayTexture(ids[0], null)
            }
            if (texture.bitmap !== bitmap) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                texture.bitmap = bitmap
            }
        }
        overlays = next
    }

    fun openSurfaceOverlay(id: String, width: Int, height: Int): Surface? {
        var surface: Surface? = null
        runBlockingOnThread {
            closeSurface(id)
            egl.makeNothingCurrent()
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val texture = SurfaceTexture(ids[0]).apply { setDefaultBufferSize(width, height) }
            val overlay = SurfaceOverlay(ids[0], texture, Surface(texture))
            texture.setOnFrameAvailableListener({ overlay.dirty = true }, handler)
            surfaceOverlays[id] = overlay
            surfaceOverlayList += overlay
            surface = overlay.surface
        }
        return surface
    }

    fun setFeedInput(id: String, input: FeedInput) = handler.post {
        val overlay = surfaceOverlays[id] ?: return@post
        overlay.input = input
        fillMatrix(overlay.fill, input.width, input.height, input.sensorOrientation, input.displayRotation, input.mirror)
    }

    fun setFrameTap(next: FrameTap?) = handler.post {
        tap = next
        tapNextNs = 0
    }

    fun closeSurfaceOverlay(id: String) = handler.post {
        if (!released) closeSurface(id)
    }

    private fun closeSurface(id: String) {
        val overlay = surfaceOverlays.remove(id) ?: return
        surfaceOverlayList -= overlay
        overlay.surfaceTexture.setOnFrameAvailableListener(null)
        overlay.surface.release()
        overlay.surfaceTexture.release()
        GLES20.glDeleteTextures(1, intArrayOf(overlay.texture), 0)
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
            GLES20.glDeleteProgram(overlayProgram)
            overlayTextures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }
            overlayTextures.clear()
            surfaceOverlays.keys.toList().forEach(::closeSurface)
            GLES20.glDeleteProgram(surfaceProgram)
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
        val posted = handler.post {
            try {
                if (!released) block()
            } finally {
                done.countDown()
            }
        }
        if (posted) done.await()
    }

    private fun render(repeatNs: Long = 0) {
        if (released) return
        egl.makeNothingCurrent()
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)
        for (index in surfaceOverlayList.indices) {
            val overlay = surfaceOverlayList[index]
            if (overlay.dirty) {
                overlay.dirty = false
                overlay.ready = true
                overlay.surfaceTexture.updateTexImage()
                overlay.surfaceTexture.getTransformMatrix(overlay.matrix)
            }
        }
        val ts = if (repeatNs != 0L) repeatNs else toMonotonic(surfaceTexture.timestamp)
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
        tap?.let { current ->
            val now = System.nanoTime()
            if (now >= tapNextNs && current.busy.compareAndSet(false, true)) {
                tapNextNs = now + current.intervalNs
                val frame = runCatching { readTap(current) }.getOrNull()
                if (frame == null) current.busy.set(false) else current.deliver(frame)
            }
        }
        previewSurface?.let { surface ->
            if (egl.makeCurrent(surface)) {
                fitViewport(egl.surfaceWidth(surface), egl.surfaceHeight(surface))
                draw(if (mirror) previewMatrix else drawMatrix)
                drawOverlays(preview = true)
                egl.swap(surface)
            }
        }
    }

    private fun encode(surface: EGLSurface, matrix: FloatArray, ts: Long) {
        if (!egl.makeCurrent(surface)) return
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        draw(matrix)
        drawOverlays(preview = false)
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
            drawOverlays(preview = false)
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

    private fun readTap(current: FrameTap): Bitmap? {
        val surface = current.surface?.let { surfaceOverlays[it] }
        if (current.surface != null && (surface == null || !surface.ready)) return null
        val landscape = outputWidth >= outputHeight
        val width = if (landscape) TAP_SIDE else TAP_SIDE * outputWidth / outputHeight
        val height = if (landscape) TAP_SIDE * outputHeight / outputWidth else TAP_SIDE
        var bitmap = tapBitmap
        egl.makeNothingCurrent()
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            if (tapIds[0] != 0) {
                GLES20.glDeleteTextures(1, tapIds, 0)
                GLES20.glDeleteFramebuffers(1, tapIds, 1)
            }
            GLES20.glGenTextures(1, tapIds, 0)
            GLES20.glGenFramebuffers(1, tapIds, 1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tapIds[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tapIds[1])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tapIds[0], 0)
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            tapBitmap = bitmap
            tapPixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        }
        val pixels = tapPixels ?: return null
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tapIds[1])
            GLES20.glViewport(0, 0, width, height)
            if (surface == null) {
                Matrix.setIdentityM(scratch, 0)
                Matrix.translateM(scratch, 0, 0f, 1f, 0f)
                Matrix.scaleM(scratch, 0, 1f, -1f, 1f)
                Matrix.multiplyMM(tapMatrix, 0, drawMatrix, 0, scratch, 0)
                draw(tapMatrix)
            } else {
                GLES20.glUseProgram(surfaceProgram)
                quad.position(2)
                GLES20.glVertexAttribPointer(surfacePos, 2, GLES20.GL_FLOAT, false, 16, quad)
                GLES20.glEnableVertexAttribArray(surfacePos)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surface.texture)
                GLES20.glUniform4f(surfaceRect, 0f, 1f, 1f, -1f)
                GLES20.glUniform4f(surfaceCrop, 0f, 0f, 1f, 1f)
                GLES20.glUniform3f(surfaceTurn, 1f, 0f, 1f)
                GLES20.glUniform1f(surfaceAlpha, 1f)
                GLES20.glUniform2f(surfaceSize, width.toFloat(), height.toFloat())
                GLES20.glUniform2f(surfaceShape, 0f, 0f)
                Matrix.multiplyMM(tapMatrix, 0, surface.matrix, 0, surface.fill, 0)
                GLES20.glUniformMatrix4fv(surfaceSt, 1, false, tapMatrix, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            pixels.position(0)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            pixels.position(0)
            bitmap.copyPixelsFromBuffer(pixels)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        return bitmap
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
        fillMatrix(sourceMatrix, srcAspect, displayRotation, mirror)
        for (index in surfaceOverlayList.indices) {
            val overlay = surfaceOverlayList[index]
            val input = overlay.input ?: continue
            fillMatrix(overlay.fill, input.width, input.height, input.sensorOrientation, input.displayRotation, input.mirror)
        }
    }

    private fun fillMatrix(matrix: FloatArray, width: Int, height: Int, sensorOrientation: Int, displayRotation: Int, mirror: Boolean) {
        val turned = (sensorOrientation + displayRotation) % 180 != 0
        fillMatrix(matrix, if (turned) height.toFloat() / width else width.toFloat() / height, displayRotation, mirror)
    }

    private fun fillMatrix(matrix: FloatArray, srcAspect: Float, displayRotation: Int, mirror: Boolean) {
        val outAspect = outputWidth.toFloat() / outputHeight
        val sx = if (srcAspect > outAspect) outAspect / srcAspect else 1f
        val sy = if (srcAspect > outAspect) 1f else srcAspect / outAspect
        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(matrix, 0, -displayRotation.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(matrix, 0, if (mirror) -sx else sx, sy, 1f)
        Matrix.translateM(matrix, 0, -0.5f, -0.5f, 0f)
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

    private fun drawOverlays(preview: Boolean) {
        if (overlays.isEmpty()) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        quad.position(2)
        for (index in overlays.indices) {
            val overlay = overlays[index]
            if (overlay.previewOnly && !preview) continue
            val turn = Math.toRadians(overlay.rotation.toDouble())
            val cos = Math.cos(turn).toFloat()
            val sin = Math.sin(turn).toFloat()
            val aspect = outputWidth.toFloat() / outputHeight
            if (overlay.bitmap != null) {
                val texture = overlayTextures[overlay.id] ?: continue
                GLES20.glUseProgram(overlayProgram)
                GLES20.glVertexAttribPointer(overlayPos, 2, GLES20.GL_FLOAT, false, 16, quad)
                GLES20.glEnableVertexAttribArray(overlayPos)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id)
                GLES20.glUniform4f(overlayRect, overlay.x, overlay.y, overlay.width, overlay.height)
                GLES20.glUniform4f(overlayCrop, overlay.cropX, overlay.cropY, overlay.cropWidth, overlay.cropHeight)
                GLES20.glUniform3f(overlayTurn, cos, sin, aspect)
                GLES20.glUniform1f(overlayAlpha, overlay.alpha)
            } else {
                val surface = surfaceOverlays[overlay.surface]
                if (surface == null || !surface.ready) continue
                GLES20.glUseProgram(surfaceProgram)
                GLES20.glVertexAttribPointer(surfacePos, 2, GLES20.GL_FLOAT, false, 16, quad)
                GLES20.glEnableVertexAttribArray(surfacePos)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surface.texture)
                GLES20.glUniform4f(surfaceRect, overlay.x, overlay.y, overlay.width, overlay.height)
                GLES20.glUniform4f(surfaceCrop, overlay.cropX, overlay.cropY, overlay.cropWidth, overlay.cropHeight)
                GLES20.glUniform3f(surfaceTurn, cos, sin, aspect)
                GLES20.glUniform1f(surfaceAlpha, overlay.alpha)
                val boxWidth = overlay.width * outputWidth
                val boxHeight = overlay.height * outputHeight
                val short = minOf(boxWidth, boxHeight)
                val color = overlay.borderColor
                val opacity = (color ushr 24) / 255f
                GLES20.glUniform2f(surfaceSize, boxWidth, boxHeight)
                GLES20.glUniform2f(surfaceShape, overlay.cornerRadius * short / 2, overlay.borderWidth * short)
                GLES20.glUniform4f(
                    surfaceBorder,
                    (color shr 16 and 0xFF) / 255f * opacity,
                    (color shr 8 and 0xFF) / 255f * opacity,
                    (color and 0xFF) / 255f * opacity,
                    opacity,
                )
                Matrix.multiplyMM(scratch, 0, surface.matrix, 0, surface.fill, 0)
                GLES20.glUniformMatrix4fv(surfaceSt, 1, false, scratch, 0)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun setupGl() {
        overlayProgram = linkProgram(OVERLAY_VERTEX, OVERLAY_FRAGMENT)
        overlayPos = GLES20.glGetAttribLocation(overlayProgram, "aUnit")
        overlayRect = GLES20.glGetUniformLocation(overlayProgram, "uRect")
        overlayCrop = GLES20.glGetUniformLocation(overlayProgram, "uCrop")
        overlayTurn = GLES20.glGetUniformLocation(overlayProgram, "uTurn")
        overlayAlpha = GLES20.glGetUniformLocation(overlayProgram, "uAlpha")
        surfaceProgram = linkProgram(SURFACE_VERTEX, SURFACE_FRAGMENT)
        surfacePos = GLES20.glGetAttribLocation(surfaceProgram, "aUnit")
        surfaceRect = GLES20.glGetUniformLocation(surfaceProgram, "uRect")
        surfaceCrop = GLES20.glGetUniformLocation(surfaceProgram, "uCrop")
        surfaceTurn = GLES20.glGetUniformLocation(surfaceProgram, "uTurn")
        surfaceAlpha = GLES20.glGetUniformLocation(surfaceProgram, "uAlpha")
        surfaceSize = GLES20.glGetUniformLocation(surfaceProgram, "uSize")
        surfaceShape = GLES20.glGetUniformLocation(surfaceProgram, "uShape")
        surfaceBorder = GLES20.glGetUniformLocation(surfaceProgram, "uBorder")
        surfaceSt = GLES20.glGetUniformLocation(surfaceProgram, "uSt")
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
            val ok = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetProgramInfoLog(it) }
        }
    }

    private companion object {
        const val TAP_SIDE = 320
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
        const val PLACE = """
            attribute vec2 aUnit;
            uniform vec4 uRect;
            uniform vec4 uCrop;
            uniform vec3 uTurn;
            vec2 place() {
                vec2 corner = vec2(aUnit.x, 1.0 - aUnit.y);
                vec2 offset = (corner - 0.5) * uRect.zw;
                offset.x *= uTurn.z;
                offset = vec2(offset.x * uTurn.x - offset.y * uTurn.y, offset.x * uTurn.y + offset.y * uTurn.x);
                offset.x /= uTurn.z;
                vec2 point = uRect.xy + uRect.zw * 0.5 + offset;
                gl_Position = vec4(point.x * 2.0 - 1.0, 1.0 - point.y * 2.0, 0.0, 1.0);
                return uCrop.xy + corner * uCrop.zw;
            }
        """
        const val OVERLAY_VERTEX = PLACE + """
            varying vec2 vTex;
            void main() {
                vTex = place();
            }
        """
        const val OVERLAY_FRAGMENT = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            uniform float uAlpha;
            void main() {
                gl_FragColor = texture2D(sTex, vTex) * uAlpha;
            }
        """
        const val SURFACE_VERTEX = PLACE + """
            uniform mat4 uSt;
            uniform mediump vec2 uSize;
            varying vec2 vTex;
            varying vec2 vBox;
            void main() {
                vec2 point = place();
                vTex = (uSt * vec4(point.x, 1.0 - point.y, 0.0, 1.0)).xy;
                vBox = (aUnit - 0.5) * uSize;
            }
        """
        const val SURFACE_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            varying vec2 vBox;
            uniform samplerExternalOES sTex;
            uniform float uAlpha;
            uniform vec2 uSize;
            uniform vec2 uShape;
            uniform vec4 uBorder;
            void main() {
                vec4 color = texture2D(sTex, vTex);
                if (uShape.x > 0.0 || uShape.y > 0.0) {
                    vec2 corner = abs(vBox) - (uSize * 0.5 - uShape.x);
                    float edge = length(max(corner, 0.0)) + min(max(corner.x, corner.y), 0.0) - uShape.x;
                    color = mix(color, uBorder, clamp(edge + uShape.y + 0.5, 0.0, 1.0) * step(0.001, uShape.y));
                    color *= clamp(0.5 - edge, 0.0, 1.0);
                }
                gl_FragColor = color * uAlpha;
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
