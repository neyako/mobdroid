package com.opirl.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.opirl.app.R
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.AutoSwitcher
import com.opirl.app.settings.Comparison
import com.opirl.app.settings.Filter
import com.opirl.app.settings.Macro
import com.opirl.app.settings.MacroAction
import com.opirl.app.settings.MacroEventType
import com.opirl.app.settings.MacroFunction
import com.opirl.app.settings.Reaction
import com.opirl.app.settings.RepeatMode
import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.SwitcherScene

private fun SettingsStore.updateSwitcher(id: String, change: (AutoSwitcher) -> AutoSwitcher) = update { s ->
    s.copy(autoSwitchers = s.autoSwitchers.map { if (it.id == id) change(it) else it })
}

private fun SettingsStore.updateMacro(id: String, change: (Macro) -> Macro) = update { s ->
    s.copy(macros = s.macros.map { if (it.id == id) change(it) else it })
}

private fun SettingsStore.updateAction(macroId: String, id: String, change: (MacroAction) -> MacroAction) =
    updateMacro(macroId) { macro -> macro.copy(actions = macro.actions.map { if (it.id == id) change(it) else it }) }

@Composable
internal fun AutomationGroups(store: SettingsStore, settings: AppSettings, push: (Route) -> Unit) {
    Group {
        settings.autoSwitchers.forEach { switcher ->
            row { shapes ->
                NavRow(shapes, switcher.name, pluralStringResource(R.plurals.scene_count, switcher.scenes.size, switcher.scenes.size)) {
                    push(Route.SwitcherPage(switcher.id))
                }
            }
        }
        row { shapes ->
            val name = stringResource(R.string.switcher_default_name, settings.autoSwitchers.size + 1)
            ActionRow(shapes, stringResource(R.string.create_auto_switcher), Icons.Rounded.Add) {
                val switcher = AutoSwitcher(name = name)
                store.update { it.copy(autoSwitchers = it.autoSwitchers + switcher) }
                push(Route.SwitcherPage(switcher.id))
            }
        }
    }
    Group {
        settings.macros.forEach { macro ->
            row { shapes ->
                NavRow(shapes, macro.name, pluralStringResource(R.plurals.action_count, macro.actions.size, macro.actions.size)) {
                    push(Route.MacroPage(macro.id))
                }
            }
        }
        row { shapes ->
            val name = stringResource(R.string.macro_default_name, settings.macros.size + 1)
            ActionRow(shapes, stringResource(R.string.create_macro), Icons.Rounded.Add) {
                val macro = Macro(name = name)
                store.update { it.copy(macros = it.macros + macro) }
                push(Route.MacroPage(macro.id))
            }
        }
    }
}

@Composable
private fun sceneName(settings: AppSettings, id: String?) =
    settings.scenes.firstOrNull { it.id == id }?.name ?: stringResource(R.string.not_set)

