package com.opirl.app.macros

import com.opirl.app.settings.Comparison
import com.opirl.app.settings.Filter
import com.opirl.app.settings.Macro
import com.opirl.app.settings.MacroAction
import com.opirl.app.settings.MacroEventType
import com.opirl.app.settings.MacroFunction
import com.opirl.app.settings.Reaction
import com.opirl.app.settings.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MacroRunnerTest {
    private val log = ArrayList<String>()
    private val host = object : MacroHost {
        override fun selectScene(id: String) = log.add("scene $id").let {}

        override fun setZoom(ratio: Float) = log.add("zoom $ratio").let {}

        override fun setFilters(filters: Set<Filter>) = log.add("filters $filters").let {}

        override fun react(reaction: Reaction) = log.add("react $reaction").let {}

        override fun setRecording(on: Boolean) = log.add("record $on").let {}

        override fun snapshot() = log.add("snapshot").let {}

        override fun setMuted(muted: Boolean) = log.add("mute $muted").let {}

        override fun setTorch(on: Boolean) = log.add("torch $on").let {}

        override fun setAutoSwitcher(id: String?) = log.add("switcher $id").let {}

        override fun sendChat(text: String) = log.add("chat $text").let {}

        override fun notify(title: String, text: String) = log.add("notify $title: $text").let {}

        override fun format(text: String) = text.replace("{time}", "12:30")
    }

    private fun scene(id: String) = MacroAction(function = MacroFunction.SCENE, sceneId = id)

    private fun delay(seconds: Float) = MacroAction(function = MacroFunction.DELAY, delaySeconds = seconds)

    private fun TestScope.runner(vararg macros: Macro) = MacroRunner(backgroundScope, host) { macros.toList() }

    @Test
    fun actionsRunInOrderAndADelayHoldsTheRest() = runTest {
        val macro = Macro(
            id = "m",
            name = "Intro",
            actions = listOf(
                scene("a"),
                MacroAction(function = MacroFunction.MUTE, on = true),
                delay(2.5f),
                MacroAction(function = MacroFunction.NOTIFICATION, text = "It is {time}"),
                MacroAction(function = MacroFunction.FILTERS, filters = setOf(Filter.SEPIA)),
            ),
        )
        val runner = runner(macro)
        runner.start("m")
        runCurrent()
        assertEquals(listOf("scene a", "mute true"), log)
        assertEquals(setOf("m"), runner.running.value)
        advanceTimeBy(2501)
        assertEquals(listOf("scene a", "mute true", "notify Intro: It is 12:30", "filters [SEPIA]"), log)
        assertEquals(emptySet(), runner.running.value)
    }

    @Test
    fun aFalseConditionSkipsTheActionsItGuards() = runTest {
        val condition = MacroAction(function = MacroFunction.IF, value = "{time}", comparison = Comparison.LESS, otherValue = "12:00", runCount = 2)
        val runner = runner(Macro(id = "m", actions = listOf(condition, scene("a"), scene("b"), scene("c"))))
        runner.start("m")
        runCurrent()
        assertEquals(listOf("scene c"), log)
    }

    @Test
    fun aMacroWaitsForTheEventItNames() = runTest {
        val wait = MacroAction(function = MacroFunction.WAIT_FOR_EVENT, event = MacroEventType.SWITCH_SCENE, sceneId = "b")
        val runner = runner(Macro(id = "m", actions = listOf(wait, MacroAction(function = MacroFunction.SNAPSHOT))))
        runner.start("m")
        runCurrent()
        runner.onEvent(MacroEvent(MacroEventType.GO_LIVE))
        runner.onEvent(MacroEvent(MacroEventType.SWITCH_SCENE, "a"))
        runCurrent()
        assertEquals(emptyList(), log)
        runner.onEvent(MacroEvent(MacroEventType.SWITCH_SCENE, "b"))
        runCurrent()
        assertEquals(listOf("snapshot"), log)
    }

    @Test
    fun aRepeatedMacroRunsItsCountAndPausesBetweenRunsWithoutADelay() = runTest {
        val runner = runner(Macro(id = "m", actions = listOf(scene("a")), repeat = RepeatMode.COUNT, repeatCount = 3))
        runner.start("m")
        runCurrent()
        assertEquals(1, log.size)
        advanceTimeBy(1001)
        assertEquals(2, log.size)
        advanceTimeBy(5000)
        assertEquals(3, log.size)
        assertEquals(emptySet(), runner.running.value)
    }

    @Test
    fun aZeroDelayStillPausesARepeatedMacro() = runTest {
        val runner = runner(Macro(id = "m", actions = listOf(scene("a"), delay(0f)), repeat = RepeatMode.FOREVER))
        runner.start("m")
        runCurrent()
        assertEquals(1, log.size)
        advanceTimeBy(2001)
        assertEquals(3, log.size)
        runner.stop("m")
    }

    @Test
    fun aMacroRunsAnotherMacroButNeverItself() = runTest {
        val inner = Macro(id = "inner", actions = listOf(scene("b"), MacroAction(function = MacroFunction.MACRO, macroId = "outer")))
        val outer = Macro(id = "outer", actions = listOf(scene("a"), MacroAction(function = MacroFunction.MACRO, macroId = "inner"), scene("c")))
        runner(outer, inner).start("outer")
        runCurrent()
        assertEquals(listOf("scene a", "scene b", "scene c"), log)
    }

    @Test
    fun stoppingAMacroCancelsWhatIsLeft() = runTest {
        val runner = runner(Macro(id = "m", actions = listOf(scene("a"), delay(1f), scene("b")), repeat = RepeatMode.FOREVER))
        runner.toggle("m")
        runCurrent()
        runner.toggle("m")
        advanceTimeBy(10_000)
        assertEquals(listOf("scene a"), log)
        assertEquals(emptySet(), runner.running.value)
    }
}
