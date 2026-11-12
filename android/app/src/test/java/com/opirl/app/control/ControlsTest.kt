package com.opirl.app.control

import android.view.KeyEvent
import com.opirl.app.settings.GamepadButton
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ControlsTest {
    @Test
    fun `gamepad key codes map to buttons`() {
        assertEquals(GamepadButton.A, gamepadButton(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(GamepadButton.MENU, gamepadButton(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(GamepadButton.DPAD_LEFT, gamepadButton(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(null, gamepadButton(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `hat and trigger axes count as buttons past half way`() {
        assertEquals(setOf(GamepadButton.DPAD_LEFT, GamepadButton.DPAD_DOWN, GamepadButton.R2), axisButtons(-1f, 1f, 0.4f, 0.5f))
        assertEquals(setOf(GamepadButton.DPAD_RIGHT, GamepadButton.DPAD_UP, GamepadButton.L2), axisButtons(1f, -1f, 1f, 0f))
        assertEquals(emptySet(), axisButtons(0.2f, -0.2f, 0f, 0f))
    }

    @Test
    fun `a keyboard key is its character unless control or meta is held`() {
        assertEquals("a", keyboardKey('a'.code, control = false, meta = false))
        assertEquals("A", keyboardKey('A'.code, control = false, meta = false))
        assertEquals(null, keyboardKey('a'.code, control = true, meta = false))
        assertEquals(null, keyboardKey('a'.code, control = false, meta = true))
        assertEquals(null, keyboardKey(0, control = false, meta = false))
    }

    @Test
    fun `stream decks are recognized by product id`() {
        assertEquals(StreamDeckKind.CLASSIC, StreamDeckKind.of(0x0fd9, 0x0080))
        assertEquals(StreamDeckKind.XL, StreamDeckKind.of(0x0fd9, 0x006c))
        assertEquals(StreamDeckKind.MINI, StreamDeckKind.of(0x0fd9, 0x0090))
        assertEquals(null, StreamDeckKind.of(0x0fd9, 0x0066))
        assertEquals(null, StreamDeckKind.of(0x1234, 0x0080))
    }

    @Test
    fun `a key image is split into reports with the classic header`() {
        val image = ByteArray(2500) { it.toByte() }
        val reports = streamDeckImageReports(StreamDeckKind.CLASSIC, 7, image)
        assertEquals(listOf(1024, 1024, 1024), reports.map { it.size })
        assertContentEquals(byteArrayOf(0x02, 0x07, 7, 0, 0xF8.toByte(), 0x03, 0, 0), reports[0].copyOf(8))
        assertContentEquals(byteArrayOf(0x02, 0x07, 7, 1, 0xD4.toByte(), 0x01, 2, 0), reports[2].copyOf(8))
        assertContentEquals(image, reports[0].copyOfRange(8, 1024) + reports[1].copyOfRange(8, 1024) + reports[2].copyOfRange(8, 8 + 468))
    }

    @Test
    fun `a mini key image uses the older header and one based keys`() {
        val reports = streamDeckImageReports(StreamDeckKind.MINI, 2, ByteArray(1500) { 1 })
        assertContentEquals(byteArrayOf(0x02, 0x01, 0, 0, 0, 3), reports[0].copyOf(6))
        assertContentEquals(byteArrayOf(0x02, 0x01, 1, 0, 1, 3), reports[1].copyOf(6))
        assertEquals(1.toByte(), reports[1][16])
    }

    @Test
    fun `key states are read after each model's header`() {
        val classic = ByteArray(19).also {
            it[0] = 1
            it[2] = 15
            it[4 + 3] = 1
        }
        assertEquals(listOf(3), streamDeckKeyStates(StreamDeckKind.CLASSIC, classic, classic.size)!!.withIndex().filter { it.value }.map { it.index })
        val mini = byteArrayOf(1, 0, 0, 0, 0, 0, 1)
        assertEquals(listOf(5), streamDeckKeyStates(StreamDeckKind.MINI, mini, mini.size)!!.withIndex().filter { it.value }.map { it.index })
        assertEquals(null, streamDeckKeyStates(StreamDeckKind.CLASSIC, classic, 10))
    }

    @Test
    fun `brightness and reset are feature reports of each model's length`() {
        assertContentEquals(byteArrayOf(0x03, 0x08, 70) + ByteArray(29), streamDeckBrightness(StreamDeckKind.XL, 70))
        assertContentEquals(byteArrayOf(0x05, 0x55, 0xaa.toByte(), 0xd1.toByte(), 0x01, 100) + ByteArray(11), streamDeckBrightness(StreamDeckKind.MINI, 150))
        assertContentEquals(byteArrayOf(0x03, 0x02) + ByteArray(30), streamDeckReset(StreamDeckKind.CLASSIC))
    }

    @Test
    fun `the mini picture is transposed and stored bottom up as a 24 bit bitmap`() {
        val pixels = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0x112233)
        assertContentEquals(intArrayOf(0xFF0000, 0x0000FF, 0x00FF00, 0x112233), streamDeckMiniPixels(2, pixels))
        val bmp = streamDeckBmp(2, pixels)
        assertEquals(54 + 16, bmp.size)
        assertEquals("BM", String(bmp, 0, 2))
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0, 0, 0x33, 0x22, 0x11), bmp.copyOfRange(54, 60))
        assertContentEquals(byteArrayOf(0, 0, 0xFF.toByte(), 0, 0xFF.toByte(), 0), bmp.copyOfRange(62, 68))
    }
}
