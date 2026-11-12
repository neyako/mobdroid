package com.opirl.app.widgets.alerts

import android.util.Log
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.widgets.Layer
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame
import com.opirl.app.widgets.WidgetRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "Alerts"
private const val RELEASE_AFTER_MS = 1000L

object AlertsWidget : WidgetDrawer {
    override val sized get() = false

    override fun placement(widget: Widget) = SceneWidget(widget.id, x = 20, y = 5)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val runtime = context.runtime
        val sequence = alertsState(runtime, widget.id).sequence
        val now = context.now
        val shown = sequence.active(now)
        if (shown == null) {
            val plan = sequence.begin(now) { resolveAlert(widget.alerts, it) }
            if (plan != null) {
                prepare(runtime, widget, plan, context.width, context.height)
            } else {
                sequence.wakeAt()?.let { context.redrawAt(it) }
            }
            return null
        }
        val prepared = shown.content
        val elapsed = now - shown.startedAt
        val index = prepared.timeline.frameAt(elapsed) ?: return null
        context.redrawAt(prepared.timeline.nextDrawAt(shown.startedAt, now))
        val unit = alertUnit(context.width, context.height)
        val message = context.cached("${widget.id}-message", listOf(prepared.plan.user, prepared.plan.message, prepared.plan.alert, unit)) {
            renderAlertMessage(prepared.plan, unit, context.width, context.height)
        }
        val alertWidth = prepared.width.toFloat()
        val alertHeight = prepared.height.toFloat()
        val padding = messagePadding(unit)
        val boxHeight = alertHeight + (message?.let { it.height - 2 * padding } ?: 0)
        val layers = ArrayList<Layer>(2)
        layers += Layer(bitmap = prepared.frames[index], width = 1f, height = alertHeight / boxHeight)
        if (message != null) {
            layers += Layer(
                bitmap = message,
                x = ((alertWidth - (message.width - 2 * padding)) / 2 - padding) / alertWidth,
                y = (alertHeight - padding) / boxHeight,
                width = message.width / alertWidth,
                height = message.height / boxHeight,
            )
        }
        return WidgetFrame(layers, alertWidth / context.width, boxHeight / context.height)
    }

    private fun prepare(runtime: WidgetRuntime, widget: Widget, plan: AlertPlan, width: Int, height: Int) {
        val sequence = alertsState(runtime, widget.id).sequence
        runtime.scope.launch(Dispatchers.IO) {
            val prepared = runCatching { prepareAlert(runtime, widget.alerts, plan, width, height) }
                .onFailure { Log.w(TAG, "Alert failed", it) }
                .getOrElse { PreparedAlert(plan, emptyList(), AlertTimeline(IntArray(0), 1), 0, 0, null) }
            sequence.start(prepared, System.currentTimeMillis(), prepared.timeline.totalMs)
            runCatching {
                val audio = alertsAudio(runtime)
                prepared.sound?.let(audio::play)
                if (plan.alert.speech) audio.speak(spokenAlertText(plan), plan.alert.speechDelayMs.toLong())
            }.onFailure { Log.w(TAG, "Alert audio failed", it) }
            runtime.invalidate()
            delay(prepared.timeline.totalMs + RELEASE_AFTER_MS)
            sequence.active(System.currentTimeMillis())
        }
    }
}
