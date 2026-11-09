package app.mobdroid.engine.flv

import java.io.ByteArrayOutputStream

fun hex(text: String): ByteArray {
    val clean = text.filter { !it.isWhitespace() }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun annexB(vararg units: ByteArray, startCode: ByteArray = byteArrayOf(0, 0, 0, 1)): ByteArray {
    val out = ByteArrayOutputStream()
    for (unit in units) {
        out.write(startCode)
        out.write(unit)
    }
    return out.toByteArray()
}

fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    parts.forEach { out.write(it) }
    return out.toByteArray()
}
