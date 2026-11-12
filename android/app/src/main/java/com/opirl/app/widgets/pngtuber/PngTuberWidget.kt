package com.opirl.app.widgets.pngtuber

import android.graphics.Bitmap
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.Corner
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.widgets.Layer
import com.opirl.app.widgets.Need
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame
import com.opirl.app.widgets.WidgetRuntime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class PngTuberState {
    @Volatile
    var model: PngTuberModel? = null
        private set

    @Volatile
    var failed: String? = null
        private set

    @Volatile
    var loading = false
        private set

    var pose = PngTuberPose(mouthOpen = false, eyesOpen = true)

    @Synchronized
    fun ensure(runtime: WidgetRuntime, settings: PngTuberSettings, size: Int, width: Int, height: Int): PngTuberModel? {
        val name = settings.model ?: return null
        val current = model?.takeIf { it.name == name }
        val level = current?.let { decodeLevel(neededScale(it.parts, it.variants, size, width, height)) }
        if (!loading && failed != name && (current == null || current.costume != settings.costume || current.level != level)) {
            loading = true
            runtime.scope.launch(Dispatchers.IO) {
                val loaded = runCatching {
                    loadPngTuber(File(runtime.files, name), name, settings.costume, size, width, height)
                }.getOrNull()
                synchronized(this@PngTuberState) {
                    model = loaded
                    failed = if (loaded == null) name else null
                    loading = false
                }
                runtime.invalidate()
            }
        }
        return current
    }
}

object PngTuberWidget : WidgetDrawer {
    override fun placement(widget: Widget) = SceneWidget(widget.id, corner = Corner.BOTTOM_RIGHT, x = 0, y = 0, size = 28)

    override fun needs(widget: Widget, settings: AppSettings): Set<Need> =
        if (widget.pngTuber.model == null) emptySet() else setOf(Need.FaceOf(widget.pngTuber.cameraKey))

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val settings = widget.pngTuber
        val runtime = context.runtime
        val state = runtime.state(widget.id) { PngTuberState() }
        val size = placement.size.coerceIn(1, 100)
        val model = state.ensure(runtime, settings, size, context.width, context.height) ?: return null
        runtime.faces.value[settings.cameraKey]?.let { face ->
            state.pose = PngTuberPose(
                isMouthOpen(face.mouthOpen, settings.mouthSensitivity / 100f),
                isEyeOpen(face.leftEyeOpen, settings.eyeSensitivity / 100f),
            )
        }
        val current = variantOf(model.variants, state.pose)
        var shown: Bitmap? = null
        for (index in model.shown.indices) {
            val variant = model.shown[index] ?: continue
            val bitmap = if (settings.mirror) {
                context.cached("${widget.id}:$index", listOf(model, index)) { mirrored(variant.bitmap) }
            } else {
                variant.bitmap
            }
            if (index == current) shown = bitmap
        }
        val extent = model.shown[current]?.extent ?: return null
        val fit = fitScale(size, extent, context.width, context.height)
        return WidgetFrame(listOf(Layer(shown ?: return null)), extent.width * fit / context.width, extent.height * fit / context.height)
    }
}
