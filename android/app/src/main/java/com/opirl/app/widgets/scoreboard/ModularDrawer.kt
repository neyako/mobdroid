package com.opirl.app.widgets.scoreboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

private const val BLACK = 0xFF000000.toInt()
private const val WHITE_TEXT = -1

private class Modular(
    val settings: ScoreboardSettings,
    val now: Long,
    val u: Float,
    val brush: Brush,
    val ball: Bitmap?,
) {
    val modular = settings.modular
    val template = modularTemplate(settings.sport)
    val rowHeight = modular.rowHeight * u
    val size = rowHeight * 0.8f
    val teamWidth = modular.width * u
    val statWidth = size * 1.55f
    val historyWidth = size * 1.5f
    val barHeight = rowHeight * 0.6f
    val fullHeight = rowHeight + if (modular.showMoreStats) barHeight else 0f
    val history = if (modular.layout == ScoreboardLayout.STACK_HISTORY) modular.maxHistory() else 0
    val infoLines = if (modular.showInfoBox) modular.infoLines(template, settings.sport, now) else emptyList()
    val weight = if (modular.bold) 700 else 400
    val statWeight = if (modular.bold) 900 else 700
    val titleHeight = if (modular.showTitle) LINE_HEIGHT * size * 0.7f + 2 * u else 0f
    val infoFont = Font(size, weight, digits = true)
    val infoWidth = if (infoLines.isEmpty()) 0f else infoLines.maxOf { brush.width(it, infoFont) } + 4 * GAP
    val ballPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun nameFont() = Font(size, weight)

    fun stat(
        canvas: Canvas,
        team: ModularTeam,
        value: String,
        label: String,
        fontSize: Float,
        x: Float,
        y: Float,
        width: Float,
        gray: Boolean,
        textWeight: Int = statWeight,
    ) {
        if (value.isEmpty()) return
        if (gray) brush.rect(canvas, x, y, x + width, y + rowHeight, BLACK_QUARTER)
        if (label.isEmpty()) {
            brush.textIn(canvas, value, Font(fontSize, textWeight), team.textColor, x, y, width, rowHeight, Align.CENTER, 0.5f)
            return
        }
        val labelFont = Font(fontSize * 0.25f, weight)
        val valueFont = Font(fontSize * 0.75f, textWeight)
        val labelHeight = LINE_HEIGHT * labelFont.size
        val total = labelHeight + LINE_HEIGHT * valueFont.size - 2 * u
        val top = y + (rowHeight - total) / 2
        brush.text(canvas, label, labelFont, team.textColor, x + (width - brush.width(label, labelFont)) / 2, top + fontSize * 0.04f)
        brush.text(canvas, value, valueFont, team.textColor, x + (width - brush.width(value, valueFont)) / 2, top + labelHeight - 2 * u)
    }

    fun primary(canvas: Canvas, team: ModularTeam, x: Float, y: Float) =
        stat(canvas, team, team.primary, "", size, x, y, statWidth, false)

    fun secondary(canvas: Canvas, team: ModularTeam, x: Float, y: Float) =
        stat(canvas, team, team.secondary, template.secondaryLabel, size, x, y, statWidth, true)

    fun possession(canvas: Canvas, x: Float, y: Float) {
        val ball = ball ?: return
        val inset = size * 0.1f
        canvas.drawBitmap(ball, null, RectF(x + inset, y + inset, x + rowHeight - inset, y + rowHeight - inset), ballPaint)
    }

    fun name(canvas: Canvas, team: ModularTeam, x: Float, y: Float, width: Float, align: Align) =
        brush.textIn(canvas, team.name, nameFont(), team.textColor, x, y, width, rowHeight, align, 0.1f)

    fun moreStats(canvas: Canvas, team: ModularTeam, x: Float, y: Float, width: Float, alignRight: Boolean) {
        if (!modular.showMoreStats) return
        brush.rect(canvas, x, y, x + width, y + barHeight, team.backgroundColor)
        brush.rect(canvas, x, y, x + width, y + barHeight, BLACK_QUARTER)
        val entries = (0 until MODULAR_STATS).map { template.statLabels[it] to team.stats[it] }
            .filter { it.second.isNotEmpty() && !it.second.startsWith("NO ") }
        if (entries.isEmpty()) return
        val shrink = maxOf(0.3f, minOf(1f, barHeight / (LINE_HEIGHT * size)))
        val labelFont = Font(size * shrink, weight)
        val valueFont = labelFont.copy(digits = true)
        val available = width - 12 * u
        val gaps = (entries.size - 1) * 8 * u
        val widths = entries.map { (label, value) ->
            (if (label.isEmpty()) 0f else brush.width("$label:", labelFont) + 2) + brush.width(value, valueFont)
        }
        val fit = minOf(1f, (available - gaps) / widths.sum())
        var left = if (alignRight) x + width - 6 * u - (widths.sum() * fit + gaps) else x + 6 * u
        entries.forEachIndexed { index, (label, value) ->
            val labelSized = labelFont.copy(size = labelFont.size * fit)
            val valueSized = valueFont.copy(size = valueFont.size * fit)
            val top = y + (barHeight - LINE_HEIGHT * labelSized.size) / 2
            if (label.isNotEmpty()) {
                brush.text(canvas, "$label:", labelSized, withAlpha(team.textColor, 0.8f), left, top)
                left += brush.width("$label:", labelSized) + 2
            }
            brush.text(canvas, value, valueSized, team.textColor, left, top)
            left += brush.width(value, valueSized) + 8 * u
        }
    }

    fun title(canvas: Canvas, width: Float) {
        if (!modular.showTitle) return
        brush.rect(canvas, 0f, 0f, width, titleHeight, BLACK)
        brush.textIn(canvas, modular.title, Font(size * 0.7f, weight), WHITE_TEXT, GAP, 0f, width - 2 * GAP, titleHeight, Align.CENTER)
    }

    fun infoBox(canvas: Canvas, x: Float, y: Float) {
        if (infoLines.isEmpty()) return
        val height = 2 * fullHeight
        brush.rect(canvas, x, y, x + infoWidth, y + height, BLACK)
        val line = LINE_HEIGHT * size
        var top = y + (height - infoLines.size * line) / 2
        for (text in infoLines) {
            brush.textIn(canvas, text, infoFont, WHITE_TEXT, x + 2 * GAP, top, infoWidth - 4 * GAP, line, Align.CENTER, 0.1f)
            top += line
        }
    }

    fun stackedTeam(canvas: Canvas, side: Side, x: Float, y: Float) {
        val team = modular.team(side)
        brush.rect(canvas, x, y, x + teamWidth, y + rowHeight, team.backgroundColor)
        val inline = modular.layout == ScoreboardLayout.STACKED_INLINE
        val leading = if (!inline && team.secondary.isNotEmpty()) statWidth else 0f
        val trailing = if (inline && team.secondary.isNotEmpty()) statWidth else 0f
        val ballWidth = if (team.possession) rowHeight else 0f
        val primaryWidth = if (team.primary.isNotEmpty()) statWidth else 0f
        val nameWidth = teamWidth - leading - ballWidth - trailing - primaryWidth
        if (leading > 0f) secondary(canvas, team, x, y)
        name(canvas, team, x + leading + 8 * u, y, nameWidth - 8 * u, Align.START)
        if (team.possession) possession(canvas, x + leading + nameWidth, y)
        if (trailing > 0f) secondary(canvas, team, x + leading + nameWidth + ballWidth, y)
        primary(canvas, team, x + teamWidth - primaryWidth, y)
        moreStats(canvas, team, x, y + rowHeight, teamWidth, false)
    }

    fun historyTeam(canvas: Canvas, side: Side, x: Float, y: Float, width: Float) {
        val team = modular.team(side)
        val other = modular.team(side.other())
        brush.rect(canvas, x, y, x + width, y + rowHeight, team.backgroundColor)
        val ballWidth = if (team.possession) rowHeight else 0f
        val primaryWidth = if (team.primary.isNotEmpty()) statWidth else 0f
        val historyTotal = history * historyWidth
        val nameWidth = width - ballWidth - historyTotal - primaryWidth
        name(canvas, team, x + 8 * u, y, nameWidth - 10 * u, Align.START)
        if (team.possession) possession(canvas, x + nameWidth, y)
        val current = modular.shownPeriod(template).trim().toIntOrNull() ?: 1
        for (index in 1..history) {
            val value = team.history[index - 1]
            val otherValue = other.history[index - 1]
            val cellX = x + nameWidth + ballWidth + (index - 1) * historyWidth
            val mine = value.toIntOrNull() ?: -1
            val theirs = otherValue.toIntOrNull() ?: -1
            val won = index < current && mine > theirs && mine >= 0
            if (value.isNotEmpty()) {
                stat(canvas, team, value, "", size * 0.9f, cellX, y, historyWidth, true, if (won) statWeight else weight)
            } else if (otherValue.isNotEmpty()) {
                stat(canvas, team, "0", "", size * 0.9f, cellX, y, historyWidth, true, weight)
            }
        }
        primary(canvas, team, x + width - primaryWidth, y)
        moreStats(canvas, team, x, y + rowHeight, width, false)
    }

    fun sideTeam(canvas: Canvas, side: Side, x: Float, y: Float, mirrored: Boolean) {
        val team = modular.team(side)
        brush.rect(canvas, x, y, x + teamWidth, y + rowHeight, team.backgroundColor)
        val ballWidth = if (team.possession) rowHeight else 0f
        val secondaryWidth = if (team.secondary.isNotEmpty()) statWidth else 0f
        val primaryWidth = if (team.primary.isNotEmpty()) statWidth else 0f
        val nameWidth = teamWidth - ballWidth - secondaryWidth - primaryWidth
        if (!mirrored) {
            if (team.possession) possession(canvas, x, y)
            name(canvas, team, x + ballWidth + 4 * u, y, nameWidth - 8 * u, Align.END)
            secondary(canvas, team, x + ballWidth + nameWidth, y)
            primary(canvas, team, x + ballWidth + nameWidth + secondaryWidth, y)
        } else {
            primary(canvas, team, x, y)
            secondary(canvas, team, x + primaryWidth, y)
            name(canvas, team, x + primaryWidth + secondaryWidth + 4 * u, y, nameWidth - 8 * u, Align.START)
            if (team.possession) possession(canvas, x + teamWidth - ballWidth, y)
        }
        moreStats(canvas, team, x, y + rowHeight, teamWidth, !mirrored)
    }

    fun center(canvas: Canvas, x: Float, y: Float, width: Float) {
        brush.rect(canvas, x, y, x + width, y + fullHeight, BLACK)
        if (!modular.showInfoBox) {
            brush.textIn(canvas, "-", Font(size, weight), WHITE_TEXT, x, y, width, fullHeight, Align.CENTER)
            return
        }
        val period = modular.periodFull(template, settings.sport)
        val clock = if (modular.showClock) modular.clock.format(now) else ""
        val periodFont = Font(size * 0.6f, weight)
        val clockFont = Font(size * 0.9f, weight, digits = true)
        val periodHeight = if (period.isEmpty()) 0f else LINE_HEIGHT * periodFont.size
        val clockHeight = if (clock.isEmpty()) 0f else LINE_HEIGHT * clockFont.size
        var top = y + (fullHeight - periodHeight - clockHeight) / 2
        brush.textIn(canvas, period, periodFont, WHITE_TEXT, x, top, width, periodHeight, Align.CENTER, 0.1f)
        top += periodHeight
        brush.textIn(canvas, clock, clockFont, WHITE_TEXT, x, top, width, clockHeight, Align.CENTER, 0.1f)
    }

    val centerWidth: Float
        get() = if (modular.showInfoBox) size * 3.5f else brush.width("-", Font(size, weight))
}

