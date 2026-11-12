package com.opirl.app.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opirl.app.R
import com.opirl.app.ui.ActionRow
import com.opirl.app.ui.ColorRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.SwitchRow
import com.opirl.app.ui.TextRow
import com.opirl.app.widgets.bingo.BingoSettings
import com.opirl.app.widgets.bingo.bingoSide
import com.opirl.app.widgets.bingo.squaresFromText
import com.opirl.app.widgets.bingo.squaresToText
import com.opirl.app.widgets.bingo.toggled
import com.opirl.app.widgets.bingo.unchecked

@Composable
internal fun BingoPage(scope: WidgetScope) {
    val bingo = scope.widget.bingo
    val update = { transform: (BingoSettings) -> BingoSettings -> scope.update { it.copy(bingo = transform(it.bingo)) } }
    var text by remember(scope.widget.id) { mutableStateOf(squaresToText(bingo.squares)) }
    Group {
        row { shapes ->
            TextRow(
                shapes,
                stringResource(R.string.bingo_squares),
                text,
                stringResource(R.string.bingo_squares_hint),
                singleLine = false,
            ) { changed ->
                text = changed
                update { it.copy(squares = squaresFromText(changed, it.squares)) }
            }
        }
    }
    if (bingo.squares.isNotEmpty()) {
        Group {
            bingo.squares.forEachIndexed { index, square ->
                row { shapes ->
                    SwitchRow(shapes, square.text.ifBlank { "${index + 1}" }, square.checked) { update { it.toggled(index) } }
                }
            }
            row { shapes -> ActionRow(shapes, stringResource(R.string.reset), Icons.Rounded.Refresh) { update { it.unchecked() } } }
        }
    }
    Group {
        row { shapes ->
            ColorRow(shapes, stringResource(R.string.bingo_background), bingo.background) { color ->
                update { it.copy(background = color) }
            }
        }
        row { shapes ->
            ColorRow(shapes, stringResource(R.string.bingo_foreground), bingo.foreground) { color ->
                update { it.copy(foreground = color) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BingoControls(scope: WidgetScope) {
    val squares = scope.widget.bingo.squares
    val side = bingoSide(squares.size)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(side) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(side) { column ->
                    val index = row * side + column
                    val square = squares.getOrNull(index)
                    if (square == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        ToggleButton(
                            checked = square.checked,
                            onCheckedChange = { scope.update { it.copy(bingo = it.bingo.toggled(index)) } },
                            colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentPadding = PaddingValues(2.dp),
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) {
                            Text(
                                square.text.ifBlank { "${index + 1}" },
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                fontSize = 11.sp,
                                lineHeight = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
