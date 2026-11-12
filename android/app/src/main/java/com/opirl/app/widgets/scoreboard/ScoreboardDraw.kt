package com.opirl.app.widgets.scoreboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil

internal const val LINE_HEIGHT = 1.193f
internal const val ASCENT = 0.952f
internal const val GAP = 8f
internal const val FOOTER = "Powered by opIRL"
internal const val BLACK_QUARTER = 0x40000000

internal enum class Align { START, CENTER, END }

internal data class Font(val size: Float, val weight: Int = 400, val mono: Boolean = false, val digits: Boolean = false)

internal class Sketch(val width: Float, val height: Float, val draw: (Canvas) -> Unit) {
    fun render(maxWidth: Int, maxHeight: Int): Bitmap {
        val fit = minOf(1f, maxWidth / width, maxHeight / height)
        val bitmap = Bitmap.createBitmap(
            ceil(width * fit).toInt().coerceIn(1, maxWidth),
            ceil(height * fit).toInt().coerceIn(1, maxHeight),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(fit, fit)
        draw(canvas)
        return bitmap
    }
}

internal fun withAlpha(color: Int, alpha: Float): Int = ((color ushr 24) * alpha).toInt().coerceIn(0, 255) shl 24 or (color and 0xFFFFFF)

internal class Brush {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faces = HashMap<Int, Typeface>()

    private fun apply(font: Font): Paint {
        ink.textSize = font.size
        ink.typeface = faces.getOrPut(font.weight + if (font.mono) 10_000 else 0) {
            Typeface.create(if (font.mono) Typeface.MONOSPACE else Typeface.DEFAULT, font.weight, false)
        }
        ink.fontFeatureSettings = if (font.digits) "tnum" else null
        return ink
    }

    fun width(text: String, font: Font): Float = apply(font).measureText(text)

    fun rect(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        fill.style = Paint.Style.FILL
        fill.color = color
        canvas.drawRect(left, top, right, bottom, fill)
    }

    fun roundRect(canvas: Canvas, rect: RectF, radius: Float, color: Int) {
        fill.style = Paint.Style.FILL
        fill.color = color
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    fun outline(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int, width: Float) {
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = width
        fill.color = color
        canvas.drawRect(left, top, right, bottom, fill)
    }

    fun clipRounded(canvas: Canvas, width: Float, height: Float, radius: Float) {
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, width, height), radius, radius, Path.Direction.CW) })
    }

    fun text(canvas: Canvas, text: String, font: Font, color: Int, x: Float, top: Float) {
        val paint = apply(font)
        paint.color = color
        canvas.drawText(text, x, top + font.size * ASCENT, paint)
    }

    fun textIn(
        canvas: Canvas,
        text: String,
        font: Font,
        color: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        align: Align,
        minScale: Float = 1f,
    ) {
        if (text.isEmpty() || width <= 0f) return
        var sized = font
        if (minScale < 1f) {
            val natural = width(text, font)
            var factor = 1f
            if (natural > width) factor = width / natural
            val line = LINE_HEIGHT * font.size
            if (line > height) factor = minOf(factor, height / line)
            sized = font.copy(size = font.size * maxOf(factor, minScale))
        }
        var shown = text
        if (width(shown, sized) > width + 0.5f) shown = ellipsize(shown, sized, width)
        val shownWidth = width(shown, sized)
        val left = when (align) {
            Align.START -> x
            Align.CENTER -> x + (width - shownWidth) / 2
            Align.END -> x + width - shownWidth
        }
        text(canvas, shown, sized, color, left, y + (height - LINE_HEIGHT * sized.size) / 2)
    }

    private fun ellipsize(text: String, font: Font, maxWidth: Float): String {
        val paint = apply(font)
        val room = maxWidth - paint.measureText("…")
        if (room <= 0f) return ""
        return text.substring(0, paint.breakText(text, true, room, null)).trimEnd() + "…"
    }
}

internal fun footerFont(u: Float) = Font(15 * u, weight = 700, mono = true)

internal fun Brush.footerWidth(u: Float) = 16 * u + width(FOOTER, footerFont(u)) + 2 * GAP

internal fun footerHeight(u: Float) = LINE_HEIGHT * 15 * u + 6 * u

internal fun Brush.drawFooter(canvas: Canvas, top: Float, width: Float, u: Float, background: Int, textColor: Int) {
    rect(canvas, 0f, top, width, top + footerHeight(u), background)
    text(canvas, FOOTER, footerFont(u), textColor, 8 * u, top + 3 * u)
}
