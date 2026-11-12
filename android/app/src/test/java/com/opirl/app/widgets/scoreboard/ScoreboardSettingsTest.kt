package com.opirl.app.widgets.scoreboard

import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class ScoreboardSettingsTest {
    private val store = SettingsStore(File(createTempDirectory().toFile(), "settings.json"), CoroutineScope(Job()))

    private fun roundTrip(scoreboard: ScoreboardSettings): ScoreboardSettings {
        val widget = Widget(id = "w", type = WidgetType.SCOREBOARD, scoreboard = scoreboard)
        store.update { it.copy(widgets = listOf(widget)) }
        return store.decode(store.exportJson()).widgets.single().scoreboard
    }

    @Test
    fun scoresAndARunningClockSurviveARestart() {
        val template = modularTemplate(ScoreboardSport.TENNIS)
        var modular = ScoreboardSettings().withSport(ScoreboardSport.TENNIS).modular
        repeat(5) { modular = modular.adjusted(template, Side.HOME, ControlKey.PRIMARY, 1) }
        modular = modular.copy(clock = ScoreboardClock(maximum = 90, seconds = 754, startedAt = 1_800_000_000_000L))
        val padel = PadelScoreboard().increment(Side.HOME).increment(Side.HOME).increment(Side.AWAY)
        val generic = GenericScoreboard().increment(Side.AWAY)
        val before = ScoreboardSettings(sport = ScoreboardSport.TENNIS, modular = modular, padel = padel, generic = generic)
        assertEquals(before, roundTrip(before))
    }

    @Test
    fun settingsWithoutTheScoreboardKeysTakeTheDefaults() {
        val settings = store.decode("""{"widgets":[{"id":"w","type":"SCOREBOARD","scoreboard":{"sport":"HOCKEY","future":1}}]}""")
        val scoreboard = settings.widgets.single().scoreboard
        assertEquals(ScoreboardSport.HOCKEY, scoreboard.sport)
        assertEquals(ScoreboardSettings().padel, scoreboard.padel)
        assertEquals(350, scoreboard.modular.width)
    }
}
