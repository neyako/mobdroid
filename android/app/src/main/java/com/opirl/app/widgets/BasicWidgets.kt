package com.opirl.app.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import com.opirl.app.chat.ChatLine
import com.opirl.app.chat.chatNameColor
import com.opirl.app.engine.chat.ChatSegment
import com.opirl.app.media.gl.BrowserPage
import com.opirl.app.settings.Corner
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import java.io.File

fun decodeImage(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
    if (!file.isFile) return null
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = maxOf((info.size.width + maxWidth - 1) / maxWidth, (info.size.height + maxHeight - 1) / maxHeight)
        decoder.setTargetSampleSize(Integer.highestOneBit(scale) * if (Integer.bitCount(scale) > 1) 2 else 1)
    }
}

object ImageWidget : WidgetDrawer {
    override fun placement(widget: Widget) = SceneWidget(widget.id, corner = Corner.TOP_RIGHT, size = 15)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val name = widget.image ?: return null
        val bitmap = context.cached(widget.id, listOf(name, context.width, context.height)) {
            decodeImage(File(context.runtime.files, name), context.width, context.height)
        } ?: return null
        return context.sized(bitmap, placement)
    }
}

object BrowserWidget : WidgetDrawer {
    override fun placement(widget: Widget) = SceneWidget(widget.id, x = 0, y = 0, size = 100)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame {
        val page = BrowserPage(widget.url, widget.browserWidth, widget.browserHeight)
        val share = placement.size / 100f
        return WidgetFrame(
            listOf(Layer(source = page, surface = widget.id)),
            share,
            share * context.width / context.height * page.height / page.width,
        )
    }
}

object ChatWidget : WidgetDrawer {
    private const val LINES = 6

    override fun placement(widget: Widget) = SceneWidget(widget.id, corner = Corner.BOTTOM_LEFT, size = 35)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val shown = context.runtime.chat.value.takeLast(LINES)
        val width = context.width * placement.size / 100
        val size = context.settings.chatTextSize.sp * context.textScale
        val emotes = HashMap<String, Bitmap>()
        for (line in shown) {
            for (segment in line.message.segments) {
                if (segment is ChatSegment.Emote) context.runtime.emotes.get(segment.url)?.let { emotes[segment.url] = it }
            }
        }
        val bitmap = context.cached(widget.id, listOf(shown.map { it.key }, width, size, context.height, emotes.keys)) {
            draw(shown, emotes, width, size, context.height)
        } ?: return null
        return context.natural(bitmap)
    }

    private fun draw(lines: List<ChatLine>, emotes: Map<String, Bitmap>, width: Int, size: Float, maxHeight: Int): Bitmap? {
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
                val emote = (segment as? ChatSegment.Emote)?.let { emotes[it.url] }
                when {
                    segment is ChatSegment.Text -> text.append(segment.text)
                    emote == null -> text.append((segment as ChatSegment.Emote).name)
                    else -> {
                        val height = (size * 1.3f).toInt()
                        val drawable = BitmapDrawable(null, emote).apply {
                            setBounds(0, 0, height * emote.width / emote.height, height)
                        }
                        text.append("\uFFFC", ImageSpan(drawable, DynamicDrawableSpan.ALIGN_CENTER), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
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
}

object MusicWidget : WidgetDrawer {
    override val sized get() = false

    override fun placement(widget: Widget) = SceneWidget(widget.id, corner = Corner.BOTTOM_LEFT, y = 5)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        val track = context.runtime.track.value?.takeIf { it.playing || !widget.hideWhenPaused } ?: return null
        val art = track.cover.takeIf { widget.cover }
        val size = widget.textSize.coerceIn(8, 60) * context.textScale
        val bitmap = context.cached(widget.id, listOf(track.title, track.artist, art, size, context.width, context.height)) {
            draw(track.title, track.artist, art, size, context.width, context.height)
        } ?: return null
        return context.natural(bitmap)
    }

    private fun draw(title: String, artist: String, cover: Bitmap?, size: Float, maxWidth: Int, maxHeight: Int): Bitmap {
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
}
