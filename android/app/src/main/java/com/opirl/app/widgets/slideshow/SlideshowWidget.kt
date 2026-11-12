package com.opirl.app.widgets.slideshow

import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import com.opirl.app.widgets.Need
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame
import com.opirl.app.widgets.drawer

val SLIDE_TYPES = setOf(WidgetType.TEXT, WidgetType.IMAGE)

private class SlideshowState {
    var index = 0
    var end = 0L
}

class SlideClock(val index: Int, val end: Long)

fun nextSlide(index: Int, end: Long, now: Long, seconds: List<Int>): SlideClock? {
    if (seconds.isEmpty()) return null
    if (end == 0L || index >= seconds.size) return SlideClock(0, now + seconds[0] * 1000L)
    if (now < end) return SlideClock(index, end)
    val next = (index + 1) % seconds.size
    return SlideClock(next, now + seconds[next] * 1000L)
}

object SlideshowWidget : WidgetDrawer {
    override fun placement(widget: Widget) = SceneWidget(widget.id, size = 30)

    private fun slides(widget: Widget, settings: AppSettings) = widget.slideshow.slides.mapNotNull { slide ->
        settings.widgets.firstOrNull { it.id == slide.widgetId && it.type in SLIDE_TYPES }?.let { slide to it }
    }

    override fun needs(widget: Widget, settings: AppSettings): Set<Need> =
        slides(widget, settings).flatMapTo(HashSet()) { (_, shown) -> shown.type.drawer.needs(shown, settings) }

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val slides = slides(widget, context.settings)
        val state = context.runtime.state(widget.id) { SlideshowState() }
        val clock = nextSlide(state.index, state.end, context.now, slides.map { it.first.seconds.coerceAtLeast(1) }) ?: return null
        state.index = clock.index
        state.end = clock.end
        context.redrawAt(clock.end)
        return context.draw(slides[clock.index].second, placement)
    }
}
