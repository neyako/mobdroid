package app.mobdroid.scenes

import app.mobdroid.settings.AppSettings
import app.mobdroid.settings.Corner
import app.mobdroid.settings.Scene
import app.mobdroid.settings.SceneWidget
import app.mobdroid.settings.Widget
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ScenesTest {
    private fun rect(corner: Corner) = overlayRect(SceneWidget("w", corner = corner, x = 10, y = 20), 0.3f, 0.2f)
        .let { listOf(it.x, it.y, it.width, it.height).map { value -> Math.round(value * 100) } }

    @Test
    fun offsetsAreMeasuredFromTheChosenCorner() {
        assertEquals(listOf(10, 20, 30, 20), rect(Corner.TOP_LEFT))
        assertEquals(listOf(60, 20, 30, 20), rect(Corner.TOP_RIGHT))
        assertEquals(listOf(10, 60, 30, 20), rect(Corner.BOTTOM_LEFT))
        assertEquals(listOf(60, 60, 30, 20), rect(Corner.BOTTOM_RIGHT))
    }

    @Test
    fun timeAndDateAreFilledIn() {
        assertEquals("IRL 07:05 on 2026-11-10", expandText("IRL {time} on {date}", LocalDateTime.of(2026, 11, 10, 7, 5, 59)))
    }

    @Test
    fun aPlacementIsAddedOnceAndThenReplaced() {
        val widget = Widget(id = "w")
        val settings = AppSettings(scenes = listOf(Scene(id = "a"), Scene(id = "b")), widgets = listOf(widget))
        val placed = settings.withPlacement("a", defaultPlacement(widget)).withPlacement("a", SceneWidget("w", x = 40))
        assertEquals(listOf(listOf(SceneWidget("w", x = 40)), emptyList()), placed.scenes.map { it.widgets })
    }

    @Test
    fun deletingAWidgetRemovesItFromEveryScene() {
        val settings = AppSettings(
            scenes = listOf(Scene(id = "a", widgets = listOf(SceneWidget("w"), SceneWidget("kept")))),
            widgets = listOf(Widget(id = "w"), Widget(id = "kept")),
        ).withoutWidget("w")
        assertEquals(listOf("kept"), settings.widgets.map { it.id })
        assertEquals(listOf("kept"), settings.scenes.single().widgets.map { it.widgetId })
    }
}
