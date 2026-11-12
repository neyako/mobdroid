package com.opirl.app.widgets.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.max

private const val LAND = 0xFFF2EFE9.toInt()
private const val RING = 0xFFECECED.toInt()
private const val DOT = 0xFF3B82F7.toInt()
private const val INK = 0xFF333333.toInt()
private const val PLATE = 0xB3FFFFFF.toInt()
private const val ATTRIBUTION = "© OpenStreetMap"

fun drawMap(
    view: MapView,
    layout: MapLayout,
    cornerRadius: Int,
    borderColor: Int,
    dot: Float,
    text: Float,
    tile: (TileKey) -> Bitmap?,
): Bitmap {
    val side = layout.side.toFloat()
    val total = layout.total.toFloat()
    val bitmap = Bitmap.createBitmap(layout.total, layout.total, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (layout.border > 0) {
        val outer = total / 2 * cornerRadius / 100
        paint.color = borderColor
        canvas.drawRoundRect(RectF(0f, 0f, total, total), outer, outer, paint)
    }
    val radius = side / 2 * cornerRadius / 100
    canvas.translate(layout.border.toFloat(), layout.border.toFloat())
    if (radius > 0f) {
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, side, side), radius, radius, Path.Direction.CW) })
    } else {
        canvas.clipRect(0f, 0f, side, side)
    }
    canvas.drawColor(LAND)
    drawTiles(canvas, view, layout.side, tile)
    paint.color = RING
    canvas.drawCircle(side / 2, side / 2 + view.marker, dot / 2, paint)
    paint.color = DOT
    canvas.drawCircle(side / 2, side / 2 + view.marker, dot / 2 * 0.75f, paint)
    drawAttribution(canvas, side, radius, text)
    return bitmap
}

private fun drawTiles(canvas: Canvas, view: MapView, side: Int, tile: (TileKey) -> Bitmap?) {
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    canvas.save()
    canvas.translate(side / 2f, side / 2f)
    canvas.rotate(-view.heading.toFloat())
    canvas.scale(view.scale, view.scale)
    for (key in view.tiles(side)) {
        val bitmap = tile(key) ?: continue
        val left = (key.x * TILE_SIZE - view.centerX).toFloat()
        val top = (key.y * TILE_SIZE - view.centerY).toFloat()
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + TILE_SIZE + 0.5f, top + TILE_SIZE + 0.5f), paint)
    }
    canvas.restore()
}

private fun drawAttribution(canvas: Canvas, side: Float, radius: Float, size: Float) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = size
    }
    var width = paint.measureText(ATTRIBUTION) + size * 0.6f
    if (width > side * 0.9f) {
        paint.textSize = size * side * 0.9f / width
        width = side * 0.9f
    }
    val metrics = paint.fontMetrics
    val height = metrics.descent - metrics.ascent + paint.textSize * 0.3f
    val inset = max(paint.textSize * 0.25f, radius * 0.293f)
    val bottom = side - inset
    val left = max(0f, side - inset - width)
    val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE }
    canvas.drawRoundRect(RectF(left, bottom - height, left + width, bottom), height / 2, height / 2, plate)
    val baseline = bottom - height / 2 - (metrics.ascent + metrics.descent) / 2
    canvas.drawText(ATTRIBUTION, left + (width - paint.measureText(ATTRIBUTION)) / 2, baseline, paint)
}
