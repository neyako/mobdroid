package com.opirl.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.engine.moblink.MoblinkRelayStatus
import com.opirl.app.moblink.relayStatusText
import com.opirl.app.remote.isWebSocketUrl
import com.opirl.app.remote.relayStreamerUrl
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.ControllerAction
import com.opirl.app.settings.ControllerFunction
import com.opirl.app.settings.GamepadButton
import com.opirl.app.settings.KeyboardKey
import com.opirl.app.settings.MoblinkSettings
import com.opirl.app.settings.RemoteControlSettings
import com.opirl.app.settings.STREAM_DECK_KEYS
import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.StreamDeckKey
import com.opirl.app.settings.StreamDeckLayout
import com.opirl.app.settings.StreamDeckModel
import com.opirl.app.settings.createDeepLink
import com.opirl.app.stream.StreamEngine
import java.net.Inet4Address
import java.net.NetworkInterface

internal sealed interface ActionTarget {
    data class Gamepad(val button: GamepadButton) : ActionTarget

    data class Key(val id: String) : ActionTarget

    data class Deck(val layoutId: String, val index: Int) : ActionTarget
}

internal fun AppSettings.action(target: ActionTarget): ControllerAction? = when (target) {
    is ActionTarget.Gamepad -> gamepad[target.button] ?: ControllerAction()
    is ActionTarget.Key -> keyboard.firstOrNull { it.id == target.id }?.action
    is ActionTarget.Deck -> streamDecks.firstOrNull { it.id == target.layoutId }?.key(target.index)?.action
}

internal fun AppSettings.withAction(target: ActionTarget, action: ControllerAction): AppSettings = when (target) {
    is ActionTarget.Gamepad -> copy(gamepad = gamepad + (target.button to action))
    is ActionTarget.Key -> copy(keyboard = keyboard.map { if (it.id == target.id) it.copy(action = action) else it })
    is ActionTarget.Deck -> withDeck(target.layoutId) { it.withKey(target.index, it.key(target.index).copy(action = action)) }
}

internal fun AppSettings.withDeck(id: String, change: (StreamDeckLayout) -> StreamDeckLayout): AppSettings =
    copy(streamDecks = streamDecks.map { if (it.id == id) change(it) else it })

fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
}.getOrDefault(emptyList())

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("opIRL", text))
}

