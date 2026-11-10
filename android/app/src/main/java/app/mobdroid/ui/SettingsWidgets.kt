package app.mobdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row -> row(ListItemDefaults.segmentedShapes(index, rows.size)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BadgeIcon(badge: Badge) {
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
    label: (T) -> String,
    isEnabled: (T) -> Boolean = { true },
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    SegmentedListItem(shapes = shapes, contentPadding = RowPadding, colors = rowColors()) {
        Column {
            Text(title, style = RowTitle)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
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
                        modifier = Modifier.weight(label(value).length + 2f).height(32.dp),
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextRow(
    shapes: ListItemShapes,
    title: String,
    value: String,
    placeholder: String? = null,
    enabled: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    val field = MaterialTheme.colorScheme.surfaceContainerHighest
    SegmentedListItem(shapes = shapes, contentPadding = PaddingValues(FieldInset), colors = rowColors()) {
        TextField(
            value,
            onChange,
            label = { Text(title) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            enabled = enabled,
            visualTransformation = if (keyboard == KeyboardType.Password) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            shape = insetShape(shapes.shape, FieldInset),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = field,
                unfocusedContainerColor = field,
                disabledContainerColor = field,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val FieldInset = 8.dp

@Composable
private fun insetShape(shape: Shape, inset: Dp): Shape {
    val outer = shape as? CornerBasedShape ?: return RoundedCornerShape(4.dp)
    val density = LocalDensity.current
    val inner = { corner: CornerSize ->
        CornerSize(with(density) { maxOf(corner.toPx(Size.Zero, this).toDp() - inset, 4.dp) })
    }
    return RoundedCornerShape(inner(outer.topStart), inner(outer.topEnd), inner(outer.bottomEnd), inner(outer.bottomStart))
}
