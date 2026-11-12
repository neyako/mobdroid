package com.opirl.app.media.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.PorterDuff
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import com.opirl.app.effects.DetectedFace
import com.opirl.app.effects.Detector
import com.opirl.app.effects.LutCube
import com.opirl.app.effects.pixellateCell
import com.opirl.app.effects.textMask
import com.opirl.app.settings.Filter
import com.opirl.app.settings.PrivacyMode
import com.opirl.app.settings.Reaction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

class EffectSettings(
    val filters: Set<Filter> = emptySet(),
    val pixellate: Float = 0.3f,
    val whirlpool: Float = 1.57f,
    val pinch: Float = 0.5f,
    val privacyMode: PrivacyMode = PrivacyMode.BLUR,
    val blurStrength: Float = 0.8f,
    val facePixellate: Float = 0.3f,
    val faceImage: Bitmap? = null,
    val icon: Bitmap? = null,
    val smoothRadius: Float = 10f,
    val smoothStrength: Float = 0.65f,
    val shapePosition: Float = 0.5f,
    val shapeRadius: Float = 0.5f,
    val shapeStrength: Float = 0.5f,
    val luts: List<LutCube> = emptyList(),
)

private class Program(vertex: String, fragment: String, all: MutableList<Program>) {
    val id = linkProgram(vertex, fragment)
    val unit = GLES20.glGetAttribLocation(id, "aUnit")
    private val locations = HashMap<String, Int>()

    init {
        all += this
    }

    fun use() = GLES20.glUseProgram(id)

    fun location(name: String): Int = locations.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

    fun set(name: String, value: Float) = GLES20.glUniform1f(location(name), value)

    fun set(name: String, x: Float, y: Float) = GLES20.glUniform2f(location(name), x, y)

    fun set(name: String, x: Float, y: Float, z: Float) = GLES20.glUniform3f(location(name), x, y, z)

    fun set(name: String, x: Float, y: Float, z: Float, w: Float) = GLES20.glUniform4f(location(name), x, y, z, w)

    fun setInt(name: String, value: Int) = GLES20.glUniform1i(location(name), value)
}

private class Target(val width: Int, val height: Int) {
    val texture: Int
    val framebuffer: Int

    init {
        val ids = IntArray(2)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glGenFramebuffers(1, ids, 1)
        texture = ids[0]
        framebuffer = ids[1]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        linear(GLES20.GL_TEXTURE_2D)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

private class Readback(width: Int, height: Int) {
    val target = Target(width, height)
    val pixels: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    val buffer: Int
    var text = false

    init {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        buffer = ids[0]
        GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, buffer)
        GLES20.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, width * height * 4, null, GLES30.GL_STREAM_READ)
        GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    fun release() {
        target.release()
        GLES20.glDeleteBuffers(1, intArrayOf(buffer), 0)
    }
}

@Suppress("DEPRECATION")
private class Prop(val reaction: Reaction, val movie: Movie, val createdNanos: Long) {
    var startNanos = 0L
    val bitmap: Bitmap = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val texture = bitmapTexture()
    val duration = movie.duration().coerceAtLeast(1)
    var frame = -1
}

private fun linear(target: Int) {
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
}

private fun bitmapTexture(): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
    linear(GLES20.GL_TEXTURE_2D)
    return ids[0]
}