private fun Side.other() = if (this == Side.HOME) Side.AWAY else Side.HOME

internal fun modularSketch(settings: ScoreboardSettings, now: Long, u: Float, brush: Brush, ball: Bitmap?): Sketch {
    val m = Modular(settings, now, u, brush, ball)
    val layout = settings.modular.layout
    val rows = when (layout) {
        ScoreboardLayout.SIDE_BY_SIDE -> 2 * m.teamWidth + m.centerWidth
        ScoreboardLayout.STACK_HISTORY -> m.teamWidth + m.history * m.historyWidth + m.infoWidth
        else -> m.teamWidth + m.infoWidth
    }
    val height = m.titleHeight + if (layout == ScoreboardLayout.SIDE_BY_SIDE) m.fullHeight else 2 * m.fullHeight
    return Sketch(rows, height) { canvas ->
        m.title(canvas, rows)
        val top = m.titleHeight
        when (layout) {
            ScoreboardLayout.SIDE_BY_SIDE -> {
                m.sideTeam(canvas, Side.HOME, 0f, top, false)
                m.center(canvas, m.teamWidth, top, m.centerWidth)
                m.sideTeam(canvas, Side.AWAY, m.teamWidth + m.centerWidth, top, true)
            }
            ScoreboardLayout.STACK_HISTORY -> {
                val width = m.teamWidth + m.history * m.historyWidth
                m.historyTeam(canvas, Side.HOME, 0f, top, width)
                m.historyTeam(canvas, Side.AWAY, 0f, top + m.fullHeight, width)
                m.infoBox(canvas, width, top)
            }
            else -> {
                m.stackedTeam(canvas, Side.HOME, 0f, top)
                m.stackedTeam(canvas, Side.AWAY, 0f, top + m.fullHeight)
                m.infoBox(canvas, m.teamWidth, top)
            }
        }
    }
}
