package com.opirl.app.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MicsTest {
    private val ports = listOf(
        MicPort(MicKind.PHONE, "SM-F731B", "bottom"),
        MicPort(MicKind.PHONE, "SM-F731B", "back"),
        MicPort(MicKind.USB, "Wireless GO II", "card=1;device=0"),
        MicPort(MicKind.BLUETOOTH, "AirPods", ""),
    )

    @Test
    fun `phone mics are told apart by where they are`() {
        assertEquals(listOf("bottom", "back", "Wireless GO II", "AirPods"), listMics(ports).map { it.name })
        assertEquals("", listMics(ports.drop(1)).first().name)
    }

    @Test
    fun `a saved mic is found by its key and an old setting by its kind`() {
        val mics = listMics(ports)
        assertEquals("back", findMic(mics, "PHONE:back")?.name)
        assertEquals("Wireless GO II", findMic(mics, "USB")?.name)
        assertNull(findMic(mics, "WIRED"))
        assertNull(findMic(mics, null))
        assertNull(findMic(mics, "USB:Unplugged:card=2"))
    }
}
