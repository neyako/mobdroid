package app.mobdroid.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NowPlayingTest {
    private fun pick(vararg sessions: String) = currentSession(sessions.toList(), { it.startsWith("own") }, { it.endsWith("playing") })

    @Test
    fun thePlayingSessionOfAnotherAppWins() {
        assertEquals("b playing", pick("a paused", "own playing", "b playing"))
        assertEquals("a paused", pick("own playing", "a paused", "b paused"))
        assertNull(pick("own playing"))
        assertNull(pick())
    }
}