private fun share(context: Context, text: String) {
    runCatching {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
internal fun LocalNetworkAccess(engine: StreamEngine) {
    if (Build.VERSION.SDK_INT < 37) return
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        if (allowed) engine.restartConnections()
    }
    if (!granted) {
        Group { row { ActionRow(it, stringResource(R.string.allow_local_network), Icons.Rounded.Lan) { launcher.launch(LOCAL_NETWORK) } } }
    }
}

@Composable
private fun onOff(on: Boolean) = stringResource(if (on) R.string.on else R.string.off)

@Composable
internal fun actionLabel(settings: AppSettings, action: ControllerAction): String = when (action.function) {
    ControllerFunction.SCENE -> settings.scenes.firstOrNull { it.id == action.sceneId }?.name
    ControllerFunction.WIDGET -> settings.widgets.firstOrNull { it.id == action.widgetId }?.name
    ControllerFunction.STREAM_DECK_LAYOUT -> settings.streamDecks.firstOrNull { it.id == action.layoutId }?.name
    else -> null
} ?: stringResource(action.function.label)

@Composable
private fun PortRow(shapes: androidx.compose.material3.ListItemShapes, port: Int, onChange: (Int) -> Unit) {
    TextRow(shapes, stringResource(R.string.port), if (port > 0) "$port" else "", keyboard = KeyboardType.Number) { text ->
        onChange(text.filter(Char::isDigit).take(5).toIntOrNull()?.coerceAtMost(65535) ?: 0)
    }
}

@Composable
internal fun RemoteControlPage(store: SettingsStore, settings: AppSettings, engine: StreamEngine, push: (Route) -> Unit) {
    val remote = settings.remoteControl
    val status by engine.remote.status.collectAsStateWithLifecycle()
    LocalNetworkAccess(engine)
    Group {
        row { shapes ->
            TextRow(shapes, stringResource(R.string.password), remote.password) { text ->
                store.update { it.copy(remoteControl = it.remoteControl.copy(password = text.trim())) }
            }
        }
        status?.let { current -> row { ValueRow(it, stringResource(R.string.status), current.text) } }
    }
    Group {
        row { NavRow(it, stringResource(R.string.remote_streamer), onOff(remote.streamer)) { push(Route.RemoteStreamer) } }
        row { NavRow(it, stringResource(R.string.remote_assistant), onOff(remote.assistant)) { push(Route.RemoteAssistant) } }
        row { NavRow(it, stringResource(R.string.remote_web), onOff(remote.web)) { push(Route.RemoteWeb) } }
    }
}

@Composable
internal fun RemoteStreamerPage(store: SettingsStore, remote: RemoteControlSettings) {
    val update = { changed: RemoteControlSettings -> store.update { it.copy(remoteControl = changed) } }
    Group {
        row { SwitchRow(it, stringResource(R.string.enabled), remote.streamer) { on -> update(remote.copy(streamer = on)) } }
        row { shapes ->
            TextRow(shapes, stringResource(R.string.remote_assistant_url), remote.streamerUrl, "ws://192.168.1.20:2345", keyboard = KeyboardType.Uri) {
                update(remote.copy(streamerUrl = it.trim()))
            }
        }
    }
    if (remote.streamer && !isWebSocketUrl(remote.streamerUrl)) Note(stringResource(R.string.message_websocket_url))
}

@Composable
internal fun RemoteAssistantPage(store: SettingsStore, remote: RemoteControlSettings) {
    val context = LocalContext.current
    val update = { changed: RemoteControlSettings -> store.update { it.copy(remoteControl = changed) } }
    Group {
        row { SwitchRow(it, stringResource(R.string.enabled), remote.assistant) { on -> update(remote.copy(assistant = on)) } }
        row { PortRow(it, remote.assistantPort) { port -> update(remote.copy(assistantPort = port)) } }
    }
    if (remote.assistant && remote.assistantPort >= 1024) {
        val addresses = remember { localAddresses() }
        if (addresses.isNotEmpty()) {
            Group {
                addresses.forEach { address ->
                    val url = "ws://$address:${remote.assistantPort}"
                    row { ActionRow(it, url, Icons.Rounded.ContentCopy) { copy(context, url) } }
                }
            }
        }
    }
    Group {
        row { SwitchRow(it, stringResource(R.string.remote_relay), remote.relay) { on -> update(remote.copy(relay = on)) } }
        if (remote.relay) {
            row { shapes ->
                TextRow(shapes, stringResource(R.string.remote_relay_url), remote.relayUrl, keyboard = KeyboardType.Uri) {
                    update(remote.copy(relayUrl = it.trim()))
                }
            }
            row { shapes ->
                TextRow(shapes, stringResource(R.string.remote_bridge_id), remote.bridgeId) { update(remote.copy(bridgeId = it.trim())) }
            }
            if (isWebSocketUrl(remote.relayUrl) && remote.bridgeId.isNotEmpty()) {
                val url = relayStreamerUrl(remote)
                row { ActionRow(it, stringResource(R.string.remote_copy_streamer_url), Icons.Rounded.ContentCopy) { copy(context, url) } }
            }
        }
    }
}

@Composable
internal fun RemoteWebPage(store: SettingsStore, remote: RemoteControlSettings) {
    val context = LocalContext.current
    val update = { changed: RemoteControlSettings -> store.update { it.copy(remoteControl = changed) } }
    Group {
        row { SwitchRow(it, stringResource(R.string.enabled), remote.web) { on -> update(remote.copy(web = on)) } }
        row { PortRow(it, remote.webPort) { port -> update(remote.copy(webPort = port)) } }
    }
    if (remote.web && remote.webPort in 1024..65534) {
        val addresses = remember { localAddresses() }
        if (addresses.isNotEmpty()) {
            Group {
                addresses.forEach { address ->
                    val url = "http://$address:${remote.webPort}"
                    row { ActionRow(it, url, Icons.Rounded.ContentCopy) { copy(context, url) } }
                }
            }
        }
    } else if (remote.web) {
        Note(stringResource(R.string.message_port_range))
    }
}

@Composable
internal fun MoblinkPage(store: SettingsStore, settings: AppSettings, engine: StreamEngine, push: (Route) -> Unit) {
    val moblink = settings.moblink
    val status by engine.moblink.status.collectAsStateWithLifecycle()
    LocalNetworkAccess(engine)
    Group {
        row { shapes ->
            TextRow(shapes, stringResource(R.string.password), moblink.password) { text ->
                store.update { it.copy(moblink = it.moblink.copy(password = text.trim())) }
            }
        }
        status?.takeIf { it.text.isNotEmpty() }?.let { current -> row { ValueRow(it, stringResource(R.string.status), current.text) } }
    }
    Group {
        row { NavRow(it, stringResource(R.string.remote_streamer), onOff(moblink.streamer)) { push(Route.MoblinkStreamer) } }
        row { NavRow(it, stringResource(R.string.moblink_relay), onOff(moblink.relay)) { push(Route.MoblinkRelay) } }
    }
}

@Composable
internal fun MoblinkStreamerPage(store: SettingsStore, moblink: MoblinkSettings, relays: List<MoblinkRelayStatus>) {
    val update = { changed: MoblinkSettings -> store.update { it.copy(moblink = changed) } }
    Group {
        row { SwitchRow(it, stringResource(R.string.enabled), moblink.streamer) { on -> update(moblink.copy(streamer = on)) } }
        row { PortRow(it, moblink.streamerPort) { port -> update(moblink.copy(streamerPort = port)) } }
    }
    if (relays.isNotEmpty()) {
        Group { relays.forEach { relay -> row { ValueRow(it, relayStatusText(relay).first, "") } } }
    }
}

@Composable
internal fun MoblinkRelayPage(store: SettingsStore, moblink: MoblinkSettings) {
    val update = { changed: MoblinkSettings -> store.update { it.copy(moblink = changed) } }
    Group {
        row { SwitchRow(it, stringResource(R.string.enabled), moblink.relay) { on -> update(moblink.copy(relay = on)) } }
        row { TextRow(it, stringResource(R.string.name), moblink.relayName) { name -> update(moblink.copy(relayName = name.take(30))) } }
    }
    Group {
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.moblink_manual), moblink.relayManual) { on -> update(moblink.copy(relayManual = on)) }
        }
        if (moblink.relayManual) {
            row { shapes ->
                TextRow(shapes, stringResource(R.string.moblink_streamer_url), moblink.relayUrl, "ws://192.168.1.20:7777", keyboard = KeyboardType.Uri) {
                    update(moblink.copy(relayUrl = it.trim()))
                }
            }
        }
    }
}

