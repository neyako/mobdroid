package com.opirl.app.macros

import com.opirl.app.effects.evaluate
import com.opirl.app.settings.Filter
import com.opirl.app.settings.Macro
import com.opirl.app.settings.MacroAction
import com.opirl.app.settings.MacroEventType
import com.opirl.app.settings.MacroFunction
import com.opirl.app.settings.Reaction
import com.opirl.app.settings.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface MacroHost {
    fun selectScene(id: String)
    fun setZoom(ratio: Float)
    fun setFilters(filters: Set<Filter>)
    fun react(reaction: Reaction)
    fun setRecording(on: Boolean)
    fun snapshot()
    fun setMuted(muted: Boolean)
    fun setTorch(on: Boolean)
    fun setAutoSwitcher(id: String?)
    fun sendChat(text: String)
    fun notify(title: String, text: String)
    fun format(text: String): String
}

class MacroEvent(val type: MacroEventType, val sceneId: String? = null)

class MacroRunner(private val scope: CoroutineScope, private val host: MacroHost, private val macros: () -> List<Macro>) {
    private class Running(val job: Job, val events: Channel<MacroEvent>)

    private val jobs = HashMap<String, Running>()
    private val _running = MutableStateFlow(emptySet<String>())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    @Synchronized
    fun start(id: String) {
        if (id in jobs) return
        val macro = macros().firstOrNull { it.id == id } ?: return
        val events = Channel<MacroEvent>(100, BufferOverflow.DROP_OLDEST)
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                run(macro, listOf(macro.id), events)
            } finally {
                finished(id, job)
            }
        }
        jobs[id] = Running(job, events)
        _running.update { it + id }
        job.start()
    }

    @Synchronized
    private fun finished(id: String, job: Job) {
        if (jobs[id]?.job !== job) return
        jobs.remove(id)
        _running.update { it - id }
    }

    @Synchronized
    fun stop(id: String) {
        jobs.remove(id)?.job?.cancel() ?: return
        _running.update { it - id }
    }

    @Synchronized
    fun toggle(id: String) = if (id in jobs) stop(id) else start(id)

    @Synchronized
    fun stopAll() = jobs.keys.toList().forEach(::stop)

    fun startAutomatic() = macros().filter { it.runAtStart }.forEach { start(it.id) }

    @Synchronized
    fun onEvent(event: MacroEvent) = jobs.values.forEach { it.events.trySend(event) }

    private suspend fun run(macro: Macro, stack: List<String>, events: ReceiveChannel<MacroEvent>): Boolean {
        var anyDelay = false
        var runs = 0
        while (true) {
            var delayed = false
            var index = 0
            while (index < macro.actions.size) {
                val action = macro.actions[index++]
                when (action.function) {
                    MacroFunction.SCENE -> action.sceneId?.let(host::selectScene)
                    MacroFunction.ZOOM -> host.setZoom(action.zoom)
                    MacroFunction.FILTERS -> host.setFilters(action.filters)
                    MacroFunction.REACTION -> host.react(action.reaction)
                    MacroFunction.RECORD -> host.setRecording(action.on)
                    MacroFunction.SNAPSHOT -> host.snapshot()
                    MacroFunction.MUTE -> host.setMuted(action.on)
                    MacroFunction.TORCH -> host.setTorch(action.on)
                    MacroFunction.AUTO_SWITCHER -> host.setAutoSwitcher(action.switcherId)
                    MacroFunction.CHAT_MESSAGE -> host.sendChat(host.format(action.text))
                    MacroFunction.NOTIFICATION -> host.notify(macro.name, host.format(action.text))
                    MacroFunction.DELAY -> (action.delaySeconds * 1000).toLong().takeIf { it > 0 }?.let {
                        delay(it)
                        delayed = true
                    }
                    MacroFunction.WAIT_FOR_EVENT -> do {
                        val event = events.receive()
                    } while (!matches(action, event))
                    MacroFunction.IF -> if (!action.comparison.evaluate(host.format(action.value), host.format(action.otherValue))) {
                        index += action.runCount
                    }
                    MacroFunction.MACRO -> macros().firstOrNull { it.id == action.macroId && it.id !in stack }?.let {
                        if (run(it, stack + it.id, events)) delayed = true
                    }
                }
            }
            anyDelay = anyDelay || delayed
            runs++
            val again = when (macro.repeat) {
                RepeatMode.OFF -> false
                RepeatMode.COUNT -> runs < macro.repeatCount
                RepeatMode.FOREVER -> true
            }
            if (!again) return anyDelay
            if (!delayed) delay(1000)
        }
    }

    private fun matches(action: MacroAction, event: MacroEvent) =
        event.type == action.event && (action.sceneId == null || event.type != MacroEventType.SWITCH_SCENE || event.sceneId == action.sceneId)
}
