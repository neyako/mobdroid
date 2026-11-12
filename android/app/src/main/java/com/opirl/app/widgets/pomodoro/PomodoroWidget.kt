package com.opirl.app.widgets.pomodoro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import com.opirl.app.settings.Corner
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame
import kotlin.math.ceil
import kotlin.math.roundToInt

object PomodoroWidget : WidgetDrawer {
    private val phaseFont = Typeface.create(Typeface.DEFAULT, 600, false)
    private val timeFont = Typeface.create(Typeface.MONOSPACE, 700, false)

    override fun placement(widget: Widget) = SceneWidget(widget.id, corner = Corner.TOP_RIGHT, x = 1, y = 1, size = 20)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val settings = widget.pomodoro
        val reading = settings.reading(context.now)
        reading.nextChange?.let { context.redrawAt(it) }
        val unit = minOf(context.width, context.height) * placement.size.coerceIn(1, 100) / 100f
        if (unit < 1f) return null
        val bitmap = context.cached(widget.id, listOf(settings, reading.phase, reading.seconds, unit, context.width)) {
            drawTimer(settings, reading, unit, context.width)
        } ?: return null
        return context.natural(bitmap)
    }

    private fun drawTimer(settings: PomodoroSettings, reading: PomodoroReading, unit: Float, maxWidth: Int): Bitmap {
        val pad = 0.06f * unit
        val barHeight = 0.07f * unit
        val phaseSize = 0.15f * unit
        val timerSize = 0.18f * unit
        val gap = 0.04f * unit
        val row = 1.19f * timerSize
        val width = (settings.widthPercent.coerceIn(100, 500) / 100f * unit).roundToInt().coerceIn(1, maxWidth)
        val height = ceil(pad / 2 + row + gap + barHeight + pad).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val focus = reading.phase == PomodoroPhase.FOCUS
        val color = if (focus) settings.focusColor else settings.breakColor
        val name = (if (focus) settings.focusName else settings.breakName).trim()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = settings.background
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 0.08f * unit, 0.08f * unit, fill)
        val phasePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = phaseSize
            typeface = phaseFont
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = settings.foreground
            textSize = timerSize
            typeface = timeFont
        }
        val center = pad / 2 + row / 2
        val iconSide = 1.2f * phaseSize
        drawPhaseIcon(canvas, reading.phase, settings, RectF(pad, center - iconSide / 2, pad + iconSide, center + iconSide / 2), color)
        val time = formatTimer(reading.seconds)
        val timeLeft = width - pad - timePaint.measureText(time)
        val nameLeft = pad + iconSide + gap
        val shown = TextUtils.ellipsize(name, phasePaint, (timeLeft - 2 * gap - nameLeft).coerceAtLeast(0f), TextUtils.TruncateAt.END)
        canvas.drawText(shown, 0, shown.length, nameLeft, center - (phasePaint.ascent() + phasePaint.descent()) / 2, phasePaint)
        canvas.drawText(time, timeLeft, center - (timePaint.ascent() + timePaint.descent()) / 2, timePaint)
        val barTop = pad / 2 + row + gap
        val barRight = width - pad
        fill.color = ColorUtils.setAlphaComponent(settings.foreground, (Color.alpha(settings.foreground) * 0.2f).roundToInt())
        canvas.drawRoundRect(pad, barTop, barRight, barTop + barHeight, barHeight / 2, barHeight / 2, fill)
        val progress = (barRight - pad) * reading.progress
        if (progress >= 0.5f) {
            fill.color = color
            canvas.drawRoundRect(pad, barTop, pad + progress, barTop + barHeight, barHeight / 2, barHeight / 2, fill)
        }
        return bitmap
    }
}
