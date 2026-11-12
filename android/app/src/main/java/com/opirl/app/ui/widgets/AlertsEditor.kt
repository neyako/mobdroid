package com.opirl.app.ui.widgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.opirl.app.R
import com.opirl.app.ui.ActionRow
import com.opirl.app.ui.ChoiceRow
import com.opirl.app.ui.ColorRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.NavRow
import com.opirl.app.ui.Note
import com.opirl.app.ui.PickerRow
import com.opirl.app.ui.RemovableRow
import com.opirl.app.ui.SliderRow
import com.opirl.app.ui.SwitchRow
import com.opirl.app.ui.TextRow
import com.opirl.app.widgets.alerts.AlertComparison
import com.opirl.app.widgets.alerts.AlertFontDesign
import com.opirl.app.widgets.alerts.AlertFontWeight
import com.opirl.app.widgets.alerts.AlertMedia
import com.opirl.app.widgets.alerts.AlertSettings
import com.opirl.app.widgets.alerts.BUNDLED_IMAGES
import com.opirl.app.widgets.alerts.BUNDLED_SOUNDS
import com.opirl.app.widgets.alerts.alert
import com.opirl.app.widgets.alerts.alertSound
import com.opirl.app.widgets.alerts.alertsAudio
import com.opirl.app.widgets.alerts.command
import com.opirl.app.widgets.alerts.deliverAlert
import com.opirl.app.widgets.alerts.imageName
import com.opirl.app.widgets.alerts.soundName
import com.opirl.app.widgets.alerts.testEvent
import com.opirl.app.widgets.alerts.threshold
import com.opirl.app.widgets.alerts.withAlert
import com.opirl.app.widgets.alerts.withCommand
import com.opirl.app.widgets.alerts.withThreshold
import com.opirl.app.widgets.alerts.withoutImage
import com.opirl.app.widgets.alerts.withoutSound
import java.io.File

private val DESIGN_LABELS = mapOf(
    AlertFontDesign.DEFAULT to R.string.alerts_design_default,
    AlertFontDesign.SERIF to R.string.alerts_design_serif,
    AlertFontDesign.ROUNDED to R.string.alerts_design_rounded,
    AlertFontDesign.MONOSPACED to R.string.alerts_design_monospaced,
)

private val WEIGHT_LABELS = mapOf(
    AlertFontWeight.REGULAR to R.string.alerts_weight_regular,
    AlertFontWeight.LIGHT to R.string.alerts_weight_light,
    AlertFontWeight.BOLD to R.string.alerts_weight_bold,
)

