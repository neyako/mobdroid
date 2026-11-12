package com.opirl.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import com.opirl.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon

val RowPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
val RowHeight: Dp
    @Composable get() = 44.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)

val TwoLineRowHeight: Dp
    @Composable get() = 56.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
val PanelHeaderHeight = 44.dp

val RowTitle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp)

val PanelTitle: TextStyle
    @Composable get() = MaterialTheme.typography.titleMediumEmphasized.copy(fontSize = 15.sp)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rowColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

class Badge(val icon: ImageVector, val color: Color, val shape: RoundedPolygon)

class GroupBuilder {
    internal val rows = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun row(content: @Composable (ListItemShapes) -> Unit) {
        rows += content
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Group(build: GroupBuilder.() -> Unit) {
    val rows = GroupBuilder().apply(build).rows
    Column(
        Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEachIndexed { index, row -> row(ListItemDefaults.segmentedShapes(index, rows.size)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BadgeIcon(badge: Badge) {
    Box(Modifier.size(26.dp).clip(badge.shape.toShape()).background(badge.color), contentAlignment = Alignment.Center) {
        Icon(badge.icon, null, tint = Color.Black, modifier = Modifier.size(15.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NavRow(
    shapes: ListItemShapes,
    title: String,
    value: String? = null,
    badge: Badge? = null,
    leading: (@Composable () -> Unit)? = null,
    supporting: String? = null,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        modifier = Modifier.heightIn(max = if (supporting == null) RowHeight else TwoLineRowHeight),
        contentPadding = RowPadding,
        colors = rowColors(),
        onClick = onClick,
        shapes = shapes,
        leadingContent = leading ?: badge?.let { { BadgeIcon(it) } },
        verticalAlignment = Alignment.CenterVertically,
        supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp),
                    )
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) { Text(title, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ValueRow(shapes: ListItemShapes, title: String, value: String) {
    SegmentedListItem(
        modifier = Modifier.heightIn(max = RowHeight),
        contentPadding = RowPadding,
        colors = rowColors(),
        shapes = shapes,
        trailingContent = {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) { Text(title, style = RowTitle) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActionRow(
    shapes: ListItemShapes,
    title: String,
    icon: ImageVector,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        contentPadding = RowPadding,
        colors = rowColors(),
        onClick = onClick,
        shapes = shapes,
        enabled = enabled,
        modifier = Modifier.heightIn(max = RowHeight),
        leadingContent = { Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) color else color.copy(alpha = 0.38f)) },
    ) { Text(title, style = RowTitle, color = if (enabled) color else color.copy(alpha = 0.38f)) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SwitchRow(shapes: ListItemShapes, title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SegmentedListItem(
        contentPadding = RowPadding,
        colors = rowColors(),
        onClick = { onChange(!checked) },
        shapes = shapes,
        enabled = enabled,
        modifier = Modifier.heightIn(max = RowHeight),
        trailingContent = { Switch(checked, null, Modifier.scale(0.85f), enabled = enabled) },
    ) { Text(title, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SwitchNavRow(shapes: ListItemShapes, title: String, checked: Boolean, onChange: (Boolean) -> Unit, onClick: () -> Unit) {
    SegmentedListItem(
        contentPadding = RowPadding,
        colors = rowColors(),
        onClick = onClick,
        shapes = shapes,
        modifier = Modifier.heightIn(max = RowHeight),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked, onChange, Modifier.scale(0.85f))
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) { Text(title, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SliderRow(
    shapes: ListItemShapes,
    title: String,
    value: Int,
    range: IntRange,
    label: String,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    SegmentedListItem(shapes = shapes, contentPadding = RowPadding, colors = rowColors()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = RowTitle)
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
                onValueChange = { onChange((it.toInt() / step * step).coerceIn(range)) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ChoiceRow(
    shapes: ListItemShapes,
    title: String,
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    isEnabled: (T) -> Boolean = { true },
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    SegmentedListItem(shapes = shapes, contentPadding = RowPadding, colors = rowColors()) {
        Column {
            Text(title, style = RowTitle)
            EqualWidthRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                values.forEachIndexed { index, value ->
                    ToggleButton(
                        checked = value == selected,
                        onCheckedChange = { onSelect(value) },
                        enabled = enabled && isEnabled(value),
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            values.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(label(value), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BitrateRow(
    shapes: ListItemShapes,
    title: String,
    kbps: Int,
    range: IntRange,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    SegmentedListItem(shapes = shapes, contentPadding = RowPadding, colors = rowColors()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = RowTitle)
                Text(
                    "${formatMbps(kbps)} Mbps",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = kbps.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
                onValueChange = { onChange(((it / 250).toInt() * 250).coerceIn(range)) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun EqualWidthRow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val height = 32.dp.roundToPx()
        val gap = ButtonGroupDefaults.ConnectedSpaceBetween.roundToPx()
        val space = (constraints.maxWidth - gap * (measurables.size - 1)).coerceAtLeast(0)
        val natural = measurables.map { it.maxIntrinsicWidth(height) }
        val fits = (natural.maxOrNull() ?: 0) * measurables.size <= space
        val total = natural.sum().coerceAtLeast(1)
        var left = space
        val placeables = measurables.mapIndexed { index, measurable ->
            val width = when {
                index == measurables.lastIndex -> left
                fits -> space / measurables.size
                else -> (space.toLong() * natural[index] / total).toInt()
            }
            left -= width
            measurable.measure(Constraints.fixed(width, height))
        }
        layout(constraints.maxWidth, height) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, 0)
                x += it.width + gap
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextRow(
    shapes: ListItemShapes,
    title: String,
    value: String,
    placeholder: String? = null,
    enabled: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    FilledField(shapes.shape, title, value, placeholder, enabled, keyboard, singleLine, onChange)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilledField(
    shape: Shape,
    title: String,
    value: String,
    placeholder: String? = null,
    enabled: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    val container = MaterialTheme.colorScheme.surfaceContainer
    val primary = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val outline by animateColorAsState(
        primary.copy(alpha = if (focused) 1f else 0f),
        MaterialTheme.motionScheme.fastEffectsSpec(),
    )
    TextField(
        value,
        onChange,
        label = { Text(title) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        enabled = enabled,
        visualTransformation = if (keyboard == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        interactionSource = interaction,
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().border(2.dp, outline, shape),
    )
}

@Composable
fun CompactIconButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        IconButton(onClick, Modifier.size(36.dp, 44.dp), enabled) { Icon(icon, label, Modifier.size(20.dp)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemovableRow(shapes: ListItemShapes, title: String, value: String, onClick: (() -> Unit)?, onRemove: () -> Unit) {
    val padding = PaddingValues(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 4.dp)
    val trailing: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            CompactIconButton(Icons.Rounded.Close, stringResource(R.string.remove_item, title), onClick = onRemove)
        }
    }
    val content: @Composable () -> Unit = { Text(title, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (onClick == null) {
        SegmentedListItem(
            contentPadding = padding,
            colors = rowColors(),
            shapes = shapes,
            modifier = Modifier.heightIn(max = RowHeight),
            trailingContent = trailing,
            content = content,
        )
    } else {
        SegmentedListItem(
            contentPadding = padding,
            colors = rowColors(),
            onClick = onClick,
            shapes = shapes,
            modifier = Modifier.heightIn(max = RowHeight),
            trailingContent = trailing,
            content = content,
        )
    }
}

@Composable
fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
}

@Composable
fun <T> PickerRow(
    shapes: ListItemShapes,
    title: String,
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    NavRow(shapes, title, label(selected)) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    values.forEach { value ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                open = false
                                onSelect(value)
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(value == selected, null, Modifier.padding(10.dp))
                            Text(label(value), style = RowTitle)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

val COLOR_PRESETS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF8E8E93, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7BE,
    0xFF32ADE6, 0xFF007AFF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF2D55, 0xFFA2845E,
).map { it.toInt() }

fun colorHex(color: Int): String = "%08X".format(color).let { if (it.startsWith("FF")) it.drop(2) else it }

fun parseColorHex(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    return when (hex.length) {
        6 -> hex.toLongOrNull(16)?.let { (it or 0xFF000000).toInt() }
        8 -> hex.toLongOrNull(16)?.toInt()
        else -> null
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PickRow(shapes: ListItemShapes, title: String, selected: Boolean, onClick: () -> Unit) {
    SegmentedListItem(
        contentPadding = RowPadding,
        colors = rowColors(),
        onClick = onClick,
        shapes = shapes,
        modifier = Modifier.heightIn(max = RowHeight),
        leadingContent = { RadioButton(selected, null) },
    ) { Text(title, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SwitchRemovableRow(shapes: ListItemShapes, title: String, checked: Boolean, onChange: (Boolean) -> Unit, onRemove: () -> Unit) {
    SegmentedListItem(
        contentPadding = PaddingValues(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        colors = rowColors(),
        onClick = { onChange(!checked) },
        shapes = shapes,
        modifier = Modifier.heightIn(max = RowHeight),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked, null, Modifier.scale(0.85f))
                CompactIconButton(Icons.Rounded.Close, stringResource(R.string.remove_item, title), onClick = onRemove)
            }
        },
    ) { Text(title, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
fun <T : Any> NumberRow(
    shapes: ListItemShapes,
    title: String,
    value: T,
    decimal: Boolean,
    parse: (String) -> T?,
    onChange: (T) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString().removeSuffix(".0")) }
    TextRow(shapes, title, text, keyboard = if (decimal) KeyboardType.Decimal else KeyboardType.Number) { typed ->
        text = typed
        parse(typed.trim())?.let(onChange)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ColorRow(shapes: ListItemShapes, title: String, color: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val swatch = @Composable { value: Int, size: Dp, click: (() -> Unit)? ->
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(50))
                .background(Color(value))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                .then(if (click == null) Modifier else Modifier.clickable(onClick = click)),
        )
    }
    SegmentedListItem(
        contentPadding = RowPadding,
        colors = rowColors(),
        onClick = { open = true },
        shapes = shapes,
        modifier = Modifier.heightIn(max = RowHeight),
        trailingContent = { swatch(color, 24.dp, null) },
    ) { Text(title, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (open) {
        var hex by remember { mutableStateOf(colorHex(color)) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    COLOR_PRESETS.chunked(7).forEach { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            line.forEach { preset ->
                                swatch(preset, 30.dp) {
                                    hex = colorHex(preset)
                                    onChange(preset)
                                }
                            }
                        }
                    }
                    TextField(
                        hex,
                        { text ->
                            hex = text
                            parseColorHex(text)?.let(onChange)
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        isError = parseColorHex(hex) == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val alpha = color ushr 24
                    Slider(
                        value = alpha.toFloat(),
                        onValueChange = { value ->
                            val next = (color and 0xFFFFFF) or (value.toInt() shl 24)
                            hex = colorHex(next)
                            onChange(next)
                        },
                        valueRange = 0f..255f,
                    )
                }
            },
            confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.done)) } },
        )
    }
}