@Composable
internal fun SwitcherPage(store: SettingsStore, settings: AppSettings, switcher: AutoSwitcher, push: (Route) -> Unit, pop: () -> Unit) {
    Group {
        row { TextRow(it, stringResource(R.string.name), switcher.name) { name -> store.updateSwitcher(switcher.id) { it.copy(name = name) } } }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.shuffle), switcher.shuffle) { on -> store.updateSwitcher(switcher.id) { it.copy(shuffle = on) } }
        }
    }
    Group {
        switcher.scenes.forEach { entry ->
            row { shapes ->
                RemovableRow(shapes, sceneName(settings, entry.sceneId), "${entry.seconds} s", { push(Route.SwitcherScenePage(switcher.id, entry.id)) }) {
                    store.updateSwitcher(switcher.id) { it.copy(scenes = it.scenes - entry) }
                }
            }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.add_scene), Icons.Rounded.Add) {
                val used = switcher.scenes.map { it.sceneId }
                val entry = SwitcherScene(sceneId = (settings.scenes.firstOrNull { it.id !in used } ?: settings.scenes.firstOrNull())?.id)
                store.updateSwitcher(switcher.id) { it.copy(scenes = it.scenes + entry) }
            }
        }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.delete), Icons.Rounded.Delete, MaterialTheme.colorScheme.error) {
                pop()
                store.update { s ->
                    s.copy(
                        autoSwitchers = s.autoSwitchers.filterNot { it.id == switcher.id },
                        autoSwitcherId = s.autoSwitcherId.takeIf { it != switcher.id },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SwitcherScenePage(store: SettingsStore, settings: AppSettings, switcher: AutoSwitcher, entry: SwitcherScene) {
    val update = { change: (SwitcherScene) -> SwitcherScene ->
        store.updateSwitcher(switcher.id) { current -> current.copy(scenes = current.scenes.map { if (it.id == entry.id) change(it) else it }) }
    }
    Group {
        settings.scenes.forEach { scene ->
            row { PickRow(it, scene.name, scene.id == entry.sceneId) { update { current -> current.copy(sceneId = scene.id) } } }
        }
    }
    Group {
        row { shapes ->
            NumberRow(shapes, stringResource(R.string.seconds), entry.seconds, false, { it.toIntOrNull()?.takeIf { value -> value in 1..86_400 } }) { value ->
                update { it.copy(seconds = value) }
            }
        }
    }
}

@Composable
private fun comparisonLabel(comparison: Comparison) =
    if (comparison == Comparison.CONTAINS) stringResource(R.string.comparison_contains) else comparison.label

@Composable
private fun onOff(on: Boolean) = stringResource(if (on) R.string.on else R.string.off)

@Composable
private fun actionSummary(settings: AppSettings, action: MacroAction): String = when (action.function) {
    MacroFunction.SCENE -> sceneName(settings, action.sceneId)
    MacroFunction.ZOOM -> formatZoom(action.zoom)
    MacroFunction.FILTERS -> if (action.filters.isEmpty()) {
        stringResource(R.string.off)
    } else {
        action.filters.map { stringResource(it.label) }.joinToString(", ")
    }
    MacroFunction.REACTION -> stringResource(action.reaction.label)
    MacroFunction.RECORD, MacroFunction.MUTE, MacroFunction.TORCH -> onOff(action.on)
    MacroFunction.SNAPSHOT -> ""
    MacroFunction.AUTO_SWITCHER -> settings.autoSwitchers.firstOrNull { it.id == action.switcherId }?.name ?: stringResource(R.string.off)
    MacroFunction.CHAT_MESSAGE, MacroFunction.NOTIFICATION -> action.text
    MacroFunction.DELAY -> "${action.delaySeconds.toString().removeSuffix(".0")} s"
    MacroFunction.WAIT_FOR_EVENT -> stringResource(action.event.label)
    MacroFunction.IF -> "${action.value} ${comparisonLabel(action.comparison)} ${action.otherValue}"
    MacroFunction.MACRO -> settings.macros.firstOrNull { it.id == action.macroId }?.name ?: stringResource(R.string.not_set)
}

@Composable
internal fun MacroPage(store: SettingsStore, settings: AppSettings, macro: Macro, push: (Route) -> Unit, pop: () -> Unit) {
    Group {
        row { TextRow(it, stringResource(R.string.name), macro.name) { name -> store.updateMacro(macro.id) { it.copy(name = name) } } }
    }
    Group {
        macro.actions.forEach { action ->
            row { shapes ->
                RemovableRow(
                    shapes,
                    stringResource(action.function.label),
                    actionSummary(settings, action),
                    { push(Route.MacroActionPage(macro.id, action.id)) },
                ) { store.updateMacro(macro.id) { it.copy(actions = it.actions - action) } }
            }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.add_action), Icons.Rounded.Add) {
                val action = MacroAction(sceneId = settings.selectedScene?.id)
                store.updateMacro(macro.id) { it.copy(actions = it.actions + action) }
                push(Route.MacroActionPage(macro.id, action.id))
                push(Route.MacroFunctionPage(macro.id, action.id))
            }
        }
    }
    Group {
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.repeat), RepeatMode.entries, macro.repeat, { stringResource(it.label) }) { mode ->
                store.updateMacro(macro.id) { it.copy(repeat = mode) }
            }
        }
        if (macro.repeat == RepeatMode.COUNT) {
            row { shapes ->
                SliderRow(shapes, stringResource(R.string.repeat_times), macro.repeatCount, 2..50, "${macro.repeatCount}") { value ->
                    store.updateMacro(macro.id) { it.copy(repeatCount = value) }
                }
            }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.run_at_start), macro.runAtStart) { on -> store.updateMacro(macro.id) { it.copy(runAtStart = on) } }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.close_panel_on_run), macro.closePanel) { on ->
                store.updateMacro(macro.id) { it.copy(closePanel = on) }
            }
        }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.delete), Icons.Rounded.Delete, MaterialTheme.colorScheme.error) {
                pop()
                store.update { s -> s.copy(macros = s.macros.filterNot { it.id == macro.id }) }
            }
        }
    }
}

@Composable
internal fun MacroFunctionPage(store: SettingsStore, macro: Macro, action: MacroAction, pop: () -> Unit) {
    Group {
        MacroFunction.entries.forEach { function ->
            row { shapes ->
                PickRow(shapes, stringResource(function.label), function == action.function) {
                    store.updateAction(macro.id, action.id) { it.copy(function = function) }
                    pop()
                }
            }
        }
    }
}

