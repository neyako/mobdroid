package com.opirl.app.widgets.text

import androidx.annotation.StringRes
import com.opirl.app.R
import kotlinx.serialization.Serializable

@Serializable
enum class TextDesign(@StringRes val label: Int) {
    DEFAULT(R.string.text_design_default),
    SERIF(R.string.text_design_serif),
    ROUNDED(R.string.text_design_rounded),
    MONOSPACED(R.string.text_design_monospaced),
}

@Serializable
enum class TextWeight(@StringRes val label: Int, val value: Int) {
    REGULAR(R.string.text_weight_regular, 400),
    LIGHT(R.string.text_weight_light, 300),
    BOLD(R.string.text_weight_bold, 700),
}

@Serializable
enum class TextAlignment(@StringRes val label: Int) {
    LEADING(R.string.text_alignment_leading),
    CENTER(R.string.text_alignment_center),
    TRAILING(R.string.text_alignment_trailing),
}

@Serializable
enum class TextFamily(@StringRes val label: Int, val family: String?) {
    SYSTEM(R.string.text_family_system, null),
    SANS(R.string.text_family_sans, "sans-serif"),
    CONDENSED(R.string.text_family_condensed, "sans-serif-condensed"),
    SERIF(R.string.text_family_serif, "serif"),
    MONOSPACE(R.string.text_family_monospace, "monospace"),
    CASUAL(R.string.text_family_casual, "casual"),
    CURSIVE(R.string.text_family_cursive, "cursive"),
    SMALL_CAPS(R.string.text_family_small_caps, "sans-serif-smallcaps"),
}

@Serializable
enum class TextFontStyle(@StringRes val label: Int, val style: Int) {
    REGULAR(R.string.text_style_regular, 0),
    BOLD(R.string.text_style_bold, 1),
    ITALIC(R.string.text_style_italic, 2),
    BOLD_ITALIC(R.string.text_style_bold_italic, 3),
}

@Serializable
data class TextTimer(val delta: Int = 5, val endTime: Long = 0)

@Serializable
data class TextStopwatch(val total: Long = 0, val startedAt: Long = 0, val running: Boolean = false)

@Serializable
data class TextLaps(val start: Long = 0, val laps: List<Long> = emptyList())

@Serializable
data class TextSettings(
    val backgroundColor: Int = 0xBF000000.toInt(),
    val foregroundColor: Int = 0xFFFFFFFF.toInt(),
    val family: TextFamily = TextFamily.SYSTEM,
    val fontStyle: TextFontStyle = TextFontStyle.REGULAR,
    val design: TextDesign = TextDesign.DEFAULT,
    val weight: TextWeight = TextWeight.REGULAR,
    val monospacedDigits: Boolean = false,
    val alignment: TextAlignment = TextAlignment.LEADING,
    val widthEnabled: Boolean = false,
    val width: Int = 150,
    val cornerRadius: Int = 5,
    val delay: Float = 0f,
    val resetWhenGoingLive: Boolean = false,
    val timers: List<TextTimer> = emptyList(),
    val stopwatches: List<TextStopwatch> = emptyList(),
    val checkboxes: List<Boolean> = emptyList(),
    val ratings: List<Int> = emptyList(),
    val lapTimes: List<TextLaps> = emptyList(),
)

private fun <T> List<T>.resized(size: Int, create: () -> T): List<T> =
    if (this.size >= size) take(size) else this + List(size - this.size) { create() }

fun TextSettings.synced(parts: List<TextPart>): TextSettings = copy(
    timers = timers.resized(parts.count { it == TextPart.Simple(SimpleField.TIMER) }, ::TextTimer),
    stopwatches = stopwatches.resized(parts.count { it == TextPart.Simple(SimpleField.STOPWATCH) }, ::TextStopwatch),
    checkboxes = checkboxes.resized(parts.count { it == TextPart.Simple(SimpleField.CHECKBOX) }) { false },
    ratings = ratings.resized(parts.count { it == TextPart.Simple(SimpleField.RATING) }) { 0 },
    lapTimes = lapTimes.resized(parts.count { it == TextPart.Simple(SimpleField.LAP_TIMES) }, ::TextLaps),
)

fun TextSettings.isSynced(parts: List<TextPart>): Boolean = synced(parts) == this
