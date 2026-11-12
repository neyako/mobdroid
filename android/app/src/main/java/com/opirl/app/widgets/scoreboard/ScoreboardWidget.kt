package com.opirl.app.widgets.scoreboard

import android.graphics.BitmapFactory
import com.opirl.app.R
import com.opirl.app.settings.Corner
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame

fun golfHolder(widget: Widget, widgets: List<Widget>): Widget {
    if (widget.scoreboard.sport != ScoreboardSport.GOLF_FULL_SCORECARD) return widget
    return widgets.firstOrNull { it.type == WidgetType.SCOREBOARD && it.scoreboard.sport == ScoreboardSport.GOLF } ?: widget
}

private fun ScoreboardSettings.visibleClock(): ScoreboardClock? = when {
    sport == ScoreboardSport.GENERIC -> generic.clock
    sport.isModular && modular.showInfoBox && modular.showClock -> modular.clock
    else -> null
}

object ScoreboardWidget : WidgetDrawer {
    override fun placement(widget: Widget): SceneWidget {
        val card = widget.scoreboard.sport == ScoreboardSport.GOLF_FULL_SCORECARD
        return SceneWidget(widget.id, corner = if (card) Corner.BOTTOM_RIGHT else Corner.TOP_LEFT, x = 1, y = 1, size = 19)
    }

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val settings = widget.scoreboard
        val now = context.now
        val clock = settings.visibleClock()
        clock?.nextChangeAt(now)?.let { context.redrawAt(it) }
        val golf = golfHolder(widget, context.settings.widgets).scoreboard.golf
        val scale = context.textScale * placement.size / 50f
        val clockText = clock?.format(now)
        val ball = if (settings.sport.isModular && (settings.modular.home.possession || settings.modular.away.possession)) {
            context.cached("${widget.id}-ball", R.drawable.scoreboard_possession) {
                BitmapFactory.decodeResource(context.runtime.context.resources, R.drawable.scoreboard_possession)
            }
        } else {
            null
        }
        val bitmap = context.cached(widget.id, listOf(settings, golf, clockText, scale, context.width, context.height, ball)) {
            val brush = Brush()
            val sketch = when (settings.sport) {
                ScoreboardSport.GENERIC -> genericSketch(settings, clockText.orEmpty(), scale, brush)
                ScoreboardSport.PADEL -> padelSketch(settings, scale, brush)
                ScoreboardSport.GOLF -> golfSketch(settings, golf, scale, brush)
                ScoreboardSport.GOLF_FULL_SCORECARD -> golfCardSketch(settings, golf, scale, brush)
                else -> modularSketch(settings, now, scale, brush, ball)
            }
            sketch.render(context.width, context.height)
        } ?: return null
        return context.natural(bitmap)
    }
}
