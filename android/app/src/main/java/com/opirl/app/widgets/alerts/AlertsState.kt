package com.opirl.app.widgets.alerts

import com.opirl.app.engine.alerts.AlertEvent
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import com.opirl.app.widgets.WidgetRuntime

internal class AlertsState {
    val sequence = AlertSequence<AlertEvent, AlertPlan, PreparedAlert>()

    @Volatile
    var lastCommandMs: Long? = null
}

internal fun alertsState(runtime: WidgetRuntime, widgetId: String): AlertsState = runtime.state(widgetId) { AlertsState() }

internal fun alertsAudio(runtime: WidgetRuntime): AlertsAudio = runtime.state("alerts-audio") { AlertsAudio(runtime.context) }

fun placedAlertWidgets(settings: AppSettings): List<Widget> {
    val found = ArrayList<Widget>()
    val placed = HashSet<String>()
    val scenes = HashSet<String>()
    fun place(sceneId: String) {
        if (!scenes.add(sceneId)) return
        val scene = settings.scenes.firstOrNull { it.id == sceneId } ?: return
        for (placement in scene.widgets) {
            if (!placement.enabled) continue
            val widget = settings.widgets.firstOrNull { it.id == placement.widgetId } ?: continue
            if (!placed.add(widget.id)) continue
            when (widget.type) {
                WidgetType.ALERTS -> found += widget
                WidgetType.SCENE -> widget.sceneId?.let(::place)
                else -> Unit
            }
        }
    }
    settings.selectedScene?.let { place(it.id) }
    return found
}

fun deliverAlert(runtime: WidgetRuntime, event: AlertEvent) {
    val widgets = placedAlertWidgets(runtime.store.settings.value)
    if (widgets.isEmpty()) return
    for (widget in widgets) alertsState(runtime, widget.id).sequence.enqueue(event)
    runtime.invalidate()
}
