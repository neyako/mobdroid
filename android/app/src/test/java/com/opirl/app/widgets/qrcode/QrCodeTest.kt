package com.opirl.app.widgets.qrcode

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrCodeTest {
    private fun scan(message: String, module: Int = 4): String {
        val matrix = assertNotNull(qrMatrix(message))
        val side = matrix.size * module
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, qrPixels(matrix, module))))).text
    }

    @Test
    fun `what is drawn scans back to the message, bytes of other languages included`() {
        assertEquals("https://moblin.mys.al/", scan("https://moblin.mys.al/"))
        assertEquals("Xin chào Việt Nam 🇻🇳", scan("Xin chào Việt Nam 🇻🇳"))
        assertEquals("x".repeat(400), scan("x".repeat(400), module = 3))
    }

    @Test
    fun `an empty or oversized message has no code`() {
        assertNull(qrMatrix(""))
        assertNull(qrMatrix("x".repeat(2400)))
        assertNotNull(qrMatrix("x".repeat(2300)))
    }

    @Test
    fun `the code is level M with one light module of quiet zone around it`() {
        val matrix = assertNotNull(qrMatrix("a".repeat(30)))
        assertEquals(29 + 2, matrix.size)
        assertFalse(matrix.isDark(0, 0))
        assertTrue(matrix.isDark(1, 1))
        assertTrue((0 until matrix.size).none { matrix.isDark(it, 0) || matrix.isDark(0, it) || matrix.isDark(it, matrix.size - 1) })
    }

    @Test
    fun `modules are whole pixels, as close to the wanted size as fits in the picture`() {
        assertEquals(9, moduleSize(248, 27, 1080))
        assertEquals(4, moduleSize(248, 63, 1080))
        assertEquals(37, moduleSize(1070, 27, 1000))
        assertEquals(1, moduleSize(10, 79, 100))
    }

    @Test
    fun `every module is a plain black or white block`() {
        val matrix = QrMatrix(2, booleanArrayOf(true, false, false, true))
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertContentEquals(
            intArrayOf(
                black, black, white, white,
                black, black, white, white,
                white, white, black, black,
                white, white, black, black,
            ),
            qrPixels(matrix, 2),
        )
    }
}
