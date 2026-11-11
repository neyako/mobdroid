package com.opirl.app.widgets

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.opirl.app.chat.ChatLine
import com.opirl.app.chat.chatNameColor
import com.opirl.app.engine.chat.ChatSegment
import com.opirl.app.media.gl.BrowserPage
import com.opirl.app.media.gl.Overlay
import com.opirl.app.scenes.expandText
import com.opirl.app.scenes.overlayRect
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import com.opirl.app.stream.outputSize
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

private data class Inputs(val placed: List<Placed>, val width: Int, val height: Int, val chatTextSize: Int) {
    val textScale: Float get() = minOf(width, height) / 400f
}

private class Rendered(val key: Any, val bitmap: Bitmap)

class WidgetRenderer(
    private val imageDirectory: File,
    scope: CoroutineScope,
    settings: StateFlow<AppSettings>,
    chat: StateFlow<List<ChatLine>>,
    track: StateFlow<Track?>,
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
        @OptIn(ExperimentalCoroutinesApi::class)
        val withMusic = withChat.flatMapLatest { (input, lines) ->
            if (input.placed.any { it.widget.type == WidgetType.MUSIC }) {
                track.map { Triple(input, lines, it) }
            } else {
                flowOf(Triple(input, lines, null))
            }
        }
        scope.launch(Dispatchers.Default) {
            combine(withMusic, minutes) { state, _ -> state }.conflate().collect { (input, lines, playing) ->
                onOverlays(render(input, lines, playing))
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

    private fun render(input: Inputs, lines: List<ChatLine>, track: Track?): List<Overlay> {
        cache.keys.retainAll(input.placed.map { it.widget.id }.toSet())
        return input.placed.mapNotNull { (widget, placement) ->
            if (widget.type == WidgetType.BROWSER) {
                val page = BrowserPage(widget.url, widget.browserWidth, widget.browserHeight)
                val width = placement.size / 100f
                val rect = overlayRect(placement, width, width * input.width / input.height * page.height / page.width)
                return@mapNotNull Overlay(widget.id, null, rect.x, rect.y, rect.width, rect.height, widget.previewOnly, page)
            }
            val bitmap = runCatching {
                when (widget.type) {
                    WidgetType.TEXT -> {
                        val text = expandText(widget.text, LocalDateTime.now())
                        cached(widget.id, Triple(text, widget.textSize, input.width to input.height)) {
                            text(text, widget.textSize * input.textScale, input.width, input.height)
                        }
                    }
                    WidgetType.IMAGE -> cached(widget.id, widget.image to input.width) {
                        image(widget.image, input.width, input.height)
                    }
                    WidgetType.CHAT -> {
                        val shown = lines.takeLast(CHAT_LINES)
                        val width = input.width * placement.size / 100
                        cached(widget.id, Triple(shown.map { it.key }, width, input.chatTextSize * input.textScale)) {
                            chat(shown, width, input.chatTextSize * input.textScale, input.height)
                        }
                    }
                    WidgetType.BROWSER -> null
                    WidgetType.MUSIC -> track?.takeIf { it.playing || !widget.hideWhenPaused }?.let { shown ->
                        val art = shown.cover.takeIf { widget.cover }
                        val size = widget.textSize.coerceIn(8, 60) * input.textScale
                        cached(widget.id, listOf(shown.title, shown.artist, art, size, input.width)) {
                            music(shown.title, shown.artist, art, size, input.width, input.height)
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

    private fun text(text: String, size: Float, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (text.isBlank()) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
        }
        val lines = text.lines()
        val padding = size * 0.45f
        val lineHeight = paint.fontSpacing
        val width = (lines.maxOf { paint.measureText(it) } + padding * 2).toInt().coerceIn(1, maxWidth)
        val height = (lineHeight * lines.size + padding).toInt().coerceIn(1, maxHeight)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73000000 }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), size * 0.4f, size * 0.4f, background)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, padding, padding / 2 - paint.fontMetrics.ascent + lineHeight * index, paint)
        }
        return bitmap
    }

    private fun music(title: String, artist: String, cover: Bitmap?, size: Float, maxWidth: Int, maxHeight: Int): Bitmap {
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(size * 0.2f, 0f, 0f, Color.BLACK)
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            textSize = size * 0.85f
            setShadowLayer(size * 0.2f, 0f, 0f, Color.BLACK)
        }
        val side = if (cover == null) 0f else size * 3.1f
        val gap = if (cover == null) 0f else size * 0.55f
        val pad = size * 0.25f
        val room = maxWidth * 0.6f - side - gap - pad
        val shownTitle = TextUtils.ellipsize(title, titlePaint, room, TextUtils.TruncateAt.END).toString()
        val shownArtist = TextUtils.ellipsize(artist, artistPaint, room, TextUtils.TruncateAt.END).toString()
        val lines = if (shownArtist.isEmpty()) titlePaint.fontSpacing else titlePaint.fontSpacing + artistPaint.fontSpacing
        val height = maxOf(side, lines) + pad * 2
        val width = side + gap + maxOf(titlePaint.measureText(shownTitle), artistPaint.measureText(shownArtist)) + pad * 2
        val bitmap = Bitmap.createBitmap(width.toInt().coerceIn(1, maxWidth), height.toInt().coerceIn(1, maxHeight), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (cover != null) {
            val rect = RectF(pad, (height - side) / 2, pad + side, (height + side) / 2)
            val crop = minOf(cover.width, cover.height)
            val source = Rect((cover.width - crop) / 2, (cover.height - crop) / 2, (cover.width + crop) / 2, (cover.height + crop) / 2)
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(rect, side * 0.14f, side * 0.14f, Path.Direction.CW) })
            canvas.drawBitmap(cover, source, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            canvas.restore()
        }
        val top = (height - lines) / 2
        canvas.drawText(shownTitle, pad + side + gap, top - titlePaint.fontMetrics.ascent, titlePaint)
        if (shownArtist.isNotEmpty()) {
            canvas.drawText(shownArtist, pad + side + gap, top + titlePaint.fontSpacing - artistPaint.fontMetrics.ascent, artistPaint)
        }
        return bitmap
    }

    private fun image(name: String?, maxWidth: Int, maxHeight: Int): Bitmap? {
        val file = File(imageDirectory, name ?: return null)
        if (!file.isFile) return null
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = maxOf((info.size.width + maxWidth - 1) / maxWidth, (info.size.height + maxHeight - 1) / maxHeight)
            decoder.setTargetSampleSize(Integer.highestOneBit(scale) * if (Integer.bitCount(scale) > 1) 2 else 1)
        }
    }

    private fun chat(lines: List<ChatLine>, width: Int, size: Float, maxHeight: Int): Bitmap? {
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
        val height = layout.height.coerceIn(1, maxHeight)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(0f, (height - layout.height).toFloat())
        layout.draw(canvas)
        return bitmap
    }

    private companion object {
        const val CHAT_LINES = 6
    }
}
