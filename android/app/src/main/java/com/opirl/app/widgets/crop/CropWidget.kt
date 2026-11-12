package com.opirl.app.widgets.crop

import com.opirl.app.media.gl.BrowserPage
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import com.opirl.app.widgets.Layer
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame

object CropWidget : WidgetDrawer {
    override fun placement(widget: Widget) = SceneWidget(widget.id, size = 20)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val source = context.settings.widgets.firstOrNull { it.id == widget.crop.sourceWidgetId && it.type == WidgetType.BROWSER }
            ?: return null
        val crop = cropRect(widget.crop, source.browserWidth, source.browserHeight) ?: return null
        val share = placement.size / 100f
        return WidgetFrame(
            listOf(
                Layer(
                    source = BrowserPage(source.url, source.browserWidth, source.browserHeight),
                    surface = source.id,
                    cropX = crop.x,
                    cropY = crop.y,
                    cropWidth = crop.width,
                    cropHeight = crop.height,
                ),
            ),
            share,
            share * context.width / context.height * (crop.height * source.browserHeight) / (crop.width * source.browserWidth),
        )
    }
}

class CropRect(val x: Float, val y: Float, val width: Float, val height: Float)

fun cropRect(crop: CropSettings, pageWidth: Int, pageHeight: Int): CropRect? {
    val left = crop.x.coerceIn(0, pageWidth)
    val top = crop.y.coerceIn(0, pageHeight)
    val right = (crop.x + crop.width).coerceIn(0, pageWidth)
    val bottom = (crop.y + crop.height).coerceIn(0, pageHeight)
    if (right <= left || bottom <= top) return null
    return CropRect(
        left.toFloat() / pageWidth,
        top.toFloat() / pageHeight,
        (right - left).toFloat() / pageWidth,
        (bottom - top).toFloat() / pageHeight,
    )
}
