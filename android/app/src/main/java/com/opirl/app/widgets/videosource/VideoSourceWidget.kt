package com.opirl.app.widgets.videosource

import com.opirl.app.media.gl.CameraFeed
import com.opirl.app.settings.Corner
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.widgets.Layer
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame

object VideoSourceWidget : WidgetDrawer {
    override fun placement(widget: Widget) = SceneWidget(widget.id, corner = Corner.BOTTOM_RIGHT, x = 0, y = 0, size = 28)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val settings = widget.videoSource
        val camera = settings.camera ?: return null
        val box = feedBox(settings, placement.size / 100f, context.width, context.height) ?: return null
        return WidgetFrame(
            listOf(
                Layer(
                    source = CameraFeed(camera),
                    surface = "camera:$camera",
                    x = box.layerX,
                    y = box.layerY,
                    width = box.layerWidth,
                    height = box.layerHeight,
                    rotation = settings.rotation.toFloat(),
                    cropX = box.cropX,
                    cropY = box.cropY,
                    cropWidth = box.cropWidth,
                    cropHeight = box.cropHeight,
                    cornerRadius = settings.cornerRadius.coerceIn(0, 100) / 100f,
                    borderWidth = settings.borderWidth.coerceIn(0, 100) / 100f * 0.1f,
                    borderColor = settings.borderColor,
                ),
            ),
            box.width,
            box.height,
        )
    }
}
