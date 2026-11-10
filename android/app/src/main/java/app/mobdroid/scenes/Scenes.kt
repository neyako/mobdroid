package app.mobdroid.scenes

import app.mobdroid.settings.AppSettings
import app.mobdroid.settings.Corner
import app.mobdroid.settings.Scene
import app.mobdroid.settings.SceneWidget
import app.mobdroid.settings.Widget
import app.mobdroid.settings.WidgetType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class OverlayRect(val x: Float, val y: Float, val width: Float, val height: Float)

fun overlayRect(placement: SceneWidget, width: Float, height: Float): OverlayRect {
    val x = placement.x / 100f
    val y = placement.y / 100f
    return OverlayRect(
        if (placement.corner.left) x else 1f - x - width,
        if (placement.corner.top) y else 1f - y - height,
        width,
        height,
    )
}

private val time = DateTimeFormatter.ofPattern("HH:mm")
private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd")

fun expandText(text: String, now: LocalDateTime): String =
    text.replace("{time}", now.format(time)).replace("{date}", now.format(date))

fun defaultScenes(cameras: List<Pair<String, String>>): List<Scene> =
    cameras.map { (key, label) -> Scene(name = label, cameraKey = key) }

fun defaultPlacement(widget: Widget): SceneWidget = when (widget.type) {
    WidgetType.TEXT -> SceneWidget(widget.id)
    WidgetType.IMAGE -> SceneWidget(widget.id, corner = Corner.TOP_RIGHT, size = 15)
    WidgetType.CHAT -> SceneWidget(widget.id, corner = Corner.BOTTOM_LEFT, size = 35)
}

fun Scene.placement(widget: Widget): SceneWidget =
    widgets.firstOrNull { it.widgetId == widget.id } ?: defaultPlacement(widget).copy(enabled = false)

fun AppSettings.withPlacement(sceneId: String, placement: SceneWidget): AppSettings = copy(
    scenes = scenes.map { scene ->
        if (scene.id != sceneId) {
            scene
        } else if (scene.widgets.any { it.widgetId == placement.widgetId }) {
            scene.copy(widgets = scene.widgets.map { if (it.widgetId == placement.widgetId) placement else it })
        } else {
            scene.copy(widgets = scene.widgets + placement)
        }
    },
)

fun AppSettings.withoutWidget(widgetId: String): AppSettings = copy(
    widgets = widgets.filterNot { it.id == widgetId },
    scenes = scenes.map { scene -> scene.copy(widgets = scene.widgets.filterNot { it.widgetId == widgetId }) },
)