@Composable
internal fun MacroActionPage(store: SettingsStore, settings: AppSettings, macro: Macro, action: MacroAction, push: (Route) -> Unit) {
    val update = { change: (MacroAction) -> MacroAction -> store.updateAction(macro.id, action.id, change) }
    Group {
        row { shapes ->
            NavRow(shapes, stringResource(R.string.macro_function), stringResource(action.function.label)) {
                push(Route.MacroFunctionPage(macro.id, action.id))
            }
        }
    }
    when (action.function) {
        MacroFunction.SCENE -> Group {
            settings.scenes.forEach { scene ->
                row { PickRow(it, scene.name, scene.id == action.sceneId) { update { current -> current.copy(sceneId = scene.id) } } }
            }
        }
        MacroFunction.ZOOM -> Group {
            row { shapes ->
                SliderRow(shapes, stringResource(R.string.zoom), (action.zoom * 10).toInt(), 5..100, formatZoom(action.zoom)) { value ->
                    update { it.copy(zoom = value / 10f) }
                }
            }
        }
        MacroFunction.FILTERS -> Group {
            Filter.entries.forEach { filter ->
                row { shapes ->
                    SwitchRow(shapes, stringResource(filter.label), filter in action.filters) { on ->
                        update { it.copy(filters = if (on) it.filters + filter else it.filters - filter) }
                    }
                }
            }
        }
        MacroFunction.REACTION -> Group {
            row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.macro_reaction), Reaction.entries, action.reaction, { stringResource(it.label) }) { reaction ->
                    update { it.copy(reaction = reaction) }
                }
            }
        }
        MacroFunction.RECORD, MacroFunction.MUTE, MacroFunction.TORCH -> Group {
            row { shapes ->
                ChoiceRow(shapes, stringResource(action.function.label), listOf(true, false), action.on, { onOff(it) }) { on ->
                    update { it.copy(on = on) }
                }
            }
        }
        MacroFunction.SNAPSHOT -> Unit
        MacroFunction.AUTO_SWITCHER -> Group {
            (listOf(null) + settings.autoSwitchers).forEach { switcher ->
                row { shapes ->
                    PickRow(shapes, switcher?.name ?: stringResource(R.string.off), switcher?.id == action.switcherId) {
                        update { it.copy(switcherId = switcher?.id) }
                    }
                }
            }
        }
        MacroFunction.CHAT_MESSAGE, MacroFunction.NOTIFICATION -> Group {
            row { shapes ->
                TextRow(shapes, stringResource(R.string.message), action.text, stringResource(R.string.text_hint)) { text -> update { it.copy(text = text) } }
            }
        }
        MacroFunction.DELAY -> Group {
            row { shapes ->
                NumberRow(shapes, stringResource(R.string.seconds), action.delaySeconds, true, { it.toFloatOrNull()?.takeIf { value -> value in 0f..86_400f } }) { value ->
                    update { it.copy(delaySeconds = value) }
                }
            }
        }
        MacroFunction.WAIT_FOR_EVENT -> {
            Group {
                MacroEventType.entries.forEach { event ->
                    row { PickRow(it, stringResource(event.label), event == action.event) { update { current -> current.copy(event = event) } } }
                }
            }
            if (action.event == MacroEventType.SWITCH_SCENE) {
                Group {
                    row { PickRow(it, stringResource(R.string.any_scene), action.sceneId == null) { update { current -> current.copy(sceneId = null) } } }
                    settings.scenes.forEach { scene ->
                        row { PickRow(it, scene.name, scene.id == action.sceneId) { update { current -> current.copy(sceneId = scene.id) } } }
                    }
                }
            }
        }
        MacroFunction.IF -> Group {
            row { shapes ->
                TextRow(shapes, stringResource(R.string.value), action.value, stringResource(R.string.text_hint)) { text -> update { it.copy(value = text) } }
            }
            row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.comparison), Comparison.entries, action.comparison, { comparisonLabel(it) }) { comparison ->
                    update { it.copy(comparison = comparison) }
                }
            }
            row { shapes ->
                TextRow(shapes, stringResource(R.string.other_value), action.otherValue, stringResource(R.string.text_hint)) { text ->
                    update { it.copy(otherValue = text) }
                }
            }
            row { shapes ->
                SliderRow(shapes, stringResource(R.string.actions_to_run), action.runCount, 1..10, "${action.runCount}") { value ->
                    update { it.copy(runCount = value) }
                }
            }
        }
        MacroFunction.MACRO -> Group {
            settings.macros.filter { it.id != macro.id }.forEach { other ->
                row { PickRow(it, other.name, other.id == action.macroId) { update { current -> current.copy(macroId = other.id) } } }
            }
        }
    }
    val index = macro.actions.indexOfFirst { it.id == action.id }
    val move = { to: Int ->
        store.updateMacro(macro.id) { current ->
            current.copy(actions = current.actions.toMutableList().apply { add(to, removeAt(index)) })
        }
    }
    Group {
        row { ActionRow(it, stringResource(R.string.move_up), Icons.Rounded.ArrowUpward, enabled = index > 0) { move(index - 1) } }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.move_down), Icons.Rounded.ArrowDownward, enabled = index in 0 until macro.actions.lastIndex) {
                move(index + 1)
            }
        }
    }
}