@Composable
internal fun AlertEditorPage(scope: WidgetScope, key: String) {
    val settings = scope.widget.alerts
    val alert = settings.alert(key) ?: return
    val update = { transform: (AlertSettings) -> AlertSettings ->
        scope.update { widget -> widget.copy(alerts = widget.alerts.alert(key)?.let { widget.alerts.withAlert(key, transform(it)) } ?: widget.alerts) }
    }
    Group {
        row { shapes -> SwitchRow(shapes, stringResource(R.string.enabled), alert.enabled) { on -> update { it.copy(enabled = on) } } }
    }
    settings.threshold(key)?.let { threshold ->
        Group {
            row { shapes ->
                AmountRow(shapes, stringResource(if (key.startsWith("twitch")) R.string.alerts_bits else R.string.alerts_amount), threshold.amount) { amount ->
                    scope.update { it.copy(alerts = it.alerts.withThreshold(key) { item -> item.copy(amount = amount) }) }
                }
            }
            row { shapes ->
                ChoiceRow(
                    shapes,
                    stringResource(R.string.alerts_comparison),
                    AlertComparison.entries,
                    threshold.comparison,
                    { it.symbol },
                ) { comparison -> scope.update { it.copy(alerts = it.alerts.withThreshold(key) { item -> item.copy(comparison = comparison) }) } }
            }
        }
    }
    settings.command(key)?.let { command ->
        Group {
            row { shapes ->
                TextRow(shapes, stringResource(R.string.name), command.name) { name ->
                    scope.update { it.copy(alerts = it.alerts.withCommand(key) { item -> item.copy(name = name.lowercase().filterNot(Char::isWhitespace)) }) }
                }
            }
        }
        Note(stringResource(R.string.alerts_command_note, command.name))
    }
    Group {
        row { shapes ->
            val title = stringResource(R.string.alerts_image)
            NavRow(shapes, title, settings.imageName(alert.image)) { scope.push("media:image/$key", title) }
        }
        row { shapes ->
            val title = stringResource(R.string.alerts_sound)
            NavRow(shapes, title, settings.soundName(alert.sound)) { scope.push("media:sound/$key", title) }
        }
    }
    Group {
        row { shapes -> ColorRow(shapes, stringResource(R.string.alerts_text_color), alert.textColor) { color -> update { it.copy(textColor = color) } } }
        row { shapes ->
            ColorRow(shapes, stringResource(R.string.alerts_accent_color), alert.accentColor) { color -> update { it.copy(accentColor = color) } }
        }
    }
    Group {
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.text_size), alert.fontSize, 10..80, "${alert.fontSize}", 5) { size ->
                update { it.copy(fontSize = size) }
            }
        }
        row { shapes ->
            ChoiceRow(
                shapes,
                stringResource(R.string.alerts_design),
                AlertFontDesign.entries,
                alert.fontDesign,
                { stringResource(DESIGN_LABELS.getValue(it)) },
            ) { design -> update { it.copy(fontDesign = design) } }
        }
        row { shapes ->
            ChoiceRow(
                shapes,
                stringResource(R.string.alerts_weight),
                AlertFontWeight.entries,
                alert.fontWeight,
                { stringResource(WEIGHT_LABELS.getValue(it)) },
            ) { weight -> update { it.copy(fontWeight = weight) } }
        }
    }
    Group {
        row { shapes -> SwitchRow(shapes, stringResource(R.string.chat_speech), alert.speech) { on -> update { it.copy(speech = on) } } }
        row { shapes ->
            SliderRow(
                shapes,
                stringResource(R.string.alerts_speech_delay),
                alert.speechDelayMs,
                0..5000,
                "%.1f s".format(alert.speechDelayMs / 1000f),
                500,
            ) { delay -> update { it.copy(speechDelayMs = delay) } }
        }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.alerts_test), Icons.Rounded.PlayArrow) {
                settings.testEvent(key)?.let { deliverAlert(scope.runtime, it) }
            }
        }
    }
}

@Composable
private fun AmountRow(shapes: androidx.compose.material3.ListItemShapes, title: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    TextRow(shapes, title, text, keyboard = KeyboardType.Number) { typed ->
        val digits = typed.filter(Char::isDigit).take(9)
        text = digits
        digits.toIntOrNull()?.let(onChange)
    }
}

@Composable
internal fun AlertMediaPage(scope: WidgetScope, spec: String) {
    val kind = spec.substringBefore('/')
    val argument = spec.substringAfter('/')
    when (kind) {
        "image" -> MediaSelectorPage(scope, argument, image = true)
        "sound" -> MediaSelectorPage(scope, argument, image = false)
        "image-item" -> MediaItemPage(scope, argument, image = true)
        "sound-item" -> MediaItemPage(scope, argument, image = false)
    }
}