@Composable
internal fun GamepadPage(settings: AppSettings, engine: StreamEngine, push: (Route) -> Unit) {
    val count by engine.controls.gamepadCount.collectAsStateWithLifecycle()
    Group {
        row { ValueRow(it, stringResource(R.string.gamepads_connected), "$count") }
    }
    Group {
        GamepadButton.entries.forEach { button ->
            row { shapes ->
                NavRow(shapes, button.label, actionLabel(settings, settings.gamepad[button] ?: ControllerAction())) {
                    push(Route.ActionPage(ActionTarget.Gamepad(button)))
                }
            }
        }
    }
}

@Composable
internal fun KeyboardPage(store: SettingsStore, settings: AppSettings, push: (Route) -> Unit) {
    Group {
        settings.keyboard.forEach { key ->
            row { shapes ->
                NavRow(shapes, key.key.ifEmpty { stringResource(R.string.not_set) }, actionLabel(settings, key.action)) {
                    push(Route.KeyboardKeyPage(key.id))
                }
            }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.add_key), Icons.Rounded.Add) {
                val key = KeyboardKey()
                store.update { it.copy(keyboard = it.keyboard + key) }
                push(Route.KeyboardKeyPage(key.id))
            }
        }
    }
}

@Composable
internal fun KeyboardKeyPage(store: SettingsStore, settings: AppSettings, key: KeyboardKey, push: (Route) -> Unit, pop: () -> Unit) {
    Group {
        row { shapes ->
            TextRow(shapes, stringResource(R.string.key), key.key) { text ->
                val char = text.removePrefix(key.key).ifEmpty { text }.takeLast(1)
                store.update { s -> s.copy(keyboard = s.keyboard.map { if (it.id == key.id) it.copy(key = char) else it }) }
            }
        }
        row { shapes ->
            NavRow(shapes, stringResource(R.string.function), actionLabel(settings, key.action)) {
                push(Route.ActionPage(ActionTarget.Key(key.id)))
            }
        }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.delete), Icons.Rounded.Delete, MaterialTheme.colorScheme.error) {
                pop()
                store.update { s -> s.copy(keyboard = s.keyboard.filterNot { it.id == key.id }) }
            }
        }
    }
}

