package com.opirl.app.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.opirl.app.R
import com.opirl.app.media.gl.EffectSettings
import com.opirl.app.settings.Effects
import com.opirl.app.settings.Lut
import com.opirl.app.settings.PrivacyMode
import java.io.File

class EffectLoader(private val context: Context, private val onError: (String, String) -> Unit) {
    private val directory = File(context.filesDir, DIRECTORY)
    private val cubes = HashMap<String, LutCube>()
    private val failed = HashSet<String>()
    private var faceImage: Pair<String, Bitmap>? = null
    private val icon by lazy { ContextCompat.getDrawable(context, R.drawable.ic_logo)!!.toBitmap(256, 256) }

    fun load(effects: Effects, maxWidth: Int, maxHeight: Int): EffectSettings {
        val enabled = effects.luts.filter { it.enabled }
        cubes.keys.retainAll(enabled.map { it.id }.toSet())
        failed.retainAll(enabled.map { it.id }.toSet())
        val image = effects.faceImage?.takeIf { effects.privacyMode == PrivacyMode.IMAGE }?.let { name ->
            faceImage?.takeIf { it.first == name }?.second ?: runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(directory, name))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = maxOf(info.size.width / maxWidth, info.size.height / maxHeight)
                    if (scale > 1) decoder.setTargetSampleSize(Integer.highestOneBit(scale))
                }
            }.getOrNull()?.also { faceImage = name to it }
        }
        if (image == null) faceImage = null
        return EffectSettings(
            filters = effects.filters,
            pixellate = effects.pixellate / 100f,
            whirlpool = Math.toRadians(effects.whirlpool.toDouble()).toFloat(),
            pinch = effects.pinch / 100f,
            privacyMode = effects.privacyMode,
            blurStrength = effects.blurStrength / 100f,
            facePixellate = effects.facePixellate / 100f,
            faceImage = image,
            icon = icon,
            smoothRadius = effects.smoothRadius.toFloat(),
            smoothStrength = effects.smoothStrength / 100f,
            shapePosition = effects.shapePosition / 100f,
            shapeRadius = effects.shapeRadius / 100f,
            shapeStrength = effects.shapeStrength / 100f,
            luts = enabled.mapNotNull(::cube),
        )
    }

    private fun cube(lut: Lut): LutCube? {
        cubes[lut.id]?.let { return it }
        if (lut.id in failed) return null
        return try {
            val bytes = if (lut.file == null) {
                context.assets.open("luts/${lut.id}.png").use { it.readBytes() }
            } else {
                File(directory, lut.file).readBytes()
            }
            val options = BitmapFactory.Options().apply { inScaled = false }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val cube = if (bitmap == null) {
                parseCubeLut(bytes.decodeToString())
            } else {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                imageLut(bitmap.width, bitmap.height) { x, y -> pixels[y * bitmap.width + x] }.also { bitmap.recycle() }
            }
            cubes[lut.id] = cube
            cube
        } catch (e: Exception) {
            failed += lut.id
            onError(lut.name, e.message ?: "")
            null
        }
    }

    companion object {
        const val DIRECTORY = "effects"
    }
}