@Composable
private fun MediaSelectorPage(scope: WidgetScope, key: String, image: Boolean) {
    val settings = scope.widget.alerts
    val alert = settings.alert(key) ?: return
    val custom = if (image) settings.images else settings.sounds
    val ids = (if (image) BUNDLED_IMAGES.map { it.id } else BUNDLED_SOUNDS.map { it.id }) + custom.map { it.id }
    val selected = if (image) alert.image else alert.sound
    val defaultName = stringResource(if (image) R.string.alerts_my_image else R.string.alerts_my_sound)
    val pick = rememberWidgetFilePicker(scope) { file ->
        scope.update {
            val media = AlertMedia(name = defaultName, file = file)
            it.copy(alerts = if (image) it.alerts.copy(images = it.alerts.images + media) else it.alerts.copy(sounds = it.alerts.sounds + media))
        }
    }
    val choose = { id: String ->
        scope.update { widget ->
            widget.copy(
                alerts = widget.alerts.alert(key)?.let { current ->
                    widget.alerts.withAlert(key, if (image) current.copy(image = id) else current.copy(sound = id))
                } ?: widget.alerts,
            )
        }
    }
    Group {
        row { shapes ->
            PickerRow(
                shapes,
                stringResource(if (image) R.string.alerts_image else R.string.alerts_sound),
                ids,
                selected,
                { if (image) settings.imageName(it) else settings.soundName(it) },
                choose,
            )
        }
        if (image) {
            row { shapes ->
                SliderRow(shapes, stringResource(R.string.alerts_repeat), alert.loops, 1..10, "${alert.loops}") { loops ->
                    scope.update { widget ->
                        widget.copy(alerts = widget.alerts.alert(key)?.let { widget.alerts.withAlert(key, it.copy(loops = loops)) } ?: widget.alerts)
                    }
                }
            }
        } else {
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.alerts_play), Icons.Rounded.PlayArrow) { playSound(scope, selected) }
            }
        }
    }
    if (image) Note(stringResource(R.string.alerts_repeat_note))
    Group {
        custom.forEach { media ->
            row { shapes ->
                RemovableRow(shapes, media.name, "", { scope.push("media:${if (image) "image" else "sound"}-item/${media.id}", media.name) }) {
                    File(scope.runtime.files, media.file).delete()
                    scope.update { widget ->
                        widget.copy(alerts = if (image) widget.alerts.withoutImage(media.id) else widget.alerts.withoutSound(media.id))
                    }
                }
            }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(if (image) R.string.alerts_add_image else R.string.alerts_add_sound), Icons.Rounded.Add) {
                pick(arrayOf(if (image) "image/*" else "audio/*"))
            }
        }
    }
    Note(stringResource(if (image) R.string.alerts_images_note else R.string.alerts_sounds_note))
}

@Composable
private fun MediaItemPage(scope: WidgetScope, id: String, image: Boolean) {
    val settings = scope.widget.alerts
    val media = (if (image) settings.images else settings.sounds).firstOrNull { it.id == id } ?: return
    val change = { transform: (AlertMedia) -> AlertMedia ->
        scope.update { widget ->
            val list = if (image) widget.alerts.images else widget.alerts.sounds
            val next = list.map { if (it.id == id) transform(it) else it }
            widget.copy(alerts = if (image) widget.alerts.copy(images = next) else widget.alerts.copy(sounds = next))
        }
    }
    val pick = rememberWidgetFilePicker(scope) { file ->
        File(scope.runtime.files, media.file).delete()
        change { it.copy(file = file) }
    }
    Group {
        row { shapes -> TextRow(shapes, stringResource(R.string.name), media.name) { name -> change { it.copy(name = name) } } }
        row { shapes ->
            ActionRow(shapes, stringResource(if (image) R.string.change_image else R.string.alerts_change_sound), Icons.Rounded.Add) {
                pick(arrayOf(if (image) "image/*" else "audio/*"))
            }
        }
        if (!image) {
            row { shapes -> ActionRow(shapes, stringResource(R.string.alerts_play), Icons.Rounded.PlayArrow) { playSound(scope, id) } }
        }
    }
}

private fun playSound(scope: WidgetScope, id: String) {
    alertSound(scope.runtime, scope.widget.alerts, id)?.let { alertsAudio(scope.runtime).play(it) }
}
