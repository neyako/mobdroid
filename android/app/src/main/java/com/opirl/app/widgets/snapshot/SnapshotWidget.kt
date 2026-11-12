package com.opirl.app.widgets.snapshot

import android.graphics.Bitmap
import com.opirl.app.settings.Corner
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame
import com.opirl.app.widgets.WidgetRuntime

private class SnapshotState {
    val waiting = ArrayDeque<Bitmap>()
    var shown: Bitmap? = null
    var hideAt = 0L
}

object SnapshotWidget : WidgetDrawer {
    private const val WAITING = 5

    override fun placement(widget: Widget) = SceneWidget(widget.id, corner = Corner.TOP_RIGHT, x = 0, y = 0, size = 40)

    fun add(runtime: WidgetRuntime, snapshot: Bitmap) {
        val widgets = runtime.store.settings.value.widgets.filter { it.type == WidgetType.SNAPSHOT }
        if (widgets.isEmpty()) return
        val small = Bitmap.createScaledBitmap(snapshot, 960, 960 * snapshot.height / snapshot.width, true)
        for (widget in widgets) {
            val state = runtime.state(widget.id) { SnapshotState() }
            synchronized(state) {
                if (state.waiting.size == WAITING) state.waiting.removeFirst()
                state.waiting.addLast(small)
            }
        }
        runtime.invalidate()
    }

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val state = context.runtime.state(widget.id) { SnapshotState() }
        val shown = synchronized(state) {
            if (state.shown == null || context.now >= state.hideAt) {
                state.shown = state.waiting.removeFirstOrNull()
                state.hideAt = context.now + widget.snapshot.showtime.coerceAtLeast(1) * 1000L
            }
            state.shown
        } ?: return null
        context.redrawAt(state.hideAt)
        return context.sized(shown, placement)
    }
}
