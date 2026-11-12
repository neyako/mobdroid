package com.opirl.app.widgets.map

import android.location.Location
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.Corner
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.widgets.Need
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame

const val MAP_TILES = "map-tiles"

private class MapState {
    val fixes = FixQueue()
    var shown: MapPicture? = null
}

private data class MapPicture(
    val view: MapView,
    val layout: MapLayout,
    val cornerRadius: Int,
    val borderColor: Int,
    val shortSide: Int,
)

private fun fixOf(location: Location) = MapFix(
    location.latitude,
    location.longitude,
    if (location.hasSpeed()) location.speed else -1f,
    if (location.hasBearing()) location.bearing else -1f,
    location.time,
)

object MapWidget : WidgetDrawer {
    override fun placement(widget: Widget) = SceneWidget(widget.id, corner = Corner.TOP_LEFT, x = 0, y = 0, size = 23)

    override fun needs(widget: Widget, settings: AppSettings): Set<Need> = setOf(Need.Location)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val map = widget.map
        val runtime = context.runtime
        val state = runtime.state(widget.id) { MapState() }
        runtime.location.value?.let { state.fixes.add(fixOf(it)) }
        val delay = (map.delay.coerceIn(0.0, 10.0) * 1000).toLong()
        val fix = state.fixes.current(context.now, delay) ?: return null
        state.fixes.nextChange(context.now, delay)?.let(context::redrawAt)
        val shortSide = minOf(context.width, context.height)
        val layout = mapLayout(shortSide, placement.size, map.borderWidth.coerceIn(0, 100))
        val view = mapView(fix, map.northUp, map.size.coerceIn(500, 10_000), layout.side)
        val tiles = runtime.state(MAP_TILES) { osmTileStore(runtime) }
        val needed = view.tiles(layout.side)
        tiles.request(needed)
        if (needed.any { tiles.get(it) == null }) {
            tiles.retryIn()?.let(context::redrawIn)
            return context.cached(widget.id, state.shown) { null }?.let(context::natural)
        }
        val picture = MapPicture(view, layout, map.cornerRadius.coerceIn(0, 100), map.borderColor, shortSide)
        val bitmap = context.cached(widget.id, picture) {
            drawMap(view, layout, picture.cornerRadius, picture.borderColor, 30f * shortSide / 1080, 5f * context.textScale, tiles::get)
        } ?: return null
        state.shown = picture
        return context.natural(bitmap)
    }
}
