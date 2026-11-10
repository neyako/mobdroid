package app.mobdroid.widgets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import app.mobdroid.chat.ChatLine
import app.mobdroid.chat.chatNameColor
import app.mobdroid.engine.chat.ChatSegment
import app.mobdroid.media.gl.Overlay
import app.mobdroid.scenes.expandText
import app.mobdroid.scenes.overlayRect
import app.mobdroid.settings.AppSettings
import app.mobdroid.settings.SceneWidget
import app.mobdroid.settings.Widget
import app.mobdroid.settings.WidgetType
import app.mobdroid.stream.outputSize
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class Placed(val widget: Widget, val placement: SceneWidget)

private data class Inputs(val placed: List<Placed>, val width: Int, val height: Int, val chatTextSize: Int)

private class Rendered(val key: Any, val bitmap: Bitmap)

class WidgetRenderer(
    private val imageDirectory: File,
    scope: CoroutineScope,
    settings: StateFlow<AppSettings>,
    chat: StateFlow<List<ChatLine>>,
    private val onOverlays: (List<Overlay>) -> Unit,
) {
    private val cache = HashMap<String, Rendered>()

    init {
        val minutes = flow {
            while (true) {
                emit(Unit)
                delay(60_050 - System.currentTimeMillis() % 60_000)
            }
        }
        val inputs = settings.map(::inputs).distinctUntilChanged()
        @OptIn(ExperimentalCoroutinesApi::class)
        val withChat = inputs.flatMapLatest { input ->
            if (input.placed.any { it.widget.type == WidgetType.CHAT }) chat.map { input to it } else flowOf(input to emptyList())
        }
        scope.launch(Dispatchers.Default) {
            combine(withChat, minutes) { state, _ -> state }.conflate().collect { (input, lines) ->
                onOverlays(render(input, lines))
            }
        }
    }

    private fun inputs(settings: AppSettings): Inputs {
        val (width, height) = outputSize(settings.selectedProfile)
        val placed = settings.selectedScene?.widgets.orEmpty().filter { it.enabled }.mapNotNull { placement ->
            settings.widgets.firstOrNull { it.id == placement.widgetId }?.let { Placed(it, placement) }
        }
        return Inputs(placed, width, height, settings.chatTextSize.sp)
    }

    private fun render(input: Inputs, lines: List<ChatLine>): List<Overlay> {
        cache.keys.retainAll(input.placed.map { it.widget.id }.toSet())
        return input.placed.mapNotNull { (widget, placement) ->
            val bitmap = runCatching {
                when (widget.type) {
                    WidgetType.TEXT -> {
                        val text = expandText(widget.text, LocalDateTime.now())
                        cached(widget.id, Triple(text, widget.textSize, input.height)) {
                            text(text, widget.textSize * input.height / 400f)
                        }
                    }
                    WidgetType.IMAGE -> cached(widget.id, widget.image to input.width) {
                        image(widget.image, input.width)
                    }
                    WidgetType.CHAT -> {
                        val shown = lines.takeLast(CHAT_LINES)
                        val width = input.width * placement.size / 100
                        cached(widget.id, Triple(shown.map { it.key }, width, input.chatTextSize * input.height)) {
                            chat(shown, width, input.chatTextSize * input.height / 400f)
                        }
                    }
                }
            }.getOrNull() ?: return@mapNotNull null
            val width = if (widget.type == WidgetType.IMAGE) placement.size / 100f else bitmap.width.toFloat() / input.width
            val height = width * input.width / input.height * bitmap.height / bitmap.width
            val rect = overlayRect(placement, width, height)
            Overlay(widget.id, bitmap, rect.x, rect.y, rect.width, rect.height, widget.previewOnly)
        }
    }

    private fun cached(id: String, key: Any, create: () -> Bitmap?): Bitmap? {
        cache[id]?.takeIf { it.key == key }?.let { return it.bitmap }
        val bitmap = create()
        if (bitmap == null) cache.remove(id) else cache[id] = Rendered(key, bitmap)
        return bitmap
    }

    private fun text(text: String, size: Float): Bitmap? {
        if (text.isBlank()) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
        }
        val lines = text.lines()
        val padding = size * 0.45f
        val lineHeight = paint.fontSpacing
        val width = (lines.maxOf { paint.measureText(it) } + padding * 2).toInt().coerceAtLeast(1)
        val height = (lineHeight * lines.size + padding).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73000000 }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), size * 0.4f, size * 0.4f, background)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, padding, padding / 2 - paint.fontMetrics.ascent + lineHeight * index, paint)
        }
        return bitmap
    }

    private fun image(name: String?, maxWidth: Int): Bitmap? {
        val file = File(imageDirectory, name ?: return null)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((bounds.outWidth / maxWidth).coerceAtLeast(1))
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun chat(lines: List<ChatLine>, width: Int, size: Float): Bitmap? {
        if (lines.isEmpty() || width <= 0) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            setShadowLayer(size * 0.2f, 0f, 0f, Color.BLACK)
        }
        val text = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) text.append('\n')
            val start = text.length
            text.append(line.message.user)
            text.setSpan(ForegroundColorSpan(chatNameColor(line.message)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append("  ")
            for (segment in line.message.segments) {
                text.append(
                    when (segment) {
                        is ChatSegment.Text -> segment.text
                        is ChatSegment.Emote -> segment.name
                    },
                )
            }
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()
        val bitmap = Bitmap.createBitmap(width, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bitmap))
        return bitmap
    }

    private companion object {
        const val CHAT_LINES = 6
    }
}
