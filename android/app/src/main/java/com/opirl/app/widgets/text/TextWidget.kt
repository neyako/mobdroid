package com.opirl.app.widgets.text

import android.location.Location
import android.os.Build
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.stream.LiveState
import com.opirl.app.stream.outputSize
import com.opirl.app.widgets.Need
import com.opirl.app.widgets.WidgetContext
import com.opirl.app.widgets.WidgetDrawer
import com.opirl.app.widgets.WidgetFrame
import kotlin.math.roundToInt

private class TextHistory {
    val samples = ArrayDeque<Pair<Long, TextVariables>>()
}

private fun Location.height(): Double = if (Build.VERSION.SDK_INT >= 34 && hasMslAltitude()) mslAltitudeMeters else altitude

private fun Location.toFix() = Fix(
    latitude,
    longitude,
    height(),
    if (hasAccuracy()) accuracy.toDouble() else 0.0,
    if (hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else null,
)

object TextWidget : WidgetDrawer {
    override val sized get() = false

    override fun placement(widget: Widget) = SceneWidget(widget.id, x = 0, y = 0)

    override fun needs(widget: Widget, settings: AppSettings): Set<Need> =
        if (parseTextFormat(widget.text).needsLocation) setOf(Need.Location) else emptySet()

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? {
        if (widget.text.isBlank()) return null
        val parts = parseTextFormat(widget.text)
        val runtime = context.runtime
        val sources = runtime.textSources()
        val options = widget.textOptions
        val environment = TextEnvironment()
        val now = context.now
        val variables = delayed(context, widget, sample(context, sources, environment), options.delay)
        val lines = formatText(parts, variables, options, now, environment)
        context.redrawAt(nextRedraw(parts, options, now, environment.zone))
        val scale = context.textScale
        val size = widget.textSize * scale
        val look = TextLook(
            background = options.backgroundColor,
            foreground = options.foregroundColor,
            alignment = options.alignment,
            size = size,
            minWidth = if (options.widthEnabled) options.width * scale else 0f,
            radius = options.cornerRadius * scale,
            typeface = textTypeface(runtime.context.assets, options),
            monospacedDigits = options.monospacedDigits && options.family == TextFamily.SYSTEM,
        )
        val key = listOf(lines, look.background, look.foreground, look.alignment, size, look.minWidth, look.radius, look.monospacedDigits,
            options.family, options.fontStyle, options.design, options.weight, context.width, context.height)
        val bitmap = context.cached(widget.id, key) { drawTextLines(lines, look, context.width, context.height) } ?: return null
        return context.natural(bitmap)
    }

    private fun sample(context: WidgetContext, sources: TextSources, environment: TextEnvironment): TextVariables {
        val runtime = context.runtime
        val stats = runtime.stats.value
        val live = runtime.ui.value.live != LiveState.OFFLINE
        val profile = context.settings.selectedProfile
        val (width, height) = outputSize(profile)
        val location = runtime.location.value
        if (location != null) sources.track(location.toFix(), location.elapsedRealtimeNanos)
        val trip = sources.tripSnapshot(context.now)
        return TextVariables(
            time = context.now,
            bitrate = if (live) formatBitrate(stats.bitrateBps) else "",
            bitrateAndTotal = if (live) formatBitrateAndTotal(stats.bitrateBps, stats.bytesSent) else "",
            bonding = if (live) formatBonding(stats.links.map { it.name to it.percent }) else "",
            resolution = resolutionLabel(width, height),
            fps = if (live && stats.fps > 0f) stats.fps.roundToInt() else profile.fps,
            speed = if (location?.hasSpeed() == true) location.speed.toDouble() else 0.0,
            averageSpeed = trip.averageSpeed,
            altitude = location?.height() ?: 0.0,
            distance = trip.distance,
            splitDistance = trip.splitDistance,
            altitudeAscent = trip.ascent,
            altitudeDescent = trip.descent,
            splitAltitudeAscent = trip.splitAscent,
            splitAltitudeDescent = trip.splitDescent,
            slope = "${trip.slope.toInt()}%",
            weather = sources.weather,
            place = sources.place,
            muted = runtime.ui.value.muted,
            gForce = sources.gForce.latest(),
        )
    }

    private fun delayed(context: WidgetContext, widget: Widget, current: TextVariables, delay: Float): TextVariables {
        if (delay <= 0f) return current
        val history = context.runtime.state("${widget.id}#history") { TextHistory() }
        val samples = history.samples
        val now = context.now
        if (samples.isEmpty() || now - samples.last().first >= 500) samples.addLast(now to current)
        while (samples.size > 1 && now - samples.first().first > (delay + 2) * 1000) samples.removeFirst()
        val wait = ((delay - 1) * 1000).toLong()
        return (samples.lastOrNull { it.first + wait <= now } ?: samples.first()).second
    }
}
