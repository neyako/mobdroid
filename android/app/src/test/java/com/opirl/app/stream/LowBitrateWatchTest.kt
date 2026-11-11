package com.opirl.app.stream

import kotlin.test.Test
import kotlin.test.assertEquals

class LowBitrateWatchTest {
    private fun fired(vararg kbps: Int): List<Boolean> {
        val watch = LowBitrateWatch()
        return kbps.map { watch.update(it * 1000L) }
    }

    @Test
    fun `fires once after three low samples in a row`() {
        assertEquals(listOf(false, false, true, false, false), fired(300, 200, 100, 50, 50))
    }

    @Test
    fun `a good sample in between restarts the count`() {
        assertEquals(listOf(false, false, false, false, false, true), fired(300, 300, 600, 300, 300, 300))
    }

    @Test
    fun `fires again only after the bitrate has recovered to twice the threshold`() {
        assertEquals(
            listOf(false, false, true, false, false, false, false, false, false, false, false, false, true),
            fired(100, 100, 100, 700, 100, 100, 100, 1200, 1200, 1200, 100, 100, 100),
        )
    }
}
