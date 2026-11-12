package com.opirl.app.widgets.scoreboard

import android.graphics.RectF

private const val GREEN = 0xFF34C759.toInt()
private const val RED = 0xFFFF3B30.toInt()
private const val GRID = 0x668E8E93

internal fun golfSketch(settings: ScoreboardSettings, golf: GolfScoreboard, u: Float, brush: Brush): Sketch {
    val holes = golf.numberOfHoles
    val hole = golf.hole
    val titleFont = Font(20 * u, weight = 700)
    val holeFont = Font(18 * u, weight = 700, digits = true)
    val holeText = "HOLE ${hole + 1}  PAR ${golf.parAt(hole)}"
    val nameFont = Font(25 * u)
    val rowHeight = 35 * u
    val totals = golf.players.map { it.totalRelativeToPar(golf.pars, holes) }
    val scoreFont = Font(minOf(37 * u, rowHeight / LINE_HEIGHT), weight = 700, digits = true)
    val colorWidth = if (golf.playerColors) 24 * u else 0f
    val namesWidth = golf.players.maxOfOrNull { 8 * u + colorWidth + brush.width(it.name.uppercase(), nameFont) } ?: 0f
    val scoresWidth = totals.maxOfOrNull { brush.width(formatRelative(it), scoreFont) + 8 * u } ?: 0f
    val thruWidth = 70 * u
    val titleHeight = LINE_HEIGHT * 20 * u + 8 * u
    val titleRow = 16 * u + brush.width(golf.title, titleFont) + 16 * u + GAP + brush.width(holeText, holeFont)
    val bodyRow = namesWidth + 4 * GAP + thruWidth + scoresWidth
    val width = maxOf(titleRow, bodyRow, brush.footerWidth(u))
    val bodyHeight = golf.players.size * rowHeight
    val height = titleHeight + bodyHeight + footerHeight(u)
    return Sketch(width, height) { canvas ->
        brush.clipRounded(canvas, width, height, 5 * u)
        brush.rect(canvas, 0f, 0f, width, titleHeight, settings.secondaryColor)
        brush.rect(canvas, 0f, titleHeight, width, titleHeight + bodyHeight, settings.primaryColor)
        val holeWidth = brush.width(holeText, holeFont)
        brush.textIn(canvas, golf.title, titleFont, settings.textColor, 8 * u, 0f, width - 24 * u - holeWidth, titleHeight, Align.START)
        brush.textIn(canvas, holeText, holeFont, settings.textColor, width - 8 * u - holeWidth, 0f, holeWidth, titleHeight, Align.START)
        val thruLeft = width - scoresWidth - GAP - thruWidth
        golf.players.forEachIndexed { index, player ->
            val top = titleHeight + index * rowHeight
            var nameLeft = 8 * u
            if (golf.playerColors) {
                val box = RectF(nameLeft, top + (rowHeight - 18 * u) / 2, nameLeft + 18 * u, top + (rowHeight + 18 * u) / 2)
                brush.roundRect(canvas, box, 3 * u, player.color)
                nameLeft += 24 * u
            }
            val nameRoom = thruLeft - nameLeft
            brush.textIn(canvas, player.name.uppercase(), nameFont, settings.textColor, nameLeft, top, nameRoom, rowHeight, Align.START)
            val played = player.holesPlayed(holes)
            val thru = if (played == 0) "" else if (played < holes) "THRU $played" else "F"
            brush.textIn(
                canvas,
                thru,
                Font(15 * u, digits = true),
                withAlpha(settings.textColor, 0.6f),
                thruLeft,
                top,
                thruWidth - 6 * u,
                rowHeight,
                Align.CENTER,
                0.5f,
            )
            val total = totals[index]
            val color = if (total < 0) GREEN else if (total > 0) RED else settings.textColor
            val scoreRoom = scoresWidth - 8 * u
            brush.textIn(canvas, formatRelative(total), scoreFont, color, width - scoresWidth, top, scoreRoom, rowHeight, Align.END, 0.5f)
        }
        brush.drawFooter(canvas, titleHeight + bodyHeight, width, u, settings.secondaryColor, settings.textColor)
    }
}

internal fun golfCardSketch(settings: ScoreboardSettings, golf: GolfScoreboard, u: Float, brush: Brush): Sketch {
    val holes = golf.numberOfHoles
    val nameWidth = 150 * u
    val cellWidth = 28 * u
    val totalWidth = 100 * u
    val width = nameWidth + holes * cellWidth + totalWidth
    val headerHeight = 20 * u
    val cellHeight = 22 * u
    val rows = (if (settings.showPars) 1 else 0) + golf.players.size
    val height = headerHeight + rows * cellHeight
    val headerFont = Font(16 * u)
    val cellFont = Font(17 * u)
    val totalFont = Font(17 * u, weight = 700)
    return Sketch(width, height) { canvas ->
        brush.clipRounded(canvas, width, height, 5 * u)
        fun cell(left: Float, top: Float, w: Float, h: Float, background: Int?, text: String, font: Font, start: Boolean, minScale: Float) {
            if (background != null) brush.rect(canvas, left, top, left + w, top + h, background)
            if (start) {
                brush.textIn(canvas, text, font, settings.textColor, left + 8 * u, top, w - 16 * u, h, Align.START, minScale)
            } else {
                brush.textIn(canvas, text, font, settings.textColor, left, top, w, h, Align.CENTER, minScale)
            }
            brush.outline(canvas, left, top, left + w, top + h, GRID, 0.5f)
        }
        brush.rect(canvas, 0f, 0f, width, headerHeight, settings.secondaryColor)
        cell(0f, 0f, nameWidth, headerHeight, null, "", headerFont, false, 0.5f)
        for (hole in 0 until holes) {
            cell(nameWidth + hole * cellWidth, 0f, cellWidth, headerHeight, null, "${hole + 1}", headerFont, false, 0.5f)
        }
        cell(nameWidth + holes * cellWidth, 0f, totalWidth, headerHeight, null, "", headerFont, false, 0.5f)
        var top = headerHeight
        if (settings.showPars) {
            brush.rect(canvas, 0f, top, width, top + cellHeight, settings.secondaryColor)
            cell(0f, top, nameWidth, cellHeight, null, "PAR", cellFont, true, 1f)
            for (hole in 0 until holes) {
                cell(nameWidth + hole * cellWidth, top, cellWidth, cellHeight, null, "${golf.parAt(hole)}", cellFont, false, 1f)
            }
            cell(nameWidth + holes * cellWidth, top, totalWidth, cellHeight, null, "${golf.pars.take(holes).sum()}", totalFont, false, 1f)
            top += cellHeight
        }
        for (player in golf.players) {
            brush.rect(canvas, 0f, top, width, top + cellHeight, settings.primaryColor)
            cell(0f, top, nameWidth, cellHeight, null, player.name.uppercase(), cellFont, true, 1f)
            for (hole in 0 until holes) {
                val strokes = player.scores.getOrElse(hole) { GOLF_NO_SCORE }
                cell(
                    nameWidth + hole * cellWidth,
                    top,
                    cellWidth,
                    cellHeight,
                    scoreCellColor(strokes, golf.parAt(hole)),
                    if (strokes >= 0) "$strokes" else "",
                    cellFont,
                    false,
                    1f,
                )
            }
            val total = "${player.totalStrokes(holes)} (${formatRelative(player.totalRelativeToPar(golf.pars, holes))})"
            cell(nameWidth + holes * cellWidth, top, totalWidth, cellHeight, null, total, totalFont, false, 1f)
            top += cellHeight
        }
    }
}
