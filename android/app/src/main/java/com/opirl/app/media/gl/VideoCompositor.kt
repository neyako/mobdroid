package com.opirl.app.media.gl

import android.graphics.Bitmap
import android.graphics.Movie
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
import com.opirl.app.effects.ChromaKey
import com.opirl.app.effects.horizonScale
import com.opirl.app.effects.horizonStep
import com.opirl.app.settings.Reaction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

data class BrowserPage(val url: String, val width: Int, val height: Int)

class Overlay(
    val id: String,
    val bitmap: Bitmap?,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val previewOnly: Boolean,
    val browser: BrowserPage? = null,
    val key: ChromaKey? = null,
    val version: Int = 0,
)

private class OverlayTexture(val id: Int, var bitmap: Bitmap?, var version: Int)

private class SurfaceOverlay(val texture: Int, val surfaceTexture: SurfaceTexture, val surface: Surface) {
    @Volatile
    var dirty = false
    var ready = false
    val matrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
}

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
    private val streamScene = FloatArray(16)
    private val previewScene = FloatArray(16)
    private val horizonMatrix = FloatArray(16)
    private var horizonAngle = 0f
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
    private var overlayKey = 0
    private var overlayThreshold = 0
    private var overlays = emptyList<Overlay>()
    private val overlayTextures = HashMap<String, OverlayTexture>()
    private var surfaceProgram = 0
    private var surfacePos = 0
    private var surfaceRect = 0
    private var surfaceSt = 0
    private var surfaceKey = 0
    private var surfaceThreshold = 0
    private lateinit var effects: EffectRenderer
    private val surfaceOverlays = HashMap<String, SurfaceOverlay>()
    private val surfaceOverlayList = ArrayList<SurfaceOverlay>()
    private var frameIntervalNs = 1_000_000_000L / 30
    private var nextEncodeNs = 0L
    private var lastStamp = -1L
    private var released = false

    @Volatile
    var lastFrameNanos = 0L
        private set

    @Volatile
    var measuredFps = 0f
        private set

    @Volatile
    var horizonTilt: Float? = null
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0

    init {
        val ready = CountDownLatch(1)
        handler.post {
            egl = EglCore()
            setupGl()
            effects = EffectRenderer(quad)
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

    private val repeat = Runnable { render(System.nanoTime()) }

    fun repeatFrame() {
        if (!handler.hasCallbacks(repeat)) handler.post(repeat)
    }

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

    fun setEffects(settings: EffectSettings) = handler.post {
        if (released) return@post
        egl.makeNothingCurrent()
        effects.setSettings(settings)
    }

    @Suppress("DEPRECATION")
    fun playProp(reaction: Reaction, movie: Movie) = handler.post {
        if (released) return@post
        egl.makeNothingCurrent()
        effects.play(reaction, movie)
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
                OverlayTexture(ids[0], null, 0)
            }
            if (texture.bitmap !== bitmap || texture.version != overlay.version) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                texture.bitmap = bitmap
                texture.version = overlay.version
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
            effects.release()
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
        val stamp = surfaceTexture.timestamp
        if (repeatNs == 0L && stamp == lastStamp) return
        lastStamp = stamp
        val ts = if (repeatNs != 0L) repeatNs else toMonotonic(stamp)
        lastFrameNanos = SystemClock.elapsedRealtimeNanos()
        countFps()
        Matrix.multiplyMM(drawMatrix, 0, stMatrix, 0, sourceMatrix, 0)
        if (mirror) {
            Matrix.setIdentityM(scratch, 0)
            Matrix.translateM(scratch, 0, 1f, 0f, 0f)
            Matrix.scaleM(scratch, 0, -1f, 1f, 1f)
            Matrix.multiplyMM(previewMatrix, 0, drawMatrix, 0, scratch, 0)
        }
        val mirroredStream = mirror && mirrorStream
        val tilt = horizonTilt
        if (tilt == null) {
            horizonAngle = 0f
            System.arraycopy(if (mirroredStream) previewMatrix else drawMatrix, 0, streamScene, 0, 16)
            System.arraycopy(if (mirror) previewMatrix else drawMatrix, 0, previewScene, 0, 16)
        } else {
            horizonAngle = horizonStep(horizonAngle, tilt)
            setHorizon(if (mirror && !mirrorStream) horizonAngle else -horizonAngle)
            Matrix.multiplyMM(streamScene, 0, if (mirroredStream) previewMatrix else drawMatrix, 0, horizonMatrix, 0)
            setHorizon(-horizonAngle)
            Matrix.multiplyMM(previewScene, 0, if (mirror) previewMatrix else drawMatrix, 0, horizonMatrix, 0)
        }
        val processed = effects.active
        if (processed) {
            effects.begin(outputWidth, outputHeight)
            draw(streamScene)
            effects.process()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        } else {
            effects.idle()
        }
        if ((encoderSurface != null || recorderSurface != null) && shouldEncode(ts)) {
            encoderSurface?.let { encode(it, processed, ts) }
            recorderSurface?.let { encode(it, processed, ts) }
        }
        snapshot?.let {
            snapshot = null
            it(runCatching { capture(processed) }.getOrNull())
        }
        previewSurface?.let { surface ->
            if (egl.makeCurrent(surface)) {
                fitViewport(egl.surfaceWidth(surface), egl.surfaceHeight(surface))
                if (processed) effects.blit(mirror && !mirrorStream) else draw(previewScene)
                drawOverlays(preview = true)
                egl.swap(surface)
            }
        }
    }

    private fun setHorizon(angle: Float) {
        val aspect = outputWidth.toFloat() / outputHeight
        val scale = 1 / horizonScale(outputWidth.toFloat(), outputHeight.toFloat(), angle)
        Matrix.setIdentityM(horizonMatrix, 0)
        Matrix.translateM(horizonMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.scaleM(horizonMatrix, 0, scale / aspect, scale, 1f)
        Matrix.rotateM(horizonMatrix, 0, Math.toDegrees(angle.toDouble()).toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(horizonMatrix, 0, aspect, 1f, 1f)
        Matrix.translateM(horizonMatrix, 0, -0.5f, -0.5f, 0f)
    }

    private fun encode(surface: EGLSurface, processed: Boolean, ts: Long) {
        if (!egl.makeCurrent(surface)) return
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        if (processed) effects.blit(false) else draw(streamScene)
        drawOverlays(preview = false)
        egl.setPresentationTime(surface, ts)
        egl.swap(surface)
    }

    private fun capture(processed: Boolean): Bitmap {
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
            if (processed) effects.blit(false) else draw(streamScene)
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

    private fun drawOverlays(preview: Boolean) {
        if (overlays.isEmpty()) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        quad.position(2)
        for (index in overlays.indices) {
            val overlay = overlays[index]
            if (overlay.previewOnly && !preview) continue
            if (overlay.bitmap != null) {
                val texture = overlayTextures[overlay.id] ?: continue
                GLES20.glUseProgram(overlayProgram)
                GLES20.glVertexAttribPointer(overlayPos, 2, GLES20.GL_FLOAT, false, 16, quad)
                GLES20.glEnableVertexAttribArray(overlayPos)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id)
                GLES20.glUniform4f(overlayRect, overlay.x, overlay.y, overlay.width, overlay.height)
                setKey(overlayKey, overlayThreshold, overlay.key)
            } else {
                val surface = surfaceOverlays[overlay.id]
                if (surface == null || !surface.ready) continue
                GLES20.glUseProgram(surfaceProgram)
                GLES20.glVertexAttribPointer(surfacePos, 2, GLES20.GL_FLOAT, false, 16, quad)
                GLES20.glEnableVertexAttribArray(surfacePos)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surface.texture)
                GLES20.glUniform4f(surfaceRect, overlay.x, overlay.y, overlay.width, overlay.height)
                GLES20.glUniformMatrix4fv(surfaceSt, 1, false, surface.matrix, 0)
                setKey(surfaceKey, surfaceThreshold, overlay.key)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun setKey(color: Int, threshold: Int, key: ChromaKey?) {
        if (key == null) {
            GLES20.glUniform1f(threshold, -1f)
        } else {
            GLES20.glUniform3f(color, key.red, key.green, key.blue)
            GLES20.glUniform1f(threshold, key.threshold)
        }
    }

    private fun setupGl() {
        overlayProgram = linkProgram(OVERLAY_VERTEX, OVERLAY_FRAGMENT + KEYED)
        overlayPos = GLES20.glGetAttribLocation(overlayProgram, "aUnit")
        overlayRect = GLES20.glGetUniformLocation(overlayProgram, "uRect")
        overlayKey = GLES20.glGetUniformLocation(overlayProgram, "uKey")
        overlayThreshold = GLES20.glGetUniformLocation(overlayProgram, "uThreshold")
        surfaceProgram = linkProgram(SURFACE_VERTEX, SURFACE_FRAGMENT + KEYED)
        surfacePos = GLES20.glGetAttribLocation(surfaceProgram, "aUnit")
        surfaceRect = GLES20.glGetUniformLocation(surfaceProgram, "uRect")
        surfaceSt = GLES20.glGetUniformLocation(surfaceProgram, "uSt")
        surfaceKey = GLES20.glGetUniformLocation(surfaceProgram, "uKey")
        surfaceThreshold = GLES20.glGetUniformLocation(surfaceProgram, "uThreshold")
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
        const val OVERLAY_VERTEX = """
            attribute vec2 aUnit;
            uniform vec4 uRect;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(
                    (uRect.x + aUnit.x * uRect.z) * 2.0 - 1.0,
                    1.0 - (uRect.y + (1.0 - aUnit.y) * uRect.w) * 2.0,
                    0.0,
                    1.0);
                vTex = vec2(aUnit.x, 1.0 - aUnit.y);
            }
        """
        const val OVERLAY_FRAGMENT = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
        """
        const val KEYED = """
            uniform vec3 uKey;
            uniform float uThreshold;
            vec2 chroma(vec3 c) {
                float luma = 0.2989 * c.r + 0.5866 * c.g + 0.1145 * c.b;
                return vec2(0.7132 * (c.r - luma), 0.5647 * (c.b - luma));
            }
            void main() {
                vec4 c = texture2D(sTex, vTex);
                if (uThreshold >= 0.0 && c.a > 0.0) {
                    c *= smoothstep(uThreshold, uThreshold + 0.1, distance(chroma(c.rgb / c.a), chroma(uKey)));
                }
                gl_FragColor = c;
            }
        """
        const val SURFACE_VERTEX = """
            attribute vec2 aUnit;
            uniform vec4 uRect;
            uniform mat4 uSt;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(
                    (uRect.x + aUnit.x * uRect.z) * 2.0 - 1.0,
                    1.0 - (uRect.y + (1.0 - aUnit.y) * uRect.w) * 2.0,
                    0.0,
                    1.0);
                vTex = (uSt * vec4(aUnit, 0.0, 1.0)).xy;
            }
        """
        const val SURFACE_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
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