@Composable
internal fun StreamDecksPage(store: SettingsStore, settings: AppSettings, engine: StreamEngine, push: (Route) -> Unit) {
    val connected by engine.streamDecks.connected.collectAsStateWithLifecycle()
    Group {
        row { shapes ->
            ValueRow(shapes, stringResource(R.string.status), connected?.let { "Stream Deck ${it.name.lowercase().replaceFirstChar(Char::uppercase)}" } ?: stringResource(R.string.not_connected))
        }
    }
    Group {
        settings.streamDecks.forEach { layout ->
            row { shapes ->
                NavRow(
                    shapes,
                    layout.name,
                    layout.model.label,
                    leading = {
                        RadioButton(layout.id == settings.selectedStreamDeckId, {
                            store.update { it.copy(selectedStreamDeckId = if (it.selectedStreamDeckId == layout.id) null else layout.id) }
                        })
                    },
                ) { push(Route.StreamDeckPage(layout.id)) }
            }
        }
        row { shapes ->
            val name = stringResource(R.string.layout_default_name, settings.streamDecks.size + 1)
            ActionRow(shapes, stringResource(R.string.create_layout), Icons.Rounded.Add) {
                val layout = StreamDeckLayout(name = name)
                store.update { it.copy(streamDecks = it.streamDecks + layout, selectedStreamDeckId = it.selectedStreamDeckId ?: layout.id) }
                push(Route.StreamDeckPage(layout.id))
            }
        }
    }
}

@Composable
internal fun StreamDeckPage(store: SettingsStore, settings: AppSettings, layout: StreamDeckLayout, push: (Route) -> Unit, pop: () -> Unit) {
    Group {
        row { TextRow(it, stringResource(R.string.name), layout.name) { name -> store.update { s -> s.withDeck(layout.id) { d -> d.copy(name = name) } } } }
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.model), StreamDeckModel.entries, layout.model, { it.label }) { model ->
                store.update { s -> s.withDeck(layout.id) { it.copy(model = model) } }
            }
        }
    }
    Group {
        repeat(minOf(layout.model.keys, STREAM_DECK_KEYS)) { index ->
            val key = layout.key(index)
            row { shapes ->
                NavRow(
                    shapes,
                    stringResource(R.string.key_position, index / layout.model.columns + 1, index % layout.model.columns + 1),
                    key.text.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: actionLabel(settings, key.action),
                ) { push(Route.StreamDeckKeyPage(layout.id, index)) }
            }
        }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.delete), Icons.Rounded.Delete, MaterialTheme.colorScheme.error) {
                pop()
                store.update { s ->
                    s.copy(
                        streamDecks = s.streamDecks.filterNot { it.id == layout.id },
                        selectedStreamDeckId = s.selectedStreamDeckId.takeIf { it != layout.id },
                    )
                }
            }
        }
    }
}