fun linkProgram(vertex: String, fragment: String): Int {
    fun compile(type: Int, source: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, source.trimIndent())
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

class EffectRenderer(private val quad: FloatBuffer) {
    private val programs = ArrayList<Program>()
    private val copy by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, COPY, programs) }
    private val color by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, COLOR, programs) }
    private val pixellate by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, PIXELLATE, programs) }
    private val fourThree by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, FOUR_THREE, programs) }
    private val twin by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, TWIN, programs) }
    private val triple by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, TRIPLE, programs) }
    private val pinch by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, PINCH, programs) }
    private val whirlpool by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, WHIRLPOOL, programs) }
    private val crt by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, CRT, programs) }
    private val lut by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, LUT, programs) }
    private val blur by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, BLUR, programs) }
    private val face by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, FACE, programs) }
    private val sprite by lazy(LazyThreadSafetyMode.NONE) { Program(SPRITE_VERTEX, SPRITE, programs) }
    private val beautyBase by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, BEAUTY_BASE, programs) }
    private val beauty by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, BEAUTY, programs) }
    private val bulge by lazy(LazyThreadSafetyMode.NONE) { Program(VERTEX, BULGE, programs) }

    private var settings = EffectSettings()
    private var current: Target? = null
    private var spare: Target? = null
    private var small: Target? = null
    private var smallSpare: Target? = null
    private var faceReadback: Readback? = null
    private var textReadback: Readback? = null
    private var pending: Readback? = null
    private var detector: Detector? = null
    private var lastFaceCapture = 0L
    private var lastTextCapture = 0L
    private val lutTextures = HashMap<LutCube, Int>()
    private var faceImage: Bitmap? = null
    private var faceImageTexture = 0
    private var icon: Bitmap? = null
    private var iconTexture = 0
    private val props = ArrayList<Prop>()
    private val faceUniforms = FloatArray(MAX_FACES * 4)
    private val textUniforms = FloatArray(MAX_TEXTS * 4)
    private val bulgeUniforms = FloatArray(MAX_FACES * 4)
    private var width = 0
    private var height = 0

    val active: Boolean get() = settings.filters.isNotEmpty() || settings.luts.isNotEmpty() || props.isNotEmpty()

    fun setSettings(next: EffectSettings) {
        settings = next
        val ids = IntArray(1)
        lutTextures.keys.filter { it !in next.luts }.forEach { cube ->
            ids[0] = lutTextures.remove(cube)!!
            GLES20.glDeleteTextures(1, ids, 0)
        }
    }

    @Suppress("DEPRECATION")
    fun play(reaction: Reaction, movie: Movie) {
        if (props.any { it.reaction == reaction }) return
        props += Prop(reaction, movie, System.nanoTime())
    }

    fun begin(outputWidth: Int, outputHeight: Int) {
        if (width != outputWidth || height != outputHeight) {
            releaseTargets()
            width = outputWidth
            height = outputHeight
        }
        val target = current ?: Target(width, height).also { current = it }
        if (spare == null) spare = Target(width, height)
        target.bind()
    }

    fun idle() {
        if (current == null) return
        releaseTargets()
        detector?.release()
        detector = null
    }

    fun process() {
        val now = System.nanoTime()
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        quad.position(2)
        detect(now)
        val filters = settings.filters
        for (index in settings.luts.indices) applyLut(settings.luts[index])
        val faces = detector?.faces ?: emptyList()
        if (props.isNotEmpty()) drawProps(now, faces)
        if (Filter.BLUR_FACES in filters || Filter.PRIVACY in filters || Filter.BLUR_TEXT in filters) applyPrivacy(faces)
        if (Filter.MOUTH in filters) drawMouths(faces)
        if (Filter.BEAUTY in filters) applyBeauty(faces)
        if (Filter.WHIRLPOOL in filters) {
            pass(whirlpool) {
                it.set("uSize", width.toFloat(), height.toFloat())
                it.set("uRadius", min(width, height) / 1.9f)
                it.set("uAngle", settings.whirlpool)
            }
        }
        if (Filter.PINCH in filters) {
            pass(pinch) {
                it.set("uSize", width.toFloat(), height.toFloat())
                it.set("uRadius", min(width, height) / 2f)
                it.set("uScale", settings.pinch)
            }
        }
        if (Filter.FOUR_THREE in filters) pass(fourThree) {}
        if (Filter.GRAYSCALE in filters) {
            pass(color) {
                it.set("uRed", 0.15945f, 0.5364f, 0.05415f)
                it.set("uGreen", 0.15945f, 0.5364f, 0.05415f)
                it.set("uBlue", 0.15945f, 0.5364f, 0.05415f)
            }
        }
        if (Filter.SEPIA in filters) {
            pass(color) {
                it.set("uRed", 0.393f, 0.769f, 0.189f)
                it.set("uGreen", 0.349f, 0.686f, 0.168f)
                it.set("uBlue", 0.272f, 0.534f, 0.131f)
            }
        }
        if (Filter.TRIPLE in filters) pass(triple) {}
        if (Filter.TWIN in filters) pass(twin) {}
        if (Filter.PIXELLATE in filters) {
            val cell = pixellateCell(width, height, settings.pixellate)
            pass(pixellate) { it.set("uCell", cell / width, cell / height) }
        }
        if (Filter.CRT in filters) {
            pass(crt) {
                it.set("uSize", width.toFloat(), height.toFloat())
                it.set("uLine", max(1f, height / 240f))
            }
        }
    }

    fun blit(flip: Boolean) {
        val source = current ?: return
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        quad.position(2)
        draw(copy, source.texture)
        if (flip) copy.set("uMap", -1f, 1f, 1f, 0f) else copy.set("uMap", 1f, 1f, 0f, 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        releaseTargets()
        detector?.release()
        detector = null
        programs.forEach { GLES20.glDeleteProgram(it.id) }
        val ids = IntArray(1)
        (lutTextures.values + faceImageTexture + iconTexture + props.map { it.texture }).filter { it != 0 }.forEach {
            ids[0] = it
            GLES20.glDeleteTextures(1, ids, 0)
        }
        lutTextures.clear()
        props.clear()
    }

    private fun releaseTargets() {
        current?.release()
        spare?.release()
        small?.release()
        smallSpare?.release()
        faceReadback?.release()
        textReadback?.release()
        current = null
        spare = null
        small = null
        smallSpare = null
        faceReadback = null
        textReadback = null
        pending = null
    }

    private fun draw(program: Program, texture: Int) {
        program.use()
        GLES20.glVertexAttribPointer(program.unit, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(program.unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
    }

    private inline fun pass(program: Program, setup: (Program) -> Unit) {
        val source = current!!
        val target = spare!!
        target.bind()
        draw(program, source.texture)
        setup(program)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        current = target
        spare = source
    }

    private fun wantsFaces(): Boolean {
        val filters = settings.filters
        return props.isNotEmpty() || Filter.BLUR_FACES in filters || Filter.PRIVACY in filters || Filter.MOUTH in filters ||
            (Filter.BEAUTY in filters && settings.shapeStrength > 0)
    }

    private fun wantsText() = Filter.BLUR_TEXT in settings.filters

    private fun detect(now: Long) {
        val faces = wantsFaces()
        val text = wantsText()
        if (!faces && !text) {
            detector?.release()
            detector = null
            return
        }
        val running = detector ?: Detector().also { detector = it }
        val waiting = pending
        if (waiting != null) {
            if (running.busy) return
            pending = null
            val size = waiting.target.width * waiting.target.height * 4
            GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, waiting.buffer)
            val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, size, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
            if (mapped != null) {
                waiting.pixels.position(0)
                waiting.pixels.put(mapped)
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            }
            GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            if (mapped != null) {
                running.submit(
                    waiting.pixels,
                    waiting.target.width,
                    waiting.target.height,
                    faces,
                    Filter.MOUTH in settings.filters,
                    text && waiting.text,
                    text,
                )
            }
            return
        }
        if (running.busy) return
        val textDue = text && now - lastTextCapture >= TEXT_INTERVAL_NANOS
        if (!textDue && (!faces || now - lastFaceCapture < FACE_INTERVAL_NANOS)) return
        val readback = if (textDue) {
            textReadback ?: readback(TEXT_SIDE).also { textReadback = it }
        } else {
            faceReadback ?: readback(FACE_SIDE).also { faceReadback = it }
        }
        readback.text = textDue
        if (textDue) lastTextCapture = now
        lastFaceCapture = now
        readback.target.bind()
        draw(copy, current!!.texture)
        copy.set("uMap", 1f, -1f, 0f, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, readback.buffer)
        GLES30.glReadPixels(0, 0, readback.target.width, readback.target.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
        GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        pending = readback
    }

    private fun readback(side: Int): Readback {
        val scale = min(1f, side.toFloat() / max(width, height))
        return Readback(max(2, (width * scale).toInt() / 2 * 2), max(2, (height * scale).toInt() / 2 * 2))
    }

    private fun applyLut(cube: LutCube) {
        val texture = lutTextures.getOrPut(cube) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, ids[0])
            linear(GLES30.GL_TEXTURE_3D)
            GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            cube.rgb.position(0)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, cube.size, cube.size, cube.size, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, cube.rgb,
            )
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            ids[0]
        }
        pass(lut) {
            it.setInt("sLut", 1)
            it.set("uSize", cube.size.toFloat())
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, texture)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
    }

    private fun blurred(sigma: Float, base: Program?): Int {
        val first = small ?: Target(max(2, width / 4), max(2, height / 4)).also { small = it }
        val second = smallSpare ?: Target(first.width, first.height).also { smallSpare = it }
        first.bind()
        val source = base ?: copy
        draw(source, current!!.texture)
        if (base == null) copy.set("uMap", 1f, 1f, 0f, 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val spacing = max(1f, sigma / 2.67f)
        second.bind()
        draw(blur, first.texture)
        blur.set("uSigma", sigma / spacing)
        blur.set("uStep", spacing / first.width, 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        first.bind()
        draw(blur, second.texture)
        blur.set("uStep", 0f, spacing / first.height)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        return first.texture
    }

    private fun applyPrivacy(faces: List<DetectedFace>) {
        val filters = settings.filters
        val running = detector
        val texts = if (Filter.BLUR_TEXT in filters && running != null) running.texts else NO_TEXTS
        val background = Filter.PRIVACY in filters
        val blurFaces = Filter.BLUR_FACES in filters
        val mode = settings.privacyMode
        val shownIcon = if (blurFaces && mode == PrivacyMode.ICON) settings.icon else null
        if (faces.isEmpty() && texts.isEmpty() && !background) return
        val image = if (mode == PrivacyMode.IMAGE) settings.faceImage else null
        var privacy = 0
        if (image != null) {
            if (faceImage !== image) {
                if (faceImageTexture == 0) faceImageTexture = bitmapTexture()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceImageTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, image, 0)
                faceImage = image
            }
            privacy = faceImageTexture
        } else if (mode != PrivacyMode.PIXELLATE) {
            val strength = if (mode == PrivacyMode.BLUR) settings.blurStrength else 1f
            privacy = blurred(width / 50f * strength / 4, null)
        }
        val ratio = if (mode == PrivacyMode.BLUR || mode == PrivacyMode.PIXELLATE) 1.5f else 1.2f
        val faceCount = min(faces.size, MAX_FACES)
        for (index in 0 until faceCount) {
            val found = faces[index]
            val radius = max(found.width * width, found.height * height) * 0.6f
            faceUniforms[index * 4] = (found.x + found.width / 2) * width
            faceUniforms[index * 4 + 1] = (1 - found.y - found.height / 2) * height
            faceUniforms[index * 4 + 2] = radius
            faceUniforms[index * 4 + 3] = radius * ratio
        }
        val textCount = textMask(texts, width, height, textUniforms)
        pass(face) {
            it.setInt("sPrivacy", 1)
            it.set("uSize", width.toFloat(), height.toFloat())
            it.setInt("uPixellate", if (mode == PrivacyMode.PIXELLATE) 1 else 0)
            it.set("uCell", pixellateCell(width, height, settings.facePixellate))
            if (image != null) {
                val fit = (image.width.toFloat() / image.height) / (width.toFloat() / height)
                val sx = if (fit > 1) 1 / fit else 1f
                val sy = if (fit > 1) 1f else fit
                it.set("uMap", sx, -sy, 0.5f - 0.5f * sx, 0.5f + 0.5f * sy)
            } else {
                it.set("uMap", 1f, 1f, 0f, 0f)
            }
            it.setInt("uFaceCount", faceCount)
            if (faceCount > 0) GLES20.glUniform4fv(it.location("uFaces"), faceCount, faceUniforms, 0)
            it.set("uFaceBlur", if (blurFaces && shownIcon == null) 1f else 0f)
            it.set("uBackground", if (background) 1f else 0f)
            it.setInt("uTextCount", textCount)
            if (textCount > 0) GLES20.glUniform4fv(it.location("uTexts"), textCount, textUniforms, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, privacy)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
        if (shownIcon != null && faces.isNotEmpty()) {
            if (icon !== shownIcon) {
                if (iconTexture == 0) iconTexture = bitmapTexture()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iconTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, shownIcon, 0)
                icon = shownIcon
            }
            beginSprites(iconTexture)
            for (index in faces.indices) {
                val found = faces[index]
                val side = 1.3f * max(found.width * width, found.height * height)
                sprite(
                    (found.x + found.width / 2) * width,
                    (1 - found.y - found.height / 2) * height,
                    side * shownIcon.width / shownIcon.height,
                    side,
                    roll(found),
                )
            }
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun roll(found: DetectedFace): Float {
        val eyes = found.eyes ?: return 0f
        return -atan2((eyes[3] - eyes[1]) * height, (eyes[2] - eyes[0]) * width)
    }

    private fun beginSprites(texture: Int) {
        current!!.bind()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        draw(sprite, texture)
        sprite.set("uView", width.toFloat(), height.toFloat())
    }

    private fun sprite(centerX: Float, centerY: Float, spriteWidth: Float, spriteHeight: Float, angle: Float) {
        sprite.set("uCenter", centerX, centerY)
        sprite.set("uSize", spriteWidth, spriteHeight)
        sprite.set("uAngle", angle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawMouths(faces: List<DetectedFace>) {
        val logo = settings.icon ?: return
        var started = false
        for (index in faces.indices) {
            val mouth = faces[index].mouth ?: continue
            val mouthWidth = (mouth[2] - mouth[0]) * width
            val mouthHeight = (mouth[3] - mouth[1]) * height
            if (mouthHeight <= mouthWidth) continue
            if (!started) {
                if (icon !== logo) {
                    if (iconTexture == 0) iconTexture = bitmapTexture()
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iconTexture)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, logo, 0)
                    icon = logo
                }
                beginSprites(iconTexture)
                started = true
            }
            sprite(
                (mouth[0] + mouth[2]) / 2 * width,
                (1 - (mouth[1] + mouth[3]) / 2) * height,
                mouthWidth,
                mouthWidth * logo.height / logo.width,
                roll(faces[index]),
            )
        }
        if (started) GLES20.glDisable(GLES20.GL_BLEND)
    }

    @Suppress("DEPRECATION")
    private fun drawProps(now: Long, faces: List<DetectedFace>) {
        var index = 0
        while (index < props.size) {
            val prop = props[index]
            if (prop.startNanos == 0L && faces.any { it.eyes != null }) prop.startNanos = now
            val elapsed = ((now - prop.startNanos) / 1_000_000).toInt()
            val loops = if (prop.reaction == Reaction.GLASSES) 3 else 1
            if (prop.startNanos == 0L && now - prop.createdNanos < PROP_WAIT_NANOS) {
                index++
                continue
            }
            if (prop.startNanos == 0L || elapsed >= prop.duration * loops) {
                GLES20.glDeleteTextures(1, intArrayOf(prop.texture), 0)
                prop.bitmap.recycle()
                props.removeAt(index)
                continue
            }
            index++
            val frame = elapsed / PROP_FRAME_MS
            if (frame != prop.frame) {
                prop.frame = frame
                prop.movie.setTime(elapsed % prop.duration)
                prop.canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                prop.movie.draw(prop.canvas, 0f, 0f)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prop.texture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, prop.bitmap, 0)
            }
            var started = false
            for (faceIndex in faces.indices) {
                val eyes = faces[faceIndex].eyes ?: continue
                if (!started) {
                    beginSprites(prop.texture)
                    started = true
                }
                val size = max(faces[faceIndex].width * width, faces[faceIndex].height * height)
                val angle = roll(faces[faceIndex])
                if (prop.reaction == Reaction.GLASSES) {
                    sprite((eyes[0] + eyes[2]) / 2 * width, (1 - (eyes[1] + eyes[3]) / 2) * height, size * 0.9f, size * 0.9f, angle)
                } else {
                    sprite(eyes[2] * width + size * 0.03f, (1 - eyes[3]) * height + size * 0.03f, size * 0.25f, size * 0.25f, angle)
                }
            }
            if (started) GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun applyBeauty(faces: List<DetectedFace>) {
        if (settings.smoothStrength > 0) {
            val blurredBase = blurred(settings.smoothRadius * width / 750f / 4, beautyBase)
            pass(beauty) {
                it.setInt("sBlur", 1)
                it.set("uAmount", settings.smoothStrength)
                it.set("uTexel", 1f / width, 1f / height)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurredBase)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            }
        }
        val count = min(faces.size, MAX_FACES)
        if (settings.shapeStrength <= 0 || count == 0) return
        for (index in 0 until count) {
            val found = faces[index]
            val length = found.height * height * 0.75f
            bulgeUniforms[index * 4] = (found.x + found.width / 2) * width
            bulgeUniforms[index * 4 + 1] = (1 - found.y - found.height) * height + length * (settings.shapePosition - 0.5f) * 0.5f
            bulgeUniforms[index * 4 + 2] = length * (0.7f + settings.shapeRadius * 0.3f)
            bulgeUniforms[index * 4 + 3] = -settings.shapeStrength * 0.075f
        }
        pass(bulge) {
            it.set("uSize", width.toFloat(), height.toFloat())
            it.setInt("uCount", count)
            GLES20.glUniform4fv(it.location("uBulges"), count, bulgeUniforms, 0)
        }
    }

    private companion object {
        const val MAX_FACES = 8
        const val MAX_TEXTS = 16
        const val FACE_SIDE = 640
        const val TEXT_SIDE = 1280
        const val FACE_INTERVAL_NANOS = 80_000_000L
        const val TEXT_INTERVAL_NANOS = 400_000_000L
        const val PROP_FRAME_MS = 50
        const val PROP_WAIT_NANOS = 10_000_000_000L
        val NO_TEXTS = FloatArray(0)

        const val VERTEX = """
            #version 300 es
            in vec2 aUnit;
            out vec2 vTex;
            void main() {
                gl_Position = vec4(aUnit * 2.0 - 1.0, 0.0, 1.0);
                vTex = aUnit;
            }
        """
        const val COPY = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            uniform vec4 uMap;
            void main() {
                oColor = texture(sTex, vTex * uMap.xy + uMap.zw);
            }
        """
        const val COLOR = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            uniform vec3 uRed;
            uniform vec3 uGreen;
            uniform vec3 uBlue;
            void main() {
                vec4 c = texture(sTex, vTex);
                oColor = vec4(dot(c.rgb, uRed), dot(c.rgb, uGreen), dot(c.rgb, uBlue), c.a);
            }
        """
        const val PIXELLATE = """
            #version 300 es
            precision highp float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            uniform vec2 uCell;
            void main() {
                oColor = texture(sTex, (floor(vTex / uCell) + 0.5) * uCell);
            }
        """
        const val FOUR_THREE = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            void main() {
                oColor = abs(vTex.x - 0.5) > 0.375 ? vec4(0.0, 0.0, 0.0, 1.0) : texture(sTex, vTex);
            }
        """
        const val TWIN = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            void main() {
                oColor = texture(sTex, vec2(vTex.x < 0.5 ? vTex.x + 0.25 : 1.25 - vTex.x, vTex.y));
            }
        """
        const val TRIPLE = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            void main() {
                oColor = texture(sTex, vec2((1.0 + fract(vTex.x * 3.0)) / 3.0, vTex.y));
            }
        """
        const val PINCH = """
            #version 300 es
            precision highp float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            uniform vec2 uSize;
            uniform float uRadius;
            uniform float uScale;
            void main() {
                vec2 center = uSize * 0.5;
                vec2 offset = vTex * uSize - center;
                float reach = length(offset);
                if (reach < uRadius) {
                    offset *= 1.0 + (uRadius - reach) / uRadius * uScale;
                }
                oColor = texture(sTex, (center + offset) / uSize);
            }
        """
        const val WHIRLPOOL = """
            #version 300 es
            precision highp float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            uniform vec2 uSize;
            uniform float uRadius;
            uniform float uAngle;
            void main() {
                vec2 center = uSize * 0.5;
                vec2 offset = vTex * uSize - center;
                float reach = length(offset);
                if (reach < uRadius) {
                    float amount = (uRadius - reach) / uRadius;
                    float theta = amount * amount * uAngle;
                    float s = sin(theta);
                    float c = cos(theta);
                    offset = vec2(offset.x * c - offset.y * s, offset.x * s + offset.y * c);
                }
                oColor = texture(sTex, (center + offset) / uSize);
            }
        """
        const val CRT = """
            #version 300 es
            precision highp float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            uniform vec2 uSize;
            uniform float uLine;
            void main() {
                vec2 center = uSize * 0.5;
                float scale = min(center.x, center.y);
                vec2 coord = (vTex * uSize - center) / scale;
                vec2 source = coord * (1.0 + 0.1 * dot(coord, coord)) * scale + center;
                vec2 uv = source / uSize;
                if (abs(uv.x - 0.5) > 0.375 || uv.y < 0.0 || uv.y > 1.0) {
                    oColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec3 c = texture(sTex, uv).rgb;
                c *= 1.0 - 0.25 * smoothstep(0.35, 0.65, abs(fract(source.y / (2.0 * uLine)) * 2.0 - 1.0));
                c = mix(vec3(dot(c, vec3(0.2126, 0.7152, 0.0722))), c, 0.7);
                c = (c - 0.5) * 1.05 + 0.48;
                vec2 edge = (uv - 0.5) * vec2(1.0 / 0.75, 1.0) * 2.0;
                c *= 1.0 - 0.35 * dot(edge, edge) * 0.5;
                oColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """
        const val LUT = """
            #version 300 es
            precision mediump float;
            precision mediump sampler3D;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            uniform sampler3D sLut;
            uniform float uSize;
            void main() {
                vec4 c = texture(sTex, vTex);
                oColor = vec4(texture(sLut, clamp(c.rgb, 0.0, 1.0) * ((uSize - 1.0) / uSize) + 0.5 / uSize).rgb, c.a);
            }
        """
        const val BLUR = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            uniform vec2 uStep;
            uniform float uSigma;
            void main() {
                vec4 sum = vec4(0.0);
                float total = 0.0;
                for (int i = -8; i <= 8; i++) {
                    float weight = exp(-float(i * i) / (2.0 * uSigma * uSigma));
                    sum += texture(sTex, vTex + uStep * float(i)) * weight;
                    total += weight;
                }
                oColor = sum / total;
            }
        """
        const val FACE = """
            #version 300 es
            precision highp float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            uniform sampler2D sPrivacy;
            uniform vec2 uSize;
            uniform int uPixellate;
            uniform float uCell;
            uniform vec4 uMap;
            uniform vec4 uFaces[8];
            uniform int uFaceCount;
            uniform float uFaceBlur;
            uniform float uBackground;
            uniform vec4 uTexts[16];
            uniform int uTextCount;
            void main() {
                vec4 c = texture(sTex, vTex);
                vec2 p = vTex * uSize;
                float face = 0.0;
                for (int i = 0; i < 8; i++) {
                    if (i >= uFaceCount) break;
                    face = max(face, 1.0 - smoothstep(uFaces[i].z, uFaces[i].w, distance(p, uFaces[i].xy)));
                }
                float amount = mix(face * uFaceBlur, 1.0 - face, uBackground);
                for (int i = 0; i < 16; i++) {
                    if (i >= uTextCount) break;
                    vec4 t = uTexts[i];
                    if (p.x >= t.x && p.x <= t.z && p.y >= t.y && p.y <= t.w) amount = 1.0;
                }
                if (amount <= 0.0) {
                    oColor = c;
                    return;
                }
                vec4 hidden = uPixellate == 1
                    ? texture(sTex, (floor(p / uCell) + 0.5) * uCell / uSize)
                    : texture(sPrivacy, vTex * uMap.xy + uMap.zw);
                oColor = vec4(mix(c.rgb, hidden.rgb, amount), 1.0);
            }
        """
        const val SPRITE_VERTEX = """
            #version 300 es
            in vec2 aUnit;
            out vec2 vTex;
            uniform vec2 uCenter;
            uniform vec2 uSize;
            uniform vec2 uView;
            uniform float uAngle;
            void main() {
                vec2 p = (aUnit - 0.5) * uSize;
                float s = sin(uAngle);
                float c = cos(uAngle);
                p = vec2(p.x * c - p.y * s, p.x * s + p.y * c) + uCenter;
                gl_Position = vec4(p / uView * 2.0 - 1.0, 0.0, 1.0);
                vTex = vec2(aUnit.x, 1.0 - aUnit.y);
            }
        """
        const val SPRITE = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            void main() {
                oColor = texture(sTex, vTex);
            }
        """
        const val BEAUTY_BASE = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            void main() {
                vec4 c = texture(sTex, vTex);
                float base = c.b < 0.5 ? 2.0 * c.b * c.g : 1.0 - 2.0 * (1.0 - c.b) * (1.0 - c.g);
                oColor = vec4(base, base, base, 1.0);
            }
        """
        const val BEAUTY = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            uniform sampler2D sBlur;
            uniform float uAmount;
            uniform vec2 uTexel;
            void main() {
                vec4 c = texture(sTex, vTex);
                float base = c.b < 0.5 ? 2.0 * c.b * c.g : 1.0 - 2.0 * (1.0 - c.b) * (1.0 - c.g);
                float detail = base - texture(sBlur, vTex).r + 0.5;
                for (int i = 0; i < 3; i++) {
                    detail = detail < 0.5 ? detail * detail * 2.0 : 1.0 - (1.0 - detail) * (1.0 - detail) * 2.0;
                }
                detail = clamp((detail - 75.0 / 255.0) * (255.0 / 89.0), 0.0, 1.0);
                vec3 lifted = mix(c.rgb, c.rgb * (1.409 - 0.409 * c.rgb), uAmount);
                vec3 smoothed = mix(c.rgb, lifted, 1.0 - detail);
                vec3 around = (texture(sTex, vTex + vec2(uTexel.x, 0.0)).rgb + texture(sTex, vTex - vec2(uTexel.x, 0.0)).rgb +
                    texture(sTex, vTex + vec2(0.0, uTexel.y)).rgb + texture(sTex, vTex - vec2(0.0, uTexel.y)).rgb) * 0.25;
                oColor = vec4(clamp(smoothed + (c.rgb - around) * 0.3 * uAmount, 0.0, 1.0), c.a);
            }
        """
        const val BULGE = """
            #version 300 es
            precision highp float;
            in vec2 vTex;
            out vec4 oColor;
            uniform sampler2D sTex;
            uniform vec2 uSize;
            uniform vec4 uBulges[8];
            uniform int uCount;
            void main() {
                vec2 p = vTex * uSize;
                for (int i = 0; i < 8; i++) {
                    if (i >= uCount) break;
                    vec2 offset = p - uBulges[i].xy;
                    float reach = length(offset);
                    float radius = uBulges[i].z;
                    if (reach < radius) {
                        float amount = 1.0 - (radius - reach) / radius * uBulges[i].w;
                        p = uBulges[i].xy + offset * amount * amount;
                    }
                }
                oColor = texture(sTex, p / uSize);
            }
        """
    }
}
