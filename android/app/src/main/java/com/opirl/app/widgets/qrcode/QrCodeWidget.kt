package com.opirl.app.widgets.qrcode

import android.graphics.Bitmap
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame

object QrCodeWidget : WidgetDrawer {
    override fun placement(widget: Widget) = SceneWidget(widget.id, x = 0, y = 0, size = 23)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val message = widget.qrCode.message.trim()
        val short = minOf(context.width, context.height)
        val side = short * placement.size.coerceIn(1, 100) / 100
        val bitmap = context.cached(widget.id, listOf(message, side, short)) { drawCode(message, side, short) } ?: return null
        return context.natural(bitmap)
    }

    private fun drawCode(message: String, side: Int, limit: Int): Bitmap? {
        val matrix = qrMatrix(message) ?: return null
        val module = moduleSize(side, matrix.size, limit)
        val size = matrix.size * module
        return Bitmap.createBitmap(qrPixels(matrix, module), size, size, Bitmap.Config.ARGB_8888)
    }
}
