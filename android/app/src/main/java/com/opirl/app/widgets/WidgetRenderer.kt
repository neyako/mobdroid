package com.opirl.app.widgets

import com.opirl.app.media.gl.Overlay
import com.opirl.app.scenes.overlayRect
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.Scene
import com.opirl.app.settings.WidgetType
import com.opirl.app.stream.outputSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class WidgetRenderer(
    private val runtime: WidgetRuntime,
    private val onOverlays: (List<Overlay>) -> Unit,
    private val onNeeds: (Set<Need>) -> Unit,
) {
    private val cache = BitmapCache()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var overlays = emptyList<Overlay>()

    @Volatile
    private var needs = emptySet<Need>()

    @Volatile
    private var types = emptySet<WidgetType>()

    @Volatile
    var active = false
        set(value) {
            field = value
            wake.trySend(Unit)
        }

    init {
        val scope = runtime.scope
        scope.launch {
            runtime.store.settings
                .map { listOf(it.widgets, it.scenes, it.selectedSceneId, outputSize(it.selectedProfile), it.chatTextSize) }
                .distinctUntilChanged()
                .collect { wake.trySend(Unit) }
        }
        scope.launch { runtime.version.collect { wake.trySend(Unit) } }
        scope.launch { runtime.chat.collect { if (WidgetType.CHAT in types) wake.trySend(Unit) } }
        scope.launch { runtime.track.collect { if (WidgetType.MUSIC in types) wake.trySend(Unit) } }
        scope.launch { runtime.location.collect { if (Need.Location in needs) wake.trySend(Unit) } }
        scope.launch { runtime.faces.collect { if (needs.any { it is Need.FaceOf }) wake.trySend(Unit) } }
        scope.launch(Dispatchers.Default) {
            while (true) {
                val deadline = if (active) render() else Long.MAX_VALUE
                if (deadline == Long.MAX_VALUE) {
                    wake.receive()
                } else {
                    withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(15)) { wake.receive() }
                }
            }
        }
    }

    private fun render(): Long {
        val settings = runtime.store.settings.value
        val (width, height) = outputSize(settings.selectedProfile)
        val context = WidgetContext(runtime, settings, width, height, System.currentTimeMillis(), cache)
        val next = ArrayList<Overlay>()
        val placed = HashSet<String>()
        val scenes = HashSet<String>()
        val nextNeeds = HashSet<Need>()
        val nextTypes = HashSet<WidgetType>()
        settings.selectedScene?.let { place(it, settings, context, next, placed, scenes, nextNeeds, nextTypes) }
        cache.sweep()
        types = nextTypes
        if (nextNeeds != needs) {
            needs = nextNeeds
            onNeeds(nextNeeds)
        }
        if (next.size != overlays.size || next.indices.any { !next[it].same(overlays[it]) }) {
            overlays = next
            onOverlays(next)
        }
        return context.deadline
    }

    private fun place(
        scene: Scene,
        settings: AppSettings,
        context: WidgetContext,
        overlays: MutableList<Overlay>,
        placed: MutableSet<String>,
        scenes: MutableSet<String>,
        needs: MutableSet<Need>,
        types: MutableSet<WidgetType>,
    ) {
        if (!scenes.add(scene.id)) return
        for (placement in scene.widgets) {
            if (!placement.enabled) continue
            val widget = settings.widgets.firstOrNull { it.id == placement.widgetId } ?: continue
            if (!placed.add(widget.id)) continue
            if (widget.type == WidgetType.SCENE) {
                settings.scenes.firstOrNull { it.id == widget.sceneId }?.let {
                    place(it, settings, context, overlays, placed, scenes, needs, types)
                }
                continue
            }
            types += widget.type
            needs += widget.type.drawer.needs(widget, settings)
            val frame = context.draw(widget, placement) ?: continue
            val rect = overlayRect(placement, frame.width, frame.height)
            frame.layers.forEachIndexed { index, layer ->
                val id = if (index == 0) widget.id else "${widget.id}#$index"
                overlays += Overlay(
                    id = id,
                    bitmap = layer.bitmap,
                    x = rect.x + layer.x * rect.width,
                    y = rect.y + layer.y * rect.height,
                    width = layer.width * rect.width,
                    height = layer.height * rect.height,
                    previewOnly = widget.previewOnly,
                    source = layer.source,
                    surface = layer.surface ?: id,
                    rotation = layer.rotation,
                    alpha = layer.alpha,
                    cropX = layer.cropX,
                    cropY = layer.cropY,
                    cropWidth = layer.cropWidth,
                    cropHeight = layer.cropHeight,
                    cornerRadius = layer.cornerRadius,
                    borderWidth = layer.borderWidth,
                    borderColor = layer.borderColor,
                )
            }
        }
    }
}
