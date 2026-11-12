package com.opirl.app.widgets.pomodoro

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FreeBreakfast
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private fun interface Glyph {
    fun draw(canvas: Canvas, box: RectF, fill: Paint, stroke: Paint)
}

private val vectors = ConcurrentHashMap<String, List<Path>>()

private fun paths(vector: ImageVector): List<Path> = vectors.getOrPut(vector.name) {
    val out = ArrayList<Path>()
    fun collect(group: VectorGroup) {
        for (node in group) {
            when (node) {
                is VectorPath -> out += node.pathData.toPath().also { it.fillType = node.pathFillType }.asAndroidPath()
                is VectorGroup -> collect(node)
            }
        }
    }
    collect(vector.root)
    out
}

private fun material(vector: ImageVector) = Glyph { canvas, box, fill, _ ->
    val side = box.width() * 1.2f
    canvas.save()
    canvas.translate(box.centerX() - side / 2, box.centerY() - side / 2)
    canvas.scale(side / vector.viewportWidth, side / vector.viewportHeight)
    paths(vector).forEach { canvas.drawPath(it, fill) }
    canvas.restore()
}

private fun RectF.x(fraction: Float) = left + fraction * width()

private fun RectF.y(fraction: Float) = top + fraction * height()

private fun ring(canvas: Canvas, box: RectF, stroke: Paint) =
    canvas.drawCircle(box.centerX(), box.centerY(), box.width() / 2 - stroke.strokeWidth / 2, stroke)

private fun polyline(box: RectF, vararg points: Float) = Path().apply {
    for (index in points.indices step 2) {
        if (index == 0) moveTo(box.x(points[0]), box.y(points[1])) else lineTo(box.x(points[index]), box.y(points[index + 1]))
    }
}

private val sun = Glyph { canvas, box, _, stroke ->
    canvas.drawCircle(box.centerX(), box.centerY(), 0.21f * box.width(), stroke)
    for (ray in 0 until 8) {
        val angle = ray * PI.toFloat() / 4
        val dx = cos(angle)
        val dy = sin(angle)
        canvas.drawLine(
            box.centerX() + dx * 0.34f * box.width(),
            box.centerY() + dy * 0.34f * box.width(),
            box.centerX() + dx * 0.455f * box.width(),
            box.centerY() + dy * 0.455f * box.width(),
            stroke,
        )
    }
}

private val bolt = Glyph { canvas, box, fill, stroke ->
    ring(canvas, box, stroke)
    material(Icons.Rounded.Bolt).draw(canvas, RectF(box.x(0.2f), box.y(0.2f), box.x(0.8f), box.y(0.8f)), fill, stroke)
}

private val play = Glyph { canvas, box, fill, stroke ->
    ring(canvas, box, stroke)
    canvas.drawPath(polyline(box, 0.41f, 0.28f, 0.41f, 0.72f, 0.74f, 0.5f).apply { close() }, fill)
}

private val target = Glyph { canvas, box, fill, stroke ->
    ring(canvas, box, stroke)
    canvas.drawCircle(box.centerX(), box.centerY(), 0.27f * box.width(), stroke)
    canvas.drawCircle(box.centerX(), box.centerY(), 0.09f * box.width(), fill)
}

private val timer = Glyph { canvas, box, _, stroke ->
    canvas.drawCircle(box.x(0.5f), box.y(0.58f), 0.4f * box.width() - stroke.strokeWidth / 2, stroke)
    canvas.drawPath(polyline(box, 0.5f, 0.58f, 0.66f, 0.42f), stroke)
    canvas.drawPath(polyline(box, 0.5f, 0.1f, 0.5f, 0.18f), stroke)
    canvas.drawPath(polyline(box, 0.4f, 0.07f, 0.6f, 0.07f), stroke)
}

private val dance = Glyph { canvas, box, fill, stroke ->
    stroke.strokeWidth = 0.11f * box.width()
    canvas.drawCircle(box.x(0.5f), box.y(0.13f), 0.1f * box.width(), fill)
    canvas.drawPath(polyline(box, 0.5f, 0.3f, 0.5f, 0.6f), stroke)
    canvas.drawPath(polyline(box, 0.3f, 0.12f, 0.36f, 0.3f, 0.5f, 0.34f, 0.74f, 0.4f, 0.88f, 0.3f), stroke)
    canvas.drawPath(polyline(box, 0.3f, 0.94f, 0.36f, 0.78f, 0.5f, 0.6f, 0.66f, 0.74f, 0.84f, 0.8f), stroke)
}

private fun glyph(icon: PomodoroFocusIcon): Glyph = when (icon) {
    PomodoroFocusIcon.SUN -> sun
    PomodoroFocusIcon.BOLT -> bolt
    PomodoroFocusIcon.CAP -> material(Icons.Rounded.School)
    PomodoroFocusIcon.BOOK -> material(Icons.Rounded.Book)
    PomodoroFocusIcon.PLAY -> play
    PomodoroFocusIcon.BRAIN -> material(Icons.Rounded.Psychology)
    PomodoroFocusIcon.PENCIL -> material(Icons.Rounded.Edit)
    PomodoroFocusIcon.FLAME -> material(Icons.Rounded.LocalFireDepartment)
    PomodoroFocusIcon.TIMER -> timer
    PomodoroFocusIcon.TARGET -> target
}

private fun glyph(icon: PomodoroBreakIcon): Glyph = when (icon) {
    PomodoroBreakIcon.CUP -> material(Icons.Rounded.FreeBreakfast)
    PomodoroBreakIcon.DANCE -> dance
    PomodoroBreakIcon.WALK -> material(Icons.AutoMirrored.Rounded.DirectionsWalk)
    PomodoroBreakIcon.MUSIC -> material(Icons.Rounded.MusicNote)
    PomodoroBreakIcon.LEAF -> material(Icons.Rounded.Eco)
    PomodoroBreakIcon.GAME -> material(Icons.Rounded.SportsEsports)
    PomodoroBreakIcon.FORK -> material(Icons.Rounded.Restaurant)
}

private fun draw(glyph: Glyph, canvas: Canvas, box: RectF, color: Int) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 0.085f * box.width()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    glyph.draw(canvas, box, fill, stroke)
}

internal fun drawPhaseIcon(canvas: Canvas, phase: PomodoroPhase, settings: PomodoroSettings, box: RectF, color: Int) =
    draw(if (phase == PomodoroPhase.FOCUS) glyph(settings.focusIcon) else glyph(settings.breakIcon), canvas, box, color)
