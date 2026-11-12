package com.opirl.app.widgets.wheel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import com.opirl.app.settings.Corner
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.widgets.Layer
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame
import kotlin.math.roundToInt

private val COLORS = listOf(0xFF007AFF, 0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFFFF2D55, 0xFF32ADE6).map { it.toInt() }

object WheelWidget : WidgetDrawer {
    override val sized get() = false

    override fun placement(widget: Widget) = SceneWidget(widget.id, corner = Corner.TOP_RIGHT, x = 1, y = 31)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val settings = widget.wheel
        val state = context.runtime.state(widget.id) { WheelState() }
        val tick = state.spin.update(context.now, settings.onlyShowWhenSpinning)
        if (tick.finished) {
            optionAtPointer(settings.options, tick.rotation)?.let {
                state.add(WheelResult(it.text, context.now))
                context.runtime.invalidate()
            }
        }
        context.redrawAt(tick.wakeAt)
        if (!tick.shown) return null
        val side = (context.width * 400f / 1920f).roundToInt().coerceAtLeast(8)
        val wheel = context.cached(widget.id, listOf(settings.options, side)) { drawWheel(settings.options, side) } ?: return null
        val arrow = context.cached("${widget.id}:arrow", side) { drawArrow(side) } ?: return null
        val width = side + 0.3f * arrow.width
        val pointer = Layer(
            arrow,
            x = (side - 0.7f * arrow.width) / width,
            y = (side - arrow.height) / 2f / side,
            width = arrow.width / width,
            height = arrow.height.toFloat() / side,
        )
        return WidgetFrame(
            listOf(Layer(wheel, width = side / width, rotation = tick.rotation.toFloat()), pointer),
            width / context.width,
            side.toFloat() / context.height,
        )
    }

    private fun drawWheel(options: List<WheelOption>, side: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val size = side.toFloat()
        val total = totalWeight(options).toFloat()
        val sector = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
        val bounds = RectF(0f, 0f, size, size)
        val middles = FloatArray(options.size)
        var start = 0f
        options.forEachIndexed { index, option ->
            val sweep = 360f * option.weight.coerceAtLeast(0) / total
            sector.color = COLORS[index % COLORS.size]
            canvas.drawArc(bounds, start - 90f, sweep, true, sector)
            middles[index] = start + sweep / 2 - 90f
            start += sweep
        }
        canvas.drawCircle(size / 2, size / 2, size / 10, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        options.forEachIndexed { index, option -> drawLabel(canvas, option.text, middles[index], size, label) }
        return bitmap
    }

    private fun drawLabel(canvas: Canvas, text: String, angle: Float, size: Float, paint: TextPaint) {
        if (text.isEmpty()) return
        val room = 150f * size / 400f
        paint.textSize = size / 10
        val natural = paint.measureText(text)
        if (natural > room) paint.textSize = size / 10 * maxOf(room / natural, 0.1f)
        val shown = TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END).toString()
        canvas.save()
        canvas.translate(size / 2, size / 2)
        canvas.rotate(angle)
        val metrics = paint.fontMetrics
        canvas.drawText(shown, size / 3.4f - paint.measureText(shown) / 2, -(metrics.ascent + metrics.descent) / 2, paint)
        canvas.restore()
    }

    private fun drawArrow(side: Int): Bitmap {
        val unit = side / 12f
        val width = unit.roundToInt().coerceAtLeast(2)
        val height = (unit * 0.85f).roundToInt().coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val path = Path().apply {
            moveTo(0f, height / 2f)
            lineTo(width.toFloat(), 0f)
            lineTo(width * 0.72f, height / 2f)
            lineTo(width.toFloat(), height.toFloat())
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            pathEffect = CornerPathEffect(unit * 0.08f)
        }
        Canvas(bitmap).drawPath(path, paint)
        return bitmap
    }
}