private val DECK_COLORS = listOf(0x000000, 0xD32F2F, 0xF57C00, 0x388E3C, 0x1976D2, 0x7B1FA2, 0xFFFFFF)

@Composable
internal fun StreamDeckKeyPage(store: SettingsStore, settings: AppSettings, layout: StreamDeckLayout, index: Int, push: (Route) -> Unit) {
    val key = layout.key(index)
    val update = { changed: StreamDeckKey -> store.update { s -> s.withDeck(layout.id) { it.withKey(index, changed) } } }
    val colors = listOf(
        R.string.color_black,
        R.string.color_red,
        R.string.color_orange,
        R.string.color_green,
        R.string.color_blue,
        R.string.color_purple,
        R.string.color_white,
    )
    Group {
        row { TextRow(it, stringResource(R.string.widget_text), key.text) { text -> update(key.copy(text = text.take(40))) } }
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.color), DECK_COLORS, key.color, { stringResource(colors[DECK_COLORS.indexOf(it)]) }) {
                update(key.copy(color = it))
            }
        }
        row { shapes ->
            NavRow(shapes, stringResource(R.string.function), actionLabel(settings, key.action)) {
                push(Route.ActionPage(ActionTarget.Deck(layout.id, index)))
            }
        }
    }
}

@Composable
internal fun ActionPage(store: SettingsStore, settings: AppSettings, target: ActionTarget) {
    val action = settings.action(target) ?: return
    val update = { changed: ControllerAction -> store.update { it.withAction(target, changed) } }
    val choices = when (action.function) {
        ControllerFunction.SCENE -> settings.scenes.map { Triple(it.id, it.name, action.sceneId) }
        ControllerFunction.WIDGET -> settings.widgets.map { Triple(it.id, it.name, action.widgetId) }
        ControllerFunction.STREAM_DECK_LAYOUT -> settings.streamDecks.map { Triple(it.id, it.name, action.layoutId) }
        else -> emptyList()
    }
    if (choices.isNotEmpty()) {
        Group {
            choices.forEach { (id, name, selected) ->
                row { shapes ->
                    NavRow(shapes, name, leading = { RadioButton(id == selected, null) }) {
                        update(
                            when (action.function) {
                                ControllerFunction.SCENE -> action.copy(sceneId = id)
                                ControllerFunction.WIDGET -> action.copy(widgetId = id)
                                else -> action.copy(layoutId = id)
                            },
                        )
                    }
                }
            }
        }
    }
    Group {
        ControllerFunction.entries.forEach { function ->
            row { shapes ->
                NavRow(shapes, stringResource(function.label), leading = { RadioButton(function == action.function, null) }) {
                    update(ControllerAction(function))
                }
            }
        }
    }
}

@Composable
internal fun ShareStreamsPage(settings: AppSettings) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(setOf(settings.selectedProfile.id)) }
    val link = createDeepLink(settings.profiles.filter { it.id in chosen })
    Note(stringResource(R.string.share_streams_note))
    Group {
        settings.profiles.forEach { profile ->
            row { shapes ->
                SwitchRow(shapes, profile.name, profile.id in chosen) { on -> chosen = if (on) chosen + profile.id else chosen - profile.id }
            }
        }
    }
    Group {
        row { ActionRow(it, stringResource(R.string.share_link), Icons.Rounded.Share, enabled = chosen.isNotEmpty()) { share(context, link) } }
        row { ActionRow(it, stringResource(R.string.copy_link), Icons.Rounded.ContentCopy, enabled = chosen.isNotEmpty()) { copy(context, link) } }
    }
}

@Composable
internal fun ImportLinkRow(shapes: androidx.compose.material3.ListItemShapes, engine: StreamEngine, enabled: Boolean) {
    val context = LocalContext.current
    ActionRow(shapes, stringResource(R.string.import_link), Icons.Rounded.ContentPaste, enabled = enabled) {
        val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        engine.pendingLink.value = text?.trim().orEmpty()
    }
}
