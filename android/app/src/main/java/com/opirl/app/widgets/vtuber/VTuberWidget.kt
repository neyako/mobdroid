package com.opirl.app.widgets.vtuber

import com.opirl.app.media.gl.VTuberSource
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.Corner
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.widgets.Layer
import com.opirl.app.widgets.Need
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame

object VTuberWidget : WidgetDrawer {
    override fun placement(widget: Widget) = SceneWidget(widget.id, corner = Corner.BOTTOM_RIGHT, x = 0, y = 0, size = 28)

    override fun needs(widget: Widget, settings: AppSettings): Set<Need> =
        if (widget.vTuber.model == null) emptySet() else setOf(Need.FaceOf(widget.vTuber.camera))

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val settings = widget.vTuber
        val file = settings.model ?: return null
        val side = (minOf(context.width, context.height) * placement.size / 100 / 2 * 2).coerceIn(64, 2048)
        val source = VTuberSource(
            file = file,
            side = side,
            positionY = settings.cameraPositionY / 100f,
            fieldOfView = settings.cameraFieldOfView,
            armsAngle = settings.armsAngle,
            mirror = settings.mirror,
            camera = settings.camera,
            mouth = settings.mouthSensitivity / 100f,
            eyes = settings.eyesSensitivity / 100f,
        )
        return WidgetFrame(listOf(Layer(source = source, surface = widget.id)), side.toFloat() / context.width, side.toFloat() / context.height)
    }
}
