package com.opirl.app.ui

import com.opirl.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.opirl.app.scenes.placement
import com.opirl.app.scenes.withPlacement
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.WidgetType
import com.opirl.app.stream.StreamEngine
import com.opirl.app.ui.widgets.CONTROLLED
import com.opirl.app.ui.widgets.WidgetControls
import com.opirl.app.ui.widgets.WidgetScope
import com.opirl.app.ui.widgets.hasTextControls

@Composable
fun WidgetsPanel(store: SettingsStore, engine: StreamEngine, settings: AppSettings, onHide: () -> Unit, onClose: () -> Unit) {
    val scene = settings.selectedScene
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(PanelHeaderHeight).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { Text(stringResource(R.string.widgets), style = PanelTitle) }
                PanelButtons(hidden = false, onHide, onClose)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (scene == null || settings.widgets.isEmpty()) {
                    Text(
                        stringResource(R.string.widgets_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                } else {
                    Group {
                        settings.widgets.forEach { widget ->
                            val placement = scene.placement(widget)
                            row { shapes ->
                                SwitchRow(shapes, widget.name, placement.enabled) { on ->
                                    store.update { it.withPlacement(scene.id, placement.copy(enabled = on)) }
                                }
                            }
                        }
                    }
                    settings.widgets.filter { it.type in CONTROLLED && scene.placement(it).enabled && (it.type != WidgetType.TEXT || hasTextControls(it)) }.forEach { widget ->
                        Text(
                            widget.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                        WidgetControls(WidgetScope(store, engine, settings, widget, null, { _, _ -> }, {}))
                    }
                }
            }
        }
    }
}
