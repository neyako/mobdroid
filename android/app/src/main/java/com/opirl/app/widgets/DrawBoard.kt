package com.opirl.app.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import com.opirl.app.media.gl.Overlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private class Line(val color: Int, val width: Float) {
    val points = ArrayList<Float>()
}

class DrawBoard {
    private val lines = ArrayList<Line>()
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var version = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    @Synchronized
    fun setSize(width: Int, height: Int) {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(width, height))
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        if (bitmap?.width == w && bitmap?.height == h) return
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { canvas = Canvas(it) }
        redraw()
    }

    @Synchronized
    fun begin(x: Float, y: Float, color: Int, width: Float) {
        lines += Line(color, width).apply {
            points += x
            points += y
        }
        _count.value = lines.size
        stroke(lines.last(), 0)
    }

    @Synchronized
    fun extend(x: Float, y: Float) {
        val line = lines.lastOrNull() ?: return
        line.points += x
        line.points += y
        stroke(line, line.points.size / 2 - 1)
    }

    @Synchronized
    fun end() = redraw()

    @Synchronized
    fun undo() {
        if (lines.isEmpty()) return
        lines.removeAt(lines.lastIndex)
        _count.value = lines.size
        redraw()
    }

    @Synchronized
    fun wipe() {
        lines.clear()
        _count.value = 0
        redraw()
    }

    @Synchronized
    fun overlay(): Overlay? {
        val target = bitmap ?: return null
        if (lines.isEmpty()) return null
        return Overlay(ID, target, 0f, 0f, 1f, 1f, previewOnly = false, version = version)
    }

    private fun redraw() {
        val target = canvas ?: return
        target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (line in lines) {
            for (index in 0 until line.points.size / 2) stroke(line, index, last = index == line.points.size / 2 - 1)
        }
        version++
    }

    private fun stroke(line: Line, index: Int, last: Boolean = false) {
        val target = canvas ?: return
        val width = target.width.toFloat()
        val height = target.height.toFloat()
        val points = line.points
        val x = points[index * 2] * width
        val y = points[index * 2 + 1] * height
        paint.color = line.color
        paint.strokeWidth = line.width * width
        path.rewind()
        if (index == 0) {
            path.moveTo(x, y)
            path.lineTo(x + 0.01f, y)
        } else {
            val previousX = points[index * 2 - 2] * width
            val previousY = points[index * 2 - 1] * height
            if (index == 1) {
                path.moveTo(previousX, previousY)
            } else {
                path.moveTo((points[index * 2 - 4] * width + previousX) / 2, (points[index * 2 - 3] * height + previousY) / 2)
            }
            path.quadTo(previousX, previousY, (previousX + x) / 2, (previousY + y) / 2)
            if (last) path.lineTo(x, y)
        }
        target.drawPath(path, paint)
        version++
    }

    companion object {
        const val ID = "draw"
        private const val MAX_SIDE = 1280
    }
}
