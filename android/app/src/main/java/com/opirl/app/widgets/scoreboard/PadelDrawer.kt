package com.opirl.app.widgets.scoreboard

internal fun padelSketch(settings: ScoreboardSettings, u: Float, brush: Brush): Sketch {
    val padel = settings.padel
    val doubles = padel.type == PadelType.DOUBLES
    val nameFont = Font(25 * u)
    val scoreFont = Font((if (doubles) 45f else 37f) * u)
    val home = padel.names(Side.HOME).map { it.uppercase() }
    val away = padel.names(Side.AWAY).map { it.uppercase() }
    val nameLine = LINE_HEIGHT * 25 * u
    val nameBlock = home.size * nameLine + (home.size - 1) * GAP
    val namesWidth = (home + away).maxOf { brush.width(it, nameFont) }
    val content = maxOf(2 * (nameBlock + 2 * GAP), 2 * (LINE_HEIGHT * scoreFont.size + 2 * GAP))
    val sets = padel.sets.ifEmpty { listOf(Score()) }
    val blockWidth = 3 * u + namesWidth + sets.size * 46 * u + 18 * u
    val blockHeight = 3 * u + content
    val width = maxOf(blockWidth, brush.footerWidth(u))
    val height = blockHeight + footerHeight(u)
    return Sketch(width, height) { canvas ->
        brush.clipRounded(canvas, width, height, 5 * u)
        brush.rect(canvas, 0f, 0f, width, blockHeight, settings.primaryColor)
        val half = content / 2
        listOf(home, away).forEachIndexed { team, names ->
            val top = 3 * u + team * half + (half - nameBlock) / 2
            names.forEachIndexed { index, name ->
                brush.text(canvas, name, nameFont, settings.textColor, 3 * u, top + index * (nameLine + GAP))
            }
        }
        sets.forEachIndexed { index, set ->
            val left = 3 * u + namesWidth + 18 * u + index * 46 * u
            val homeFont = if (isSetWin(set.home, set.away)) scoreFont.copy(weight = 700) else scoreFont
            val awayFont = if (isSetWin(set.away, set.home)) scoreFont.copy(weight = 700) else scoreFont
            brush.textIn(canvas, set.home.toString(), homeFont, settings.textColor, left, 3 * u, 28 * u, half, Align.CENTER)
            brush.textIn(canvas, set.away.toString(), awayFont, settings.textColor, left, 3 * u + half, 28 * u, half, Align.CENTER)
        }
        brush.drawFooter(canvas, blockHeight, width, u, settings.secondaryColor, settings.textColor)
    }
}
