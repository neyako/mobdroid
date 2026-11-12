package com.opirl.app.widgets.scoreboard

internal fun genericSketch(settings: ScoreboardSettings, clock: String, u: Float, brush: Brush): Sketch {
    val generic = settings.generic
    val label = Font(25 * u)
    val clockFont = Font(25 * u, digits = true)
    val scoreFont = Font(37 * u)
    val home = generic.home.uppercase()
    val away = generic.away.uppercase()
    val scoreWidth = 37 * 1.33f * u
    val namesWidth = maxOf(brush.width(home, label), brush.width(away, label))
    val titleRow = 10 * u + brush.width(generic.title, label) + 3 * GAP + brush.width(clock, clockFont)
    val teamsRow = 10 * u + namesWidth + 12 * u + GAP + scoreWidth
    val width = maxOf(titleRow, teamsRow, brush.footerWidth(u))
    val titleHeight = LINE_HEIGHT * 25 * u + 10 * u
    val teamsHeight = 2 * (LINE_HEIGHT * 37 * u + 2 * GAP)
    val height = titleHeight + teamsHeight + footerHeight(u)
    return Sketch(width, height) { canvas ->
        brush.clipRounded(canvas, width, height, 5 * u)
        brush.rect(canvas, 0f, 0f, width, titleHeight, settings.secondaryColor)
        brush.rect(canvas, 0f, titleHeight, width, titleHeight + teamsHeight, settings.primaryColor)
        brush.textIn(canvas, generic.title, label, settings.textColor, 5 * u, 0f, width, titleHeight, Align.START)
        val clockWidth = brush.width(clock, clockFont)
        brush.textIn(canvas, clock, clockFont, settings.textColor, width - 5 * u - clockWidth, 0f, clockWidth, titleHeight, Align.START)
        val half = teamsHeight / 2
        val scoreLeft = width - 5 * u - scoreWidth
        listOf(home to generic.score.home, away to generic.score.away).forEachIndexed { index, (name, score) ->
            val top = titleHeight + index * half
            brush.textIn(canvas, name, label, settings.textColor, 5 * u, top, scoreLeft - 5 * u, half, Align.START)
            brush.textIn(canvas, score.toString(), scoreFont, settings.textColor, scoreLeft, top, scoreWidth, half, Align.CENTER)
        }
        brush.drawFooter(canvas, titleHeight + teamsHeight, width, u, settings.secondaryColor, settings.textColor)
    }
}
