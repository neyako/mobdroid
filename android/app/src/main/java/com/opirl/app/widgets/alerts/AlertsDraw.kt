package com.opirl.app.widgets.alerts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val WRAP_WIDTH = 1000
private const val BORDER_WIDTH = 2f

internal fun messagePadding(unit: Float): Int = ceil(BORDER_WIDTH * unit).toInt()

internal fun alertTypeface(design: AlertFontDesign, weight: AlertFontWeight): Typeface {
    val family = when (design) {
        AlertFontDesign.DEFAULT -> Typeface.SANS_SERIF
        AlertFontDesign.SERIF -> Typeface.SERIF
        AlertFontDesign.ROUNDED -> Typeface.create("sans-serif-rounded", Typeface.NORMAL)
        AlertFontDesign.MONOSPACED -> Typeface.MONOSPACE
    }
    val value = when (weight) {
        AlertFontWeight.LIGHT -> 300
        AlertFontWeight.REGULAR -> 400
        AlertFontWeight.BOLD -> 700
    }
    return Typeface.create(family, value, false)
}

internal fun renderAlertMessage(plan: AlertPlan, unit: Float, maxWidth: Int, maxHeight: Int): Bitmap {
    val alert = plan.alert
    val size = alert.fontSize.coerceIn(10, 80) * unit
    val border = BORDER_WIDTH * unit
    val wrap = (WRAP_WIDTH * unit).roundToInt().coerceAtLeast(1)
    val typeface = alertTypeface(alert.fontDesign, alert.fontWeight)
    val user = "${plan.user} "
    val words = alertWords(plan)
    val plain = "$user$words "
    val colored = SpannableStringBuilder(plain).apply {
        setSpan(ForegroundColorSpan(alert.accentColor), 0, user.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.typeface = typeface
        color = alert.textColor
    }
    val stroke = TextPaint(fill).apply {
        style = Paint.Style.STROKE
        strokeWidth = border * 2
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
    }
    val fillLayout = layout(colored, fill, wrap)
    val strokeLayout = layout(plain, stroke, wrap)
    var widest = 0f
    for (line in 0 until fillLayout.lineCount) widest = maxOf(widest, fillLayout.getLineWidth(line))
    val padding = messagePadding(unit)
    val width = (ceil(widest).toInt() + 2 * padding).coerceIn(1, maxWidth)
    val height = (fillLayout.height + 2 * padding).coerceIn(1, maxHeight)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.translate(padding.toFloat(), padding.toFloat())
    strokeLayout.draw(canvas)
    fillLayout.draw(canvas)
    return bitmap
}

private fun layout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setIncludePad(false)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
