package com.opirl.app.scenes

import com.opirl.app.settings.AutoSwitcher
import com.opirl.app.settings.SwitcherScene
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AutoSceneSwitcherTest {
    private val scenes = listOf("a", "b", "c").map { SwitcherScene(id = "entry-$it", sceneId = it, seconds = 5) }

    private fun order(switcher: AutoSwitcher, count: Int, random: Random = Random(1), exists: (String) -> Boolean = { true }): List<String?> {
        val switching = AutoSceneSwitcher(random)
        return List(count) { switching.next(switcher, exists)?.sceneId }
    }

    @Test
    fun scenesAreShownInOrderAndStartOver() {
        assertEquals(listOf("a", "b", "c", "a", "b"), order(AutoSwitcher(scenes = scenes), 5))
    }

    @Test
    fun deletedAndUnsetScenesAreSkipped() {
        val switcher = AutoSwitcher(scenes = scenes + SwitcherScene(id = "unset"))
        assertEquals(listOf("a", "c", "a"), order(switcher, 3) { it != "b" })
    }

    @Test
    fun aSwitcherWithNothingToShowGivesNothing() {
        assertNull(AutoSceneSwitcher().next(AutoSwitcher(), { true }))
        assertEquals(listOf<String?>(null, null), order(AutoSwitcher(scenes = scenes), 2) { false })
    }

    @Test
    fun aShuffledRoundShowsEverySceneAndNeverTheSameSceneTwiceInARow() {
        repeat(20) { seed ->
            val shown = order(AutoSwitcher(shuffle = true, scenes = scenes), 12, Random(seed))
            shown.chunked(3).forEach { assertEquals(setOf<String?>("a", "b", "c"), it.toSet()) }
            shown.zipWithNext().forEach { (first, second) -> assertNotEquals(first, second) }
        }
    }
}
