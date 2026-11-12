package com.opirl.app.widgets.text

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

internal class TextLook(
    val background: Int,
    val foreground: Int,
    val alignment: TextAlignment,
    val size: Float,
    val minWidth: Float,
    val radius: Float,
    val typeface: Typeface,
    val monospacedDigits: Boolean,
)

private data class TypefaceKey(val family: TextFamily, val style: TextFontStyle, val design: TextDesign, val weight: TextWeight)

private val typefaces = HashMap<TypefaceKey, Typeface>()

private fun system(name: String, weight: Int) = Typeface.create(Typeface.create(name, Typeface.NORMAL), weight, false)

internal fun textTypeface(assets: AssetManager, options: TextSettings): Typeface = synchronized(typefaces) {
    typefaces.getOrPut(TypefaceKey(options.family, options.fontStyle, options.design, options.weight)) {
        val weight = options.weight.value
        if (options.family != TextFamily.SYSTEM) {
            Typeface.create(options.family.family, options.fontStyle.style)
        } else {
            when (options.design) {
                TextDesign.DEFAULT -> system("sans-serif", weight)
                TextDesign.SERIF -> system("serif", weight)
                TextDesign.MONOSPACED -> system("monospace", weight)
                TextDesign.ROUNDED -> runCatching {
                    Typeface.Builder(assets, "fonts/nunito.ttf").setFontVariationSettings("'wght' $weight").setWeight(weight).build()
                }.getOrNull() ?: system("sans-serif", weight)
            }
        }
    }
}

private const val STAR = 0xFFFFCC00.toInt()

private fun glyphWidth(segment: TextSegment, paint: Paint, size: Float): Float = when (segment) {
    is TextSegment.Run -> paint.measureText(segment.text)
    is TextSegment.Checkbox -> size
    TextSegment.Muted -> size
    is TextSegment.Stars -> size * 0.95f * 5
}

private fun drawCheckbox(canvas: Canvas, x: Float, baseline: Float, size: Float, checked: Boolean, paint: Paint) {
    val box = size * 0.8f
    val left = x + (size - box) / 2
    val top = baseline - size * 0.33f - box / 2
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    paint.strokeWidth = box * 0.085f
    canvas.drawRoundRect(RectF(left, top, left + box, top + box), box * 0.16f, box * 0.16f, paint)
    if (checked) {
        paint.strokeWidth = box * 0.11f
        val check = Path().apply {
            moveTo(left + box * 0.22f, top + box * 0.52f)
            lineTo(left + box * 0.43f, top + box * 0.72f)
            lineTo(left + box * 0.80f, top + box * 0.28f)
        }
        canvas.drawPath(check, paint)
    }
}

private fun drawMuted(canvas: Canvas, x: Float, baseline: Float, size: Float, paint: Paint) {
    val cx = x + size / 2
    val cy = baseline - size * 0.33f
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    paint.strokeWidth = size * 0.075f
    canvas.drawRoundRect(RectF(cx - size * 0.13f, cy - size * 0.34f, cx + size * 0.13f, cy + size * 0.1f), size * 0.13f, size * 0.13f, paint)
    canvas.drawArc(RectF(cx - size * 0.24f, cy - size * 0.2f, cx + size * 0.24f, cy + size * 0.2f), 0f, 180f, false, paint)
    canvas.drawLine(cx, cy + size * 0.2f, cx, cy + size * 0.33f, paint)
    canvas.drawLine(cx - size * 0.12f, cy + size * 0.33f, cx + size * 0.12f, cy + size * 0.33f, paint)
    canvas.drawLine(cx - size * 0.36f, cy - size * 0.38f, cx + size * 0.36f, cy + size * 0.42f, paint)
}

private fun drawStars(canvas: Canvas, x: Float, baseline: Float, size: Float, count: Int, foreground: Int, paint: Paint) {
    val advance = size * 0.95f
    val outer = size * 0.42f
    val inner = outer * 0.45f
    val cy = baseline - size * 0.34f
    paint.strokeJoin = Paint.Join.ROUND
    paint.strokeWidth = size * 0.05f
    for (index in 0 until 5) {
        val cx = x + advance * (index + 0.5f)
        val path = Path()
        for (point in 0 until 10) {
            val radius = if (point % 2 == 0) outer else inner
            val angle = Math.PI / 5 * point - Math.PI / 2
            val px = cx + radius * cos(angle).toFloat()
            val py = cy + radius * sin(angle).toFloat()
            if (point == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        if (index < count) {
            paint.style = Paint.Style.FILL
            paint.color = STAR
        } else {
            paint.style = Paint.Style.STROKE
            paint.color = foreground
        }
        canvas.drawPath(path, paint)
    }
}

internal fun drawTextLines(lines: List<TextLine>, look: TextLook, maxWidth: Int, maxHeight: Int): Bitmap? {
    val visible = lines.filter { it.visible }
    if (visible.isEmpty() || maxWidth <= 0 || maxHeight <= 0) return null
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = look.size
        typeface = look.typeface
        color = look.foreground
        if (look.monospacedDigits) fontFeatureSettings = "tnum"
    }
    val metrics = text.fontMetrics
    val lineHeight = metrics.descent - metrics.ascent
    val pad = 7 * look.size / 30 + minOf(look.radius / 5, look.size / 7.5f)
    val spacing = look.size / 15
    val contents = visible.map { line -> line.segments.sumOf { glyphWidth(it, text, look.size).toDouble() }.toFloat() }
    val boxes = contents.map { maxOf(it + 2 * pad, look.minWidth) }
    val stackWidth = boxes.max()
    val stackHeight = lineHeight * visible.size + spacing * (visible.size - 1)
    val bitmap = Bitmap.createBitmap(
        ceil(stackWidth).toInt().coerceIn(1, maxWidth),
        ceil(stackHeight).toInt().coerceIn(1, maxHeight),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.background }
    val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.foreground }
    visible.forEachIndexed { index, line ->
        val top = index * (lineHeight + spacing)
        val box = boxes[index]
        val left = when (look.alignment) {
            TextAlignment.LEADING -> 0f
            TextAlignment.CENTER -> (stackWidth - box) / 2
            TextAlignment.TRAILING -> stackWidth - box
        }
        if (look.background ushr 24 != 0) canvas.drawRoundRect(left, top, left + box, top + lineHeight, look.radius, look.radius, fill)
        val baseline = top - metrics.ascent
        var x = left + when (look.alignment) {
            TextAlignment.LEADING -> pad
            TextAlignment.CENTER -> (box - contents[index]) / 2
            TextAlignment.TRAILING -> box - pad - contents[index]
        }
        for (segment in line.segments) {
            when (segment) {
                is TextSegment.Run -> canvas.drawText(segment.text, x, baseline, text)
                is TextSegment.Checkbox -> {
                    glyph.color = look.foreground
                    drawCheckbox(canvas, x, baseline, look.size, segment.checked, glyph)
                }
                TextSegment.Muted -> {
                    glyph.color = look.foreground
                    drawMuted(canvas, x, baseline, look.size, glyph)
                }
                is TextSegment.Stars -> drawStars(canvas, x, baseline, look.size, segment.count, look.foreground, glyph)
            }
            x += glyphWidth(segment, text, look.size)
        }
    }
    return bitmap
}
