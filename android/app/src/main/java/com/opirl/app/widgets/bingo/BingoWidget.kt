package com.opirl.app.widgets.bingo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.opirl.app.settings.Corner
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame
import kotlin.math.roundToInt

object BingoWidget : WidgetDrawer {
    override fun placement(widget: Widget) = SceneWidget(widget.id, corner = Corner.TOP_RIGHT, x = 1, y = 33, size = 33)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val settings = widget.bingo
        val short = minOf(context.width, context.height)
        val side = (short * placement.size / 100f).roundToInt().coerceAtLeast(8)
        val bitmap = context.cached(widget.id, listOf(settings, side, short)) { drawCard(settings, side, short / 1080f) } ?: return null
        return context.natural(bitmap)
    }

    private fun drawCard(settings: BingoSettings, side: Int, scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val count = bingoSide(settings.squares.size)
        val line = maxOf(2f * scale, 1f)
        val half = line / 2
        val cell = (side - line) / count
        val fill = Paint().apply { color = settings.background }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = settings.foreground }
        for (row in 0 until count) {
            for (column in 0 until count) {
                canvas.drawRect(half + column * cell, half + row * cell, half + (column + 1) * cell, half + (row + 1) * cell, fill)
            }
        }
        for (step in 0..count) {
            val at = half + step * cell
            canvas.drawRect(at - half, 0f, at + half, side.toFloat(), ink)
            canvas.drawRect(0f, at - half, side.toFloat(), at + half, ink)
        }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = settings.foreground }
        val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = settings.foreground
            style = Paint.Style.STROKE
            strokeWidth = maxOf(0.035f * cell, 1f)
        }
        val padding = 4f * scale
        settings.squares.take(count * count).forEachIndexed { index, square ->
            val left = half + index % count * cell
            val top = half + index / count * cell
            if (square.text.isNotEmpty()) {
                text.textSize = 0.35f * cell
                val room = (cell - 2 * padding).toInt().coerceAtLeast(1)
                val layout = fitted(square.text, room, text)
                canvas.save()
                canvas.translate(left + padding, top + (cell - layout.height) / 2)
                layout.draw(canvas)
                canvas.restore()
            }
            if (square.checked) {
                val x = left + cell / 2
                val y = top + cell / 2
                val reach = 0.36f * cell
                canvas.drawLine(x - reach, y - reach, x + reach, y + reach, cross)
                canvas.drawLine(x - reach, y + reach, x + reach, y - reach, cross)
            }
        }
        return bitmap
    }

    private fun fitted(text: String, room: Int, paint: TextPaint): StaticLayout {
        val size = paint.textSize
        var scale = 1f
        var layout = layout(text, room, paint, false)
        while ((layout.lineCount > 3 || layout.height > room) && scale > 0.4f) {
            scale = maxOf(scale - 0.02f, 0.4f)
            paint.textSize = size * scale
            layout = layout(text, room, paint, false)
        }
        return if (layout.lineCount > 3) layout(text, room, paint, true) else layout
    }

    private fun layout(text: String, width: Int, paint: TextPaint, cut: Boolean): StaticLayout {
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_CENTER)
        return if (cut) builder.setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build() else builder.build()
    }
}
