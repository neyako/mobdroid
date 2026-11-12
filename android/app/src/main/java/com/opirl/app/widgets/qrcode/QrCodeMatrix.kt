package com.opirl.app.widgets.qrcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private const val DARK = 0xFF000000.toInt()
private const val LIGHT = 0xFFFFFFFF.toInt()

class QrMatrix(val size: Int, private val dark: BooleanArray) {
    fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]
}

fun qrMatrix(message: String): QrMatrix? {
    if (message.isEmpty()) return null
    val bytes = String(message.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
    val bits = try {
        QRCodeWriter().encode(bytes, BarcodeFormat.QR_CODE, 0, 0, hints)
    } catch (e: WriterException) {
        return null
    }
    return QrMatrix(bits.width, BooleanArray(bits.width * bits.height) { bits.get(it % bits.width, it / bits.width) })
}

fun moduleSize(side: Int, modules: Int, limit: Int): Int =
    ((side + modules / 2) / modules).coerceAtMost(limit / modules).coerceAtLeast(1)

fun qrPixels(matrix: QrMatrix, module: Int): IntArray {
    val side = matrix.size * module
    return IntArray(side * side) { if (matrix.isDark(it % side / module, it / side / module)) DARK else LIGHT }
}
